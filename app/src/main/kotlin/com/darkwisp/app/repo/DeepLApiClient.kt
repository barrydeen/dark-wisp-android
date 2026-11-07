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
    private val json = Json { ignoreUnknownKeys = true }

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

        val protected = TranslationTextProtector.protect(sourceText)
        val payload = DeepLTranslateRequest(
            text = listOf(protected.text),
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
        val translated = response.translations.firstOrNull()
            ?: throw IOException("DeepL returned an empty translation.")
        val restored = protected.restore(translated.text)

        return DeepLTranslationResult(
            text = restored,
            detectedSourceLanguage = translated.detectedSourceLanguage
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
    private val replacements: Map<String, String>
) {
    fun restore(translatedText: String): String {
        var restored = translatedText
        replacements.forEach { (marker, original) ->
            val markerRegex = Regex(Regex.escape(marker), RegexOption.IGNORE_CASE)
            val occurrences = markerRegex.findAll(restored).count()
            if (occurrences != 1) {
                throw IOException("DeepL changed a protected link or Nostr reference; the translation was not applied.")
            }
            restored = markerRegex.replace(restored) { original }
        }
        return restored
    }
}

internal object TranslationTextProtector {
    private val patterns = listOf(
        Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE),
        Regex(
            """\b(?:nostr:)?(?:npub|nprofile|note|nevent|naddr|nrelay|nsec|ncryptsec)1[023456789acdefghjklmnpqrstuvwxyz]{10,}\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""\b(?:lnbc|lntb|lnbcrt|lnurl)[0-9a-z]+\b""", RegexOption.IGNORE_CASE),
        Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")
    )

    fun protect(sourceText: String): ProtectedTranslationText {
        val replacements = linkedMapOf<String, String>()
        var protectedText = sourceText
        patterns.forEach { pattern ->
            protectedText = pattern.replace(protectedText) { match ->
                val marker = "ZXQWISP" + replacements.size + "TOKEN"
                replacements[marker] = match.value
                marker
            }
        }
        return ProtectedTranslationText(protectedText, replacements)
    }
}
