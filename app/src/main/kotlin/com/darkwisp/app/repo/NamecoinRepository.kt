/*
 * Singleton repository for Namecoin (.bit / d/ / id/) name resolution
 * over ElectrumX, mirroring the role Nip05Repository plays for DNS-based
 * NIP-05. Used by the global search bar to surface .bit identifiers
 * inline before the relay-side search returns results.
 */
package com.darkwisp.app.repo

import com.darkwisp.app.nostr.NamecoinElectrumXClient
import com.darkwisp.app.nostr.NamecoinResolveOutcome
import com.darkwisp.app.nostr.NamecoinResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Coarse-grained UI state for the inline resolution row. */
sealed class NamecoinResolutionState {
    data object Idle : NamecoinResolutionState()
    data class Resolving(val identifier: String) : NamecoinResolutionState()
    data class Resolved(val identifier: String, val outcome: NamecoinResolveOutcome) :
        NamecoinResolutionState()
}

class NamecoinRepository(
    private val client: NamecoinElectrumXClient = NamecoinElectrumXClient(),
    private val resolver: NamecoinResolver = NamecoinResolver(client),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Outcome cache keyed by normalised identifier. */
    private val cache = ConcurrentHashMap<String, NamecoinResolveOutcome>()

    private val _state = MutableStateFlow<NamecoinResolutionState>(NamecoinResolutionState.Idle)
    val state: StateFlow<NamecoinResolutionState> = _state.asStateFlow()

    private var inflight: Job? = null
    private var inflightKey: String? = null

    /** True only for inputs the resolver would actually try. */
    fun looksLikeNamecoinIdentifier(input: String): Boolean =
        NamecoinResolver.looksLikeNamecoinIdentifier(input)

    /**
     * Start (or reuse) a resolution for [identifier]. Returns immediately;
     * observe [state] for the outcome.
     */
    fun resolve(identifier: String) {
        val normalised = identifier.trim().removePrefix("nostr:").lowercase()
        if (normalised.isEmpty() || !looksLikeNamecoinIdentifier(normalised)) {
            clear()
            return
        }
        cache[normalised]?.let { cached ->
            _state.value = NamecoinResolutionState.Resolved(normalised, cached)
            return
        }
        if (inflightKey == normalised && inflight?.isActive == true) return

        inflight?.cancel()
        inflightKey = normalised
        _state.value = NamecoinResolutionState.Resolving(normalised)
        inflight = scope.launch {
            val outcome = try {
                resolver.resolve(normalised)
            } catch (e: Exception) {
                NamecoinResolveOutcome.ServersUnreachable(e.message ?: "lookup failed")
            }
            cache[normalised] = outcome
            // Only publish if the active query is still this one.
            if (inflightKey == normalised) {
                _state.value = NamecoinResolutionState.Resolved(normalised, outcome)
            }
        }
    }

    /** Drop in-flight work and reset to Idle. */
    fun clear() {
        inflight?.cancel()
        inflight = null
        inflightKey = null
        _state.value = NamecoinResolutionState.Idle
    }

    /** Wipe the cache; useful for sign-out. */
    fun clearCache() {
        cache.clear()
        clear()
    }
}
