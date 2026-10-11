package com.yunsmall.usbipdcpp

import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

// Three rules for this file (details and reasons appear in the relevant comments):
// 1. All JNI calls use jniContext: native calls are serialized and non-cancellable.
// 2. Library-loading guards (libraryLoaded) belong only in public methods; *Impl methods do not check independently.
// 3. Public suspend methods switch to jniContext themselves; callers need not remember to switch.
object UsbIpNative {
    private const val TAG = "UsbIpNative"

    // Load during class initialization: accessing any member of this object triggers this first, ensuring every JNI
    // call occurs after library loading. Even when setLogCallback in the initial UI composition executes before Service.onCreate
    // (a cold-start race), it will not throw UnsatisfiedLinkError.
    // Initial access points (Service construction, setLogCallback during composition, etc.) all run on the main thread: loading is
    // a millisecond-scale dlopen operation with no material impact on the startup budget; no background warmup is performed.
    // Catch exceptions here: escaping class initialization makes the JVM mark this class as having failed initialization, after which
    // every access throws NoClassDefFoundError; an outer try-catch cannot recover it.
    private val libraryLoaded: Boolean = try {
        System.loadLibrary("usbipdcpp_native")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load native library", e)
        false
    } catch (e: Throwable) {
        // Some ROMs report loading failures other than UnsatisfiedLinkError (such as SecurityException):
        // letting them escape fails class initialization, turning every subsequent access into NoClassDefFoundError.
        Log.e(TAG, "Unexpected error loading native library", e)
        false
    }

    // LibusbServer has no internal locks; concurrent access to state variables/containers corrupts state, so all calls must be
    // serialized. Each suspend method below switches to this dispatcher internally; callers need not remember to
    // switch. Cases requiring Kotlin state and native calls in the same critical section (UsbService's device table)
    // can still wrap an outer withContext using the same dispatcher; multiple nested levels do not redispatch
    // or queue to wait: when withContext sees that the new context's interceptor equals the current coroutine's (by instance
    // equality), it executes the block inline without even entering the scheduling queue, independently of LimitedDispatcher's
    // queue behavior. UsbService.serviceNativeContext directly reuses the jniContext instance below:
    // instance equality does not rely on a convention of remembering nativeDispatcher in both places.
    private val nativeDispatcher = NativeTaskDispatcher(Dispatchers.IO.limitedParallelism(1))

    // Wrap the limited dispatcher with a thread marker active during task execution: runOnNativeThread's misuse check
    //(blocking inside a native task while waiting for itself) needs to know whether the current thread is executing a native task;
    // the limiter's permit ownership is not observable, so this marker is the only runtime signal. The wrapper remains
    // a single instance in coroutine contexts: withContext's inline fast path checks interceptor instance
    // equality; jniContext and its reusers (UsbService.serviceNativeContext) share
    // the same wrapper instance, preserving inline behavior for nested dispatch.
    private class NativeTaskDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val inNativeTask = ThreadLocal<Boolean>()

