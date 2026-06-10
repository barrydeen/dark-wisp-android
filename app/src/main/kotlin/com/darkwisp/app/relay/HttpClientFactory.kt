package com.darkwisp.app.relay

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object HttpClientFactory {

    /**
     * Tor proxy state for every client built here. Fail-closed: [Pending] routes
     * to a loopback port that always refuses connections (unprivileged processes
     * can't bind ports < 1024 on Android), so nothing reaches clearnet between
     * "user enabled Tor" and "Tor finished bootstrapping".
     */
    sealed interface TorSocks {
        data object Off : TorSocks
        data object Pending : TorSocks
        data class Ready(val port: Int) : TorSocks
    }

    @Volatile private var torSocks: TorSocks = TorSocks.Off

    @Volatile private var imageClient: OkHttpClient? = null
    @Volatile private var generalClient: OkHttpClient? = null
    @Volatile private var shortTimeoutClient: OkHttpClient? = null
    @Volatile private var mediaClient: OkHttpClient? = null
    @Volatile private var nip05Client: OkHttpClient? = null
    @Volatile private var downloadClient: OkHttpClient? = null
    @Volatile private var dmMediaClient: OkHttpClient? = null
    @Volatile private var relayClient: OkHttpClient? = null
    @Volatile private var loopbackClient: OkHttpClient? = null

    /**
     * Every client built since the last [setTorSocks], including per-call ones
     * (Blossom uploads, relay probes), so a toggle can cancel their in-flight
     * requests and evict their keep-alive pools. Weak keys: abandoned clients
     * just fall out.
     */
    private val liveClients: MutableSet<OkHttpClient> =
        Collections.newSetFromMap(WeakHashMap())

    private fun OkHttpClient.Builder.applyProxy(): OkHttpClient.Builder = apply {
        when (val t = torSocks) {
            TorSocks.Off -> {}
            TorSocks.Pending -> proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9)))
            is TorSocks.Ready -> proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", t.port)))
        }
    }

    private fun track(client: OkHttpClient): OkHttpClient {
        synchronized(liveClients) { liveClients.add(client) }
        return client
    }

    /**
     * Applies the new proxy state to all future clients and kills every existing
     * one — cancels in-flight requests and evicts pooled keep-alive connections,
     * so no pre-toggle socket survives the switch.
     */
    fun setTorSocks(mode: TorSocks) {
        val old: List<OkHttpClient>
        synchronized(this) {
            if (torSocks == mode) return
            torSocks = mode
            old = synchronized(liveClients) {
                val snapshot = liveClients.toList()
                liveClients.clear()
                snapshot
            }
            imageClient = null
            generalClient = null
            shortTimeoutClient = null
            mediaClient = null
            nip05Client = null
            downloadClient = null
            dmMediaClient = null
            relayClient = null
            // loopbackClient intentionally survives — never proxied, can't leak
        }
        thread(name = "tor-client-shutdown") {
            old.forEach { runCatching { safeShutdownClient(it) } }
        }
    }

    private fun relayClientBuilder(): OkHttpClient.Builder {
        // OkHttp's default Dispatcher.maxRequests is 64, which caps concurrent
        // WebSocket upgrade requests. With outbox routing creating 50+ ephemeral
        // connections, new user-initiated connections get queued and time out.
        val dispatcher = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 10
        }

        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Strip permessage-deflate from the REQUEST so the server never negotiates
            // compression. The previous approach (network interceptor stripping the
            // response header) left the request header intact — servers that support
            // deflate would negotiate it, then send compressed frames to a client with
            // no inflater, causing ProtocolException and a reconnect loop.
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .removeHeader("Sec-WebSocket-Extensions")
                    .build()
                chain.proceed(request)
            }
    }

    fun createRelayClient(): OkHttpClient = track(relayClientBuilder().applyProxy().build())

    /** Shared relay WebSocket client; relays resolve it through their client provider on every (re)connect. */
    fun getRelayClient(): OkHttpClient {
        relayClient?.let { return it }
        return synchronized(this) {
            relayClient ?: createRelayClient().also { relayClient = it }
        }
    }

    /**
     * For loopback/LAN endpoints only (local relay). Never proxied: Tor refuses
     * connections to loopback targets, and 127.0.0.1 can't leak anyway.
     */
    fun getLoopbackClient(): OkHttpClient {
        loopbackClient?.let { return it }
        return synchronized(this) {
            loopbackClient ?: relayClientBuilder().build().also { loopbackClient = it }
        }
    }

    fun getImageClient(): OkHttpClient {
        imageClient?.let { return it }
        return synchronized(this) {
            imageClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 30
            ).also { imageClient = it }
        }
    }

    fun getGeneralClient(): OkHttpClient {
        generalClient?.let { return it }
        return synchronized(this) {
            generalClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 15
            ).also { generalClient = it }
        }
    }

    fun getShortTimeoutClient(): OkHttpClient {
        shortTimeoutClient?.let { return it }
        return synchronized(this) {
            shortTimeoutClient ?: createHttpClient(
                connectTimeoutSeconds = 5,
                readTimeoutSeconds = 5
            ).also { shortTimeoutClient = it }
        }
    }

    // NIP-05 verification fans out to many distinct hosts; failing fast on
    // unreachable .well-known/nostr.json endpoints keeps the badge responsive.
    fun getNip05Client(): OkHttpClient {
        nip05Client?.let { return it }
        return synchronized(this) {
            nip05Client ?: createHttpClient(
                connectTimeoutSeconds = 5,
                readTimeoutSeconds = 10
            ).also { nip05Client = it }
        }
    }

    fun getMediaClient(): OkHttpClient {
        mediaClient?.let { return it }
        return synchronized(this) {
            mediaClient ?: createHttpClient(
                connectTimeoutSeconds = 10,
                readTimeoutSeconds = 30
            ).also { mediaClient = it }
        }
    }

    // Full-file downloads need longer read timeouts than streaming —
    // a stalled chunk on a flaky connection shouldn't kill the download.
    fun getDownloadClient(): OkHttpClient {
        downloadClient?.let { return it }
        return synchronized(this) {
            downloadClient ?: createHttpClient(
                connectTimeoutSeconds = 30,
                readTimeoutSeconds = 60
            ).also { downloadClient = it }
        }
    }

    // Encrypted DM media: small files but slow hosts; generous read timeout.
    fun getDmMediaClient(): OkHttpClient {
        dmMediaClient?.let { return it }
        return synchronized(this) {
            dmMediaClient ?: createHttpClient(
                connectTimeoutSeconds = 15,
                readTimeoutSeconds = 60,
                writeTimeoutSeconds = 15
            ).also { dmMediaClient = it }
        }
    }

    fun createExoPlayer(context: Context): ExoPlayer {
        val client = getMediaClient()
        val dataSourceFactory = OkHttpDataSource.Factory(client)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    fun safeShutdownClient(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        try {
            if (!client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                client.dispatcher.executorService.shutdownNow()
            }
        } catch (_: InterruptedException) {
            client.dispatcher.executorService.shutdownNow()
        }
    }

    fun createHttpClient(
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 10,
        writeTimeoutSeconds: Long = 0,
        followRedirects: Boolean = true
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .applyProxy()

        if (writeTimeoutSeconds > 0) {
            builder.writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        }

        return track(builder.build())
    }
}
