package com.yunsmall.usbipdcpp.ui

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunsmall.usbipdcpp.R

@Composable
fun ColumnScope.DeviceListSection(
    devices: Map<String, UsbDevice>,
    // deviceName -> busid: use the same data to check binding and retrieve busid, rather than separate parameter paths.
    boundDevices: Map<String, String>,
    busyDevices: Set<String>,
    serverRunning: Boolean,
    onBindDevice: (UsbDevice) -> Unit,
    onUnbindDevice: (UsbDevice) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // fill = false: with few devices, size the card to its content and leave spare space for the log area
            //(LogSection's weight uses the default fill = true to receive the remaining space).
            .weight(1.5f, fill = false)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Cache entries by devices: recomposition (such as frames driven by busyDevices progress animations)
            // does not rebuild them. Keep this outside the condition, like LogSection's scrollState, so branch switching preserves the cache.
            val deviceEntries = remember(devices) { devices.entries.toList() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.usb_devices), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
            }

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(deviceEntries, key = { it.value.deviceName }) { entry ->
                        val device = entry.value
                        val isBound = boundDevices.containsKey(device.deviceName)

                        val busid = boundDevices[device.deviceName]
                        DeviceItem(
                            device = device,
                            isBound = isBound,
                            isBusy = busyDevices.contains(device.deviceName),
                            busid = busid,
                            canBind = serverRunning && !isBound,
                            onBind = { onBindDevice(device) },
                            onUnbind = { onUnbindDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: UsbDevice,
    isBound: Boolean,
    isBusy: Boolean,
    busid: String?,
    canBind: Boolean,
    onBind: () -> Unit,
    onUnbind: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.productName?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown_device),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "VID: ${device.vendorId.toString(16).uppercase()}, PID: ${device.productId.toString(16).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (busid != null) {
                Text(
                    text = "BUSID: $busid",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        } else if (isBound) {
            TextButton(onClick = onUnbind, modifier = Modifier.height(36.dp)) {
                Text(stringResource(R.string.unbind), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Button(onClick = onBind, modifier = Modifier.height(36.dp), enabled = canBind) {
                Text(stringResource(R.string.bind), fontSize = 12.sp)
            }
        }
    }
}
