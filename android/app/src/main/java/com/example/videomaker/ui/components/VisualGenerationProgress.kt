package com.example.videomaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

@Composable
fun rememberVisualGenerationProgress(
    progress: Int,
    isRunning: Boolean = true
): Float {
    val reportedProgress = progress.coerceIn(0, 100)
    val visualProgress = remember { Animatable(reportedProgress.toFloat()) }

    LaunchedEffect(reportedProgress, isRunning) {
        if (isRunning && reportedProgress < visualProgress.value - 1f) {
            visualProgress.snapTo(reportedProgress.toFloat())
        }

        if (visualProgress.value < reportedProgress) {
            visualProgress.animateTo(
                targetValue = reportedProgress.toFloat(),
                animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
            )
        }

        if (!isRunning || reportedProgress >= 99) {
            return@LaunchedEffect
        }

        val visualTarget = nextVisualProgressTarget(reportedProgress)
        if (visualTarget > visualProgress.value) {
            visualProgress.animateTo(
                targetValue = visualTarget.toFloat(),
                animationSpec = tween(
                    durationMillis = visualProgressDurationMillis(
                        from = visualProgress.value,
                        to = visualTarget.toFloat()
                    ),
                    easing = LinearEasing
                )
            )
        }
    }

    return visualProgress.value.coerceIn(0f, 100f)
}

private fun nextVisualProgressTarget(progress: Int): Int {
    val stops = intArrayOf(25, 30, 60, 85, 95, 99)
    return stops.firstOrNull { it > progress } ?: progress.coerceIn(0, 100)
}

private fun visualProgressDurationMillis(from: Float, to: Float): Int {
    val distance = (to - from).coerceAtLeast(0f)
    return (distance * 2_400f).roundToInt().coerceIn(2_500, 90_000)
}
