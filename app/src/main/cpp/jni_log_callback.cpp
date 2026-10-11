#include "jni_log_callback.h"
#include <android/log.h>
#include <mutex>

namespace jni_log {

namespace {
    JavaVM* g_jvm = nullptr;
    jobject g_callback_obj = nullptr;
    jmethodID g_log_method = nullptr;
    // Matches LOG_TAG in usbipd_jni.cpp so logcat can filter this module's output with one tag.
    constexpr char LOG_TAG[] = "UsbIpNative";

    // Central detach exit: log failures (such as JNI_EDETACHED) once, so investigating
    // attach/detach leaks does not lack clues.
    void detach_if_needed(JavaVM* jvm, bool need_detach) {
        if (need_detach && jvm->DetachCurrentThread() != JNI_OK) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "jni_log: DetachCurrentThread failed");
        }
    }
    // Protect globals such as g_callback_obj: when cleanup deletes a global reference, log_callback
    // may be executing on usbipdcpp's logging thread; without locking it would access a released reference.
    std::mutex g_mutex;
}

InitResult init(JNIEnv* env, jobject callback_obj, jmethodID log_method) {
    // MainActivity's DisposableEffect calls setLogCallback on each recreation;
    // failing to release the old reference would permanently leak global references.
    // GetJavaVM only reads the cached vm pointer inside env (no reference-table access or blocking); execute it outside the lock;
    // write g_jvm under the lock to avoid racing with cleanup setting it to null.
    JavaVM* jvm = nullptr;
    if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
        // Failure is permitted by the specification (the implementation reads env's cached vm pointer, so it is practically impossible): on failure,
        // global state stays unchanged (the old callback keeps working); release only this call's new reference.
        env->DeleteGlobalRef(callback_obj);
        return InitResult::FailedRefReleased;
    }
    jobject old = nullptr;
    {
        std::lock_guard lock(g_mutex);
        old = g_callback_obj;
        g_jvm = jvm;
        g_callback_obj = callback_obj;
        g_log_method = log_method;
    }
    // DeleteGlobalRef accesses the JVM reference table; execute outside the lock, consistent with this file's rule
    // that JNI calls stay outside the lock, and to avoid waiting for JVM internal locks while holding this lock.
    if (old) {
        env->DeleteGlobalRef(old);
    }
    return InitResult::Installed;
}

void cleanup(JNIEnv* env) {
    // Same discipline as init: only extract the reference under the lock; execute DeleteGlobalRef outside it.
    jobject old = nullptr;
    {
        std::lock_guard lock(g_mutex);
        old = g_callback_obj;
        g_callback_obj = nullptr;
        g_jvm = nullptr;
        g_log_method = nullptr;
    }
    if (old) {
        env->DeleteGlobalRef(old);
    }
}

void log_callback(spdlog::level::level_enum level, const std::string& message) {
    // Allowlist under the lock: only nonblocking JNI calls that cannot reenter this lock (GetEnv,
    // AttachCurrentThread, NewLocalRef, and reading the cached methodID); all other JNI calls
    //(NewStringUTF, CallVoidMethod, etc.) must execute outside the lock:
    // - Local references have independent reference counts; the object survives cleanup deleting its global reference during calls outside the lock.
    //     (Snapshotting a raw pointer would cause use-after-free here and cannot be used to narrow the lock scope.)
    // - Run user code outside the lock, so even a future Kotlin callback synchronously triggering native logs cannot deadlock.
    JavaVM* jvm = nullptr;
    JNIEnv* env = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    bool need_detach = false;
    {
        std::lock_guard lock(g_mutex);
        if (!g_jvm || !g_callback_obj || !g_log_method) return;
        jvm = g_jvm;

        // Attach under the lock: ART creates the java.lang.Thread peer object,
        // but that path does not pass through this module's logging callback (only spdlog
        // output can reenter g_mutex), so there is no same-thread reacquisition; GetEnv must precede deriving the local reference under the lock.
        // Attaching outside the lock (get jvm -> unlock -> attach -> relock to derive the reference) would require handling
        // the window where cleanup cleared the callback after relocking, adding complexity without practical benefit.
        int get_env_result = jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (get_env_result == JNI_EDETACHED) {
            // Detach immediately after use rather than caching attachment state: the library creates the logging thread, so we have no
            // opportunity to clean up at thread exit; leaving it attached would violate JNI's requirement to detach before exit.
            // attach/detach takes microseconds; do not optimize it.
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                need_detach = true;
            } else {
                // Cannot obtain env: drop this log and emit one line to help diagnose abnormal thread state in logcat.
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "jni_log: AttachCurrentThread failed");
                return;
            }
        } else if (get_env_result != JNI_OK) {
            // As with attachment failure: JNI_EVERSION and similar results mean no usable env is available for this thread;
            // the log is silently dropped; emit one line to help diagnose ROM compatibility issues.
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "jni_log: GetEnv failed: %d", get_env_result);
            return;
        }

        // Exception to keeping JNI calls outside the lock: NewLocalRef only accesses the reference table, executes no
        // user code, and does not block (the same reasoning as attaching under the lock), so deriving it under the lock is allowed.
        callback = env->NewLocalRef(g_callback_obj);
        method = g_log_method;
    }
    // A null env can only indicate abnormal JVM behavior (normal implementations provide a valid env after successful
    // attach/GetEnv, and need_detach is not set in this case). Short-circuit separately so the logic below does not rely on
    // the unreachable assumption of a non-null env; if a nonconforming implementation reports successful attach without writing env,
    // need_detach is already set, so still detach, keeping this exit consistent with the branches below.
    if (!env) {
        detach_if_needed(jvm, need_detach);
        return;
    }
    if (!callback || !method) {
        // NewLocalRef failure (OOM) leaves OutOfMemoryError pending; clear it: leaving the exception on
        // an already-attached thread (such as the calling UI thread) would break its subsequent JNI calls.
        // A thread attached here must be detached, otherwise it remains attached to the JVM indefinitely.
        env->ExceptionClear();
        detach_if_needed(jvm, need_detach);
        return;
    }

    // NewStringUTF converts using NUL-terminated semantics: a message containing an embedded NUL (theoretically introduced by
    // formatting arguments) is truncated; this only affects display of that log, so no extra detection is performed.
    // Four-byte UTF-8 (such as emoji) is safe: ART's NewStringUTF accepts standard UTF-8,
    // correctly converting it into surrogate pairs (more permissive than the modified UTF-8 required by JNI).
    jstring jmessage = env->NewStringUTF(message.c_str());
    if (!jmessage) {
        // On OOM, NewStringUTF returns null and leaves OutOfMemoryError pending; clear it to avoid leaving the exception behind.
        env->ExceptionClear();
        env->DeleteLocalRef(callback);
        detach_if_needed(jvm, need_detach);
        return;
    }

    jint jlevel = static_cast<jint>(level);
    env->CallVoidMethod(callback, method, jlevel, jmessage);
    env->DeleteLocalRef(jmessage);
    env->DeleteLocalRef(callback);

    // Clear exceptions thrown by the Kotlin callback so pending exceptions on the JNI stack do not break subsequent calls.
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    detach_if_needed(jvm, need_detach);
}

} // namespace jni_log