package com.darkwisp.app.repo

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DeepLTranslationResult(
    val text: String,
    val detectedSourceLanguage: String?
)

data class DeepLUsageInfo(
    val characterCount: Long,
    val characterLimit: Long
)

class DeepLApiException(
    val statusCode: Int,
    message: String
) : IOException(message)

class DeepLApiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun translate(
        apiKey: String,
        sourceText: String,
        targetLanguage: String
    ): DeepLTranslationResult {
        require(apiKey.isNotBlank()) { "A DeepL API key is required." }
        require(sourceText.isNotBlank()) { "There is no text to translate." }
        if (sourceText.length > MAX_TEXT_LENGTH) {
            throw IOException("This post is too long to translate in one request.")
        }

        // Translate prose line by line. Newlines are presentation structure in
        // Nostr notes: a standalone image URL must not become an inline URL,
        // or RichContent may render it with a different layout.
        val document = TranslationTextProtector.protectDocument(sourceText)
        val translatedTexts = mutableListOf<String>()
        var detectedSourceLanguage: String? = null

        for (batch in document.texts.chunked(MAX_TEXTS_PER_REQUEST)) {
            val payload = DeepLTranslateRequest(
                text = batch,
                targetLang = deepLTargetLanguageCode(targetLanguage)
            )
            val request = Request.Builder()
                .url(deepLApiBaseUrl(apiKey) + "/v2/translate")
                .header("Authorization", "DeepL-Auth-Key " + apiKey.trim())
                .header("Content-Type", "application/json")
                .post(json.encodeToString(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val body = execute(request)
            val response = try {
                json.decodeFromString<DeepLTranslateResponse>(body)
            } catch (e: Exception) {
                throw IOException("DeepL returned an unreadable translation response.", e)
            }
            if (response.translations.size != batch.size) {
                throw IOException("DeepL returned an incomplete translation.")
            }
            response.translations.forEach { translated ->
                translatedTexts += translated.text
                if (detectedSourceLanguage == null) {
                    detectedSourceLanguage = translated.detectedSourceLanguage
                }
            }
        }

        return DeepLTranslationResult(
            text = document.restore(translatedTexts),
            detectedSourceLanguage = detectedSourceLanguage
        )
    }

    suspend fun getUsage(apiKey: String): DeepLUsageInfo {
        require(apiKey.isNotBlank()) { "A DeepL API key is required." }
        val request = Request.Builder()
            .url(deepLApiBaseUrl(apiKey) + "/v2/usage")
            .header("Authorization", "DeepL-Auth-Key " + apiKey.trim())
            .get()
            .build()
        val body = execute(request)
        val usage = try {
            json.decodeFromString<DeepLUsageResponse>(body)
        } catch (e: Exception) {
            throw IOException("DeepL returned an unreadable usage response.", e)
        }
        return DeepLUsageInfo(
            characterCount = usage.characterCount,
            characterLimit = usage.characterLimit
        )
    }

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val responseBody = it.body?.string().orEmpty()
                        if (!continuation.isActive) return
                        if (it.isSuccessful) {
                            continuation.resume(responseBody)
                        } else {
                            continuation.resumeWithException(
                                DeepLApiException(it.code, errorMessage(it.code))
                            )
                        }
                    }
                }
            })
        }

    private fun errorMessage(code: Int): String = when (code) {
        400 -> "DeepL rejected the request. Check the selected target language."
        401 -> "DeepL rejected the API key (HTTP 401)."
        403 -> "DeepL denied access. Check that the API key matches the Free or Pro endpoint."
        429 -> "DeepL rate limit reached. Please retry shortly."
        456 -> "DeepL character quota reached for this billing period."
        in 500..599 -> "DeepL is temporarily unavailable (HTTP " + code + ")."
        else -> "DeepL request failed (HTTP " + code + ")."
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 30_000
        const val MAX_TEXTS_PER_REQUEST = 50
    }
}

