package com.yunsmall.usbipdcpp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Unified entry point for USB permission requests and device-list changes.
 * permissionIntent is lazy: PendingIntent.getBroadcast creates a system object, needed only when
 * actually requesting permission. This class has a path where it is constructed but never requests permission
 *(the fallback unregister in onDestroy); avoid these side effects during construction.
 */
class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    companion object {
        private const val TAG = "UsbPermissionManager"
        const val ACTION_USB_PERMISSION = "com.yunsmall.usbipdcpp.USB_PERMISSION"
        // PendingIntent's requestCode is part of the system matching key: use a project-unique value
        // to prevent merging with future broadcast PendingIntents (whose default requestCode is also 0).
        private const val PERMISSION_REQUEST_CODE = 1001
        // Stale window for pending entries: if the system swallows a result and never delivers it, allow a new request for this device
        // after timeout; align with the camera-permission stale threshold.
        private const val PERMISSION_PENDING_TIMEOUT_MS = 60_000L
    }

    private val permissionIntent: PendingIntent by lazy {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        PendingIntent.getBroadcast(context, PERMISSION_REQUEST_CODE, intent, flags)
    }

    // Shared lock object: if onReceive and requestPermission each use synchronized(this),
    // this refers to different objects (Receiver vs Manager instances), providing no mutual exclusion at all.
    private val lock = Any()
    // Accepted device names awaiting system results -> acceptance time (elapsedRealtime),
    // removed on result arrival or detach. Store only device names, not callbacks: the Activity may be recreated while
    // the USB permission dialog is open, invalidating callbacks with the old composition; onPermissionResult instead delivers
    // results to the new listener after recreation. Record time for stale detection: if the system swallows a result and never delivers it,
    // the entry would permanently block this device (same principle as CAMERA_PENDING_TIMEOUT_MS for camera permission).
    private val pendingDevices = mutableMapOf<String, Long>()
    // Listeners are read/written only on the main thread (composition and broadcast onReceive both run there); no locking needed.
    private var onDeviceListChanged: (() -> Unit)? = null
    // Sole exit for permission results; see setOnPermissionResultListener.
    private var onPermissionResult: ((UsbDevice, Boolean) -> Unit)? = null

    fun setOnDeviceListChangedListener(listener: (() -> Unit)?) {
        onDeviceListChanged = listener
    }

    /**
     * Deliver all permission results through the current listener rather than registering callbacks per request: the Activity may be recreated
     * while the dialog is open, leaving callbacks registered on the old instance unclaimed (the new instance's receiver
     * receives the result broadcast). Only delivering to the currently surviving UI can continue the binding flow.
     * Immediate results for existing permission also use this exit; callers need not distinguish fast and slow paths.
     * The listener is called synchronously on the existing-permission path: implementations must return immediately and must not
     * reenter requestPermission in this callback (which would cause recursion on the same stack).
     */
    fun setOnPermissionResultListener(listener: ((UsbDevice, Boolean) -> Unit)?) {
        onPermissionResult = listener
    }

    // Sole delivery point for permission results: listener implementations come from the composition layer, and both delivery paths' stacks
    // are at boundaries: onReceive is a system callback, while synchronous delivery for existing permission occurs on the Compose event
    // stack. A listener exception crossing either boundary would crash the process; catch and log it here.
    private fun deliverResult(device: UsbDevice, granted: Boolean) {
        try {
            onPermissionResult?.invoke(device, granted)
        } catch (t: Throwable) {
            Log.e(TAG, "Permission result listener threw", t)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    // System permission results always carry EXTRA_DEVICE; a missing extra indicates an unusual implementation and can be ignored;
                    // the timeout window allows retry of the pending entry for that device name.
                    device?.let { usbDevice ->
                        Log.d(TAG, "USB permission result for ${usbDevice.deviceName}: $granted")
                        // Only modify the registry under the lock; deliver results outside it: the listener may initiate another permission
                        // request, and synchronously executing user code under the non-reentrant lock risks deadlock.
                        synchronized(lock) {
                            pendingDevices.remove(usbDevice.deviceName)
                        }
                        // Deliver unconditionally: a request can span Activity recreation (rotation while the dialog is open);
                        // the new instance's registry is empty, so registration cannot be a prerequisite for delivery. The listener checks
                        // whether the device is still present. If no listener exists (the UI is between instances during recreation),
                        // discard the result; the user can tap again.
                        // Known cost: if the old dialog is actually still alive after timeout takeover, both old and new results
                        // are delivered (the second is rejected as DeviceInUse). Schemes distinguishing old and new requests
                        // (such as request tokens) require the new instance to recognize old requests, but tokens disappear with the old
                        // instance, conflicting with delivery across recreation; prioritize delivery.
                        deliverResult(usbDevice, granted)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    onDeviceListChanged?.invoke()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.let { usbDevice ->
                        Log.d(TAG, "USB device detached: ${usbDevice.deviceName}")
                        // After detach, no permission-result broadcast returns; clear the corresponding entry,
                        // otherwise the leftover entry would prevent future permission requests for that device name.
                        synchronized(lock) {
                            pendingDevices.remove(usbDevice.deviceName)
                        }
                    }
                    // The device was physically detached; refresh the device list (UsbService's own receiver handles unbinding
                    // cleanup, independently of this manager).
                    onDeviceListChanged?.invoke()
                }
            }
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // RECEIVER_NOT_EXPORTED blocks only broadcasts from other applications: the system's (system uid)
            // attach/detach broadcasts are unaffected, and permission-result broadcasts arrive through PendingIntent as this application
            //(AMS permits the same uid). Accept both sources; explicitly exporting would instead allow forged broadcasts.
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    fun getDeviceList(): Map<String, UsbDevice> {
        // Exporting hubs through USB/IP is meaningless (they are topology nodes rather than functional devices).
        // Upstream AOSP already excludes hubs from UsbManager in UsbHostManager; filter again here because
        // custom ROMs may change that behavior, and these devices should not appear in the UI.
        return usbManager.deviceList.filterValues { !isHubDevice(it) }
    }

    // Match AOSP UsbHostManager's hub detection: a device descriptor declaring the hub class counts directly;
    // if the descriptor gives no class (bDeviceClass=0), check interfaces; only count it if all interfaces are hub class,
    // avoiding false positives for composite devices containing a hub.
    private fun isHubDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_HUB) return true
        if (device.deviceClass != 0 || device.interfaceCount == 0) return false
        return (0 until device.interfaceCount).all {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HUB
        }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    /**
     * @return true means the request was accepted; results are delivered through [setOnPermissionResultListener]
     *                 (synchronously if permission already exists); false means the same device's latest request is still in flight and this one was not accepted.
     */
    fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) {
            // Existing permission: deliver synchronously; callers need not distinguish fast and slow paths. A null listener
            //(the UI is between instances during recreation) discards delivery; callers all invoke this method within composition,
            // so the listener should already be installed. Keep one log line to diagnose unusual timing.
            if (onPermissionResult != null) {
                deliverResult(device, true)
            } else {
                Log.w(TAG, "Permission already granted but no listener to deliver to")
            }
            return true
        }

        // Register only the device name and acceptance time under the lock; initiate the system request outside it to avoid calling external code while locked.
        // Reject a new request when this device's latest request is still in flight: repeated system requests for the same device have
        // undefined behavior (possibly duplicate dialogs), and the accepting side would lose the basis for reporting an existing in-flight request;
        // after the stale window, treat the result as swallowed by the system (no broadcast delivered), overwrite the entry and request again,
        // otherwise this device name would stay blocked forever until Activity recreation.
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val submittedAt = pendingDevices[device.deviceName]
            if (submittedAt != null && now - submittedAt < PERMISSION_PENDING_TIMEOUT_MS) {
                return false
            }
            if (submittedAt != null) {
                // Reaching here means the entry timed out: log once to diagnose why the user sees two
                // permission dialogs/mismatched results; the previous request's result was deemed lost and taken over.
                Log.w(TAG, "Stale permission request taken over: ${device.deviceName}")
            }
            pendingDevices[device.deviceName] = now
        }
        // On exception, remove the entry before rethrowing: AOSP system-service exceptions also crash the process (no surviving object
        // holds stale state), but a custom ROM might throw without crashing (such as SecurityException),
        // and a leftover entry would prevent any future request for that device name.
        try {
            usbManager.requestPermission(device, permissionIntent)
        } catch (t: Throwable) {
            synchronized(lock) {
                pendingDevices.remove(device.deviceName)
            }
            throw t
        }
        return true
    }
}