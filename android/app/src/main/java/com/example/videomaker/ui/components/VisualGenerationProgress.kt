package com.example.videomaker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun rememberVisualGenerationProgress(
    progress: Int,
    isRunning: Boolean = true,
    visualProgressStartedAtMillis: Long = 0L,
    visualProgressKey: String? = null
): Float {
    val reportedProgress = progress.coerceIn(0, 100)
    var visualProgress by remember(visualProgressKey) {
        mutableStateOf(
            visualGenerationProgressAt(
                progress = reportedProgress,
                isRunning = isRunning,
                visualProgressStartedAtMillis = visualProgressStartedAtMillis,
                nowMillis = System.currentTimeMillis()
            )
        )
    }

    LaunchedEffect(reportedProgress, isRunning, visualProgressStartedAtMillis, visualProgressKey) {
        if (!isRunning || reportedProgress >= 99) {
            visualProgress = reportedProgress.toFloat()
            return@LaunchedEffect
        }

        while (true) {
            val nextProgress = visualGenerationProgressAt(
                progress = reportedProgress,
                isRunning = isRunning,
                visualProgressStartedAtMillis = visualProgressStartedAtMillis,
                nowMillis = System.currentTimeMillis()
            )
            visualProgress = maxOf(visualProgress, nextProgress, reportedProgress.toFloat()).coerceIn(0f, 99f)
            delay(1_000L)
        }
    }

    return maxOf(visualProgress, reportedProgress.toFloat()).coerceIn(0f, 100f)
}

internal fun visualGenerationProgressAt(
    progress: Int,
    isRunning: Boolean,
    visualProgressStartedAtMillis: Long,
    nowMillis: Long
): Float {
    val reportedProgress = progress.coerceIn(0, 100)
    if (!isRunning || reportedProgress >= 99) {
        return reportedProgress.toFloat()
    }

    val visualTarget = nextVisualProgressTarget(reportedProgress)
    if (visualTarget <= reportedProgress) {
        return reportedProgress.toFloat()
    }

    val startedAtMillis = if (visualProgressStartedAtMillis > 0L) {
        visualProgressStartedAtMillis
    } else {
        nowMillis
    }
    val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
    val durationMillis = visualProgressDurationMillis(
        from = reportedProgress.toFloat(),
        to = visualTarget.toFloat()
    )
    val fraction = (elapsedMillis.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    return (reportedProgress + (visualTarget - reportedProgress) * fraction).coerceIn(
        reportedProgress.toFloat(),
        99f
    )
}

private fun nextVisualProgressTarget(progress: Int): Int {
    val stops = intArrayOf(25, 30, 60, 85, 95, 99)
    return stops.firstOrNull { it > progress } ?: progress.coerceIn(0, 100)
}

private fun visualProgressDurationMillis(from: Float, to: Float): Int {
    val distance = (to - from).coerceAtLeast(0f)
    return (distance * 2_400f).roundToInt().coerceIn(2_500, 90_000)
}
