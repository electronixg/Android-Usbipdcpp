package com.yunsmall.usbipdcpp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Keep only the latest log entries: debug logs are numerous and the service runs for long periods; without a limit,
// list copying on each append and joining the entire text for display become increasingly expensive.
private const val MAX_LOG_LINES = 500

/**
 * UI log buffer: the message list and auto-scroll version must advance together (size becomes
 * constant once capped, and repeated messages within the same second can have identical content, so only the version can trigger the scroll effect).
 * Keep both states together, maintained solely by append. Use SnapshotStateList: appending/trimming sends
 * element-level notifications; readers (LogSection's joining) subscribe to recomposition through element reads, without relying on
 * copying the entire list on each update to trigger changes.
 */
class LogBuffer {
    private val _messages = mutableStateListOf<String>()

    // Read-only view: readers subscribe to recomposition through element reads; they neither need nor should modify it.
    val messages: List<String> = _messages

    // Monotonically increasing: advance on every list change (append or clear); the scroll effect uses this as its reference.
    var version by mutableStateOf(0L)
        private set

    fun append(formatted: String) {
        _messages.add(formatted)
        if (_messages.size > MAX_LOG_LINES) {
            _messages.removeAt(0)
        }
        version++
    }

    fun clear() {
        _messages.clear()
        version++
    }
}