@Serializable
private data class DeepLTranslateRequest(
    val text: List<String>,
    @SerialName("target_lang") val targetLang: String,
    @SerialName("preserve_formatting") val preserveFormatting: Boolean = true,
    @SerialName("split_sentences") val splitSentences: String = "nonewlines"
)

@Serializable
private data class DeepLTranslateResponse(
    val translations: List<DeepLTranslatedText> = emptyList()
)

@Serializable
private data class DeepLTranslatedText(
    val text: String,
    @SerialName("detected_source_language") val detectedSourceLanguage: String? = null
)

@Serializable
private data class DeepLUsageResponse(
    @SerialName("character_count") val characterCount: Long,
    @SerialName("character_limit") val characterLimit: Long
)

internal fun deepLApiBaseUrl(apiKey: String): String =
    if (apiKey.trim().endsWith(":fx", ignoreCase = true)) {
        "https://api-free.deepl.com"
    } else {
        "https://api.deepl.com"
    }

internal fun deepLTargetLanguageCode(languageTag: String): String {
    val normalized = languageTag.trim().replace('_', '-').lowercase(Locale.ROOT)
    return when {
        normalized == "en-gb" || normalized.startsWith("en-gb-") -> "EN-GB"
        normalized == "en" || normalized == "en-us" || normalized.startsWith("en-us-") -> "EN-US"
        normalized == "pt-br" || normalized.startsWith("pt-br-") -> "PT-BR"
        normalized == "no" || normalized.startsWith("no-") -> "NB"
        normalized == "pt" || normalized == "pt-pt" || normalized.startsWith("pt-pt-") -> "PT-PT"
        else -> normalized.substringBefore('-').uppercase(Locale.ROOT)
    }
}

internal data class ProtectedTranslationText(
    val text: String,
    private val replacements: Map<String, String>,
    val hasTranslatableText: Boolean,
    val languageIdentificationText: String
) {
    fun restore(translatedText: String): String {
        var restored = translatedText
        replacements.forEach { (marker, original) ->
            val markerRegex = Regex(Regex.escape(marker), RegexOption.IGNORE_CASE)
            val occurrences = markerRegex.findAll(restored).count()
            if (occurrences != 1) {
                throw IOException("Translation changed a protected link or Nostr reference; the translation was not applied.")
            }
            restored = markerRegex.replace(restored) { original }
        }
        return restored
    }
}

/**
 * A translation-safe representation of a Nostr note.
 *
 * Each physical line is sent as a separate DeepL text item. Original line
 * separators and horizontal whitespace are reassembled locally, so DeepL
 * cannot turn a standalone media URL into an inline URL or collapse a stanza.
 * Lines containing only links/media are retained without being sent.
 */
