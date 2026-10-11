package com.yunsmall.usbipdcpp.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.yunsmall.usbipdcpp.DeviceBindResult
import com.yunsmall.usbipdcpp.DeviceUnbindResult
import com.yunsmall.usbipdcpp.LogCallback
import com.yunsmall.usbipdcpp.R
import com.yunsmall.usbipdcpp.UsbIpNative
import com.yunsmall.usbipdcpp.UsbPermissionManager
import com.yunsmall.usbipdcpp.UsbService
import com.yunsmall.usbipdcpp.util.getDeviceIpAddress
import com.yunsmall.usbipdcpp.util.isCameraDevice
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keep the pre-split "MainActivity" tag so existing logcat filtering habits continue to work.
private const val TAG = "MainActivity"

// Naming convention in this file: deviceName always means UsbDevice.deviceName (the system device name and map key);
// the user-visible name (productName) is consistently called displayName.

// Stale threshold for a pending camera-permission request: after process death the system dialog may already be gone and
// the authorization result may never be delivered. A pending value restored from saved state would then have no reset path and
// would keep blocking camera-device binding. After this timeout, the next user click takes over the request (see onBindDevice).
private const val CAMERA_PENDING_TIMEOUT_MS = 60_000L

// Use DateTimeFormatter instead of SimpleDateFormat because the latter is not thread-safe. addLog currently runs only on the
// main thread (native callbacks are posted through mainHandler and manual refresh runs on main), but callers should not have to
// rely on that hidden assumption. Reuse the formatter instance on the hot path where every log line is formatted.
private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

