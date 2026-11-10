package com.darkwisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's DeepL credential in AndroidX encrypted preferences.
 * The key is never written to ordinary preferences, BuildConfig, or logs.
 */
class DeepLKeyStore(context: Context) {
    private val encryptedPrefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        encryptedPrefs = EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(): String? =
        encryptedPrefs.getString(KEY_DEEPL_API_KEY, null)

    fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "The DeepL API key cannot be empty." }
        encryptedPrefs.edit().putString(KEY_DEEPL_API_KEY, apiKey.trim()).apply()
    }

    fun clearApiKey() {
        encryptedPrefs.edit().remove(KEY_DEEPL_API_KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "wisp_translation_secrets"
        const val KEY_DEEPL_API_KEY = "deepl_api_key"
    }
}
