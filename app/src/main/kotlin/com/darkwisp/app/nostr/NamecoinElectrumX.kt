/*
 * MIT License
 *
 * Lightweight Namecoin ElectrumX client for Nostr name resolution.
 *
 * Ported from Vitor Pamplona's Amethyst (Quartz) implementation:
 *   quartz/src/commonMain/.../namecoin/{ElectrumXServer.kt,IElectrumXClient.kt,NamecoinNameResolver.kt}
 *   quartz/src/jvmAndroid/.../namecoin/ElectrumXClient.kt
 *
 * Server list, pinned self-signed certs, scripthash protocol, and resolver
 * semantics are kept compatible with Amethyst so a Namecoin record that
 * resolves in Amethyst also resolves here.
 */
package com.darkwisp.app.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// ── Server descriptors ─────────────────────────────────────────────────

/** Single ElectrumX endpoint (host + port + TLS settings). */
data class NamecoinElectrumxServer(
    val host: String,
    val port: Int,
    val useSsl: Boolean = true,
    /**
     * When true, TLS uses the pinned trust store (hardcoded self-signed
     * ElectrumX certs plus system CAs). Required for the Namecoin ElectrumX
     * ecosystem because most public servers serve self-signed certs.
     */
    val usePinnedTrustStore: Boolean = false,
)

/**
 * Default Namecoin ElectrumX servers. These are the same endpoints Amethyst
 * uses, so a record that resolves in Amethyst also resolves here.
 *
 * Mix of self-signed (pinned) and Let's Encrypt (system trust) operators.
 */
val DEFAULT_NAMECOIN_ELECTRUMX_SERVERS =
    listOf(
        NamecoinElectrumxServer("electrumx.testls.space", 50002, useSsl = true, usePinnedTrustStore = true),
        NamecoinElectrumxServer("nmc2.bitcoins.sk", 57002, useSsl = true, usePinnedTrustStore = true),
        NamecoinElectrumxServer("46.229.238.187", 57002, useSsl = true, usePinnedTrustStore = true),
        NamecoinElectrumxServer("relay.testls.bit", 50002, useSsl = true, usePinnedTrustStore = true),
        NamecoinElectrumxServer("23.158.233.10", 50002, useSsl = true, usePinnedTrustStore = true),
        // Let's Encrypt cert — uses system trust store, fallback for hardened
        // networks that reject self-signed.
        NamecoinElectrumxServer("electrum.nmc.ethicnology.com", 50002, useSsl = true, usePinnedTrustStore = false),
    )

// ── Result types ───────────────────────────────────────────────────────

/** Parsed result of an ElectrumX name_show. */
data class NamecoinNameShowResult(
    val name: String,
    val value: String,
    val txid: String? = null,
    val height: Int? = null,
    val expiresIn: Int? = null,
)

/** Distinguishes "name doesn't exist" from "all servers unreachable". */
sealed class NamecoinLookupException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class NameNotFound(val name: String) : NamecoinLookupException("Name not found: $name")
    class NameExpired(val name: String) : NamecoinLookupException("Name expired: $name")
    class ServersUnreachable(val lastError: Throwable? = null) :
        NamecoinLookupException("All ElectrumX servers unreachable", lastError)
}

// ── ElectrumX client ───────────────────────────────────────────────────

/**
 * Query-only ElectrumX client for Namecoin name resolution.
 *
 * Resolution strategy:
 * 1. Build the canonical Namecoin name-index script for the identifier.
 * 2. Compute the Electrum scripthash (reversed SHA-256 hex).
 * 3. Query blockchain.scripthash.get_history to find the latest update.
 * 4. Fetch the verbose transaction and parse the name + value from its
 *    NAME_UPDATE / NAME_FIRSTUPDATE output script.
 * 5. Cross-check current chain height to flag expired names (>36000 blocks).
 *
 * Single-purpose: no wallet, no subscription, no transaction broadcast.
 */
