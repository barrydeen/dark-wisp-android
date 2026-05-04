package com.wisp.app.relay

import android.content.Context
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClientFactory {

    fun createRelayClient(): OkHttpClient {
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
            .build()
    }

    private var imageClient: OkHttpClient? = null

    fun getImageClient(): OkHttpClient {
        imageClient?.let { return it }
        return createHttpClient(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 30
        ).also { imageClient = it }
    }

    fun createExoPlayer(context: Context): ExoPlayer {
        val client = createHttpClient(connectTimeoutSeconds = 10, readTimeoutSeconds = 30)
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

        if (writeTimeoutSeconds > 0) {
            builder.writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        }

        return builder.build()
    }
}
