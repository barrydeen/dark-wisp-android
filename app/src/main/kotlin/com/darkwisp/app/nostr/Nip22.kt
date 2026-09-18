package com.darkwisp.app.nostr

/**
 * NIP-22 comments (kind 1111).
 *
 * Partial: enough to *read* comment threads. A comment is scoped to a root by
 * uppercase `E`/`A`/`I` tags, with lowercase tags naming the immediate parent.
 *
 * Composing replies is not handled here. NIP-22 forbids answering a comment with
 * a kind 1, so a reply to a comment must itself be kind 1111 — until that's
 * wired up, replies to comments are still built as NIP-10 kind-1 events.
 */
object Nip22 {
    const val KIND_COMMENT = 1111

    fun isComment(event: NostrEvent): Boolean = event.kind == KIND_COMMENT

    /**
     * Root scope of a comment: the uppercase `E` tag per NIP-22. Lowercase `e`
     * names only the immediate parent, so for a reply-to-a-comment this is the
     * only pointer to the thread root.
     */
    fun getRootScopeId(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "E" }?.get(1)

    /**
     * True if the event belongs to the thread rooted at [rootId], either as a
     * direct reply (lowercase `e`) or via NIP-22 root scope (uppercase `E`).
     */
    fun referencesRoot(event: NostrEvent, rootId: String): Boolean =
        event.tags.any { it.size >= 2 && (it[0] == "e" || it[0] == "E") && it[1] == rootId }
}
