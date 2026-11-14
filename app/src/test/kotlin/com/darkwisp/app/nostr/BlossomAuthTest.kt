package com.darkwisp.app.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlossomAuthTest {

    private val sha256 = "a".repeat(64)

    @Test
    fun mediaEndpointUsesMediaAuthorizationAction() {
        val tags = Blossom.authTags(sha256, Blossom.AuthAction.MEDIA)

        assertEquals(listOf("t", "media"), tags[0])
        assertEquals(listOf("x", sha256), tags[1])
        assertEquals("expiration", tags[2][0])
        assertTrue(tags[2][1].toLong() > System.currentTimeMillis() / 1000)
    }

    @Test
    fun uploadEndpointUsesUploadAuthorizationAction() {
        val tags = Blossom.authTags(sha256, Blossom.AuthAction.UPLOAD)

        assertEquals(listOf("t", "upload"), tags[0])
        assertEquals(listOf("x", sha256), tags[1])
        assertEquals("expiration", tags[2][0])
        assertTrue(tags[2][1].toLong() > System.currentTimeMillis() / 1000)
    }
}
