package com.yunsmall.usbipdcpp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.yunsmall.usbipdcpp.ui.MainScreen
import com.yunsmall.usbipdcpp.ui.theme.UsbipdcppTheme

// Keep the pre-split "MainActivity" log tag so splitting files does not affect familiar logcat filters;
// MainScreen.kt and NetworkUtils.kt also retain the same tag individually.
private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    // Use lazy: onDestroy calls unregisterReceiver even if registerReceiver was never called,
    // triggering initialization here, but construction itself has no side effects (PendingIntent is also lazy),
    // and unregisterReceiver internally catches the exception thrown when not registered.
    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(this, usbManager)
    }

    // Service reference observed by Compose: ServiceConnection callbacks run on the main thread; writing
    // State directly automatically recomposes, without manually adding state-change callbacks and remembering to clear them everywhere.
    private val usbServiceHolder = mutableStateOf<UsbService?>(null)

    // bindService's return value: true means the binding request was accepted (onServiceConnected merely
    // arrives asynchronously later), so unbindService must subsequently run even if the callback never arrived;
    // calling unbindService after false (the system rejected binding) throws IllegalArgumentException.
    private var bindRequested = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Safe cast: the system only returns the Binder from this Service's onBind, but if a second
            // Binder (multiple Actions) or another IBinder type is introduced later, a hard cast would throw ClassCastException
            // inside the system callback and crash; simply treat cast failure as disconnected.
            val binder = service as? UsbService.UsbBinder ?: run {
                Log.e(TAG, "onServiceConnected with unexpected binder: $service")
                return
            }
            usbServiceHolder.value = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbServiceHolder.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionManager.registerReceiver()

        // Start and bind the Service.
        val serviceIntent = Intent(this, UsbService::class.java)
        startForegroundService(serviceIntent)
        bindRequested = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            UsbipdcppTheme {
                MainScreen(
                    usbManager = usbManager,
                    permissionManager = permissionManager,
                    usbService = usbServiceHolder.value
                )
            }
        }
    }

    override fun onDestroy() {
        permissionManager.unregisterReceiver()
        // Unbind only when bindRequested (bindService's return value) is true: it means binding was accepted,
        // whether or not onServiceConnected arrived; false means it was not accepted, and calling
        // unbindService then throws IllegalArgumentException.
        // Note: onServiceDisconnected (service process crash) does not revoke this flag; the binding remains valid,
        // and the system invokes onServiceConnected again after service restart, so unbinding is still required.
        // usbServiceHolder need not be cleared: it shares this Activity's lifetime; the entire reference chain is reclaimed together.
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        // Do not stop the Service; let it keep running.
        // Call super last: destruction callbacks generally clean up owned resources first, then let the base class
        // finish (creation callbacks such as onCreate reverse this, calling super first). Future cleanup code
        // can simply be added above.
        super.onDestroy()
    }
}
