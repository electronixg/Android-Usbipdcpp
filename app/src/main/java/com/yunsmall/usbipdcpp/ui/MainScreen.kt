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

// Implementation note translated from the original source.
private const val TAG = "MainActivity"

// Implementation note translated from the original source.
// Implementation note translated from the original source.

// Implementation note translated from the original source.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
private const val CAMERA_PENDING_TIMEOUT_MS = 60_000L

// Implementation note translated from the original source.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
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
    // Implementation note translated from the original source.
    var portText by rememberSaveable { mutableStateOf(UsbService.DEFAULT_PORT.toString()) }
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    val logBuffer = remember { LogBuffer() }
    var devices by remember { mutableStateOf(mapOf<String, UsbDevice>()) }
    var showFullLog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    var busyDevices by remember { mutableStateOf(setOf<String>()) }
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Bind device.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    var pendingBindDeviceName by rememberSaveable { mutableStateOf<String?>(null) }
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    var pendingBindSubmittedAt by rememberSaveable { mutableStateOf(0L) }

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    val emptyServiceState = remember { flowOf(UsbService.UiState()) }
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    val serviceState by (usbService?.state ?: emptyServiceState).collectAsState(UsbService.UiState())
    val serverRunning = serviceState.serverRunning
    val boundDevices = serviceState.boundDevices

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    LaunchedEffect(serviceState.port, serverRunning) {
        if (serverRunning) {
            portText = serviceState.port.toString()
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    val currentUsbService by rememberUpdatedState(usbService)

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    fun requireReadyService(): UsbService? {
        val service = currentUsbService ?: run {
            Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
            return null
        }
        // Implementation note translated from the original source.
        if (!service.state.value.nativeReady) {
            Toast.makeText(context, context.getString(R.string.native_init_failed), Toast.LENGTH_SHORT).show()
            return null
        }
        return service
    }

    // Implementation note translated from the original source.
    fun performBind(device: UsbDevice) {
        // Implementation note translated from the original source.
        // Implementation note translated from the original source.
        if (requireReadyService() == null) {
            return
        }
        // Implementation note translated from the original source.
        if (!permissionManager.requestPermission(device)) {
            // Implementation note translated from the original source.
            Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
        }
    }

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    fun bindAfterPermission(device: UsbDevice) {
        // Implementation note translated from the original source.
        // Implementation note translated from the original source.
        // Implementation note translated from the original source.
        val service = requireReadyService() ?: return
        // Implementation note translated from the original source.
        val displayName = device.productName?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.unknown_device)
        scope.launch {
            busyDevices = busyDevices + device.deviceName
            try {
                val result = service.bindDevice(usbManager, device)
                // Implementation note translated from the original source.
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

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    fun clearPendingCameraBind() {
        pendingBindDeviceName = null
        pendingBindSubmittedAt = 0L
    }

    // Implementation note translated from the original source.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val deviceName = pendingBindDeviceName ?: return@rememberLauncherForActivityResult
        clearPendingCameraBind()
        // Implementation note translated from the original source.
        // Implementation note translated from the original source.
        val device = usbManager.deviceList[deviceName] ?: run {
            Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        if (granted) {
            performBind(device)
        } else {
            // Implementation note translated from the original source.
            Toast.makeText(context, context.getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    fun addLog(message: String) {
        logBuffer.append("[${LocalTime.now().format(logTimeFormat)}] $message")
    }

    fun refreshDevices() {
        devices = permissionManager.getDeviceList()
        addLog("Found ${devices.size} USB device(s)")
    }

    val ipAddress = remember { mutableStateOf<String?>(null) }

    // Implementation note translated from the original source.
    LaunchedEffect(serverRunning) {
        if (serverRunning) {
            // Implementation note translated from the original source.
            ipAddress.value = withContext(Dispatchers.IO) { getDeviceIpAddress() }
        } else {
            // Implementation note translated from the original source.
            ipAddress.value = null
        }
    }

    // Log callback.
    NativeLogBridge { addLog(it) }

    // Implementation note translated from the original source.
    LaunchedEffect(Unit) { refreshDevices() }

    // Implementation note translated from the original source.
    DisposableEffect(permissionManager) {
        permissionManager.setOnDeviceListChangedListener {
            refreshDevices()
        }
        onDispose {
            permissionManager.setOnDeviceListChangedListener(null)
        }
    }

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
    DisposableEffect(permissionManager) {
        permissionManager.setOnPermissionResultListener { device, granted ->
            if (!granted) {
                Toast.makeText(context, context.getString(R.string.device_unavailable), Toast.LENGTH_SHORT).show()
                return@setOnPermissionResultListener
            }
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
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

    // Implementation note translated from the original source.
    // Implementation note translated from the original source.
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
                    // Implementation note translated from the original source.
                    // Implementation note translated from the original source.
                    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
                    if (port == null) {
                        Toast.makeText(context, context.getString(R.string.invalid_port), Toast.LENGTH_SHORT).show()
                        return@ServerControlPanel
                    }
                    // Implementation note translated from the original source.
                    // Implementation note translated from the original source.
                    // Implementation note translated from the original source.
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStarting = true
                    scope.launch {
                        // Implementation note translated from the original source.
                        // Implementation note translated from the original source.
                        try {
                            // Implementation note translated from the original source.
                            // Implementation note translated from the original source.
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
                    // Implementation note translated from the original source.
                    val service = requireReadyService() ?: return@ServerControlPanel
                    isStopping = true
                    scope.launch {
                        try {
                            // Implementation note translated from the original source.
                            service.stopServer()
                        } finally {
                            // Implementation note translated from the original source.
                            isStopping = false
                        }
                    }
                }
            )

            StatusCard(
                serverRunning = serverRunning,
                boundCount = boundDevices.size,
                ipAddress = ipAddress.value,
                // Implementation note translated from the original source.
                // Implementation note translated from the original source.
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
                    // Implementation note translated from the original source.
                    if (currentUsbService == null) {
                        Toast.makeText(context, context.getString(R.string.service_not_ready), Toast.LENGTH_SHORT).show()
                        return@DeviceListSection
                    }
                    // Implementation note translated from the original source.
                    if (isCameraDevice(device) &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    ) {
                        if (pendingBindDeviceName != null) {
                            // Implementation note translated from the original source.
                            // Implementation note translated from the original source.
                            if (SystemClock.elapsedRealtime() - pendingBindSubmittedAt > CAMERA_PENDING_TIMEOUT_MS) {
                                clearPendingCameraBind()
                            } else {
                                // Implementation note translated from the original source.
                                // Implementation note translated from the original source.
                                // Implementation note translated from the original source.
                                Toast.makeText(context, context.getString(R.string.permission_request_pending), Toast.LENGTH_SHORT).show()
                                return@DeviceListSection
                            }
                        }
                        pendingBindDeviceName = device.deviceName
                        pendingBindSubmittedAt = SystemClock.elapsedRealtime()
                        try {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } catch (e: Exception) {
                            // Implementation note translated from the original source.
                            // Implementation note translated from the original source.
                            clearPendingCameraBind()
                            Log.e(TAG, "Failed to launch camera permission request", e)
                        }
                    } else {
                        performBind(device)
                    }
                },
                onUnbindDevice = { device ->
                    // Implementation note translated from the original source.
                    val service = requireReadyService() ?: return@DeviceListSection
                    val displayName = device.productName?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.unknown_device)
                    scope.launch {
                        busyDevices = busyDevices + device.deviceName
                        try {
                            val result = service.unbindDevice(device.deviceName)
                            // Implementation note translated from the original source.
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
        // Implementation note translated from the original source.
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

// Log callback.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
@Composable
private fun NativeLogBridge(addLog: (String) -> Unit) {
    DisposableEffect(Unit) {
        // Implementation note translated from the original source.
        // Implementation note translated from the original source.
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : LogCallback {
            override fun onLog(level: Int, message: String) {
                // Implementation note translated from the original source.
                // Implementation note translated from the original source.
                // Implementation note translated from the original source.
                mainHandler.post { addLog(message.trim()) }
            }
        }
        UsbIpNative.setLogCallback(callback)
        onDispose {
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            // Implementation note translated from the original source.
            UsbIpNative.setLogCallback(null)
            mainHandler.removeCallbacksAndMessages(null)
        }
    }
}

// Implementation note translated from the original source.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
// Implementation note translated from the original source.
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
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.language_zh)) },
                        onClick = {
                            setLanguage("zh")
                            showLanguageMenu = false
                        }
                    )
                }
            }
        }
    )
}
