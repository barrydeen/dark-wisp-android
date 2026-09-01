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

    data class TargetStyle(val displayName: String, val ticker: String?)

    /**
     * Types the UI styles and offers as quick-pick chips. The first group is the
     * NIP-A3 "Commonly Used Tags" list; the second is our own additions, which the
     * spec allows since unrecognized types stay valid and fall back to payto://.
     * Display names and tickers are ours — the spec prescribes no stylization.
     * Visual marks live in PaymentTargetGlyph, not here (protocol layer stays UI-free).
     */
    val RECOGNIZED: Map<String, TargetStyle> = mapOf(
        "lightning" to TargetStyle("Lightning", "LBTC"),
        "bitcoin" to TargetStyle("Bitcoin", "BTC"),
        "bip352" to TargetStyle("Silent Payments", null),
        "bip353" to TargetStyle("DNS Address", null),
        "monero" to TargetStyle("Monero", "XMR"),
        "ethereum" to TargetStyle("Ethereum", "ETH"),
        "solana" to TargetStyle("Solana", "SOL"),
        "litecoin" to TargetStyle("Litecoin", "LTC"),
        "zcash" to TargetStyle("Zcash", "ZEC"),
        "bitcoincash" to TargetStyle("Bitcoin Cash", "BCH"),
        "dash" to TargetStyle("Dash", "DASH"),
        "nano" to TargetStyle("Nano", "XNO"),
        "cashme" to TargetStyle("Cash App", null),
        "paypal" to TargetStyle("PayPal", null),
        "revolut" to TargetStyle("Revolut", null),
        "venmo" to TargetStyle("Venmo", null)
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
        "solana" to "solana:",
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

    fun ticker(type: String): String? = RECOGNIZED[type]?.ticker

    /** Result of decoding a scanned QR payload. [type] is null when the payload
     *  carried no scheme, in which case only the address could be recovered. */
    data class ScanResult(val type: String?, val authority: String)

    /** scheme (without ":") -> payto type, derived from [NATIVE_SCHEMES]. */
    private val SCHEME_TO_TYPE: Map<String, String> =
        NATIVE_SCHEMES.entries.associate { (type, scheme) -> scheme.removeSuffix(":") to type }

    private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** Schemes that are never payment types. Scanning a website or profile QR by
     *  mistake should surface the raw payload, not invent a "https"/"nostr" type. */
    private val NON_PAYMENT_SCHEMES = setOf("http", "https", "nostr", "mailto", "tel", "sms", "geo")

    /**
     * Decode a scanned QR payload into a type + address.
     *
     * Handles `payto://<type>/<address>` (RFC-8905), native wallet schemes
     * (`bitcoin:`, `monero:`, ...) and bare addresses. Query strings such as
     * `?amount=` are dropped, since a payment target stores only the address.
     */
    fun parseScannedUri(raw: String): ScanResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ScanResult(null, "")

        // payto://<type>/<address>
        val paytoPrefix = "payto://"
        if (trimmed.regionMatches(0, paytoPrefix, 0, paytoPrefix.length, ignoreCase = true)) {
            val rest = trimmed.substring(paytoPrefix.length).substringBefore('?').substringBefore('#')
            val type = normalizeType(rest.substringBefore('/'))
            val authority = rest.substringAfter('/', "").let {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
            }
            return ScanResult(type, authority)
        }

        val match = SCHEME_REGEX.find(trimmed)
        if (match != null) {
            val scheme = match.groupValues[1].lowercase()
            // Strip an authority-style "//" prefix, then any query/fragment.
            val body = match.groupValues[2]
                .removePrefix("//")
                .substringBefore('?')
                .substringBefore('#')
            if (scheme in NON_PAYMENT_SCHEMES) return ScanResult(null, trimmed)
            val type = SCHEME_TO_TYPE[scheme] ?: normalizeType(scheme)
            return ScanResult(type, body)
        }

        // Bare address — the caller keeps whichever type is already selected.
        return ScanResult(null, trimmed.substringBefore('?'))
    }
}
