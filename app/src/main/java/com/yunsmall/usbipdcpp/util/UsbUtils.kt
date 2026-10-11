package com.yunsmall.usbipdcpp.util

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice

fun isCameraDevice(device: UsbDevice): Boolean {
    // The video class can be declared in two ways: directly at device level (some UVC devices), or at interface level
    // (composite devices). Checking only interface classes would miss the first case; check both locations.
    if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
    for (i in 0 until device.interfaceCount) {
        if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
    }
    return false
}
