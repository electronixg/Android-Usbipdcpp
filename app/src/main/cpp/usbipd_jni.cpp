#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <libusb-1.0/libusb.h>

#include <spdlog/spdlog.h>
#include <spdlog/sinks/android_sink.h>

#include <usbipdcpp/Server.h>
#include <usbipdcpp/LibusbHandler/LibusbServer.h>
#include <usbipdcpp/LibusbHandler/tools.h>

#include "jni_callback_sink.h"
#include "jni_log_callback.h"

#define LOG_TAG "UsbIpNative"

// Error code definitions - must match the constants in the Kotlin layer's UsbIpNative.kt
namespace ErrorCode {
    constexpr int SUCCESS = 0;
    constexpr int DEVICE_NOT_FOUND = 1;
    constexpr int DEVICE_IN_USE = 2;
    constexpr int DEVICE_OPEN_FAILED = 3;
    constexpr int GET_DESCRIPTOR_FAILED = 4;
    constexpr int GET_CONFIG_FAILED = 5;
    constexpr int CLAIM_INTERFACE_FAILED = 6;
    constexpr int DEVICE_ALREADY_BOUND = 7;
    constexpr int HUB_FILTERED = 8;
    constexpr int SERVER_NOT_RUNNING = 9;
    constexpr int UNKNOWN_ERROR = 99;
}

namespace {
    std::mutex g_server_mutex;
    // Lock discipline: do not call paths that can return through JNI callbacks to native entry points; they would reenter this lock
    //(std::mutex is nonrecursive, causing same-thread deadlock). spdlog output is an exception: it uses jni_log's own
    // g_mutex, independently of this lock.
    // All reads and writes of g_server, g_server_running, g_server_port, and g_initialized occur under g_server_mutex.
    // g_server and g_server_running are maintained together: true is set only after successful startup; failure/stop
    // paths return before true is set or reset both together. No state has running true but server null.
    // Short-circuit checks in stopServerImpl and elsewhere rely on this invariant; no self-recovery branch is needed.
    // The libusb initialization for g_initialized also occurs under the lock: later concurrent nativeInit calls
    // wait for the earlier call to finish, preventing a window where the initialized flag is visible but libusb is not ready.
    std::unique_ptr<usbipdcpp::LibusbServer> g_server;
    bool g_initialized = false;
    bool g_server_running = false;
    // Listening port of the last successful startup; -1 means never started. Lets the already-running branch report
    // the actual port (the caller cannot substitute its input); not cleared on stop, as nobody reads it when running is false.
    int g_server_port = -1;

    // Idempotent libusb initialization; the caller must hold g_server_mutex. Shared by nativeInitImpl and
    // startServerImpl: initialization and other startup state operations occur in the same critical section,
    // with no intervening unlock, so maintaining g_initialized entirely under the lock does not depend on caller serialization.
    bool try_init_locked() {
        if (g_initialized) {
            return true;
        }
        libusb_set_option(nullptr, LIBUSB_OPTION_WEAK_AUTHORITY);
        int err = libusb_init(nullptr);
        if (err < 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to initialize libusb: %s", libusb_strerror(err));
            // Leave the flag unset to allow retry on the next call.
            return false;
        }
        g_initialized = true;
        return true;
    }

    int toErrorCode(usbipdcpp::DeviceOperationResult result) {
        using namespace usbipdcpp;
        switch (result) {
            case DeviceOperationResult::Success: return ErrorCode::SUCCESS;
            case DeviceOperationResult::DeviceNotFound: return ErrorCode::DEVICE_NOT_FOUND;
            case DeviceOperationResult::DeviceInUse: return ErrorCode::DEVICE_IN_USE;
            case DeviceOperationResult::DeviceAlreadyBound: return ErrorCode::DEVICE_ALREADY_BOUND;
            case DeviceOperationResult::DeviceOpenFailed: return ErrorCode::DEVICE_OPEN_FAILED;
            case DeviceOperationResult::GetDescriptorFailed: return ErrorCode::GET_DESCRIPTOR_FAILED;
            case DeviceOperationResult::GetConfigFailed: return ErrorCode::GET_CONFIG_FAILED;
            case DeviceOperationResult::ClaimInterfaceFailed: return ErrorCode::CLAIM_INTERFACE_FAILED;
            case DeviceOperationResult::HubFiltered: return ErrorCode::HUB_FILTERED;
            // DeviceFiltered occurs only when device_bind_filter is configured; this application does not configure it,
            // so classify it as an unknown error.
            default: return ErrorCode::UNKNOWN_ERROR;
        }
    }
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    // Declare only the version: no method table needs registration (default symbol lookup is used), and JavaVM need not be cached
    //(jni_log::init obtains it through env->GetJavaVM itself; the specification says this cannot fail).
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    // Intentionally empty: on Android, System.loadLibrary libraries live with the process and do not trigger unload;
    // resources are cleaned up centrally by releaseImpl. If unload support is needed later (for example in a ClassLoader-isolated
    // test environment), add g_server destruction and jni_log global-reference cleanup here.
}

