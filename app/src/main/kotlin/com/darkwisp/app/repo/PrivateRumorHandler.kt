package com.darkwisp.app.repo

import com.darkwisp.app.nostr.Nip10
import com.darkwisp.app.nostr.Nip17
import com.darkwisp.app.nostr.NostrEvent

/**
 * Routes non-DM rumors received via NIP-17 gift wrap (kind 1 private replies) into the
 * note + notification repositories.
 *
 * Shared by [com.darkwisp.app.viewmodel.EventRouter] (local-signer path, wraps decrypted
 * as they arrive) and the remote-signer pending-decrypt paths in
 * [com.darkwisp.app.viewmodel.DmListViewModel] and
 * [com.darkwisp.app.viewmodel.DmConversationViewModel], which would otherwise misfile
 * these rumors as DM messages.
 */
object PrivateRumorHandler {

    /** Materialise a kind 1 private-reply rumor: mark it private, cache a synthetic event,
     *  bump the parent's reply count, and notify (unless it's our own self-copy wrap). */
    fun handlePrivateReply(
        rumor: Nip17.Rumor,
        myPubkey: String,
        eventRepo: EventRepository,
        notifRepo: NotificationRepository,
        muteRepo: MuteRepository?,
        onMissingProfile: (String) -> Unit = {}
    ) {
        if (muteRepo?.isBlocked(rumor.pubkey) == true) return

        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 1,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivateReply(rumorId)
        eventRepo.cacheEvent(synthetic)
        if (!Nip10.isStandaloneQuote(synthetic)) {
            val parentId = Nip10.getReplyTarget(synthetic)
            if (parentId != null) eventRepo.addReplyCount(parentId, synthetic.id)
        }
        // Self-copy wraps from another device land here too — skip the notification, but
        // the cache write above still surfaces the reply in our thread view.
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, replyToMyEvent = true, source = "gift-wrap-private-reply")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            onMissingProfile(rumor.pubkey)
        }
    }
}
