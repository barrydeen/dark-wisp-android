package com.darkwisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.nostr.DmConversation
import com.darkwisp.app.nostr.DmMessage
import com.darkwisp.app.nostr.DmReaction
import com.darkwisp.app.nostr.EncryptedMedia
import com.darkwisp.app.nostr.Nip17
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.nostr.toHex
import com.darkwisp.app.repo.DmRepository
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.KeyRepository
import com.darkwisp.app.repo.NotificationRepository
import com.darkwisp.app.repo.PrivateRumorHandler
import com.darkwisp.app.repo.SigningMode
import com.darkwisp.app.repo.MuteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DmListViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    val conversationList: StateFlow<List<DmConversation>>
        get() = dmRepo?.conversationList ?: MutableStateFlow(emptyList())

    val hasUnreadDms: StateFlow<Boolean>
        get() = dmRepo?.hasUnreadDms ?: MutableStateFlow(false)

    private var dmRepo: DmRepository? = null
    private var muteRepo: MuteRepository? = null
    private var eventRepo: EventRepository? = null
    private var notifRepo: NotificationRepository? = null

    fun init(
        dmRepository: DmRepository,
        muteRepository: MuteRepository? = null,
        eventRepository: EventRepository? = null,
        notificationRepository: NotificationRepository? = null
    ) {
        dmRepo = dmRepository
        muteRepo = muteRepository
        eventRepo = eventRepository
        notifRepo = notificationRepository
    }

    /** Route a non-DM rumor (kind 1 private reply) out of the DM pipeline.
     *  Returns true when the rumor was consumed. */
    private fun routePrivateRumor(rumor: Nip17.Rumor, myPubkey: String): Boolean {
        if (rumor.kind != 1) return false
        val eRepo = eventRepo ?: return true   // can't materialise without repos — drop, don't misfile as DM
        val nRepo = notifRepo ?: return true
        PrivateRumorHandler.handlePrivateReply(rumor, myPubkey, eRepo, nRepo, muteRepo)
        return true
    }

    fun markDmsRead() {
        dmRepo?.markDmsRead()
    }

    fun processGiftWrap(event: NostrEvent, relayUrl: String = "") {
        if (event.kind != 1059) return
        val repo = dmRepo ?: return

        // Remote signer mode: store raw gift wraps, decrypt later when user views DMs
        if (keyRepo.getSigningMode() == SigningMode.REMOTE) {
            repo.addPendingGiftWrap(event, relayUrl)
            return
        }

        val keypair = keyRepo.getKeypair() ?: return
        val myPubkey = keypair.pubkey.toHex()

        viewModelScope.launch(Dispatchers.Default) {
            val rumor = Nip17.unwrapGiftWrap(keypair.privkey, event) ?: return@launch

            // NIP-17 private reply (kind 1 rumor) — belongs in threads, not DMs
            if (routePrivateRumor(rumor, myPubkey)) return@launch

            // Private DM reaction — associate with the target message
            if (Nip17.isReaction(rumor)) {
                val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                    ?: return@launch
                val participants = Nip17.getConversationParticipants(rumor, myPubkey)
                val convKey = DmRepository.conversationKey(participants + myPubkey)
                repo.addReaction(convKey, targetId, DmReaction(rumor.pubkey, rumor.content.trim(), rumor.createdAt))
                return@launch
            }

            val participants = Nip17.getConversationParticipants(rumor, myPubkey)
            if (participants.any { muteRepo?.isBlocked(it) == true }) return@launch

            val convKey = DmRepository.conversationKey(participants + myPubkey)
            val replyToId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" && it.any { v -> v == "reply" } }?.get(1)
            val rumorId = Nip17.computeRumorId(rumor)
            val fileMetadata = if (Nip17.isFileMessage(rumor)) {
                EncryptedMedia.parseKind15Tags(rumor.tags, rumor.content)
            } else null
            val msg = DmMessage(
                id = "${event.id}:${rumor.createdAt}",
                senderPubkey = rumor.pubkey,
                content = rumor.content,
                createdAt = rumor.createdAt,
                giftWrapId = event.id,
                relayUrls = if (relayUrl.isNotEmpty()) setOf(relayUrl) else emptySet(),
                rumorId = rumorId,
                replyToId = replyToId,
                participants = participants,
                encryptedFileMetadata = fileMetadata,
                debugGiftWrapJson = event.toJson(),
                debugRumorJson = Nip17.rumorToJson(rumor)
            )
            repo.addMessage(msg, convKey)
        }
    }

    val decrypting: StateFlow<Boolean>
        get() = dmRepo?.decrypting ?: MutableStateFlow(false)

    val pendingDecryptCount: StateFlow<Int>
        get() = dmRepo?.pendingDecryptCount ?: MutableStateFlow(0)

    // ---- Group DM creation ----

    private val _selectedContacts = MutableStateFlow<Set<String>>(emptySet())
    val selectedContacts: StateFlow<Set<String>> = _selectedContacts

    fun toggleContactSelection(pubkey: String) {
        _selectedContacts.value = _selectedContacts.value.let {
            if (it.contains(pubkey)) it - pubkey else it + pubkey
        }
    }

    fun clearContactSelection() {
        _selectedContacts.value = emptySet()
    }

    /**
     * Pre-create a group conversation and return the conversationKey for navigation.
     * [myPubkey] is the local user's pubkey included in the key computation.
     */
    fun createGroupConversation(participantPubkeys: List<String>, myPubkey: String): String {
        val repo = dmRepo ?: return ""
        val allParticipants = (participantPubkeys + myPubkey).distinct()
        val convKey = DmRepository.conversationKey(allParticipants)
        val participants = allParticipants.filter { it != myPubkey }
        repo.initConversation(convKey, participants)
        return convKey
    }

    /**
     * Decrypt pending gift wraps using the remote signer, one at a time.
     * Called when user navigates to the DM list screen in remote signer mode.
     */
    fun decryptPending(signer: NostrSigner) {
        val repo = dmRepo ?: return
        val myPubkey = signer.pubkeyHex

        viewModelScope.launch(Dispatchers.Default) {
            if (repo.pendingDecryptCount.value == 0) return@launch

            repo.markDecryptingStart()
            try {
                while (true) {
                    val wrap = repo.takeNextPendingGiftWrap() ?: break
                    try {
                        val rumor = Nip17.unwrapGiftWrapRemote(signer, wrap.event) ?: continue

                        // NIP-17 private reply (kind 1 rumor) — belongs in threads, not DMs
                        if (routePrivateRumor(rumor, myPubkey)) continue

                        if (Nip17.isReaction(rumor)) {
                            val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
                                ?: continue
                            val participants = Nip17.getConversationParticipants(rumor, myPubkey)
                            val convKey = DmRepository.conversationKey(participants + myPubkey)
                            repo.addReaction(convKey, targetId, DmReaction(rumor.pubkey, rumor.content.trim(), rumor.createdAt))
                            continue
                        }

                        val participants = Nip17.getConversationParticipants(rumor, myPubkey)
                        if (participants.any { muteRepo?.isBlocked(it) == true }) continue

                        val convKey = DmRepository.conversationKey(participants + myPubkey)
                        val replyToId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" && it.any { v -> v == "reply" } }?.get(1)
                        val rumorId = Nip17.computeRumorId(rumor)
                        val fileMetadata = if (Nip17.isFileMessage(rumor)) {
                            EncryptedMedia.parseKind15Tags(rumor.tags, rumor.content)
                        } else null
                        val msg = DmMessage(
                            id = "${wrap.event.id}:${rumor.createdAt}",
                            senderPubkey = rumor.pubkey,
                            content = rumor.content,
                            createdAt = rumor.createdAt,
                            giftWrapId = wrap.event.id,
                            relayUrls = if (wrap.relayUrl.isNotEmpty()) setOf(wrap.relayUrl) else emptySet(),
                            rumorId = rumorId,
                            replyToId = replyToId,
                            participants = participants,
                            encryptedFileMetadata = fileMetadata,
                            debugGiftWrapJson = wrap.event.toJson(),
                            debugRumorJson = Nip17.rumorToJson(rumor)
                        )
                        repo.addMessage(msg, convKey)
                    } catch (_: Exception) {
                        // Individual wrap failed, continue with the rest
                    }
                }
            } finally {
                repo.markDecryptingEnd()
            }
        }
    }
}
