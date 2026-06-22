package com.example.videomaker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualGenerationProgressTest {
    @Test
    fun visualProgressContinuesFromPersistedStartTime() {
        val startedAtMillis = 1_000L

        val initial = visualGenerationProgressAt(
            progress = 30,
            isRunning = true,
            visualProgressStartedAtMillis = startedAtMillis,
            nowMillis = startedAtMillis
        )
        val later = visualGenerationProgressAt(
            progress = 30,
            isRunning = true,
            visualProgressStartedAtMillis = startedAtMillis,
            nowMillis = startedAtMillis + 36_000L
        )

        assertEquals(30f, initial, 0.01f)
        assertTrue(later > initial)
        assertEquals(45f, later, 0.5f)
    }

    @Test
    fun visualProgressDoesNotAdvanceWhenNotRunning() {
        val value = visualGenerationProgressAt(
            progress = 42,
            isRunning = false,
            visualProgressStartedAtMillis = 1_000L,
            nowMillis = 91_000L
        )

        assertEquals(42f, value, 0.01f)
    }

    @Test
    fun visualProgressCapsBeforeCompletion() {
        val value = visualGenerationProgressAt(
            progress = 95,
            isRunning = true,
            visualProgressStartedAtMillis = 1_000L,
            nowMillis = 91_000L
        )

        assertEquals(99f, value, 0.01f)
    }
}
