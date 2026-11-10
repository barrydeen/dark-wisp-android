package com.darkwisp.app.nostr

/**
 * NIP-99 classified listing helpers.
 *
 * Listings are addressable events (kind 30402). Updating a listing means publishing
 * a newer event with the same author and d-tag; readers should resolve the newest
 * version of that address.
 */
object Nip99 {
    const val KIND_LISTING = 30402
    const val KIND_DRAFT = 30403

    data class Price(val amount: String, val currency: String, val frequency: String? = null)

    fun tag(event: NostrEvent, name: String): List<String>? =
        event.tags.lastOrNull { it.size >= 2 && it[0] == name }

    fun tags(event: NostrEvent, name: String): List<List<String>> =
        event.tags.filter { it.size >= 2 && it[0] == name }

    fun dTag(event: NostrEvent): String = tag(event, "d")?.getOrNull(1).orEmpty()
    fun title(event: NostrEvent): String = tag(event, "title")?.getOrNull(1).orEmpty()
    fun summary(event: NostrEvent): String = tag(event, "summary")?.getOrNull(1).orEmpty()
    fun location(event: NostrEvent): String = tag(event, "location")?.getOrNull(1).orEmpty()
    fun status(event: NostrEvent): String = tag(event, "status")?.getOrNull(1)?.lowercase() ?: "active"
    fun isSold(event: NostrEvent): Boolean = status(event) == "sold"
    fun publishedAt(event: NostrEvent): String =
        tag(event, "published_at")?.getOrNull(1) ?: event.created_at.toString()

    fun images(event: NostrEvent): List<String> =
        tags(event, "image").mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }.distinct()

    fun categories(event: NostrEvent): List<String> =
        tags(event, "t").mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }.distinct()

    fun price(event: NostrEvent): Price? {
        val tag = tag(event, "price") ?: return null
        val amount = tag.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val currency = tag.getOrNull(2)?.takeIf(String::isNotBlank) ?: return null
        return Price(amount, currency, tag.getOrNull(3)?.takeIf(String::isNotBlank))
    }

    fun priceLabel(event: NostrEvent): String? {
        val value = price(event) ?: return null
        val frequency = value.frequency?.let { "/$it" }.orEmpty()
        return value.amount + " " + value.currency + frequency
    }

    fun buildTags(
        dTag: String,
        title: String,
        summary: String,
        publishedAt: String,
        amount: String,
        currency: String,
        location: String,
        category: String,
        images: List<String>,
        status: String
    ): List<List<String>> {
        val result = mutableListOf(
            listOf("d", dTag.trim()),
            listOf("title", title.trim()),
            listOf("summary", summary.trim()),
            listOf("published_at", publishedAt),
            listOf("status", if (status.equals("sold", true)) "sold" else "active")
        )
        if (location.isNotBlank()) result.add(listOf("location", location.trim()))
        if (amount.isNotBlank() && currency.isNotBlank()) {
            result.add(listOf("price", amount.trim(), currency.trim().uppercase()))
        }
        category.split(',', '#', '\n')
            .map { it.trim().removePrefix("#").lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { result.add(listOf("t", it)) }
        images.map(String::trim).filter(String::isNotBlank).distinct()
            .forEach { result.add(listOf("image", it)) }
        result.add(listOf("client", "Dark Wisp"))
        return result
    }
}