fun setLanguage(language: String) {
    val localeList = if (language == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    usbManager: UsbManager,
    permissionManager: UsbPermissionManager,
    usbService: UsbService?
) {
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    // Use rememberSaveable so rotation does not discard the port the user just entered.
    var portText by rememberSaveable { mutableStateOf(UsbService.DEFAULT_PORT.toString()) }
    // Logs do not use rememberSaveable: losing them on rotation is harmless (native logs continue to arrive), and
    // serializing up to 500 strings into the Bundle would be pure overhead.
    val logBuffer = remember { LogBuffer() }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var showFullLog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // Set of devices with operations in flight (a Set rather than one value allows multiple devices to bind/unbind concurrently,
    // showing progress and disabling buttons per device; a single value would break those concurrency semantics).
    var busyDevices by remember { mutableStateOf(setOf<String>()) }
    // Store the device name rather than a UsbDevice object (not Parcelable for SavedState): if the system recreates the Activity
    // while the permission dialog is open (for example with "Don’t keep activities"), ordinary remember state would be lost;
    // rememberSaveable ensures the permission callback can still identify the device that was waiting to bind.
    // Normally system result delivery provides the fallback across recreation and process restore; exception paths from launch
    // reset below. After process death, however, the dialog may have disappeared and no result may ever arrive, leaving restored
    // pending state stuck. onBindDevice uses the acceptance timestamp to detect and take over stale requests.
    var pendingBindDeviceName by rememberSaveable { mutableStateOf<String?>(null) }
    // Acceptance time (elapsedRealtime), used only for the stale-request check above. It is monotonic within one boot,
    // and keeps the same time base after process death/recreation with Bundle restore. A device reboot clears the task stack,
    // so an old value cannot be restored across different elapsedRealtime bases.
    var pendingBindSubmittedAt by rememberSaveable { mutableStateOf(0L) }

    // Service state is the single source of truth; the UI only observes it. It no longer keeps its own serverRunning/boundDevices
    // copies and manually refreshes them at each mutation. While the Service is unbound, use an empty placeholder state:
    // flowOf expresses that it is read-only; remember keeps the flow instance stable so collectAsState does not
    // restart collection on every recomposition because of a newly created flow instance.
    val emptyServiceState = remember { flowOf(UsbService.UiState()) }
    // The State object returned by collectAsState remains stable across recompositions and delegated reads always see the current value.
    // rememberLauncherForActivityResult callbacks are forwarded through rememberUpdatedState, so they always call the latest composed
    // performBind. Both paths therefore avoid reading a stale nativeReady value.
    val serviceState by (usbService?.state ?: emptyServiceState).collectAsState(UsbService.UiState())
    val serverRunning = serviceState.serverRunning
    val boundDevices = serviceState.boundDevices

    // After a successful start, fill in the actual port in use. Only do this while running; otherwise Activity recreation would
    // overwrite a port the user typed but has not submitted yet with the default, defeating rememberSaveable.
    LaunchedEffect(serviceState.port, serverRunning) {
        if (serverRunning) {
            portText = serviceState.port.toString()
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The permission callback spans the system permission dialog and possible Activity recreation. Read the latest usbService through
    // rememberUpdatedState; directly capturing a snapshot risks keeping null from before the Service finished binding.
    val currentUsbService by rememberUpdatedState(usbService)

    // Single entry point for Service/native readiness checks, shared before binding and after permission results return, so validation
    // and messages cannot drift apart. Read nativeReady directly from service.state.value rather than the composed serviceState snapshot:
    // during the composition frame where usbService has just connected, that snapshot may still belong to the previous flow.
    fun requireReadyService(): UsbService? {
        val service = currentUsbService ?: run {
            Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            return null
        }
        // Binding is meaningless if native initialization failed; show a clear message instead of waiting for a vague native error.
        if (!service.state.value.nativeReady) {
            Toast.makeText(context, context.getString(R.string.native_init_failed), Toast.LENGTH_SHORT).show()
            return null
        }
        return service
    }

    // Perform device binding (USB permission + native binding).
    fun performBind(device: UsbDevice) {
        // Validate before requesting permission: if Service/native is unavailable, do not show a permission dialog first.
        // bindAfterPermission validates again because the result may arrive after Activity recreation.
        if (requireReadyService() == null) {
            return
        }
        // The result returns through the onPermissionResult listener (delivered synchronously when permission already exists).
        if (!permissionManager.requestPermission(device)) {
            // Request not accepted: the same device already has a pending permission request; tell the user instead of appearing unresponsive.
            Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
        }
    }

    // Binding stage after permission is granted. Do not keep this as a closure nested in the request call: the Activity can be recreated
    // while the USB permission dialog is open, and the recreated composition must be able to continue processing the result.
    fun bindAfterPermission(device: UsbDevice) {
        // The result may arrive after recreation, so obtain the current Service/native state and validate it again.
        // The validation rules come from requireReadyService, shared with performBind,
        // rather than duplicating the checks here.
        val service = requireReadyService() ?: return
        // displayName is the user-visible name; device.deviceName is the system device name used as the key.
        val displayName = device.productName?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.unknown_device)
        scope.launch {
            busyDevices = busyDevices + device.deviceName
            try {
                val result = service.bindDevice(usbManager, device)
                // Binding state propagates automatically to the UI through the Service StateFlow; no manual synchronization is needed.
                when (result) {
                    is DeviceBindResult.Success -> {
                        Toast.makeText(context, context.getString(R.string.bind_success, displayName), Toast.LENGTH_SHORT).show()
                    }
                    is DeviceBindResult.Failure -> {
                        Toast.makeText(context, context.getString(R.string.bind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                busyDevices = busyDevices - device.deviceName
            }
        }
    }

    // Reset the complete pending camera-permission state together: device name and timestamp are two halves of one in-flight request,
    // so clear them as a pair to prevent stale detection from reading mismatched partial state.
    fun clearPendingCameraBind() {
        pendingBindDeviceName = null
        pendingBindSubmittedAt = 0L
    }

    // Camera permission request.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val deviceName = pendingBindDeviceName ?: return@rememberLauncherForActivityResult
        clearPendingCameraBind()
        // After Activity recreation, look the device object up again in the current device list. If it was unplugged while permission
        // was pending, show a message; otherwise pressing "Allow" would appear to do nothing.
        val device = usbManager.deviceList[deviceName] ?: run {
            Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (granted) {
            performBind(device)
        } else {
            // The user explicitly denied camera permission; distinguish this message from "device unavailable" to avoid misleading them.
            Toast.makeText(context, context.getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // logBuffer is a remembered instance. The closure captures the instance reference rather than a value snapshot,
    // so the function instance captured by DisposableEffect during first composition always reads/writes the current state;
    // no rememberUpdatedState forwarding is required.
    fun addLog(message: String) {
        logBuffer.append("[${LocalTime.now().format(logTimeFormat)}] $message")
    }

    fun refreshDevices() {
        devices = permissionManager.getDeviceList()
        addLog("Found ${devices.size} USB device(s)")
    }

    val ipAddress = remember { mutableStateOf<String?>(null) }

    // Obtain the IP address.
    LaunchedEffect(serverRunning) {
        if (serverRunning) {
            // Enumerating network interfaces can take time when many virtual adapters exist; use the IO thread to avoid blocking main.
            ipAddress.value = withContext(Dispatchers.IO) { getDeviceIpAddress() }
        } else {
            // Clear it after stopping: the old address is stale and has no meaning before the next server start.
            ipAddress.value = null
        }
    }

    // Configure the native log callback (registration/removal and main-thread forwarding are handled by NativeLogBridge).
    NativeLogBridge { addLog(it) }

    // Refresh the device list once on first composition; subsequent refreshes are driven by attach/detach broadcasts and the Refresh button.
    LaunchedEffect(Unit) { refreshDevices() }

    // Listen for USB device attachment/detachment through a BroadcastReceiver.
    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceListChangedListener {
            refreshDevices()
        }
        onDispose {
            permissionManager.setOnDeviceListChangedListener(null)
        }
    }

    // USB permission result: the composition that launched the request may already be gone after rotation/system recreation while the
    // dialog was open. Continue binding from the current composition when the result arrives, matching the camera-permission path.
    // permissionManager alone is sufficient as the key: the listener does not capture a usbService snapshot;
    // the binding stage reads the latest Service through currentUsbService (rememberUpdatedState).
    DisposableEffect(permissionManager) {
        permissionManager.setOnPermissionResultListener { device, granted ->
            if (!granted) {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            // If the device was unplugged while permission was pending, show a message just like the camera-permission callback:
            // look it up again in the current device list; if it is missing, it is no longer connected.
            val current = usbManager.deviceList[device.deviceName]
            if (current == null) {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            bindAfterPermission(current)
        }
        onDispose {
            permissionManager.setOnPermissionResultListener(null)
        }
    }

    // Detach notification: the Service performs all device cleanup (its receiver remains active after Activity destruction).
    // The UI only consumes the event for user notification; binding state updates automatically through serviceState.
    LaunchedEffect(usbService) {
        usbService?.events?.collect { event ->
            when (event) {
                is UsbService.Event.DeviceDetached -> {
                    if (event.wasBound) {
                        val displayName = event.device.productName?.takeIf { it.isNotEmpty() }
                            ?: context.getString(R.string.unknown_device)
                        Toast.makeText(context, context.getString(R.string.device_detached, displayName), Toast.LENGTH_SHORT).show()
                    }
                }

                is UsbService.Event.Diagnostic -> {
                    addLog(event.message)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { MainTopBar(onAboutClick = { showAbout = true }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ServerControlPanel(
                serverRunning = serverRunning,
                isStarting = isStarting,
                isStopping = isStopping,
                portText = portText,
                onPortChange = { portText = it },
                onStart = {
                    // An out-of-range port such as 65536 would wrap on the native side when converted to unsigned short, causing the
                    // UI port and actual listening port to disagree. Reject it here first.
                    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    if (port == null) {
                        Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    // Use the single requireReadyService entry point for Service/native readiness, shared with bind/unbind paths,
                    // so validation rules and messages stay consistent. Read the latest Service through currentUsbService
                    // rather than taking another snapshot.
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStarting = true
                    scope.launch {
                        // Reset in finally: if the coroutine is cancelled during composition destruction, do not leave "starting" set.
                        // Recreation initializes state again, but structurally we still avoid leaving a half-finished operation.
                        try {
                            // Start results are reflected through the Service StateFlow; on success the UI automatically enters running state.
                            // On failure (for example port already in use), state does not change, so explicitly notify the user.
                            val started = service.startServer(port)
                            if (!started) {
                                Toast.makeText(context, context.getString(R.string.start_failed), Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            isStarting = false
                        }
                    }
                },
                onStop = {
                    // Same as onStart: readiness validation goes through the single requireReadyService entry point.
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStopping = true
                    scope.launch {
                        try {
                            // After stopping, serverRunning and the bound-device list are cleared through the Service StateFlow.
                            service.stopServer()
                        } finally {
                            // Same as onStart: cancellation during composition destruction must not leave "stopping" set.
                            isStopping = false
                        }
                    }
                }
            )

            StatusCard(
                serverRunning = serverRunning,
                boundCount = boundDevices.size,
                ipAddress = ipAddress.value,
                // Display the port directly from the state source. portText is only the input-field draft and can briefly differ
                // from the actual listening port during the transition frame before LaunchedEffect fills it in.
                port = serviceState.port
            )

            DeviceListSection(
                devices = devices,
                boundDevices = boundDevices,
                busyDevices = busyDevices,
                serverRunning = serverRunning,
                onBindDevice = { device ->
                    if (!serverRunning) {
                        Toast.makeText(context, context.getString(R.string.please_start_server), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // Check through the latest reference, matching the source that performBind reads later.
                    if (currentUsbService == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // Camera devices require CAMERA permission first.
                    if (isCameraDevice(device) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (pendingBindDeviceName != null) {
                            // Stale check: after process death the system dialog may have disappeared and the permission result
                            // may never be delivered. Treat a timed-out request as stale, clear it, and continue with a new request.
                            if (SystemClock.elapsedRealtime() - pendingBindSubmittedAt > CAMERA_PENDING_TIMEOUT_MS) {
                                clearPendingCameraBind()
                            } else {
                                // A request is already in flight: ignore the new one so pendingBindDeviceName is not overwritten
                                // and the wrong device is not bound after authorization. Show a message rather than silently ignoring it,
                                // because silence would look like the tap did nothing.
                                Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
                                return@DeviceListSection
                            }
                        }
                        pendingBindDeviceName = device.deviceName
                        pendingBindSubmittedAt = SystemClock.elapsedRealtime()
                        try {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } catch (e: Exception) {
                            // If launch throws, there will be no callback. Reset here or this device would remain stuck with an
                            // "existing pending request" state preserved across recreation by rememberSaveable.
                            clearPendingCameraBind()
                            Log.e(TAG, "Failed to launch camera permission request", e)
                        }
                    } else {
                        performBind(device)
                    }
                },
                onUnbindDevice = { device ->
                    // Same as onStart: readiness validation uses the single requireReadyService entry point.
                    val service = requireReadyService() ?: return@DeviceListSection
                    val displayName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    scope.launch {
                        busyDevices = busyDevices + device.deviceName
                        try {
                            val result = service.unbindDevice(device.deviceName)
                            // Unbind state propagates automatically to the UI through the Service StateFlow.
                            when (result) {
                                is DeviceUnbindResult.Success -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_success, displayName), Toast.LENGTH_SHORT).show()
                                }
                                is DeviceUnbindResult.Failure -> {
                                    Toast.makeText(context, context.getString(R.string.unbind_failed, result.getMessage(context)), Toast.LENGTH_SHORT).show()
                                }
                            }
                        } finally {
                            busyDevices = busyDevices - device.deviceName
                        }
                    }
                },
                onRefresh = { refreshDevices() }
            )

            LogSection(
                logMessages = logBuffer.messages,
                logVersion = logBuffer.version,
                onClear = { logBuffer.clear() },
                onViewFullLog = { showFullLog = true },
                onCopyLog = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Log", logBuffer.messages.joinToString("\n")))
                    Toast.makeText(context, context.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    if (showFullLog) {
        FullLogDialog(
            logMessages = logBuffer.messages,
            logVersion = logBuffer.version,
            onDismiss = { showFullLog = false }
        )
    }

    if (showAbout) {
        // Failure to find this app package information should be impossible in practice, but handle it defensively.
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get package info", e)
            "unknown"
        }
        val githubUrl = "https://github.com/yunsmall/Android-Usbipdcpp"
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_description))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.about_version, version))
                    Text(stringResource(R.string.about_license))
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                            context.startActivity(intent)
                        }
                    ) {
                        Text(stringResource(R.string.about_github), color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

// Bridge for native log callbacks: registration/removal and main-thread forwarding live here. addLog is passed as a lambda
// capturing the remembered logBuffer instance. DisposableEffect(Unit) registers only once during first composition;
// capturing the first composition lambda is safe because its behavior depends only on the stable logBuffer instance and
// does not read changing composition snapshots.
@Composable
private fun NativeLogBridge(addLog: (String) -> Unit) {
    DisposableEffect(Unit) {
        // onLog is called from the native logging thread. Updating Compose state directly would be a cross-thread write,
        // so dispatch to the main thread first.
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : LogCallback {
            override fun onLog(level: Int, message: String) {
                // Native pattern output normally has no trailing whitespace; trim is defensive cleanup.
                // A few already-posted tasks may remain queued after onDispose; they only write to state belonging to the destroyed
                // composition (which has no observers and causes no recomposition), so explicit cancellation tracking is unnecessary.
                mainHandler.post { addLog(message.trim()) }
            }
        }
        UsbIpNative.setLogCallback(callback)
        onDispose {
            // The callback must be cleared. During rotation, a new setLogCallback replaces the old reference, but if the app goes to
            // background and the Activity is destroyed with no new callback, JNI would otherwise retain a global reference to the old
            // Activity and leak it.
            // During rotation the old instance is destroyed before the new one is created (fixed system recreation order), so clearing here
            // cannot accidentally clear the new callback. In unusual reverse ordering such as multi-window duplication, the worst case is
            // that logging stops until rotation/re-entry restores it; that does not affect correctness and does not justify cleanup tokens.
            // After clearing the callback, clear the queue as well: already-posted residual log tasks would otherwise write only to
            // state belonging to the destroyed composition.
            UsbIpNative.setLogCallback(null)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
}

// Top bar: title, language menu, and About entry. The language-menu expanded state is local to this component.
// Selection calls the file-level setLanguage directly; the About dialog belongs to MainScreen state and is exposed through a callback.
// and passed outward through the callback.
// TopAppBar is an experimental API, so the opt-in must be placed on the function containing the call.
// The @OptIn on MainScreen covers only its own function body and does not apply here.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(onAboutClick: () -> Unit) {
    var showLanguageMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.app_title)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        actions = {
            TextButton(onClick = onAboutClick) {
                Text(stringResource(R.string.about))
            }
            Box {
                TextButton(onClick = { showLanguageMenu = true }) {
                    Text(stringResource(R.string.language))
                }
                DropdownMenu(
                    expanded = showLanguageMenu,
                    onDismissRequest = { showLanguageMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.language_en)) },
                        onClick = {
                            setLanguage("en")
                            showLanguageMenu = false
                        }
                    )
                }
            }
        }
    )
}
