package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleDownloadTimeoutPolicyTest {

    @Test
    fun `read timeout accommodates slow subtitle translation responses`() {
        assertTrue(subtitleAutoSyncHttpClient.readTimeoutMillis >= 60_000)
    }

    @Test
    fun `call timeout does not undercut the response wait budget`() {
        assertTrue(subtitleAutoSyncHttpClient.callTimeoutMillis >= 60_000)
        assertTrue(
            subtitleAutoSyncHttpClient.callTimeoutMillis >=
                subtitleAutoSyncHttpClient.readTimeoutMillis
        )
    }

    @Test
    fun `connection establishment policy remains unchanged`() {
        assertEquals(12_000, subtitleAutoSyncHttpClient.connectTimeoutMillis)
        assertTrue(subtitleAutoSyncHttpClient.retryOnConnectionFailure)
    }
}
