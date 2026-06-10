package com.darkwisp.app.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import com.darkwisp.app.nostr.NipA3
import com.darkwisp.app.nostr.NostrEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Cache of NIP-A3 payment target lists (kind 10133) keyed by pubkey. */
class PaymentTargetRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wisp_payment_targets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // pubkey -> parsed payment targets
    private val cache = LruCache<String, List<NipA3.PaymentTarget>>(2000)
    // pubkey -> event timestamp
    private val timestamps = LruCache<String, Long>(2000)

    private val _version = MutableStateFlow(0)
    /** Bumped on every cache update so Compose can react to late-arriving events. */
    val version: StateFlow<Int> = _version

    init {
        loadFromPrefs()
    }

    fun updateFromEvent(event: NostrEvent) {
        if (event.kind != NipA3.KIND) return
        val existing = timestamps.get(event.pubkey)
        if (existing != null && event.created_at <= existing) return

        // Unlike relay lists, an empty result must be stored: an empty kind 10133
        // means the user cleared their targets, and dropping it would pin stale ones.
        val targets = NipA3.parse(event)
        cache.put(event.pubkey, targets)
        timestamps.put(event.pubkey, event.created_at)
        saveToPrefs(event.pubkey, targets, event.created_at)
        _version.value++
    }

    /** null = never fetched; empty list = fetched and known to have none. */
    fun getTargets(pubkey: String): List<NipA3.PaymentTarget>? = cache.get(pubkey)

    fun hasEntry(pubkey: String): Boolean = cache.get(pubkey) != null

    fun clear() {
        cache.evictAll()
        timestamps.evictAll()
        prefs.edit().clear().apply()
        _version.value++
    }

    private fun saveToPrefs(pubkey: String, targets: List<NipA3.PaymentTarget>, timestamp: Long) {
        val serializable = targets.map { SerializableTarget(it.type, it.authority) }
        prefs.edit()
            .putString("pt_$pubkey", json.encodeToString(serializable))
            .putLong("pt_ts_$pubkey", timestamp)
            .apply()
    }

    private fun loadFromPrefs() {
        val pubkeys = prefs.all.keys
            .filter { it.startsWith("pt_") && !it.startsWith("pt_ts_") }
            .map { it.removePrefix("pt_") }

        for (pubkey in pubkeys) {
            try {
                val str = prefs.getString("pt_$pubkey", null) ?: continue
                val ts = prefs.getLong("pt_ts_$pubkey", 0)
                val serializable = json.decodeFromString<List<SerializableTarget>>(str)
                cache.put(pubkey, serializable.map { NipA3.PaymentTarget(it.type, it.authority) })
                timestamps.put(pubkey, ts)
            } catch (_: Exception) {}
        }
    }

    @Serializable
    private data class SerializableTarget(val type: String, val authority: String)
}
