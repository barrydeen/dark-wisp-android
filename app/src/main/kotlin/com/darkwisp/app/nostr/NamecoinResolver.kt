/*
 * MIT License
 *
 * Namecoin → Nostr resolver.
 *
 * Ported from Vitor Pamplona's Amethyst (Quartz) implementation:
 *   quartz/.../namecoin/NamecoinNameResolver.kt
 *
 * Accepts the same identifier shapes Amethyst accepts:
 *   alice@example.bit   → d/example, localPart=alice
 *   _@example.bit       → d/example, localPart=_
 *   example.bit         → d/example, localPart=_
 *   d/example           → d/example, localPart=_
 *   id/alice            → id/alice,  localPart=_
 *
 * And the same Namecoin record shapes for the `nostr` field:
 *   { "nostr": "hex-pubkey" }                          simple
 *   { "nostr": { "pubkey": "hex", "relays": [...] } }  single-identity
 *   { "nostr": { "names": { ... }, "relays": {...} } } NIP-05-like
 *
 * ifa-0001 `import` chains are NOT supported in this minimal port.
 * Add later in step with Amethyst's NamecoinImportResolver if records
 * that use `import` need to resolve here.
 */
package com.darkwisp.app.nostr

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Resolved Nostr identity from a Namecoin name. */
data class NamecoinResolvedIdentity(
    /** Hex-encoded 32-byte schnorr pubkey. */
    val pubkey: String,
    /** Optional relay URLs the record advertises for this identity. */
    val relays: List<String> = emptyList(),
    /** The Namecoin name that was queried (e.g. `d/example`). */
    val namecoinName: String,
    /** The local-part matched (e.g. `alice` or `_`). */
    val localPart: String = "_",
)

/** Detailed outcome used by the search bar for inline status messages. */
sealed class NamecoinResolveOutcome {
    data class Success(val result: NamecoinResolvedIdentity) : NamecoinResolveOutcome()
    /** Identifier didn't look like a Namecoin name (so we skipped). */
    data class NotANamecoinIdentifier(val identifier: String) : NamecoinResolveOutcome()
    /** Blockchain says no such name. */
    data class NameNotFound(val name: String) : NamecoinResolveOutcome()
    /** Name exists, value parsed, but no `nostr` field. */
    data class NoNostrField(val name: String) : NamecoinResolveOutcome()
    /** Name exists but value is malformed JSON. */
    data class MalformedRecord(val name: String, val error: String) : NamecoinResolveOutcome()
    /** All ElectrumX servers were unreachable. */
    data class ServersUnreachable(val message: String) : NamecoinResolveOutcome()
    /** Resolution exceeded its deadline. */
    data object Timeout : NamecoinResolveOutcome()
}

