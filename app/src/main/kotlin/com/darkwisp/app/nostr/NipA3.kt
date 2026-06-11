package com.darkwisp.app.nostr

import java.net.URLEncoder

/**
 * NIP-A3: payto: Payment Targets (RFC-8905).
 *
 * Kind 10133 is a replaceable event whose ["payto", "<type>", "<authority>"] tags
 * declare payment addresses (bitcoin, monero, venmo, ...) for the author.
 * Clients assemble payto://<type>/<authority> URIs from each tag.
 */
object NipA3 {
    const val KIND = 10133
    const val TAG_NAME = "payto"

    data class PaymentTarget(val type: String, val authority: String)

    data class TargetStyle(val displayName: String, val symbol: String?, val ticker: String?)

    /** Recognized types from the NIP-A3 stylization table. */
    val RECOGNIZED: Map<String, TargetStyle> = mapOf(
        "bitcoin" to TargetStyle("Bitcoin", "₿", "BTC"),
        "cashme" to TargetStyle("Cash App", "$", null),
        "ethereum" to TargetStyle("Ethereum", "Ξ", "ETH"),
        "lightning" to TargetStyle("Lightning", "⚡", "LBTC"),
        "monero" to TargetStyle("Monero", "ɱ", "XMR"),
        "nano" to TargetStyle("Nano", "Ӿ", "XNO"),
        "revolut" to TargetStyle("Revolut", null, null),
        "venmo" to TargetStyle("Venmo", "$", null),
        "bitcoincash" to TargetStyle("Bitcoin Cash", "₿", "BCH"),
        "dash" to TargetStyle("Dash", "Đ", "DASH"),
        "litecoin" to TargetStyle("Litecoin", "Ł", "LTC"),
        "zcash" to TargetStyle("Zcash", "Z", "ZEC")
    )

    private val TYPE_REGEX = Regex("^[a-z0-9-]+$")

    /** Types with a widely supported native Android URI scheme; preferred over payto://. */
    private val NATIVE_SCHEMES = mapOf(
        "bitcoin" to "bitcoin:",
        "bitcoincash" to "bitcoincash:",
        "dash" to "dash:",
        "ethereum" to "ethereum:",
        "lightning" to "lightning:",
        "litecoin" to "litecoin:",
        "monero" to "monero:",
        "nano" to "nano:",
        "zcash" to "zcash:"
    )

    /** Lowercased, trimmed type, or null if it isn't a valid payto type. */
    fun normalizeType(raw: String): String? {
        val type = raw.trim().lowercase()
        return if (TYPE_REGEX.matches(type)) type else null
    }

    fun isValidAuthority(authority: String): Boolean =
        authority.isNotBlank() && authority.none { it.isWhitespace() || it.isISOControl() }

    fun parse(event: NostrEvent): List<PaymentTarget> {
        if (event.kind != KIND) return emptyList()
        return event.tags.mapNotNull { tag ->
            // Elements past index 2 are reserved for future RFC-8905 features; ignore them.
            if (tag.size < 3 || tag[0] != TAG_NAME) return@mapNotNull null
            val type = normalizeType(tag[1]) ?: return@mapNotNull null
            val authority = tag[2]
            if (!isValidAuthority(authority)) return@mapNotNull null
            PaymentTarget(type, authority)
        }.distinct()
    }

    fun buildTags(targets: List<PaymentTarget>): List<List<String>> =
        targets.map { listOf(TAG_NAME, it.type, it.authority) }

    fun assemblePaytoUri(target: PaymentTarget): String {
        val encoded = URLEncoder.encode(target.authority, "UTF-8").replace("+", "%20")
        return "payto://${target.type}/$encoded"
    }

    /**
     * URI for launching a wallet app: native scheme (bitcoin:, monero:, ...) for
     * recognized types since almost no Android wallet handles payto://, else payto://.
     */
    fun nativeUri(target: PaymentTarget): String =
        NATIVE_SCHEMES[target.type]?.let { it + target.authority } ?: assemblePaytoUri(target)

    fun displayName(type: String): String =
        RECOGNIZED[type]?.displayName ?: type.replaceFirstChar { it.uppercase() }

    fun symbol(type: String): String? = RECOGNIZED[type]?.symbol

    fun ticker(type: String): String? = RECOGNIZED[type]?.ticker
}
