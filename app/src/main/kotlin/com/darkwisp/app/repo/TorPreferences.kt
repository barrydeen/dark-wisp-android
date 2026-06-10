package com.darkwisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Device-level Tor preference. Deliberately NOT per-account: the toggle must work
 * before login and survive logout/account switches.
 */
object TorPreferences {
    private const val PREFS_NAME = "wisp_tor"
    private const val KEY_ENABLED = "tor_enabled"

    private lateinit var prefs: SharedPreferences

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(): Boolean = _enabled.value

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
