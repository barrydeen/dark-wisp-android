package com.darkwisp.app.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepLApiClientTest {
    @Test
    fun freeAndProKeysUseTheirMatchingHosts() {
        assertEquals("https://api-free.deepl.com", deepLApiBaseUrl("example:fx"))
        assertEquals("https://api.deepl.com", deepLApiBaseUrl("example-pro-key"))
        assertEquals("https://api-free.deepl.com", deepLApiBaseUrl("example:FX"))
    }

    @Test
    fun targetLanguageTagsMapToDeepLCodes() {
        assertEquals("EN-US", deepLTargetLanguageCode("en"))
        assertEquals("EN-GB", deepLTargetLanguageCode("en-GB"))
        assertEquals("PT-PT", deepLTargetLanguageCode("pt"))
        assertEquals("PT-BR", deepLTargetLanguageCode("pt-BR"))
        assertEquals("IT", deepLTargetLanguageCode("it-IT"))
        assertEquals("NB", deepLTargetLanguageCode("no"))
    }

    @Test
    fun protectedNostrLinksInvoicesAndHashtagsAreRestoredExactly() {
        val original = "Read https://example.com/post?q=1, #Bitcoin nostr:note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq and lnbc1234567890abc and lno1qqqqqqqqqqqqqqqq."
        val protected = TranslationTextProtector.protect(original)

        assertNotEquals(original, protected.text)
        assertEquals(original, protected.restore(protected.text))
    }

    @Test
    fun missingProtectedMarkerFailsRatherThanCorruptingAReference() {
        val protected = TranslationTextProtector.protect("Visit https://example.com")
        assertThrows(java.io.IOException::class.java) {
            protected.restore("Translated text without the marker")
        }
    }
}
