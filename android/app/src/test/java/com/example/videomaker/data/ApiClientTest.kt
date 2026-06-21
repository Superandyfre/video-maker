package com.example.videomaker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiClientTest {
    @Test
    fun normalizeBaseUrlAddsTrailingSlash() {
        assertEquals("https://example.com/", ApiClient.normalizeBaseUrl("https://example.com"))
    }

    @Test
    fun buildAbsoluteUrlKeepsAbsoluteUrl() {
        val value = "https://cdn.example.com/video.mp4"

        assertEquals(value, ApiClient.buildAbsoluteUrl("https://api.example.com", value))
    }

    @Test
    fun buildAbsoluteUrlCombinesRelativePath() {
        assertEquals(
            "https://api.example.com/outputs/sample.mp4",
            ApiClient.buildAbsoluteUrl("https://api.example.com/", "/outputs/sample.mp4")
        )
    }
}
