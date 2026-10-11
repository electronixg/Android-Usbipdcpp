package com.yunsmall.usbipdcpp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yunsmall.usbipdcpp.R
import kotlinx.coroutines.flow.first

// Bottom-following tolerance (dp) for automatic log scrolling: after a user finishes dragging, a distance from the bottom within this value
// still counts as at the bottom and keeps following the latest logs; use dp because line height varies with system font scaling.
private const val AUTO_SCROLL_SLACK_DP = 72

@Composable
fun ColumnScope.LogSection(
    logMessages: List<String>,
    logVersion: Long,
    onClear: () -> Unit,
    onViewFullLog: () -> Unit,
    onCopyLog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1.5f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.log), style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onViewFullLog) {
                        Text(stringResource(R.string.expand))
                    }
                    TextButton(onClick = onCopyLog) {
                        Text(stringResource(R.string.copy))
                    }
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp)
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
            ) {
                // Keep rememberScrollState outside the condition: remember inside a branch is discarded and recreated when switching branches
                //(clearing logs <-> generating new logs).
                val scrollState = rememberScrollState()
                // Convert the bottom tolerance to pixels: dp follows system font scaling, leaving enough line-height margin at any font size.
                val slackPx = with(LocalDensity.current) { AUTO_SCROLL_SLACK_DP.dp.roundToPx() }

                if (logMessages.isEmpty()) {
                    Text(
                        stringResource(R.string.no_logs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AutoScrollEffect(scrollState, logVersion, slackPx)

                    Text(
                        // Join only when log content changes (Compose skips recomposition if parameters are unchanged,
                        // and scrolling does not trigger recomposition). With a 500-entry cap, overhead is microseconds; adding a cache or switching to
                        // LazyColumn adds complexity without benefit.
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                    )
                }
            }
        }
    }
}

@Composable
fun FullLogDialog(logMessages: List<String>, logVersion: Long, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    val slackPx = with(LocalDensity.current) { AUTO_SCROLL_SLACK_DP.dp.roundToPx() }

    AutoScrollEffect(scrollState, logVersion, slackPx)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_messages)) },
        text = {
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = logMessages.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

// Shared auto-scroll effect (LogSection and FullLogDialog): following is explicit state,
// changed only by user dragging; the caller calculates slackPx using its own LocalDensity.
@Composable
private fun AutoScrollEffect(scrollState: ScrollState, logVersion: Long, slackPx: Int) {
    // Bottom-following switch, enabled by default. Do not decide by reading the instantaneous difference between value and maxValue:
    // one content increase exceeding slack (log burst or one entry wrapping across many lines) can push the bottom far away at once,
    // and an instantaneous check would mistake content growth for the user scrolling upward, permanently stopping following.
    var followLatest by rememberSaveable { mutableStateOf(true) }

    // The only place changing the switch: decide from the final position after user dragging stops. Wait until inertial scrolling also stops
    // before deciding; programmatic scrolling (scrollTo below) produces no DragInteraction and cannot incorrectly change state.
    // saveable preserves the choice across rotation/recreation so users who scrolled up are not pulled to the bottom.
    LaunchedEffect(scrollState, slackPx) {
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Stop) {
                snapshotFlow { scrollState.isScrollInProgress }.first { !it }
                followLatest = scrollState.value >= scrollState.maxValue - slackPx
                // Logs arriving during dragging are skipped by the effect below (do not compete with dragging);
                // perform one catch-up here according to the decision, rather than waiting for the next log to restore the position.
                if (followLatest) {
                    scrollState.scrollTo(scrollState.maxValue)
                }
            }
        }
    }

    LaunchedEffect(logVersion, slackPx, followLatest) {
        if (!followLatest) return@LaunchedEffect
        // Do not compete for position during dragging/inertial scrolling; the decision above restores it after stopping.
        if (scrollState.isScrollInProgress) return@LaunchedEffect
        // New text measurement/layout occurs after this effect starts; wait one frame to make the scroll target as accurate as possible.
        // Even if maxValue is read from the old layout this time, the next log's effect continues scrolling to the bottom,
        // instead of accumulating lag into a permanent stop as a position-checking scheme would.
        withFrameNanos { }
        // Scroll immediately rather than animate: new logs keep restarting this effect and cancelling the animation;
        // under a flood, the position increasingly lags behind appends, and following visibly stops.
        scrollState.scrollTo(scrollState.maxValue)
    }
}
