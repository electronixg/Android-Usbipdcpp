package com.yunsmall.usbipdcpp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsbService : Service() {

    companion object {
        private const val TAG = "UsbService"
        private const val NOTIFICATION_CHANNEL_ID = "usbipd_service"
        private const val NOTIFICATION_ID = 1
        // Default listening port. The port input field also uses this value so the literal is defined only once.
        const val DEFAULT_PORT = 3240
    }

    /**
     * Complete externally visible Service state. Both the UI and notification are rendered from this single source,
     * instead of maintaining separate cached copies that require manual synchronization.
     */
    data class UiState(
        val nativeReady: Boolean = false,
        val serverRunning: Boolean = false,
        // Preserve the last port value after stopping the server (stopServer only clears the running flag and device table),
        // so the UI can display it and reuse it when restarting.
        val port: Int = DEFAULT_PORT,
        // deviceName -> busid, used by the UI to display the busid.
        val boundDevices: Map<String, String> = emptyMap()
    )

    /**
     * One-shot events: SharedFlow has no replay, so events are naturally dropped when there is no collector
     * (for example when the UI is absent, or just after binding before the UI begins collecting). This is intentional:
     * only the notification is lost; binding state is published through _state and does not depend on event delivery.
     */
    sealed interface Event {
        /** Device was detached; wasBound indicates whether it was bound before detachment, so the UI can decide whether to notify. */
        data class DeviceDetached(val device: UsbDevice, val wasBound: Boolean) : Event

        /** Diagnostic text intended to be shown in the app's on-screen Log panel. */
        data class Diagnostic(val message: String) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Internal service coroutine: native initialization, notification refresh on state changes, and detach cleanup.
    // State writes are thread-safe (MutableStateFlow.update uses a CAS loop).
    // Main.immediate is used only so notification updates occur on the main thread.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Unified context for native operations: native thread + non-cancellable execution.
    // Start/stop, binding/registration, and detach cleanup must run to completion. Cancelling midway could leave native state
    // active while Kotlin has not registered it (the library has the device bound, while Kotlin has no record and may close the connection),
    // creating an orphan that neither side can clean up reliably. Final cleanup is handled by release in onDestroy rather than cancellation;
    // cancellation of the caller (UI) only affects whether the caller receives the result.
    // Reuse UsbIpNative.jniContext rather than composing another context. Nested withContext fast-path behavior compares interceptor
    // instances, so sharing the same object prevents the two sides from diverging.
    private val serviceNativeContext = UsbIpNative.jniContext

    private val binder = UsbBinder()

    // Store active USB connections. Read/write only on the nativeDispatcher thread (same critical section as native calls).
    // Externally visible binding state is published through _state, so a normal map is sufficient here.
    private data class DeviceInfo(
        val connection: UsbDeviceConnection,
        val fd: Int,
        val busid: String
    )
    private val activeDevices = mutableMapOf<String, DeviceInfo>()

    // Calling notify with the same ID before onStartCommand would take effect before startForeground and create confusing semantics.
    // Wait until the foreground notification is established, then let state changes drive updates.
    private var notificationStarted = false

    // Set when onDestroy begins cleanup (release + close all connections) to reject new bindDevice requests.
    // Such a bind would either fail after cleanup or succeed just before cleanup and then be removed by the cleanup thread,
    // leaving a stale "registered in Kotlin but already cleaned on both sides" state. @Volatile: written on the main thread
    // (onDestroy) and read on the native thread (inside bindDevice's withContext).
    @Volatile
    private var destroying = false

    inner class UsbBinder : Binder() {
        fun getService(): UsbService = this@UsbService
    }

    // Service-owned detach receiver: the service may keep running in the foreground after the Activity is destroyed (user leaves the UI).
    // Detach cleanup therefore cannot depend on the UI layer, or activeDevices and connections could leak.
    // This is the only detach-cleanup path; the UI only consumes Event objects to show messages.
    private val deviceDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { usbDevice ->
                val detachDiagnostic =
                    "USB DETACH BROADCAST: " +
                        "name=${usbDevice.deviceName}, " +
                        "vid=${String.format("%04X", usbDevice.vendorId)}, " +
                        "pid=${String.format("%04X", usbDevice.productId)}, " +
                        "deviceId=${usbDevice.deviceId}, " +
                        "interfaces=${usbDevice.interfaceCount}, " +
                        "bound=${state.value.boundDevices.containsKey(usbDevice.deviceName)}"

                Log.e(TAG, detachDiagnostic)
                _events.tryEmit(Event.Diagnostic(detachDiagnostic))
                // onReceive runs on the main thread; switch device cleanup to the native thread.
                // Launch a coroutine rather than using runBlocking so the main thread is not blocked.
                serviceScope.launch { handleDeviceDetached(usbDevice) }
            }
        }
    }

    init {
        // Single state-change output path: notification text follows _state, avoiding manual refresh calls at every mutation point.
        serviceScope.launch {
            state.collect { if (notificationStarted) updateNotification() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Native initialization includes library loading and libusb initialization; run it on the native thread to avoid blocking the main thread.
        serviceScope.launch {
            val ready = UsbIpNative.init()
            _state.update { it.copy(nativeReady = ready) }
            if (!ready) {
                Log.e(TAG, "Native initialization failed, USB/IP features unavailable")
            }
        }

        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System broadcasts are not blocked by RECEIVER_NOT_EXPORTED (it only blocks broadcasts from other apps).
            // Exporting this receiver would unnecessarily allow forged "device detached" broadcasts.
            registerReceiver(deviceDetachedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceDetachedReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationStarted = true
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Set this flag as early as possible: while the cleanup thread waits behind in-flight native calls,
        // a late bindDevice request could otherwise still be accepted.
        destroying = true
        try {
            unregisterReceiver(deviceDetachedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered", e)
        }
        // Cancellation here does not conflict with cleanup below. If init() has not started, it is cancelled (g_initialized
        // remains false and release skips libusb_exit). If it already started on jniContext (NonCancellable), it completes and
        // sets the flag, correctly pairing with release's libusb_exit.
        serviceScope.cancel()
        // Native cleanup can include expensive operations such as joining threads. runBlocking on the main thread could cause an ANR,
        // so cleanup runs on a background thread (the system may reclaim the process on exit, so completion is not guaranteed).
        // closeAllDevices also runs on the native thread: during destruction, a MainActivity coroutine may still be executing bindDevice
        // on nativeDispatcher, so map writes must remain serialized on the same thread.
        // The closure holds a strong reference to this, so on the JVM the Service object cannot be garbage-collected while the cleanup
        // thread is running; there is no C++-style use-after-free.
        // The cleanup thread may need to wait for an in-progress native call (for example bindDevice blocked on a USB transfer),
        // potentially for a long time. No timeout is used: onDestroy is the terminal path, and the worst case is that the thread remains
        // until Android kills the process. Process termination is controlled by the system regardless of daemon status; a timeout could
        // prevent release from running at all and leave cleanup even less complete.
        // release must run before closeAllDevices. release first stops the server; LibusbServer::stop releases/closes/unrefs all available
        // and in-use devices (verified against the library source). After it returns the library no longer depends on the fd, so the
        // UsbDeviceConnection can then be closed without a window where libusb still uses an already-closed fd.
        // If the system destroys and recreates the Service in the same process (rare for a foreground service), this cleanup and the new
        // instance's init have no guaranteed ordering. In the bad order (new init first, old cleanup second), cleanup may exit the newly
        // initialized libusb; startServerImpl's idempotent init restores it (binding requires the server to be started first). In an extreme
        // scheduling case where old cleanup runs after the new instance starts its server, it may also stop the new server. Restarting the
        // server recovers, so no generation protocol is introduced for this rare case.
        // Whether native logs reach the UI during cleanup depends on whether the log callback is still registered. If the Activity is
        // destroyed before the Service, onDispose has already removed the callback and those logs only go to logcat. This does not affect
        // cleanup itself, so the callback is not kept alive just for logging.
        // Name the thread so logs can identify this cleanup path if it stalls or fails (instead of an anonymous Thread-N).
        thread(name = "usbipd-native-cleanup") {
            // Log both start and finish. Cleanup may wait for a non-cancellable native call to complete, and the time between these
            // messages makes it clear where cleanup is stalled.
            Log.i(TAG, "Native cleanup started")
            // Add an outer safety net: an exception escaping to this raw thread's default handler could terminate the process, while a
            // Service being stopped/reclaimed does not necessarily mean the app process should exit. Final cleanup is best-effort;
            // failures are logged only.
            try {
                UsbIpNative.runOnNativeThread {
                    // release stops the server and releases libusb internally, so there is no need to separately check whether the server
                    // is running. finally ensures connections are closed even if release throws.
                    try {
                        UsbIpNative.release()
                    } finally {
                        closeAllDevices()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Native cleanup failed", t)
            }
            Log.i(TAG, "Native cleanup finished")
        }
        // Call super last: the usual order for destruction callbacks is to clean up owned resources first, then let the base class finish.
        // Creation callbacks such as onCreate use the opposite order (super first). This matches MainActivity.onDestroy.
        super.onDestroy()
    }

    suspend fun startServer(port: Int): Boolean {
        // As with bindDevice, reject requests once destruction begins (see destroying). Cleanup is releasing all native resources;
        // starting now could leave an unmanaged server outside the destruction lifecycle.
        if (destroying) {
            return false
        }
        // Keep the check, start operation, and state update in the same critical section; checking outside it could race with
        // a concurrent startServer call and allow the later caller to store a state.port that does not match the actual listening
        // port. The UI's isStarting flag only prevents repeated taps and is not relied upon for concurrency correctness.
        // Always fill state.port with the actual listening port returned by JNI rather than inferring it from the input.
        return withContext(serviceNativeContext) {
            // Recheck the destruction flag because the main thread may have set it between the entry check and this block (same as bindDevice).
            if (destroying) {
                return@withContext false
            }
            // If already running, return whether the actual listening port matches this request. The same port is an idempotent success;
            // a different port returns false because the server was not started with the requested value. Compare against state.port,
            // which stores the actual value, rather than inferring it from the input.
            if (state.value.serverRunning) {
                return@withContext state.value.port == port
            }

            val actualPort = UsbIpNative.startServer(port)
            val success = actualPort >= 0
            if (success) {
                // Store the actual port returned by native code. If state somehow lags native state (for example an OOM-level update
                // failure leaves serverRunning false while the server is actually running), execution reaches here and records the true
                // listening port instead of displaying an inferred value.
                _state.update { it.copy(serverRunning = true, port = actualPort) }
            }
            success
        }
    }

    suspend fun stopServer() {
        // As with startServer/bindDevice, reject once destruction begins (see destroying). The cleanup thread is responsible for stopping
        // the server and releasing all resources; another stop would duplicate that work. Keeping all three operation entry points
        // consistent makes destruction semantics predictable.
        if (destroying) {
            return
        }
        withContext(serviceNativeContext) {
            // Recheck the destruction flag because the main thread may have set it between the entry check and this block (same as bindDevice).
            if (destroying) {
                return@withContext
            }
            try {
                UsbIpNative.stopServer()
            } finally {
                // Cleanup belongs in finally: even if stop throws, connections and state must return to "stopped"; otherwise the UI could
                // show running while native is stopped, and retrying would not reliably repair the mismatch.
                // closeAllDevices runs on the native thread (guaranteed by withContext), avoiding races with concurrent binding.
                // The order must not be reversed: first make the library stop using the fd, then close the connection.
                closeAllDevices()
                _state.update { it.copy(serverRunning = false, boundDevices = emptyMap()) }
            }
        }
    }

    suspend fun bindDevice(usbManager: UsbManager, device: UsbDevice): DeviceBindResult {
        // Reject new bindings after destruction begins (see destroying). Requests queued after this check are caught by the recheck
        // inside the critical section.
        if (destroying) {
            return DeviceBindResult.Failure.ServerNotRunning
        }
        // The entire method runs on the native thread: device-table reads/writes must share the same critical section as native calls.
        // The context is non-cancellable (see serviceNativeContext), so binding and registration either complete together or do not occur;
        // there is no orphan state where native is bound but Kotlin is not. Caller cancellation only affects receipt of the result.
        return withContext(serviceNativeContext) {
            // Recheck the destruction flag because the main thread may have set it between the entry check and this block (onDestroy
            // and bindDevice both enter from the main thread). Cleanup is queued on the same jniContext;
            // rejecting here inside the critical section avoids a stale state where a device is bound and immediately cleaned up.
            if (destroying) {
                return@withContext DeviceBindResult.Failure.ServerNotRunning
            }
            // Defensive check: reject a device that is already bound, otherwise replacing the map entry would leak the old connection.
            if (activeDevices.containsKey(device.deviceName)) {
                return@withContext DeviceBindResult.Failure.DeviceInUse
            }

            val bindDiagnostic = buildString {
                appendLine("=== USB DEVICE BEFORE BIND ===")
                appendLine("name=${device.deviceName}")
                appendLine("vid=${String.format("%04X", device.vendorId)}")
                appendLine("pid=${String.format("%04X", device.productId)}")
                appendLine("deviceId=${device.deviceId}")
                appendLine("class=${device.deviceClass}")
                appendLine("subclass=${device.deviceSubclass}")
                appendLine("protocol=${device.deviceProtocol}")
                appendLine("interfaces=${device.interfaceCount}")

                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    appendLine(
                        "IF[$i]: id=${intf.id}, class=${intf.interfaceClass}, " +
                            "subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}, " +
                            "endpoints=${intf.endpointCount}"
                    )
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        appendLine(
                            "  EP[$e]: address=0x${ep.address.toString(16)}, " +
                                "type=${ep.type}, direction=${ep.direction}, " +
                                "maxPacket=${ep.maxPacketSize}, interval=${ep.interval}"
                        )
                    }
                }
                append("=== END USB DEVICE ===")
            }
            Log.e(TAG, bindDiagnostic)
            _events.tryEmit(Event.Diagnostic(bindDiagnostic))

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                return@withContext DeviceBindResult.Failure.DeviceOpenFailed
            }

            // After successful registration the connection is owned by activeDevices. All other paths (including exceptions) close it in
            // finally; if binding fails or throws before registration, keeping it open would leak the fd.
            var registered = false
            try {
                // fd < 0 should not occur after a successful openDevice, but handle it defensively: return failure and let finally close
                // the connection. getFileDescriptor is a public SDK API (since API 12) and returns an int raw fd, not a FileDescriptor;
                // it is not a hidden API and requires no reflection fallback.
                val fd = connection.fileDescriptor
                if (fd < 0) {
                    return@withContext DeviceBindResult.Failure.DeviceOpenFailed
                }

                when (val result = UsbIpNative.bindUsbDevice(fd, device.vendorId, device.productId)) {
                    is DeviceBindResult.Success -> {
                        activeDevices[device.deviceName] = DeviceInfo(connection, fd, result.busid)
                        // Successful registration transfers ownership of the connection to activeDevices (finally uses this to decide whether to close it).
                        // Set registered exactly when registration succeeds. If the map insertion fails (OOM-level failure), registered
                        // remains false and finally closes the unregistered connection. Native may already hold a binding with no Kotlin
                        // registration; the common stopServer/detach/release cleanup chain handles that residual state. Adding another
                        // unbind fallback for an OOM-level path would add complexity; setting registered earlier would instead allow a
                        // failed connection to escape finally without being closed.
                        // registered must also be set before the state update. If the state update fails, registration is not rolled back;
                        // the connection is already owned by the device table and must not be closed here. The common stopServer/detach/release
                        // cleanup path will eventually reconcile it. Until then the UI may differ from native state; no active self-healing
                        // is attempted for this exceptional condition.
                        registered = true
                        _state.update {
                            it.copy(boundDevices = it.boundDevices + (device.deviceName to result.busid))
                        }
                        Log.i(TAG, "Device bound: ${device.deviceName} -> ${result.busid}")
                        result
                    }
                    is DeviceBindResult.Failure -> result
                }
            } catch (t: CancellationException) {
                // Structured concurrency rule: cancellation must propagate upward and must not be converted into an ordinary failure result.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "bindDevice failed unexpectedly: ${device.deviceName}", t)
                // If registered is already true, native binding and device-table registration completed; only the subsequent state publish
                // failed (an OOM-level condition). The truth is that the device is bound. Reporting failure would make a retry return
                // DeviceInUse and be more confusing. Return Success and let the stopServer/detach/release cleanup chain eventually align
                // UI state. Do not roll back native binding here: under the fault conditions that reached this branch, rollback could also
                // fail. Keeping the registration and using the single common cleanup path is more deterministic.
                val boundBusid = if (registered) activeDevices[device.deviceName]?.busid else null
                if (boundBusid != null) {
                    DeviceBindResult.Success(boundBusid)
                } else {
                    DeviceBindResult.Failure.UnknownError
                }
            } finally {
                if (!registered) {
                    // Guard close against exceptions: a release failure is logged only. Throwing from finally could become an uncaught
                    // coroutine exception (the caller scope has no fallback) and crash the app.
                    runCatching { connection.close() }
                        .onFailure { e -> Log.w(TAG, "close device failed", e) }
                }
            }
        }
    }

    suspend fun unbindDevice(deviceName: String): DeviceUnbindResult {
        // As with startServer/bindDevice, reject once destruction begins (see destroying). The cleanup thread will remove all devices,
        // so an unbind here would only duplicate cleanup. Report idempotent success, matching the DeviceNotFound semantics: the target
        // state (connection closed, state cleared) will be achieved.
        if (destroying) {
            return DeviceUnbindResult.Success
        }
        // As with bindDevice, use serviceNativeContext so cleanup cannot be interrupted by caller cancellation.
        return withContext(serviceNativeContext) {
            // Recheck the destruction flag because the main thread may have set it between the entry check and this block (same as bindDevice).
            if (destroying) {
                return@withContext DeviceUnbindResult.Success
            }
            val info = activeDevices[deviceName]
                ?: return@withContext DeviceUnbindResult.Failure.DeviceNotFound

            // If native no longer has this device (for example it was removed by detach cleanup), Kotlin must also clear the connection
            // and state; otherwise the connection leaks and the UI remains stuck showing it as bound.
            fun cleanup() {
                // Remove registration before closing the connection so any close failure cannot prevent removal and state update;
                // otherwise the UI could permanently show "bound" after native has already unbound it. closeAllDevices follows the same rule.
                val removed = activeDevices.remove(deviceName)
                runCatching { removed?.connection?.close() }
                    .onFailure { e -> Log.w(TAG, "close device failed", e) }
                _state.update { it.copy(boundDevices = it.boundDevices - deviceName) }
            }

            // On exception, keep the registration intact. The device may still be bound natively; deleting Kotlin's record would create
            // an orphan that cannot be unbound. Keeping the record allows the caller to retry.
            try {
                when (val result = UsbIpNative.unbindUsbDevice(info.fd)) {
                    DeviceUnbindResult.Success -> {
                        cleanup()
                        Log.i(TAG, "Device unbound: $deviceName")
                        result
                    }
                    // If the server is gone or the device has disappeared, still clear the Kotlin connection/state and report idempotent
                    // success. The unbind goal (connection closed, state cleared) has already been achieved; reporting Failure would make
                    // the UI claim unbind failed when cleanup actually succeeded.
                    DeviceUnbindResult.Failure.DeviceNotFound,
                    DeviceUnbindResult.Failure.ServerNotRunning -> {
                        cleanup()
                        Log.w(TAG, "Device already gone in native: $deviceName")
                        DeviceUnbindResult.Success
                    }
                    DeviceUnbindResult.Failure.DeviceInUse -> result
                    else -> {
                        Log.e(TAG, "Unknown unbind error: $result for $deviceName")
                        result
                    }
                }
            } catch (t: CancellationException) {
                // As with bindDevice, cancellation must propagate upward and must not be converted into an ordinary failure result.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "unbindDevice failed unexpectedly: $deviceName", t)
                DeviceUnbindResult.Failure.UnknownError
            }
        }
    }

    /**
     * Device-detach cleanup: the entire block runs on the native thread. Notify native code first, then close the Kotlin connection.
     * notify_device_removed synchronously removes the device or stops its session. After physical detach the fd is invalid, and the native
     * libusb handle owns an independent fd, so closing one does not affect the other. This is called only by the Service's own receiver;
     * the UI no longer participates in cleanup.
     */
    private suspend fun handleDeviceDetached(usbDevice: UsbDevice) {
        // Use serviceNativeContext just like bindDevice/unbindDevice: registration removal, native notification, and state update form one
        // cleanup transaction that caller cancellation cannot interrupt.
        // Emit the event inside the block as well. Outside the block there is a coroutine resumption point; if the Service is destroyed
        // at exactly that time, the event might never be emitted.
        withContext(serviceNativeContext) {
            val info = activeDevices.remove(usbDevice.deviceName)
            if (info == null) {
                if (!_events.tryEmit(Event.DeviceDetached(usbDevice, false))) {
                    Log.w(TAG, "DeviceDetached event dropped (buffer full): ${usbDevice.deviceName}")
                }
                return@withContext
            }
            // Even if native notification fails, Kotlin-side cleanup must finish. The device is physically detached, so the connection
            // and state must be cleared or the UI would show it as "bound" forever.
            try {
                UsbIpNative.notifyDeviceRemoved(info.busid)
            } catch (t: CancellationException) {
                // As with bindDevice, cancellation must propagate upward and must not be converted into an ordinary failure result.
                throw t
            } catch (t: Throwable) {
                Log.e(TAG, "notifyDeviceRemoved failed: ${usbDevice.deviceName}", t)
            } finally {
                // Guard close against exceptions: failure must not skip the following state cleanup and event emission, or the UI could
                // permanently show the detached device as "bound".
                runCatching { info.connection.close() }
                    .onFailure { e -> Log.w(TAG, "close device failed", e) }
                _state.update { it.copy(boundDevices = it.boundDevices - usbDevice.deviceName) }
                // Event emission and state update should stay together. A cancellation exception would skip statements after finally,
                // leaving state cleared but the UI without a "device detached" message. If tryEmit drops the event (for example the buffer
                // is full while the UI main thread is blocked), only the message is lost; state is already correct. Log it to diagnose
                // reports where no message was shown.
                if (!_events.tryEmit(Event.DeviceDetached(usbDevice, true))) {
                    Log.w(TAG, "DeviceDetached event dropped (buffer full): ${usbDevice.deviceName}")
                }
            }
            Log.i(TAG, "Device detached: ${usbDevice.deviceName}")
        }
    }

    // Call only on the nativeDispatcher thread. Closing devices and clearing the bound-device list are coupled here so callers
    // (stopServer/onDestroy cleanup) do not each need to remember to clear _state. Otherwise the UI could show devices as bound after
    // their connections are already closed. _state is the single source of truth. Guard each close independently so one failure does not
    // interrupt closing the remaining devices or clearing state.
    private fun closeAllDevices() {
        // Misuse assertion: catch the most realistic bad call path, the main thread (serviceScope uses Main.immediate, so a direct call
        // from there would trigger this). A complete check that the nativeDispatcher permit is held would require exposing the dispatcher;
        // avoid adding that machinery for a hypothetical case.
        check(Looper.myLooper() != Looper.getMainLooper()) { "closeAllDevices must not run on the main thread" }
        activeDevices.values.forEach {
            runCatching { it.connection.close() }
                .onFailure { e -> Log.w(TAG, "close device failed", e) }
        }
        activeDevices.clear()
        _state.update { it.copy(boundDevices = emptyMap()) }
        Log.i(TAG, "All devices closed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "USB/IP Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            // Display running state dynamically: when the server is not running, show it as stopped to avoid misleading the user.
            .setContentText(getString(if (state.value.serverRunning) R.string.server_running else R.string.server_stopped))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification())
    }
}
