package com.darkwisp.app.repo

import android.content.Context
import com.darkwisp.app.R
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class TranslationStatus {
    IDLE,
    IDENTIFYING_LANGUAGE,
    DOWNLOADING_MODEL,
    TRANSLATING,
    DONE,
    ERROR,
    SAME_LANGUAGE
}

data class TranslationState(
    val status: TranslationStatus = TranslationStatus.IDLE,
    val translatedText: String = "",
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
    val errorMessage: String = ""
)

class TranslationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val interfacePrefs = InterfacePreferences(appContext)
    private val deepLKeyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DeepLKeyStore(appContext)
    }
    private val deepLClient = DeepLApiClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = ConcurrentHashMap<String, TranslationState>()
    private val observations = KeyedObservation<String, TranslationState>(this, ::getState)

    /** Coarse global counter for screens that re-snapshot all visible translations. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun stateFor(eventId: String): Flow<TranslationState> =
        observations.observe(eventId).distinctUntilChanged()

    fun getState(eventId: String): TranslationState =
        states[eventId] ?: TranslationState()

    fun translate(eventId: String, content: String) {
        fun publish(state: TranslationState) = synchronized(this) {
            states[eventId] = state
            observations.publish(eventId)
            _version.value++
        }

        synchronized(this) {
            val current = getState(eventId)
            if (current.status != TranslationStatus.IDLE && current.status != TranslationStatus.ERROR) return
            publish(TranslationState(status = TranslationStatus.IDENTIFYING_LANGUAGE))
        }

        val targetLanguage = interfacePrefs.getResolvedTranslationTargetLanguage()
        val targetDisplayName = displayName(targetLanguage)
        val engine = interfacePrefs.getTranslationEngine()

        scope.launch {
            try {
                when (engine) {
                    InterfacePreferences.TranslationEngine.DEEPL -> {
                        // DeepL detects the source language itself. This avoids requiring
                        // an on-device language model before a cloud translation request.
                        val apiKey = deepLKeyStore.getApiKey()?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException(
                                appContext.getString(R.string.translation_deepl_key_missing)
                            )

                        publish(
                            TranslationState(
                                status = TranslationStatus.TRANSLATING,
                                targetLanguage = targetDisplayName
                            )
                        )
                        val result = deepLClient.translate(
                            apiKey = apiKey,
                            sourceText = content,
                            targetLanguage = targetLanguage
                        )
                        publish(
                            TranslationState(
                                status = TranslationStatus.DONE,
                                translatedText = result.text,
                                sourceLanguage = result.detectedSourceLanguage
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::displayName)
                                    .orEmpty(),
                                targetLanguage = targetDisplayName
                            )
                        )
                    }

                    InterfacePreferences.TranslationEngine.ON_DEVICE -> {
                        // ML Kit can rewrite line breaks and media URLs when given a
                        // whole Nostr note. Translate only prose segments, then restore
                        // the exact original layout before rendering the inline result.
                        val document = TranslationTextProtector.protectDocument(
                            content,
                            preserveCustomEmojiShortcodes = true
                        )
                        if (document.texts.isEmpty() || document.languageIdentificationText.isBlank()) {
                            publish(
                                TranslationState(
                                    status = TranslationStatus.ERROR,
                                    errorMessage = "There is no text to translate."
                                )
                            )
                            return@launch
                        }

                        val identifier = LanguageIdentification.getClient()
                        val detectedTag = try {
                            identifier.identifyLanguage(document.languageIdentificationText).await()
                        } finally {
                            identifier.close()
                        }

                        if (detectedTag == "und") {
                            publish(
                                TranslationState(
                                    status = TranslationStatus.ERROR,
                                    errorMessage = "Could not detect language"
                                )
                            )
                            return@launch
                        }

                        if (sameLanguage(detectedTag, targetLanguage)) {
                            publish(
                                TranslationState(
                                    status = TranslationStatus.SAME_LANGUAGE,
                                    sourceLanguage = displayName(detectedTag),
                                    targetLanguage = targetDisplayName
                                )
                            )
                            return@launch
                        }

                        val sourceTag = TranslateLanguage.fromLanguageTag(detectedTag)
                        val targetTag = TranslateLanguage.fromLanguageTag(targetLanguage)
                            ?: TranslateLanguage.fromLanguageTag(targetLanguage.substringBefore('-'))

                        if (sourceTag == null) {
                            publish(
                                TranslationState(
                                    status = TranslationStatus.ERROR,
                                    errorMessage = "Unsupported source language: " + detectedTag
                                )
                            )
                            return@launch
                        }
                        if (targetTag == null) {
                            publish(
                                TranslationState(
                                    status = TranslationStatus.ERROR,
                                    errorMessage = "Unsupported target language: " + targetLanguage
                                )
                            )
                            return@launch
                        }

                        publish(
                            TranslationState(
                                status = TranslationStatus.DOWNLOADING_MODEL,
                                sourceLanguage = displayName(detectedTag),
                                targetLanguage = targetDisplayName
                            )
                        )

                        val options = TranslatorOptions.Builder()
                            .setSourceLanguage(sourceTag)
                            .setTargetLanguage(targetTag)
                            .build()
                        val translator = Translation.getClient(options)

                        val translatedText = try {
                            translator.downloadModelIfNeeded().await()
                            publish(
                                TranslationState(
                                    status = TranslationStatus.TRANSLATING,
                                    sourceLanguage = displayName(detectedTag),
                                    targetLanguage = targetDisplayName
                                )
                            )
                            val translatedLines = document.texts.map { line ->
                                translator.translate(line).await()
                            }
                            document.restore(translatedLines)
                        } finally {
                            translator.close()
                        }

                        publish(
                            TranslationState(
                                status = TranslationStatus.DONE,
                                translatedText = translatedText,
                                sourceLanguage = displayName(detectedTag),
                                targetLanguage = targetDisplayName
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                publish(TranslationState())
                throw e
            } catch (e: Exception) {
                publish(
                    TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = e.message ?: "Translation failed"
                    )
                )
            }
        }
    }

    private fun sameLanguage(source: String, target: String): Boolean =
        source.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)

    private fun displayName(languageTag: String): String =
        Locale.forLanguageTag(languageTag).displayLanguage
}