        // Whether the current thread is executing a native task; read by runOnNativeThread's misuse check.
        val isInNativeTask: Boolean
            get() = inNativeTask.get() == true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // The thread actually executing the task sets the marker (the dispatch caller and worker threads
            // may differ); use remove rather than set(false), leaving no stale value on long-lived pool threads.
            delegate.dispatch(context, Runnable {
                inNativeTask.set(true)
                try {
                    block.run()
                } finally {
                    inNativeTask.remove()
                }
            })
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            delegate.isDispatchNeeded(context)
    }

    // All JNI calls execute in this context:
    // - nativeDispatcher ensures serialization (LibusbServer has no locks).
    // - NonCancellable ensures execution cannot be cancelled once started: JNI calls are atomic; cancellation would
    //     leave native and Kotlin state inconsistent: native has bound the device but the caller cannot receive
    //     the result, neither registering it in the device table nor getting an opportunity to close the opened connection.
    // internal: UsbService.serviceNativeContext directly reuses this instance; the prerequisite for nested
    // withContext's inline fast path (equal interceptor instances) is guaranteed by reusing the same
    // object, rather than relying on each call site to assemble an equivalent context.
    internal val jniContext = nativeDispatcher + NonCancellable

    // ==================== JNI implementations, all private ====================
    // If library loading failed, JNI calls throw UnsatisfiedLinkError (the root cause of cold-start crashes);
    // centralize guards in the public methods below: distributing checks across call sites means a single omission causes a crash.
    private external fun nativeInitImpl(): Boolean
    private external fun setLogCallbackImpl(callback: LogCallback?)
    private external fun bindUsbDeviceImpl(fd: Int, vendorId: Int, productId: Int, outBusid: Array<String?>): Int
    private external fun unbindUsbDeviceImpl(fd: Int): Int
    private external fun notifyDeviceRemovedImpl(busid: String)
    private external fun startServerImpl(port: Int): Int
    private external fun stopServerImpl()
    private external fun releaseImpl()

    // Error codes - must match the ErrorCode namespace in the JNI layer's usbipd_jni.cpp.
    private object ErrorCode {
        const val SUCCESS = 0
        const val DEVICE_NOT_FOUND = 1
        const val DEVICE_IN_USE = 2
        const val DEVICE_OPEN_FAILED = 3
        const val GET_DESCRIPTOR_FAILED = 4
        const val GET_CONFIG_FAILED = 5
        const val CLAIM_INTERFACE_FAILED = 6
        const val DEVICE_ALREADY_BOUND = 7
        const val HUB_FILTERED = 8
        const val SERVER_NOT_RUNNING = 9
        const val UNKNOWN_ERROR = 99
    }

    // ==================== Public API ====================

    /**
     * Initialize the native layer. false means native is unavailable (library loading or libusb initialization failed),
     * and the caller must skip all subsequent native operations.
     */
    suspend fun init(): Boolean {
        if (!libraryLoaded) return false
        return withContext(jniContext) { nativeInitImpl() }
    }

    /**
     * Set the log callback; null clears it (releasing the JNI global reference).
     * Called by the UI thread during composition/destruction; must execute synchronously (must not become asynchronous/suspend dispatch):
     * if clearing in onDispose were queued, the global reference might not be released in time after Activity destruction;
     * the native implementation must also remain nonblocking: this is a synchronous composition-path call, so blocking causes ANR;
     * silently ignored if the library is unavailable.
     */
    fun setLogCallback(callback: LogCallback?) {
        if (libraryLoaded) setLogCallbackImpl(callback)
    }

    suspend fun bindUsbDevice(fd: Int, vendorId: Int, productId: Int): DeviceBindResult {
        if (!libraryLoaded) return DeviceBindResult.Failure.UnknownError
        return withContext(jniContext) {
            val outBusid = arrayOfNulls<String>(1)
            val code = bindUsbDeviceImpl(fd, vendorId, productId, outBusid)
            if (code == ErrorCode.SUCCESS) {
                // JNI must have written busid on SUCCESS; this defensive check prevents NPE after future implementation changes.
                val busid = outBusid[0]
                if (busid != null) {
                    DeviceBindResult.Success(busid)
                } else {
                    // Contract violation: native reports success without delivering busid. On delivery failure (NewStringUTF/
                    // SetObjectArrayElement), native rolls back itself and returns UNKNOWN_ERROR,
                    // taking the else branch; reaching here means native falsely reported success, rather than duplicate unbinding.
                    // The caller has not registered the device and will only close the connection later; without explicitly unbinding, native retains an orphan
                    // that nobody can unbind. Use the InContext version rather than the public method: the jniContext
                    // permit is already held, and the public method's withContext would queue waiting for itself (without relying on the inline
                    // fast path). The library-loading guard is in the public layer, and this call is already past the outer guard.
                    val rollback = unbindUsbDeviceInContext(fd)
                    // Log the two paths separately to avoid describing nothing to roll back (DeviceNotFound, etc.) with the same
                    // wording as success: rollback failure leaves a native orphan nobody can unbind, since busid is unavailable;
                    // only release/server restart can reclaim everything.
                    if (rollback is DeviceUnbindResult.Success) {
                        Log.e(TAG, "bind returned SUCCESS but busid is null, rolled back by fd, fd=$fd")
                    } else {
                        Log.e(TAG, "bind returned SUCCESS but busid is null, rollback by fd failed: ${rollback::class.simpleName}, fd=$fd")
                    }
                    DeviceBindResult.Failure.UnknownError
                }
            } else {
                errorCodeToBindFailure(code)
            }
        }
    }

    suspend fun unbindUsbDevice(fd: Int): DeviceUnbindResult {
        if (!libraryLoaded) return DeviceUnbindResult.Failure.UnknownError
        return withContext(jniContext) { unbindUsbDeviceInContext(fd) }
    }

    // Version without withContext: for callers already holding the jniContext permit (bindUsbDevice's
    // contract-violation rollback). Nesting the public method's withContext while holding the permit would queue waiting for itself;
    // this does not rely on implementation details such as the same-dispatcher inline fast path. Public methods centralize the
    // library-loading guard; the caller must ensure it has passed the guard and is already inside jniContext.
    private fun unbindUsbDeviceInContext(fd: Int): DeviceUnbindResult {
        return when (val code = unbindUsbDeviceImpl(fd)) {
            ErrorCode.SUCCESS -> DeviceUnbindResult.Success
            ErrorCode.DEVICE_NOT_FOUND -> DeviceUnbindResult.Failure.DeviceNotFound
            ErrorCode.DEVICE_IN_USE -> DeviceUnbindResult.Failure.DeviceInUse
            ErrorCode.SERVER_NOT_RUNNING -> DeviceUnbindResult.Failure.ServerNotRunning
            else -> {
                Log.e(TAG, "Unknown unbind error: $code")
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    /**
     * Notify native that a device has disappeared. No result is returned: a missing busid on the native side is normal
     *(the device was cleaned up through another path); processing failures only produce native logs. The caller follows the semantics that
     * detach cleanup must complete, independently of success or failure here.
     */
    suspend fun notifyDeviceRemoved(busid: String) {
        if (!libraryLoaded) return
        withContext(jniContext) { notifyDeviceRemovedImpl(busid) }
    }

    /**
     * Start the server. Return the actual listening port: the current real port after successful startup or if already running
     *(if already running, this may be the port from an earlier startup, rather than this input); return -1 on failure.
     * The caller must use this result to fill in state's port, rather than infer it from the input.
     */
    suspend fun startServer(port: Int): Int {
        if (!libraryLoaded) return -1
        return withContext(jniContext) { startServerImpl(port) }
    }

    suspend fun stopServer() {
        if (!libraryLoaded) return
        withContext(jniContext) { stopServerImpl() }
    }

    suspend fun release() {
        if (!libraryLoaded) return
        withContext(jniContext) { releaseImpl() }
    }

    /**
     * Execute a block synchronously on the native thread, for non-coroutine cases such as Service destruction.
     * Note: runBlocking blocks the current thread; calling it on the main thread is forbidden (causes ANR);
     * calling it inside the native context (nativeDispatcher/jniContext tasks) is also forbidden:
     * if the current thread holds this limited dispatcher's sole permit, blocking to wait for its own permit causes self-deadlock
     *(unlike withContext's inline fast path, which switches within the same coroutine; here the entire thread is blocked).
     * The only current call site is onDestroy's dedicated cleanup thread (a plain thread holding no permit).
     * Both misuses are caught at runtime: main-thread check plus the thread marker inside native tasks (see
     * the wrapper at nativeDispatcher's declaration).
     * internal: for this application's service layer only, not a public API.
     */
    @WorkerThread
    internal fun runOnNativeThread(block: suspend () -> Unit) {
        // Misuse assertion (active in all builds): a main-thread call would block runBlocking until native finishes;
        // the assertion fails immediately instead of silently stalling until ANR.
        check(Looper.myLooper() != Looper.getMainLooper()) { "runOnNativeThread must not be called on the main thread" }
        // When the same thread is already inside a native task (holding the sole permit), runBlocking blocks it,
        // and acquiring the permit again necessarily queues waiting for itself (silent deadlock, hard to diagnose without logs); fail at entry.
        check(!nativeDispatcher.isInNativeTask) { "runOnNativeThread must not be called from inside a native task" }
        runBlocking {
            // Use jniContext rather than the bare dispatcher: follow the same discipline as other JNI calls
            //(serialized and non-cancellable), avoiding cancellation leaving partially completed state when the calling context changes.
            withContext(jniContext) {
                block()
            }
        }
    }

    // JNI error code -> failure result: this is the sole mapping, so adding error codes does not miss updates at call sites.
    private fun errorCodeToBindFailure(code: Int): DeviceBindResult.Failure = when (code) {
        ErrorCode.DEVICE_NOT_FOUND -> DeviceBindResult.Failure.DeviceNotFound
        ErrorCode.DEVICE_IN_USE -> DeviceBindResult.Failure.DeviceInUse
        // An already-bound busid also means unavailable; reuse the same message.
        ErrorCode.DEVICE_ALREADY_BOUND -> DeviceBindResult.Failure.DeviceInUse
        ErrorCode.DEVICE_OPEN_FAILED -> DeviceBindResult.Failure.DeviceOpenFailed
        ErrorCode.GET_DESCRIPTOR_FAILED -> DeviceBindResult.Failure.GetDescriptorFailed
        ErrorCode.GET_CONFIG_FAILED -> DeviceBindResult.Failure.GetConfigFailed
        ErrorCode.CLAIM_INTERFACE_FAILED -> DeviceBindResult.Failure.ClaimInterfaceFailed
        ErrorCode.HUB_FILTERED -> DeviceBindResult.Failure.HubFiltered
        ErrorCode.SERVER_NOT_RUNNING -> DeviceBindResult.Failure.ServerNotRunning
        else -> DeviceBindResult.Failure.UnknownError
    }
}
