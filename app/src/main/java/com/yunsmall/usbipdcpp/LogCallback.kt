package com.yunsmall.usbipdcpp

import androidx.annotation.IntDef

/**
 * spdlog level constants: values exactly match native spdlog::level::level_enum,
 * for onLog implementations to filter/color logs without copying the numbers again in the UI.
 */
object SpdLogLevels {
    const val TRACE = 0
    const val DEBUG = 1
    const val INFO = 2
    const val WARN = 3
    const val ERROR = 4
    const val CRITICAL = 5
    const val OFF = 6
}

// SOURCE retention: only for coding-time type hints (lint/IDE), excluded from runtime artifacts.
@IntDef(
    SpdLogLevels.TRACE, SpdLogLevels.DEBUG, SpdLogLevels.INFO, SpdLogLevels.WARN,
    SpdLogLevels.ERROR, SpdLogLevels.CRITICAL, SpdLogLevels.OFF
)
@Retention(AnnotationRetention.SOURCE)
annotation class SpdLogLevel

/**
 * Native log callback. onLog is called synchronously by the native logging thread, which must wait for it to return before processing
 * subsequent logs: implementations must return immediately and move heavy work to another thread, otherwise the entire native logging path slows down.
 * Never call any UsbIpNative method inside onLog: logs may originate from a call chain holding native internal locks
 * (such as spdlog output under g_server_mutex), so reentering native would deadlock.
 */
interface LogCallback {
    /**
     * @param level spdlog level (integer value of spdlog::level::level_enum: 0=trace ... 5=critical;
     *               see SpdLogLevels for constants). The current UI does not distinguish levels; retain the parameter to match spdlog's
     *               original form so future filtering/coloring does not require JNI changes.
     * @param message Log text already formatted according to the pattern.
     */
    fun onLog(@SpdLogLevel level: Int, message: String)
}