JNIEXPORT jboolean JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_nativeInitImpl(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Initializing native layer");

    // Hold g_server_mutex throughout initialization: only one concurrent caller performs it; the others wait until it finishes
    // and then see the initialized state. Do not use atomic CAS: it ensures initialization runs once but not that it has finished;
    // later calls would optimistically report initialized while libusb might not yet be ready.
    std::lock_guard<std::mutex> lock(g_server_mutex);
    // Take the snapshot before try_init_locked: it is idempotent, and g_initialized is always
    // true after success, so reading afterward cannot distinguish initialization in this call from earlier initialization.
    bool was_initialized = g_initialized;
    if (!try_init_locked()) {
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        was_initialized ? "Already initialized" : "Native layer initialized successfully");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_setLogCallbackImpl(JNIEnv* env, jobject thiz, jobject callback) {
    if (callback == nullptr) {
        // null clears the callback: release the global reference when the UI is destroyed (the app enters the background),
        // otherwise the JNI global reference would retain the old Activity indefinitely and leak it.
        jni_log::cleanup(env);
        return;
    }

    jobject callback_global = env->NewGlobalRef(callback);
    if (callback_global == nullptr) {
        // On OOM, NewGlobalRef returns null and leaves OutOfMemoryError pending: without clearing it, it propagates to the Kotlin
        // caller (setLogCallback has no try-catch) and crashes when it reaches Compose.
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "NewGlobalRef failed");
        return;
    }

    jclass callback_class = env->GetObjectClass(callback);
    if (callback_class == nullptr) {
        // On OOM, GetObjectClass may return null; passing it to GetMethodID is UB (abort under ART).
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetObjectClass failed");
        return;
    }
    jmethodID log_method = env->GetMethodID(callback_class, "onLog", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(callback_class);
    if (log_method == nullptr) {
        // When GetMethodID fails, NoSuchMethodError is pending on the JNI stack; clear it and release the reference.
        // Do not reset spdlog or the old callback: keeping the old callback logging is preferable to silently dropping logs.
        env->ExceptionClear();
        env->DeleteGlobalRef(callback_global);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to find onLog method");
        return;
    }

    // make_shared/spdlog may throw bad_alloc; C++ exceptions cannot cross the JNI boundary (ART cannot
    // unwind and terminates immediately). callback_ref_owned tracks whether this function still owns the reference:
    // construction precedes ownership transfer, so an exception leaves jni_log and the old logger unchanged.
    bool callback_ref_owned = true;
    try {
        // Use LOG_TAG consistently for logcat: this module's spdlog output and __android_log_print
        // output share one tag, so filtering does not miss half of the output.
        auto android_sink = std::make_shared<spdlog::sinks::android_sink_mt>("UsbIpNative");
        auto jni_sink = std::make_shared<jni_callback_sink_mt>(jni_log::log_callback);

        // Create a new logger to replace the default on each recreation (Activity rotation/restart): set_default_logger
        // both registers the new logger in the registry (loggers_[name]) and makes it the default. Both locations
        // replace the same-name logger, removing both registry references to the old logger; its reference count reaches zero
        // and it is destroyed, rather than accumulating with each recreation.
        auto logger = std::make_shared<spdlog::logger>("usbipdcpp", spdlog::sinks_init_list{android_sink, jni_sink});
        logger->set_level(spdlog::level::debug);

        auto init_result = jni_log::init(env, callback_global, log_method);
        // After init, this function no longer owns the reference whether init succeeds or fails (on failure it releases it immediately);
        // the sole exception is when init throws (lock acquisition failure): throwing means ownership was not taken (see the contract in
        // jni_log_callback.h), so catch releases the still-owned reference according to callback_ref_owned.
        callback_ref_owned = false;
        if (init_result != jni_log::InitResult::Installed) {
            // Callback installation failed: leave the old logger unchanged to avoid a mismatch where the new logger is active
            // but the callback is still the old one.
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to install log callback");
            return;
        }

        // The window between init and set_default_logger (the callback is replaced but the default is still the old
        // logger) is harmless: both generations of JNI sinks look up the current callback through jni_log's global state.
        // Logs in this window still reach the new callback, not the old Activity. Consistency across generations is not
        // guaranteed here: callback replacement already spans two calls, set(null) and set(new).
        spdlog::set_default_logger(logger);
        // Actual spdlog::set_pattern path: set_formatter -> registry::set_formatter
        // iterates registered loggers -> each logger sets the formatter on each sink (the formatter belongs to the
        // sink, not the logger). The preceding set_default_logger has registered this logger in the
        // registry, so iteration necessarily reaches it: android_sink and jni_sink both use this pattern.
        spdlog::set_pattern("[%H:%M:%S] [%l] %v");

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Log callback set");
    } catch (const std::exception& e) {
        if (callback_ref_owned) {
            env->DeleteGlobalRef(callback_global);
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setLogCallback threw: %s", e.what());
    } catch (...) {
        if (callback_ref_owned) {
            env->DeleteGlobalRef(callback_global);
        }
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "setLogCallback threw unknown exception");
    }
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_bindUsbDeviceImpl(
    JNIEnv* env, jobject thiz, jint fd, jint vendor_id, jint product_id, jobjectArray outBusid) {

    // Define busid and rollback outside try because the outer catch also needs them: if the library throws after binding completes
    // (the current implementation has no throwing operations after binding, but this guards future changes), the binding already
    // stored in the library must be rolled back as far as possible.
    std::string busid;
    // If delivery fails, fall back to removal by busid: failure to unbind by fd means fd matching is no longer reliable;
    // notify_device_removed uses the busid key, providing a second cleanup path independent of fd.
    // noexcept plus an inner try: an exception from rollback itself also cannot cross the boundary and would otherwise conceal
    // even the leftover device; at least leave a log.
    // The caller must hold g_server_mutex (LibusbServer has no locks; all calls must be serialized);
    // pass busid as a parameter and capture fd by value: the lambda holds no stack references, so even if later taken outside
    //(for example wrapped in std::function), it cannot dangle.
    auto rollback = [fd](const std::string& busid) noexcept {
        // Exceptions before the g_server check also reach catch.
        if (!g_server) {
            return;
        }
        try {
            if (g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd)) == usbipdcpp::DeviceOperationResult::Success) {
                return;
            }
            // An empty busid means the exception occurred before obtaining it; failure of get_device_busid itself
            // is also this case. No object is available for fallback removal by busid, so skipping is expected;
            // this also avoids a lookup with an empty key.
            if (busid.empty()) {
                return;
            }
            auto notify_result = g_server->notify_device_removed(busid);
            // This lambda is noexcept: all internal logging uses __android_log_print
            //(a libc function with no C++ exceptions); spdlog cannot be included in a promise never to throw.
            if (notify_result == usbipdcpp::DeviceOperationResult::Success) {
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Rollback: fd unbind failed, busid fallback succeeded");
            } else {
                // Both paths failed: an orphaned device remains on the native side and requires manual attention.
                __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Rollback failed by both fd and busid: %d", static_cast<int>(notify_result));
            }
        } catch (const std::exception& e) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Rollback threw: %s", e.what());
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Rollback threw unknown exception");
        }
    };

    // Rollback after reacquiring the lock on exception, shared by both catch branches (the lock inside try has already
    // been released during stack unwinding). Parameters follow rollback's discipline: capture no stack state. rollback
    // itself is noexcept, but lock_guard construction may throw, so wrap it again: any subsequent exception would
    // cross the extern "C" boundary; only logging and abandoning rollback are allowed.
    auto try_rollback = [](const std::string& busid, const auto& rollback) noexcept {
        try {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            rollback(busid);
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "bindUsbDevice rollback skipped: lock acquisition threw");
        }
    };

    // Overall exception guard: library calls and std::string allocations can throw (for example bad_alloc); when C++ exceptions cross
    // the extern "C" JNI boundary, ART cannot unwind and terminates immediately.
    try {
        spdlog::info("Binding USB device: fd={}, vid=0x{:04x}, pid=0x{:04x}", fd, vendor_id, product_id);

        // An invalid output array prevents delivering busid to the caller: validate before binding, otherwise a partially completed state remains
        // where binding succeeded but the caller cannot get busid (without busid, Kotlin only closes the connection and does not unbind).
        if (outBusid == nullptr || env->GetArrayLength(outBusid) == 0) {
            // A non-array argument leaves an exception pending at GetArrayLength (theoretically possible through reflection or similar entry points,
            // but unreachable from normal Kotlin calls): clear it before returning, since a pending exception propagates to the caller.
            env->ExceptionClear();
            spdlog::error("outBusid is null or empty");
            return ErrorCode::UNKNOWN_ERROR;
        }

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_initialized) {
            spdlog::error("Native layer not initialized");
            return ErrorCode::UNKNOWN_ERROR;
        }

        if (!g_server) {
            spdlog::error("Server not created");
            return ErrorCode::SERVER_NOT_RUNNING;
        }

        // Get busid before binding: if information retrieval fails, no binding exists and we can return directly, avoiding a state where binding
        // exists but busid cannot be delivered. Temporary wrapping is standard usbipdcpp usage (the library also wraps once when binding and
        // once when a client connects; lazy binding does not interfere). libusb sets fd_keep for wrapped
        // file descriptors; closing the temporary handle does not close the original fd (UsbDeviceConnection is unaffected).
        // fd_keep and the wrap API were both introduced in libusb 1.0.23, so no combination with an older version closes the fd.
        libusb_device_handle* temp_handle = nullptr;
        int err = libusb_wrap_sys_device(nullptr, static_cast<intptr_t>(fd), &temp_handle);
        if (err < 0) {
            spdlog::error("Failed to get device info for busid: {}", libusb_strerror(err));
            return ErrorCode::DEVICE_OPEN_FAILED;
        }

        // Manage the temporary handle with RAII: std::string construction in get_device_busid may throw,
        // and the outer catch must not skip libusb_close. Limit its scope to retrieving busid,
        // so subsequent binding occurs only after the temporary handle is closed.
        {
            struct TempHandleGuard {
                libusb_device_handle* handle;
                ~TempHandleGuard() {
                    if (handle) libusb_close(handle);
                }
            } guard{temp_handle};

            libusb_device* dev = libusb_get_device(temp_handle);
            if (!dev) {
                spdlog::error("libusb_get_device returned nullptr");
                return ErrorCode::DEVICE_NOT_FOUND;
            }
            busid = usbipdcpp::get_device_busid(dev);
        }

        // An empty busid returned by the library is abnormal: it would be delivered as Success(""), after which all unbinding and detach
        // notifications by busid would fail; since binding has not happened yet, reject it as not found.
        if (busid.empty()) {
            spdlog::error("get_device_busid returned empty");
            return ErrorCode::DEVICE_NOT_FOUND;
        }

        auto result = g_server->bind_host_device_with_wrapped_fd(static_cast<intptr_t>(fd));
        if (result != usbipdcpp::DeviceOperationResult::Success) {
            spdlog::error("bind_host_device_with_wrapped_fd failed: {}", static_cast<int>(result));
            return toErrorCode(result);
        }

        // Output busid (array validity was checked at entry).
        jstring busid_str = env->NewStringUTF(busid.c_str());
        if (busid_str == nullptr) {
            // On OOM, NewStringUTF returns null: writing null into the array would cause Kotlin's
            // outBusid[0]!! to throw NPE; roll back the binding and return an error.
            env->ExceptionClear();
            rollback(busid);
            return ErrorCode::UNKNOWN_ERROR;
        }
        env->SetObjectArrayElement(outBusid, 0, busid_str);
        // DeleteLocalRef is safe even with a pending exception (per the JNI specification); release first, then check centrally.
        env->DeleteLocalRef(busid_str);

        // Also roll back on write failure: the contract is either deliver busid to the caller or leave no native binding,
        // otherwise the caller cannot unbind without busid (SetObjectArrayElement cannot fail with normal arguments).
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            rollback(busid);
            return ErrorCode::UNKNOWN_ERROR;
        }

        // Log success only after delivery completes: the failure branches above roll back; logging earlier would
        // leave a misleading binding-success message in logcat if rollback failed.
        spdlog::info("Device bound successfully: {}", busid);

        return ErrorCode::SUCCESS;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "bindUsbDevice threw exception: %s", e.what());
        // If binding never occurred, unbind finds no matching fd, and the notify fallback is also a harmless no-op.
        try_rollback(busid, rollback);
        return ErrorCode::UNKNOWN_ERROR;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "bindUsbDevice threw unknown exception");
        try_rollback(busid, rollback);
        return ErrorCode::UNKNOWN_ERROR;
    }
}

