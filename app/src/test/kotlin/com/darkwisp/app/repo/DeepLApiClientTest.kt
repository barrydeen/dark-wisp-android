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

    @Test
    fun documentTranslationPreservesOriginalLinesBlankLinesAndMediaOnlyLines() {
        val original = "A sentence with https://example.com/photo.jpg\n" +
            "https://example.com/standalone.png\n" +
            "\n" +
            "Second stanza line one\n" +
            "Second stanza line two\r\n"

        val document = TranslationTextProtector.protectDocument(original)
        assertEquals(3, document.texts.size)

        val translated = document.texts.map { text ->
            text.replace("A sentence with", "Una frase con")
                .replace("Second stanza line one", "Secondo verso uno")
                .replace("Second stanza line two", "Secondo verso due")
        }

        assertEquals(
            "Una frase con https://example.com/photo.jpg\n" +
                "https://example.com/standalone.png\n" +
                "\n" +
                "Secondo verso uno\n" +
                "Secondo verso due\r\n",
            document.restore(translated)
        )
    }

    @Test
    fun protectedDocumentKeepsLinksNostrReferencesAndLightningOffersVerbatim() {
        val original = "Visit example.com and wss://relay.example.com, " +
            "nostr:note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq, " +
            "noffer1qqqqqqqqqqqqqqqqqqqqqqqqqq and #Bitcoin."

        val protected = TranslationTextProtector.protect(original)
        assertNotEquals(original, protected.text)
        assertEquals(original, protected.restore(protected.text))
    }

    @Test
    fun mlKitDocumentTranslationPreservesCustomEmojisMediaAndOriginalLayout() {
        val original = "First line :paintbrush: with https://example.com/photo.jpg\\n" +
            "https://example.com/standalone.png\\n" +
            "\\n" +
            "Second line mentions example.org and #Nostr\\n"

        val document = TranslationTextProtector.protectDocument(
            original,
            preserveCustomEmojiShortcodes = true
        )
        assertEquals(2, document.texts.size)
        assertEquals(
            "First line  with \\nSecond line mentions  ",
            document.languageIdentificationText
        )

        val translated = document.texts.map { text ->
            text.replace("First line", "Prima riga")
                .replace("Second line mentions", "La seconda riga cita")
        }

        assertEquals(
            "Prima riga :paintbrush: with https://example.com/photo.jpg\\n" +
                "https://example.com/standalone.png\\n" +
                "\\n" +
                "La seconda riga cita example.org and #Nostr\\n",
            document.restore(translated)
        )
    }

    @Test
    fun documentWithOnlyMediaAndWhitespaceRequiresNoDeepLTextItems() {
        val original = "https://example.com/image.jpg\n\n  https://example.com/another.png  "
        val document = TranslationTextProtector.protectDocument(original)

        assertEquals(0, document.texts.size)
        assertEquals(original, document.restore(emptyList()))
    }
}