internal class ProtectedTranslationDocument private constructor(
    private val lines: List<Line>
) {
    private data class Line(
        val leadingWhitespace: String,
        val originalBody: String,
        val trailingWhitespace: String,
        val lineBreak: String,
        val protectedBody: ProtectedTranslationText?
    )

    val texts: List<String>
        get() = lines.mapNotNull { it.protectedBody?.text }

    /** Plain prose only, used for on-device language identification. */
    val languageIdentificationText: String
        get() = lines.mapNotNull { it.protectedBody?.languageIdentificationText }.joinToString("\n")

    fun restore(translatedTexts: List<String>): String {
        if (translatedTexts.size != texts.size) {
            throw IOException("DeepL returned an incomplete translation.")
        }
        var translatedIndex = 0
        return buildString {
            lines.forEach { line ->
                append(line.leadingWhitespace)
                val protectedBody = line.protectedBody
                if (protectedBody != null) {
                    append(protectedBody.restore(translatedTexts[translatedIndex++]))
                } else {
                    append(line.originalBody)
                }
                append(line.trailingWhitespace)
                append(line.lineBreak)
            }
        }
    }

    companion object {
        fun from(
            sourceText: String,
            preserveCustomEmojiShortcodes: Boolean
        ): ProtectedTranslationDocument {
            val lines = mutableListOf<Line>()
            var start = 0

            while (start < sourceText.length) {
                var lineEnd = start
                while (lineEnd < sourceText.length &&
                    sourceText[lineEnd] != '\n' && sourceText[lineEnd] != '\r'
                ) {
                    lineEnd++
                }

                val originalLine = sourceText.substring(start, lineEnd)
                var separatorEnd = lineEnd
                if (separatorEnd < sourceText.length) {
                    if (sourceText[separatorEnd] == '\r' &&
                        separatorEnd + 1 < sourceText.length &&
                        sourceText[separatorEnd + 1] == '\n'
                    ) {
                        separatorEnd += 2
                    } else {
                        separatorEnd++
                    }
                }
                val lineBreak = sourceText.substring(lineEnd, separatorEnd)

                val leading = originalLine.takeWhile { it == ' ' || it == '\t' }
                val afterLeading = originalLine.drop(leading.length)
                val trailing = afterLeading.takeLastWhile { it == ' ' || it == '\t' }
                val body = if (trailing.isEmpty()) afterLeading else {
                    afterLeading.dropLast(trailing.length)
                }
                val protectedText = if (body.isNotEmpty()) {
                    TranslationTextProtector.protect(
                        body,
                        preserveCustomEmojiShortcodes
                    ).takeIf { it.hasTranslatableText }
                } else {
                    null
                }

                lines += Line(leading, body, trailing, lineBreak, protectedText)
                start = separatorEnd
            }

            // No synthetic empty line is needed after a trailing line break.
            if (sourceText.isEmpty()) {
                lines += Line("", "", "", "", null)
            }
            return ProtectedTranslationDocument(lines)
        }
    }
}

internal object TranslationTextProtector {
    private val customEmojiShortcodePattern =
        Regex(""":[a-zA-Z_][a-zA-Z0-9_-]*:""")

    private val patterns = listOf(
        Regex("""(?:https?|wss?)://[^\s<>]+""", RegexOption.IGNORE_CASE),
        Regex("""\bwww\.[^\s<>]+""", RegexOption.IGNORE_CASE),
        Regex(
            """\b(?:nostr:)?(?:npub|nprofile|note|nevent|naddr|nrelay|nsec|ncryptsec)1[023456789acdefghjklmnpqrstuvwxyz]{10,}\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""\b(?:lightning:)?(?:lnbc|lntb|lnbcrt|lnurl|lno)[0-9a-z]+\b""", RegexOption.IGNORE_CASE),
        Regex("""(?:nostr:)?noffer1[023456789acdefghjklmnpqrstuvwxyz]{20,}""", RegexOption.IGNORE_CASE),
        Regex("""(?<![\p{L}\p{N}_@.])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}(?:/[^\s<>]*)?""", RegexOption.IGNORE_CASE),
        Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")
    )

    fun protectDocument(
        sourceText: String,
        preserveCustomEmojiShortcodes: Boolean = false
    ): ProtectedTranslationDocument =
        ProtectedTranslationDocument.from(sourceText, preserveCustomEmojiShortcodes)

    fun protect(
        sourceText: String,
        preserveCustomEmojiShortcodes: Boolean = false
    ): ProtectedTranslationText {
        val activePatterns = if (preserveCustomEmojiShortcodes) {
            patterns + customEmojiShortcodePattern
        } else {
            patterns
        }
        val replacements = linkedMapOf<String, String>()
        var protectedText = sourceText
        activePatterns.forEach { pattern ->
            protectedText = pattern.replace(protectedText) { match ->
                val marker = "ZXQWISP" + replacements.size + "TOKEN"
                replacements[marker] = match.value
                marker
            }
        }
        var prose = sourceText
        activePatterns.forEach { pattern ->
            prose = pattern.replace(prose) { "" }
        }
        return ProtectedTranslationText(
            text = protectedText,
            replacements = replacements,
            hasTranslatableText = prose.any { it.isLetter() },
            languageIdentificationText = prose
        )
    }
}