class NamecoinElectrumXClient(
    private val connectTimeoutMs: Long = 10_000L,
    private val readTimeoutMs: Long = 15_000L,
    private val socketFactory: () -> SocketFactory = { SocketFactory.getDefault() },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val requestId = AtomicInteger(0)
    private val serverMutexes = ConcurrentHashMap<String, Mutex>()

    /** Query a single server. Returns null on transport failure; throws for
     *  blockchain-definitive answers (name not found / expired). */
    suspend fun nameShow(
        identifier: String,
        server: NamecoinElectrumxServer = DEFAULT_NAMECOIN_ELECTRUMX_SERVERS.first(),
    ): NamecoinNameShowResult? = withContext(Dispatchers.IO) {
        val mutex = serverMutexes.getOrPut("${server.host}:${server.port}") { Mutex() }
        mutex.withLock {
            try {
                connectAndQuery(identifier, server)
            } catch (e: NamecoinLookupException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /** Try each server in order until one yields a definitive answer. */
    suspend fun nameShowWithFallback(
        identifier: String,
        servers: List<NamecoinElectrumxServer> = DEFAULT_NAMECOIN_ELECTRUMX_SERVERS,
    ): NamecoinNameShowResult? {
        var lastError: Exception? = null
        for (server in servers) {
            try {
                val result = nameShow(identifier, server)
                if (result != null) return result
            } catch (e: NamecoinLookupException.NameNotFound) {
                throw e
            } catch (e: NamecoinLookupException.NameExpired) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw NamecoinLookupException.ServersUnreachable(lastError)
    }

    // ── internals ──────────────────────────────────────────────────────

    private fun connectAndQuery(
        identifier: String,
        server: NamecoinElectrumxServer,
    ): NamecoinNameShowResult? {
        val socket = createSocket(server)
        socket.soTimeout = readTimeoutMs.toInt()
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        try {
            // 1. Negotiate protocol version
            writer.println(buildRpcRequest("server.version", listOf(USER_AGENT, PROTOCOL_VERSION)))
            reader.readLine() // discard version reply

            // 2. Scripthash for the canonical name-index script
            val nameScript = buildNameIndexScript(identifier.toByteArray(Charsets.US_ASCII))
            val scriptHash = electrumScriptHash(nameScript)

            // 3. History for this name
            writer.println(buildRpcRequest("blockchain.scripthash.get_history", listOf(scriptHash)))
            val historyResponse = reader.readLine() ?: return null
            val historyEntries = parseHistoryResponse(historyResponse) ?: return null
            if (historyEntries.isEmpty()) throw NamecoinLookupException.NameNotFound(identifier)

            // 4. Latest entry = most recent update
            val (txHash, height) = historyEntries.last()
            writer.println(buildRpcRequest("blockchain.transaction.get", listOf(txHash, true)))
            val txResponse = reader.readLine() ?: return null

            // 5. Current chain height — used for expiry check + expiresIn
            writer.println(buildRpcRequest("blockchain.headers.subscribe", emptyList<String>()))
            val currentHeight = parseBlockHeight(reader.readLine())

            if (currentHeight != null && height > 0 &&
                currentHeight - height >= NAME_EXPIRE_DEPTH
            ) {
                throw NamecoinLookupException.NameExpired(identifier)
            }

            val result = parseNameFromTransaction(identifier, txHash, height, txResponse)
            return if (result != null && currentHeight != null && height > 0) {
                result.copy(expiresIn = NAME_EXPIRE_DEPTH - (currentHeight - height))
            } else {
                result
            }
        } finally {
            runCatching { writer.close() }
            runCatching { reader.close() }
            runCatching { socket.close() }
        }
    }

    /**
     * Canonical Namecoin name-index script — mirrors `build_name_index_script`
     * in the Namecoin ElectrumX fork:
     *   OP_NAME_UPDATE <push(name)> <push("")> OP_2DROP OP_DROP OP_RETURN
     */
    private fun buildNameIndexScript(nameBytes: ByteArray): ByteArray {
        val out = ArrayList<Byte>(nameBytes.size + 8)
        out.add(OP_NAME_UPDATE)
        out.addAll(pushData(nameBytes).toList())
        out.addAll(pushData(byteArrayOf()).toList())
        out.add(OP_2DROP)
        out.add(OP_DROP)
        out.add(OP_RETURN)
        return out.toByteArray()
    }

    private fun pushData(data: ByteArray): ByteArray {
        val len = data.size
        return when {
            len < 0x4c -> byteArrayOf(len.toByte()) + data
            len <= 0xff -> byteArrayOf(OP_PUSHDATA1, len.toByte()) + data
            else -> byteArrayOf(OP_PUSHDATA2, (len and 0xff).toByte(), ((len shr 8) and 0xff).toByte()) + data
        }
    }

    /** Electrum scripthash: SHA-256(script), byte-reversed, hex. */
    private fun electrumScriptHash(script: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(script)
        return digest.reversedArray().joinToString("") { "%02x".format(it) }
    }

    private fun parseBlockHeight(raw: String?): Int? {
        if (raw == null) return null
        return runCatching {
            json.parseToJsonElement(raw).jsonObject["result"]?.jsonObject?.get("height")?.jsonPrimitive?.int
        }.getOrNull()
    }

    private fun parseHistoryResponse(raw: String): List<Pair<String, Int>>? {
        val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val error = envelope["error"]
        if (error != null && error !is JsonNull) return null
        val result = envelope["result"]?.jsonArray ?: return null
        return result.mapNotNull { entry ->
            val obj = entry.jsonObject
            val txHash = obj["tx_hash"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val height = obj["height"]?.jsonPrimitive?.int ?: return@mapNotNull null
            txHash to height
        }
    }

    private fun parseNameFromTransaction(
        identifier: String,
        txHash: String,
        height: Int,
        raw: String,
    ): NamecoinNameShowResult? {
        val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val error = envelope["error"]
        if (error != null && error !is JsonNull) return null
        val result = envelope["result"]?.jsonObject ?: return null
        val vouts = result["vout"]?.jsonArray ?: return null

        for (vout in vouts) {
            val scriptHex = vout.jsonObject["scriptPubKey"]?.jsonObject
                ?.get("hex")?.jsonPrimitive?.content ?: continue
            // NAME_UPDATE (0x53) or NAME_FIRSTUPDATE (0x52)
            if (!scriptHex.startsWith("53") && !scriptHex.startsWith("52")) continue
            val parsed = parseNameScript(hexToBytes(scriptHex)) ?: continue
            if (parsed.first == identifier) {
                return NamecoinNameShowResult(
                    name = parsed.first,
                    value = parsed.second,
                    txid = txHash,
                    height = height,
                )
            }
        }
        return null
    }

    /** Parse NAME_UPDATE / NAME_FIRSTUPDATE → (name, value). */
    private fun parseNameScript(script: ByteArray): Pair<String, String>? {
        if (script.isEmpty()) return null
        val op = script[0]
        if (op != OP_NAME_UPDATE && op != OP_NAME_FIRSTUPDATE) return null
        var pos = 1
        val (nameBytes, p1) = readPushData(script, pos) ?: return null
        pos = p1
        // FIRSTUPDATE has a <rand> push between name and value
        if (op == OP_NAME_FIRSTUPDATE) {
            val (_, p2) = readPushData(script, pos) ?: return null
            pos = p2
        }
        val (valueBytes, _) = readPushData(script, pos) ?: return null
        return String(nameBytes, Charsets.US_ASCII) to String(valueBytes, Charsets.UTF_8)
    }

    private fun readPushData(script: ByteArray, pos: Int): Pair<ByteArray, Int>? {
        if (pos >= script.size) return null
        val op = script[pos].toInt() and 0xff
        return when {
            op == 0 -> byteArrayOf() to (pos + 1)
            op < 0x4c -> {
                val end = pos + 1 + op
                if (end > script.size) null else script.copyOfRange(pos + 1, end) to end
            }
            op == 0x4c -> {
                if (pos + 2 > script.size) return null
                val len = script[pos + 1].toInt() and 0xff
                val end = pos + 2 + len
                if (end > script.size) null else script.copyOfRange(pos + 2, end) to end
            }
            op == 0x4d -> {
                if (pos + 3 > script.size) return null
                val len = (script[pos + 1].toInt() and 0xff) or ((script[pos + 2].toInt() and 0xff) shl 8)
                val end = pos + 3 + len
                if (end > script.size) null else script.copyOfRange(pos + 3, end) to end
            }
            else -> null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun createSocket(server: NamecoinElectrumxServer): Socket {
        val base = socketFactory().createSocket().apply {
            connect(InetSocketAddress(server.host, server.port), connectTimeoutMs.toInt())
        }
        if (!server.useSsl) return base
        val sslFactory = if (server.usePinnedTrustStore) cachedPinnedSslFactory()
            else SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = sslFactory.createSocket(base, server.host, server.port, true)
        if (sslSocket is javax.net.ssl.SSLSocket) {
            // Force TLS 1.2+ — some OEM Conscrypt forks default lower for socket upgrades.
            val modern = sslSocket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            if (modern.isNotEmpty()) sslSocket.enabledProtocols = modern.toTypedArray()
        }
        return sslSocket
    }

    @Volatile private var pinnedFactory: SSLSocketFactory? = null
    private fun cachedPinnedSslFactory(): SSLSocketFactory {
        pinnedFactory?.let { return it }
        synchronized(this) {
            pinnedFactory?.let { return it }
            return buildPinnedSslFactory().also { pinnedFactory = it }
        }
    }

    /**
     * Build an SSLSocketFactory that trusts the pinned ElectrumX certs plus
     * the system CA store. Pinning (vs trust-all) keeps connections alive on
     * hardened TLS stacks (Samsung One UI 7, GrapheneOS) that reject no-op
     * X509TrustManagers.
     */
    private fun buildPinnedSslFactory(): SSLSocketFactory {
        val ks = runCatching {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        }.getOrElse { KeyStore.getInstance("PKCS12").apply { load(null, null) } }
        val cf = CertificateFactory.getInstance("X.509")
        for ((i, pem) in PINNED_ELECTRUMX_CERTS.withIndex()) {
            runCatching {
                val cert = cf.generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)))
                ks.setCertificateEntry("nmc_electrumx_$i", cert)
            }
        }
        // Mix in the system CAs so LE-signed servers still verify.
        val systemTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTmf.init(null as KeyStore?)
        val systemTm = systemTmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        if (systemTm != null) {
            for ((i, issuer) in systemTm.acceptedIssuers.withIndex()) {
                runCatching { ks.setCertificateEntry("nmc_system_$i", issuer) }
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ctx = runCatching { SSLContext.getInstance("TLSv1.2") }
            .getOrElse { SSLContext.getInstance("TLS") }
        ctx.init(null, tmf.trustManagers, SecureRandom())
        return ctx.socketFactory
    }

    private fun buildRpcRequest(method: String, params: List<Any>): String {
        val id = requestId.incrementAndGet()
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            val arr = params.map { p ->
                when (p) {
                    is Boolean -> JsonPrimitive(p)
                    is Number -> JsonPrimitive(p)
                    else -> JsonPrimitive(p.toString())
                }
            }
            put("params", JsonArray(arr))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        private const val USER_AGENT = "DarkWispNMC/0.1"
        private const val PROTOCOL_VERSION = "1.4"

        /** Namecoin name expiry: 36000 blocks (~250 days). */
        const val NAME_EXPIRE_DEPTH = 36_000

        private const val OP_NAME_FIRSTUPDATE: Byte = 0x52
        private const val OP_NAME_UPDATE: Byte = 0x53
        private const val OP_2DROP: Byte = 0x6d
        private const val OP_DROP: Byte = 0x75
        private const val OP_RETURN: Byte = 0x6a
        private const val OP_PUSHDATA1: Byte = 0x4c
        private const val OP_PUSHDATA2: Byte = 0x4d

        /**
         * Pinned PEM certs for the self-signed ElectrumX servers above.
         * Identical to Amethyst's PINNED_ELECTRUMX_CERTS — refresh both in
         * lockstep when an operator rotates a cert.
         *
         * To refresh:
         *   echo | openssl s_client -connect HOST:PORT 2>/dev/null \
         *       | openssl x509 -outform PEM
         */
        private val PINNED_ELECTRUMX_CERTS = listOf(
            // electrumx.testls.space:50002 — expires 2027-05-04
            """
-----BEGIN CERTIFICATE-----
MIIDwzCCAqsCFGGKT5mjh7oN98aNyjOCiqafL8VyMA0GCSqGSIb3DQEBCwUAMIGd
MQswCQYDVQQGEwJVUzEQMA4GA1UECAwHQ2hpY2FnbzEQMA4GA1UEBwwHQ2hpY2Fn
bzESMBAGA1UECgwJSW50ZXJuZXRzMQ8wDQYDVQQLDAZJbnRlcncxHjAcBgNVBAMM
FWVsZWN0cnVtLnRlc3Rscy5zcGFjZTElMCMGCSqGSIb3DQEJARYWbWpfZ2lsbF84
OUBob3RtYWlsLmNvbTAeFw0yMjA1MDUwNjIzNDFaFw0yNzA1MDQwNjIzNDFaMIGd
MQswCQYDVQQGEwJVUzEQMA4GA1UECAwHQ2hpY2FnbzEQMA4GA1UEBwwHQ2hpY2Fn
bzESMBAGA1UECgwJSW50ZXJuZXRzMQ8wDQYDVQQLDAZJbnRlcncxHjAcBgNVBAMM
FWVsZWN0cnVtLnRlc3Rscy5zcGFjZTElMCMGCSqGSIb3DQEJARYWbWpfZ2lsbF84
OUBob3RtYWlsLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAO4H
+PKCdiiz3jNOA77aAmS2YaU7eOQ8ZGliEVr/PlLcgF5gmthb2DI6iK4KhC1ad34G
1n9IhkXPhkVJ94i8wB3uoTBlA7mI5h59m01yhzSkJAoYoU/i6DM9ipbakqWFCTEp
P+yE216NTU5MbYwThZdRSAIIABe9RyIliMSidyrwHvKBLfnJPFScghW6rhBWN7PG
PA8k0MFGzf+HXbpnV/jAvz08ZC34qiBIjkJrTgh49JweyoZKdppyJcH4UbkslJ2t
YUJR3oURBvrPj+D7TwLVRbX36ul7r4+dP3IjgmljsSAHDK4N/PfWrCBdlj9Pc1Cp
yX+ZDh8X2NrL4ukHoVMCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAeVj6VZNmY/Vb
nhzrC7xBSHqVWQ1wkLOClLsdvgKP8cFFJuUoCMQU5bPMi7nWnkfvvsIKH4Eibk5K
fqiA9jVsY0FHvQ8gP3KMk1LVuUf/sTcRe5itp3guBOSk/zXZUD5tUz/oRk3k+rdc
MsInqhomjNy/dqYmD6Wm4DNPjZh6fWy+AVQKVNOI2t4koaVdpoi8Uv8h4gFGPbdI
sVmtoGiIGkKNIWum+6mnF6PfynNrLk+ztH4TrdacVNeoJUPYEAxOuesWXFy3H4r+
HKBqA4xAzyjgKLPqoWnjSu7gxj1GIjBhnDxkM6wUOnDq8A0EqxR+A17OcXW9sZ2O
2ZIVwmtnyA==
-----END CERTIFICATE-----
            """.trimIndent(),
            // nmc2.bitcoins.sk:57002 / 46.229.238.187:57002 — expires 2030-10-22
            """
-----BEGIN CERTIFICATE-----
MIID+TCCAuGgAwIBAgIUdmJGukmfPvqmAYpTfuGcjRoYHJ8wDQYJKoZIhvcNAQEL
BQAwgYsxCzAJBgNVBAYTAlNLMREwDwYDVQQIDAhTbG92YWtpYTETMBEGA1UEBwwK
QnJhdGlzbGF2YTEUMBIGA1UECgwLYml0Y29pbnMuc2sxGTAXBgNVBAMMEG5tYzIu
Yml0Y29pbnMuc2sxIzAhBgkqhkiG9w0BCQEWFGRlYWZib3lAY2ljb2xpbmEub3Jn
MB4XDTIwMTAyNDE5MjQzOVoXDTMwMTAyMjE5MjQzOVowgYsxCzAJBgNVBAYTAlNL
MREwDwYDVQQIDAhTbG92YWtpYTETMBEGA1UEBwwKQnJhdGlzbGF2YTEUMBIGA1UE
CgwLYml0Y29pbnMuc2sxGTAXBgNVBAMMEG5tYzIuYml0Y29pbnMuc2sxIzAhBgkq
hkiG9w0BCQEWFGRlYWZib3lAY2ljb2xpbmEub3JnMIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAzBUkZNDfaz7kc28l5tDKohJjekWmz1ynzfGx3ZLsqOZE
c+kNfcMaWU+zT/j0mV6pX6KSH7G9pPAku+8PRdKRq+d63wiJDEjGSaFztQWKW6L1
vTxgCK5gu+Eir3BkTagJObsrLKS+T6qH610/3+btGgoR3lunB5TzCgB/9oQanjDW
zjg2CwmxgR5Iw1Eqfenx7zkSK33FSXSF2SvbUs1Atj2oPU4DLivyrx0RaUmaPemn
cmcpnax+py4pQeB6dJWU1INhzXt3hTJRyoqsSGY3vCECIKIBIkh8GsYjAX4z+Y9y
6pJx0da2b88qPWdsoxaIMvrQiuWknDrSJwAyw2Yd8QIDAQABo1MwUTAdBgNVHQ4E
FgQUT2J83B2/9jxGGdFeWrxMohTzHNwwHwYDVR0jBBgwFoAUT2J83B2/9jxGGdFe
WrxMohTzHNwwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAsbxX
wN8tZaXOybImMZCQS7zfxmKl2IAcqu+R01KPfnIfrFqXPsGDDl3rYLkwh1O4/hYQ
NKNW9KTxoJxuBmAkm7EXQQh1XUUzajdEDqDBVRyvR0Z2MdMYnMSAiiMXMl2wUZnc
QXYftBo0HbtfsaJjImQdDjmlmRPSzE/RW6iUe+1cesKBC7e8nVf69Yu/fxO4m083
VWwAstlWJfk1GyU7jzVc8svealg/oIiDoOMe6CFSLx1BDv2FeHSpRdqd3fn+AC73
bK2N2smrHUOQnFijuiFw3WOrjERi0eMhjVNfVu9W9ZYa/Wd6SdIzV55LbG+NpmSf
5W7ix41hRvdT6cTAJA==
-----END CERTIFICATE-----
            """.trimIndent(),
            // relay.testls.bit:50002 — expires 2036-04-30
            """
-----BEGIN CERTIFICATE-----
MIIDFzCCAf+gAwIBAgIUAz+Ky5Lu2u1QchHKTwQIStWD8fQwDQYJKoZIhvcNAQEL
BQAwGzEZMBcGA1UEAwwQcmVsYXkudGVzdGxzLmJpdDAeFw0yNjA1MDMwNjEyNDBa
Fw0zNjA0MzAwNjEyNDBaMBsxGTAXBgNVBAMMEHJlbGF5LnRlc3Rscy5iaXQwggEi
MA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCngMim0wilCdclMyIIBRAeFBjE
HWA0l5n3ebCtyepYmyXUrbth2TDdyMHiUNVvE3f7RSAUFtE+Tjb9+xzmLf04qlbC
i3b2DV3xdVOZpRCI4kybmraYG6lRLQJ5I/N8NWgPsFgcy2mZF3q0yMVEkzGAKqUT
QGZd2eBs/OVicbCWKmgyhXlnqGeHIs4iKOqenHKSZ8QE5bhAKaMn+Q116QEdBg12
svGNoSZSlX8hDNUf5N5pOcsK8vj0Yb0ypJOd0J5eVpYS/KA0oMMwALnEs0H17hfO
IIaWqrbfaqmdR67uzUfci2EoiwQJrXSy7WkNiVX0ikN02VlUPCb2OaSABmwjAgMB
AAGjUzBRMB0GA1UdDgQWBBSgnHYFU93wufP131xk5w843VWhATAfBgNVHSMEGDAW
gBSgnHYFU93wufP131xk5w843VWhATAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3
DQEBCwUAA4IBAQAiVC5RJj/Y3S5uNYraPmRLQrgPui9eGlWDh6LeZvjftACA3WiN
XabJtTmHxOFFwxmBtzGVCWKR6udETHyfqaq0me/XoYLjGstYTd14GfoV9Klx7Glh
gLOgqC3Do04o2xnXxhPh00c0jUggKdI05KLVAc4dLfU/XDrOfxBl4XHZY4lYz4CF
bn+n5Q1cqqcGqoJIHl2cgDdrxSMigIpsunjk8cJXO+hsteA/Pd1UUY5plvE9nbNv
hy4QgfNqjy36b0Nbm7Fc9Te9W6zgXjHM+q7KhuQvTfKh2sHbCJzRcy7Chgwqyrfq
NsK0JcBkyRPB+fXmEoE/Xmj/UnOD0fZCCanD
-----END CERTIFICATE-----
            """.trimIndent(),
        )
    }
}
