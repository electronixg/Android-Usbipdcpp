package com.yunsmall.usbipdcpp

import android.content.Context

/**
 * Device unbinding operation result
 */
sealed class DeviceUnbindResult {
    // Successful unbinding has no payload (object); successful binding carries busid, see DeviceBindResult.
    object Success : DeviceUnbindResult()

    sealed class Failure : DeviceUnbindResult() {
        object DeviceNotFound : Failure()
        object DeviceInUse : Failure()
        object ServerNotRunning : Failure()
        object UnknownError : Failure()

        fun getMessage(context: Context): String = when (this) {
            DeviceNotFound -> context.getString(R.string.error_device_not_found)
            DeviceInUse -> context.getString(R.string.error_device_in_use)
            ServerNotRunning -> context.getString(R.string.error_server_not_running)
            UnknownError -> context.getString(R.string.error_unknown)
        }
    }
}