// Return the actual listening port (the current real port after successful startup or if already running), or -1 on failure:
// the caller uses this to fill in state instead of inferring it from the input.
JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_startServerImpl(JNIEnv* env, jobject thiz, jint port) {

    // Reject out-of-range ports directly: endpoint stores the port as unsigned short, so 65536 wraps to 0
    //(a system-assigned random port), and 99999 wraps to another port, making the UI disagree with the actual listener.
    if (port < 1 || port > 65535) {
        spdlog::error("Invalid port: {}", port);
        return -1;
    }

    // Always initialize once (idempotently): JNI is a public boundary; do not assume the caller has already called
    // nativeInit. Ensure libusb is ready here and return directly on failure. Initialization and the startup checks/
    // creation below occur in the same critical section, without relying on caller serialization across layers.
    std::lock_guard<std::mutex> lock(g_server_mutex);
    if (!try_init_locked()) {
        return -1;
    }

    // Log after validation and initialization: emit this only when actually entering startup; an invalid port or
    // readiness failure must not produce misleading output saying startup is imminent.
    spdlog::info("Starting USB/IP server on port {}", port);

    if (g_server_running) {
        // Return the actual listening port rather than a success boolean: the caller fills in state from it; without this
        // distinction, lagging state could cause the caller to display the input port.
        spdlog::info("Server already running");
        return g_server_port;
    }

    try {
        g_server = std::make_unique<usbipdcpp::LibusbServer>();
        g_server->set_hotplug_enabled(false);
        asio::ip::tcp::endpoint endpoint(asio::ip::tcp::v4(), static_cast<unsigned short>(port));
        // Since v1.0.8, start no longer throws; startup failures (such as a port already in use) are reported by the return value.
        auto ec = g_server->start(endpoint);
        if (ec) {
            spdlog::error("Failed to start server: {}", ec.message());
            // The start failure path already cleans up internally (hotplug monitoring and the libusb event thread); destroy directly without stop.
            g_server.reset();
            return -1;
        }
        g_server_running = true;
        g_server_port = port;
        spdlog::info("Server started successfully");
        return port;
    } catch (const std::exception& e) {
        // Exception guard for construction paths such as make_unique (start itself no longer throws).
        spdlog::error("Failed to start server: {}", e.what());
        if (g_server) {
            try {
                g_server->stop();
            } catch (...) {}
        }
        g_server.reset();
        return -1;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServerImpl(JNIEnv* env, jobject thiz) {

    spdlog::info("Stopping USB/IP server");

    std::lock_guard<std::mutex> lock(g_server_mutex);

    // || !g_server is defensive: by the invariant at g_server's declaration, running being true
    // implies server exists; this handles possible invariant violations to avoid calling stop on a null pointer.
    if (!g_server_running || !g_server) {
        spdlog::info("Server not running");
        return;
    }

    try {
        g_server->stop();
        g_server.reset();
        g_server_running = false;

        spdlog::info("Server stopped successfully");
    } catch (const std::exception& e) {
        // Reset state even if stopping fails, otherwise g_server_running remains true and prevents restarting.
        spdlog::error("Error stopping server: {}", e.what());
        g_server.reset();
        g_server_running = false;
    }
}

JNIEXPORT jint JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_unbindUsbDeviceImpl(
    JNIEnv* env, jobject thiz, jint fd) {

    // As in bindUsbDeviceImpl: library calls may throw; exceptions cannot cross the extern "C" boundary.
    try {
        spdlog::info("Unbinding USB device with fd={}", fd);

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_initialized) {
            spdlog::error("Native layer not initialized");
            return ErrorCode::UNKNOWN_ERROR;
        }

        if (!g_server) {
            spdlog::error("Server not created");
            return ErrorCode::SERVER_NOT_RUNNING;
        }

        auto result = g_server->unbind_host_device_by_fd(static_cast<intptr_t>(fd));
        int errorCode = toErrorCode(result);

        if (errorCode == ErrorCode::SUCCESS) {
            spdlog::info("Device unbound successfully");
        } else {
            spdlog::error("Failed to unbind device: {}", errorCode);
        }

        return errorCode;
    } catch (const std::exception& e) {
        spdlog::error("unbindUsbDevice threw exception: {}", e.what());
        return ErrorCode::UNKNOWN_ERROR;
    } catch (...) {
        spdlog::error("unbindUsbDevice threw unknown exception");
        return ErrorCode::UNKNOWN_ERROR;
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_notifyDeviceRemovedImpl(
    JNIEnv* env, jobject thiz, jstring busid) {

    // As in bind/unbind: string construction and library calls may throw; exceptions cannot cross the extern "C" boundary.
    try {
        // Kotlin's busid is a non-null String, so normal calls cannot reach this; JNI is a public boundary
        //(reflection and similar entry points can also reach it), and passing null to GetStringUTFChars is UB on ART.
        if (busid == nullptr) {
            spdlog::error("notifyDeviceRemoved: busid is null");
            return;
        }
        const char* busid_cstr = env->GetStringUTFChars(busid, nullptr);
        if (busid_cstr == nullptr) {
            // OOM: GetStringUTFChars returns null and leaves OutOfMemoryError pending;
            // constructing std::string directly would crash, so clear the exception and return safely.
            env->ExceptionClear();
            spdlog::error("Failed to get busid string");
            return;
        }
        // RAII management (as with TempHandleGuard in bindUsbDeviceImpl): std::string
        // construction may throw bad_alloc; the outer catch must not skip ReleaseStringUTFChars.
        struct UtfCharsGuard {
            JNIEnv* env;
            jstring str;
            const char* cstr;
            ~UtfCharsGuard() { env->ReleaseStringUTFChars(str, cstr); }
        } utf_guard{env, busid, busid_cstr};

        std::string busid_str(busid_cstr);

        spdlog::info("Notifying device removed: {}", busid_str);

        std::lock_guard<std::mutex> lock(g_server_mutex);

        if (!g_server) {
            spdlog::warn("Server not created, ignoring device removal notification");
            return;
        }

        auto notify_result = g_server->notify_device_removed(busid_str);
        if (notify_result != usbipdcpp::DeviceOperationResult::Success) {
            // A missing busid is normal (the device was cleaned up through another path), so debug level suffices;
            // other results indicate internal library errors; raise them to warn so they are not buried in debug logs.
            if (notify_result == usbipdcpp::DeviceOperationResult::DeviceNotFound) {
                spdlog::debug("notify_device_removed: busid not found (already cleaned): {}", busid_str);
            } else {
                spdlog::warn("notify_device_removed failed: {} for busid {}",
                             static_cast<int>(notify_result), busid_str);
            }
        }
    } catch (const std::exception& e) {
        spdlog::error("notifyDeviceRemoved threw exception: {}", e.what());
    } catch (...) {
        spdlog::error("notifyDeviceRemoved threw unknown exception");
    }
}

JNIEXPORT void JNICALL
Java_com_yunsmall_usbipdcpp_UsbIpNative_releaseImpl(JNIEnv* env, jobject thiz) {
    // Overall exception guard: spdlog allocations and similar operations may throw, and Kotlin try/catch cannot catch C++ exceptions.
    try {
        spdlog::info("Releasing native resources");

        // Call the JNI export in the same translation unit directly: keep a single copy of the stop logic,
        // without reflection through env->CallStaticVoidMethod or duplicating the implementation.
        Java_com_yunsmall_usbipdcpp_UsbIpNative_stopServerImpl(env, thiz);

        // State reset and libusb_exit occur in the same critical section: if exit were outside the lock, another thread could enter
        // try_init_locked, see g_initialized=false, and call libusb_init again, interleaving with exit.
        // Keeping it inside the lock makes the JNI layer self-contained, without relying on external serialization of all callers.
        // The cost of waiting with the lock held is bounded: stopServerImpl has already joined library threads and released all devices;
        // exit only releases resources, and state stays false throughout, so other calls would already fail quickly.
        bool was_initialized = false;
        {
            std::lock_guard<std::mutex> lock(g_server_mutex);
            was_initialized = g_initialized;
            g_initialized = false;
            if (was_initialized) {
                libusb_exit(nullptr);
            }
        }

        // Do not clear the jni_log callback here: callback registration/clearing is paired entirely by the UI layer (set during composition,
        // clear in onDispose). The Service may be destroyed before the Activity (split screen or system reclamation);
        // clearing here would unexpectedly silence logging for the surviving UI. The UI releases the reference when needed,
        // and the system reclaims it on process exit.
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "release threw: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "release threw unknown exception");
    }
}

} // extern "C"