class NamecoinResolver(
    private val client: NamecoinElectrumXClient,
    private val lookupTimeoutMs: Long = 20_000L,
    private val serverListProvider: () -> List<NamecoinElectrumxServer> =
        { DEFAULT_NAMECOIN_ELECTRUMX_SERVERS },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val HEX_PUBKEY_REGEX = Regex("^[0-9a-fA-F]{64}$")

        /** Cheap pre-filter: does this input look like a Namecoin name? */
        fun looksLikeNamecoinIdentifier(input: String): Boolean {
            val s = input.trim().lowercase().removePrefix("nostr:")
            if (s.isEmpty()) return false
            return s.endsWith(".bit") ||
                s.startsWith("d/") ||
                s.startsWith("id/") ||
                // alice@example.bit
                (s.contains("@") && s.substringAfterLast("@").endsWith(".bit"))
        }
    }

    /** Resolve with detailed outcome for UI feedback. */
    suspend fun resolve(identifier: String): NamecoinResolveOutcome {
        val parsed = parseIdentifier(identifier)
            ?: return NamecoinResolveOutcome.NotANamecoinIdentifier(identifier)
        return withTimeoutOrNull(lookupTimeoutMs) { perform(parsed) }
            ?: NamecoinResolveOutcome.Timeout
    }

    // ── Identifier parsing ─────────────────────────────────────────────

    private data class ParsedIdentifier(
        val namecoinName: String,
        val localPart: String,
        val namespace: Namespace,
    )

    private enum class Namespace { DOMAIN, IDENTITY }

    private fun parseIdentifier(raw: String): ParsedIdentifier? {
        val input = raw.trim().removePrefix("nostr:")
        if (input.startsWith("d/", ignoreCase = true)) {
            return ParsedIdentifier(input.lowercase(), "_", Namespace.DOMAIN)
        }
        if (input.startsWith("id/", ignoreCase = true)) {
            return ParsedIdentifier(input.lowercase(), "_", Namespace.IDENTITY)
        }
        if (input.contains("@") && input.endsWith(".bit", ignoreCase = true)) {
            val parts = input.split("@", limit = 2)
            if (parts.size != 2) return null
            val local = parts[0].lowercase().ifEmpty { "_" }
            val domain = parts[1].removeSuffix(".bit").removeSuffix(".BIT").lowercase()
            if (domain.isEmpty()) return null
            return ParsedIdentifier("d/$domain", local, Namespace.DOMAIN)
        }
        if (input.endsWith(".bit", ignoreCase = true)) {
            val domain = input.lowercase().removeSuffix(".bit")
            if (domain.isEmpty()) return null
            return ParsedIdentifier("d/$domain", "_", Namespace.DOMAIN)
        }
        return null
    }

    // ── Lookup + value parsing ─────────────────────────────────────────

    private suspend fun perform(parsed: ParsedIdentifier): NamecoinResolveOutcome {
        val nameResult: NamecoinNameShowResult
        try {
            nameResult = client.nameShowWithFallback(parsed.namecoinName, serverListProvider())
                ?: return NamecoinResolveOutcome.NameNotFound(parsed.namecoinName)
        } catch (e: NamecoinLookupException.NameNotFound) {
            return NamecoinResolveOutcome.NameNotFound(parsed.namecoinName)
        } catch (e: NamecoinLookupException.NameExpired) {
            return NamecoinResolveOutcome.NameNotFound(parsed.namecoinName)
        } catch (e: NamecoinLookupException.ServersUnreachable) {
            return NamecoinResolveOutcome.ServersUnreachable(
                e.message ?: "All ElectrumX servers unreachable"
            )
        }

        val valueJson = try {
            val el = json.parseToJsonElement(nameResult.value)
            el as? JsonObject
                ?: return NamecoinResolveOutcome.MalformedRecord(
                    parsed.namecoinName,
                    "top-level value is not a JSON object"
                )
        } catch (e: Exception) {
            return NamecoinResolveOutcome.MalformedRecord(
                parsed.namecoinName,
                e.message ?: "unparseable JSON value"
            )
        }

        val result = when (parsed.namespace) {
            Namespace.DOMAIN -> extractFromDomain(valueJson, parsed)
            Namespace.IDENTITY -> extractFromIdentity(valueJson, parsed)
        }
        return if (result != null) NamecoinResolveOutcome.Success(result)
        else NamecoinResolveOutcome.NoNostrField(parsed.namecoinName)
    }

    private fun extractFromDomain(value: JsonObject, parsed: ParsedIdentifier): NamecoinResolvedIdentity? {
        val nostr = value["nostr"] ?: return null

        // Simple-string: only resolves root.
        if (nostr is JsonPrimitive && nostr.isString) {
            if (parsed.localPart == "_" && isValidPubkey(nostr.content)) {
                return NamecoinResolvedIdentity(
                    pubkey = nostr.content.lowercase(),
                    namecoinName = parsed.namecoinName,
                    localPart = "_",
                )
            }
            if (parsed.localPart != "_") return null
        }

        if (nostr is JsonObject) {
            // NIP-05-like extended form: { "names": {...}, "relays": {...} }
            val names = nostr["names"]?.jsonObject
            if (names != null) {
                val exact = names[parsed.localPart]
                val root = names["_"]
                if (exact is JsonPrimitive && isValidPubkey(exact.content)) {
                    return NamecoinResolvedIdentity(
                        pubkey = exact.content.lowercase(),
                        relays = extractRelays(nostr, exact.content),
                        namecoinName = parsed.namecoinName,
                        localPart = parsed.localPart,
                    )
                }
                if (root is JsonPrimitive && isValidPubkey(root.content)) {
                    return NamecoinResolvedIdentity(
                        pubkey = root.content.lowercase(),
                        relays = extractRelays(nostr, root.content),
                        namecoinName = parsed.namecoinName,
                        localPart = "_",
                    )
                }
                // names was present but no match. Non-root requests stop here so
                // we never hand alice@example.bit the root operator's identity.
                if (parsed.localPart != "_") return null
            }

            // Single-identity form: { "pubkey": "hex", "relays": [...] }
            if (parsed.localPart == "_") {
                val pubkey = (nostr["pubkey"] as? JsonPrimitive)?.content
                if (pubkey != null && isValidPubkey(pubkey)) {
                    val relays = try {
                        nostr["relays"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                    return NamecoinResolvedIdentity(
                        pubkey = pubkey.lowercase(),
                        relays = relays,
                        namecoinName = parsed.namecoinName,
                        localPart = "_",
                    )
                }
            }
        }
        return null
    }

    private fun extractFromIdentity(value: JsonObject, parsed: ParsedIdentifier): NamecoinResolvedIdentity? {
        val nostr = value["nostr"] ?: return null

        if (nostr is JsonPrimitive && nostr.isString && isValidPubkey(nostr.content)) {
            return NamecoinResolvedIdentity(
                pubkey = nostr.content.lowercase(),
                namecoinName = parsed.namecoinName,
            )
        }

        if (nostr is JsonObject) {
            val pubkey = (nostr["pubkey"] as? JsonPrimitive)?.content
            if (pubkey != null && isValidPubkey(pubkey)) {
                val relays = try {
                    nostr["relays"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                } catch (_: Exception) { emptyList() }
                return NamecoinResolvedIdentity(
                    pubkey = pubkey.lowercase(),
                    relays = relays,
                    namecoinName = parsed.namecoinName,
                )
            }
            // Also try a names-style record under id/.
            val names = nostr["names"]?.jsonObject
            if (names != null) {
                val rootPubkey = (names["_"] as? JsonPrimitive)?.content
                if (rootPubkey != null && isValidPubkey(rootPubkey)) {
                    return NamecoinResolvedIdentity(
                        pubkey = rootPubkey.lowercase(),
                        relays = extractRelays(nostr, rootPubkey),
                        namecoinName = parsed.namecoinName,
                    )
                }
            }
        }
        return null
    }

    private fun extractRelays(nostrObj: JsonObject, pubkey: String): List<String> = try {
        val relaysMap = nostrObj["relays"]?.jsonObject ?: return emptyList()
        val arr = relaysMap[pubkey.lowercase()]?.jsonArray
            ?: relaysMap[pubkey]?.jsonArray
            ?: return emptyList()
        arr.mapNotNull { (it as? JsonPrimitive)?.content }
    } catch (_: Exception) { emptyList() }

    private fun isValidPubkey(s: String): Boolean = HEX_PUBKEY_REGEX.matches(s)
}
