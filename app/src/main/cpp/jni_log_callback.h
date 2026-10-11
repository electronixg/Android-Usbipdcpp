#pragma once

#include <jni.h>
#include <spdlog/spdlog.h>

namespace jni_log {

// init results: value names encode reference ownership in the type, so callers need not remember what true/false mean.
// Together with throwing, these form three mutually exclusive exits: Installed = ownership taken; FailedRefReleased =
// failed and already released; throwing = ownership not taken (the reference belongs to the caller).
enum class InitResult {
    Installed,
    FailedRefReleased,
};

// Initialize the JNI log callback. env releases the old global reference when replacing a callback, preventing leaks on repeated calls.
// callback_obj must be a global reference whose ownership is transferred to this function: after success the callback holds it and later
// init/cleanup releases it. Failure has two mutually exclusive exits; any new failure branch must belong to one:
//     Return FailedRefReleased -- this function has already released the reference (currently only when JavaVM cannot be obtained);
//     Throw                   -- this function has not released the reference; the caller still owns it (released by setLogCallbackImpl's catch).
// FailedRefReleased means global state is completely unchanged (the reference was released); the caller must not enable the new logging path.
// Contract: this function never throws after taking ownership of the global reference; its only theoretical throw point (lock acquisition failure) occurs
// before ownership transfer. Cleanup afterward only releases the old global reference (DeleteGlobalRef does not throw per the JNI
// specification), so it is not a second throw point. Implementation changes must preserve the atomicity of either taking full ownership or
// leaving everything unchanged: setLogCallbackImpl's callback_ref_owned ownership flag
// relies on Installed meaning ownership taken and throwing meaning ownership not taken; if a failure path both throws
// and releases the reference, that catch would call DeleteGlobalRef a second time.
InitResult init(JNIEnv* env, jobject callback_obj, jmethodID log_method);

// Cleanup
void cleanup(JNIEnv* env);

// spdlog callback function
void log_callback(spdlog::level::level_enum level, const std::string& message);

} // namespace jni_log