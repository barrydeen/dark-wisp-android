package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Filter
import com.darkwisp.app.nostr.Nip99
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.relay.RelayConfig
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.EventRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

enum class MarketplaceScope { ALL, FOLLOWING, MINE }

class MarketplaceViewModel(app: Application) : AndroidViewModel(app) {
    private val _listings = MutableStateFlow<List<NostrEvent>>(emptyList())
    val listings: StateFlow<List<NostrEvent>> = _listings

    private val _scope = MutableStateFlow(MarketplaceScope.ALL)
    val scope: StateFlow<MarketplaceScope> = _scope

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var loadJob: Job? = null
    private var activeSubscriptionId: String? = null
    private var displayedScope: MarketplaceScope? = null
    private val listingsByScope = ConcurrentHashMap<MarketplaceScope, List<NostrEvent>>()

    fun setScope(scope: MarketplaceScope) {
        _scope.value = scope
    }

    fun load(
        selectedScope: MarketplaceScope,
        relayPool: RelayPool,
        eventRepository: EventRepository,
        followedPubkeys: List<String>,
        userPubkey: String?
    ) {
        _scope.value = selectedScope
        if (displayedScope != selectedScope) {
            // Restore the last snapshot for this scope instead of briefly showing
            // listings from another scope or clearing the screen during a refresh.
            _listings.value = listingsByScope[selectedScope].orEmpty()
            displayedScope = selectedScope
        }
        loadJob?.cancel()
        activeSubscriptionId?.let { oldId ->
            val close = ClientMessage.close(oldId)
            relayPool.sendToAll(close)
            RelayConfig.DEFAULT_INDEXER_RELAYS.forEach { relayPool.sendToRelay(it, close) }
        }

        val authors = when (selectedScope) {
            MarketplaceScope.ALL -> emptyList()
            MarketplaceScope.FOLLOWING -> followedPubkeys.distinct()
            MarketplaceScope.MINE -> listOfNotNull(userPubkey)
        }

        if (selectedScope != MarketplaceScope.ALL && authors.isEmpty()) {
            listingsByScope[selectedScope] = emptyList()
            _listings.value = emptyList()
            _loading.value = false
            _error.value = null
            return
        }

        val subId = "marketplace-" + System.currentTimeMillis().toString()
        activeSubscriptionId = subId
        _loading.value = true
        _error.value = null

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val filter = Filter(
                kinds = listOf(Nip99.KIND_LISTING),
                authors = authors.takeIf { it.isNotEmpty() },
                limit = 200
            )
            val request = ClientMessage.req(subId, filter)
            RelayConfig.DEFAULT_INDEXER_RELAYS.forEach {
                relayPool.sendToRelayOrEphemeral(it, request, skipBadCheck = true)
            }
            relayPool.sendToAll(request)

            val byAddress = linkedMapOf<String, NostrEvent>()
            listingsByScope[selectedScope].orEmpty().forEach { cached ->
                byAddress[cached.pubkey + ":" + Nip99.dTag(cached)] = cached
            }
            try {
                withTimeoutOrNull(7000L) {
                    relayPool.relayEvents
                        .filter { it.subscriptionId == subId }
                        .collect { relayEvent ->
                            val event = relayEvent.event
                            if (event.kind != Nip99.KIND_LISTING) return@collect
                            if (selectedScope == MarketplaceScope.FOLLOWING && event.pubkey !in authors) return@collect
                            if (selectedScope == MarketplaceScope.MINE && event.pubkey != userPubkey) return@collect
                            if (!eventRepository.isEventVisibleToUser(event)) return@collect
                            val dTag = Nip99.dTag(event)
                            if (dTag.isBlank() || Nip99.title(event).isBlank()) return@collect

                            eventRepository.addEvent(event)
                            val address = event.pubkey + ":" + dTag
                            val previous = byAddress[address]
                            if (previous == null || event.created_at >= previous.created_at) {
                                byAddress[address] = event
                                val snapshot = byAddress.values.sortedByDescending { it.created_at }
                                listingsByScope[selectedScope] = snapshot
                                if (displayedScope == selectedScope) {
                                    _listings.value = snapshot
                                }
                            }
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                val close = ClientMessage.close(subId)
                relayPool.sendToAll(close)
                RelayConfig.DEFAULT_INDEXER_RELAYS.forEach { relayPool.sendToRelay(it, close) }
                val snapshot = byAddress.values.sortedByDescending { it.created_at }
                listingsByScope[selectedScope] = snapshot
                if (activeSubscriptionId == subId) {
                    _loading.value = false
                    if (displayedScope == selectedScope) _listings.value = snapshot
                }
            }
        }
    }

    fun addOrUpdate(event: NostrEvent) {
        if (event.kind != Nip99.KIND_LISTING || Nip99.dTag(event).isBlank()) return
        val key = event.pubkey + ":" + Nip99.dTag(event)
        val updated = _listings.value.filterNot {
            it.pubkey + ":" + Nip99.dTag(it) == key
        } + event
        val snapshot = updated.sortedByDescending { it.created_at }
        listingsByScope[_scope.value] = snapshot
        _listings.value = snapshot
    }

    fun clear() {
        loadJob?.cancel()
        activeSubscriptionId = null
        displayedScope = null
        listingsByScope.clear()
        _listings.value = emptyList()
        _loading.value = false
    }

    override fun onCleared() {
        loadJob?.cancel()
        super.onCleared()
    }
}
