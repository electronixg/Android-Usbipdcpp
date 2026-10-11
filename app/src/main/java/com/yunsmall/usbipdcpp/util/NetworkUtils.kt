package com.yunsmall.usbipdcpp.util

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

// Keep the pre-split "MainActivity" tag so splitting files does not affect familiar logcat filters.
private const val TAG = "MainActivity"

// Get the device IP address.
fun getDeviceIpAddress(): String? {
    try {
        // getNetworkInterfaces documentation permits null (no network interfaces): no address is available in this case either,
        // so short-circuit directly instead of causing an NPE and misleading error logging in catch.
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        // Wi-Fi/hotspot interface addresses are reachable from the PC; cellular (rmnet/wwan) addresses belong to the
        // carrier's internal network. When both exist, enumeration does not guarantee Wi-Fi comes first, so scan once to prioritize it;
        // only fall back to the first available IPv4 if neither exists (for cellular-only connections, showing it is better than null).
        var fallback: String? = null
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            // Skip loopback and inactive interfaces.
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            // Skip VPN/virtual adapters (tun/ppp prefixes) to avoid returning a virtual interface address.
            // Interface names are ASCII; use ROOT for case conversion: using the system locale (such as Turkish
            // i/I rules) would produce unexpected names.
            val ifName = networkInterface.name?.lowercase(Locale.ROOT) ?: ""
            if (ifName.startsWith("tun") || ifName.startsWith("ppp") || ifName.startsWith("vpn")) continue

            val ipv4 = firstIpv4Address(networkInterface) ?: continue
            // The ap prefix indicates hotspot interfaces (such as ap0); swlan is used by some ROMs.
            if (ifName.startsWith("wlan") || ifName.startsWith("ap") || ifName.startsWith("swlan")) {
                return ipv4
            }
            if (fallback == null) {
                fallback = ipv4
            }
        }
        return fallback
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get IP address", e)
    }
    return null
}

// Get the interface's first non-loopback IPv4 address; return null if none exists.
private fun firstIpv4Address(networkInterface: NetworkInterface): String? {
    val addresses = networkInterface.inetAddresses
    while (addresses.hasMoreElements()) {
        val address = addresses.nextElement()
        if (!address.isLoopbackAddress && address is Inet4Address) {
            return address.hostAddress
        }
    }
    return null
}
