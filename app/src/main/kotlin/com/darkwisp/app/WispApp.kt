package com.darkwisp.app

import android.app.Application
import android.util.Log
import androidx.profileinstaller.ProfileVerifier
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ImageResult
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.darkwisp.app.db.WispObjectBox
import com.darkwisp.app.nostr.BlossomUri
import com.darkwisp.app.relay.HttpClientFactory
import com.darkwisp.app.relay.TorManager
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.repo.BlossomUriResolver
import com.darkwisp.app.repo.DiagnosticLogger
import com.darkwisp.app.repo.ExchangeRateRepository
import com.darkwisp.app.repo.KeyRepository
import com.darkwisp.app.repo.TorPreferences
import com.darkwisp.app.repo.ZapSender
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.Call

class WispApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        DiagnosticLogger.init(this)
        TorPreferences.init(this)
        TorManager.init()
        if (TorPreferences.isEnabled()) {
            // Fail-closed BEFORE any repo init can touch the network: every client
            // built from here on routes to a dead SOCKS port until Tor bootstraps.
            HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Pending)
            MainScope().launch {
                TorManager.start()?.let {
                    HttpClientFactory.setTorSocks(HttpClientFactory.TorSocks.Ready(it))
                }
            }
        }
        WispObjectBox.init(this)
        ZapSender.init(this)
        // Exchange-rate init does disk I/O (loadCached) + kicks off a network refresh;
        // keep it off the main thread so it doesn't gate first frame. Rates populate
        // asynchronously regardless, and consumers already handle the empty initial map.
        MainScope().launch(Dispatchers.IO) {
            ExchangeRateRepository.init(applicationContext)
        }
        reportBaselineProfileStatus()
    }

    // Sideloaded installs get no install-time AOT from the store, so surface whether the
    // bundled baseline profile was actually compiled (adb logcat -s ProfileVerifier)
    private fun reportBaselineProfileStatus() {
        // Daemon thread, shut down after the one-shot check so it doesn't linger for
        // the app's lifetime (thread creation/teardown is costlier under GrapheneOS
        // exec-based spawning).
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "baseline-profile-check").apply { isDaemon = true }
        }
        executor.execute {
            try {
                val status = ProfileVerifier.getCompilationStatusAsync().get(20, TimeUnit.SECONDS)
                val msg = "compiledWithProfile=${status.isCompiledWithProfile} " +
                    "enqueuedForCompilation=${status.hasProfileEnqueuedForCompilation()} " +
                    "installResultCode=${status.profileInstallResultCode}"
                Log.i("ProfileVerifier", msg)
                DiagnosticLogger.log("ProfileVerifier", msg)
            } catch (e: Exception) {
                Log.w("ProfileVerifier", "status check failed: ${e.message}")
            }
        }
        executor.shutdown()
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        // Resolves BUD-10 `blossom:` URIs before the network fetcher sees the
        // request. One KeyRepository, read per resolution, so the account
        // switcher is picked up without rebuilding the loader.
        val keyRepo = KeyRepository(context)
        val resolver = BlossomUriResolver(
            // GET-and-verify downloads whole blobs (hash-checked before use),
            // so the per-attempt budget has room for a multi-MB image on a
            // slow server; the factory keeps Tor routing current per fetch.
            httpClient = {
                HttpClientFactory.getImageClient().newBuilder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build()
            },
            accountServers = {
                BlossomRepository.readServersFromPrefs(context, keyRepo.getPubkeyHex())
            },
        )
        return ImageLoader.Builder(context)
            .components {
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                // Coil caches the callFactory lambda's result forever; the delegating
                // Call.Factory re-resolves the current client per request so a Tor
                // toggle applies to image loading without an app restart.
                add(OkHttpNetworkFetcherFactory(callFactory = {
                    Call.Factory { request -> HttpClientFactory.getImageClient().newCall(request) }
                }))
                add(BlossomUriInterceptor(resolver))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.15)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /** Rewrites `blossom:` model strings to their resolved https URL; every other request passes through. */
    private class BlossomUriInterceptor(private val resolver: BlossomUriResolver) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val data = chain.request.data as? String
            if (data == null || !data.startsWith(BlossomUri.SCHEME, ignoreCase = true)) {
                return chain.proceed()
            }
            val resolved = resolver.resolve(data)
                // No server answered — proceed and let the fetch fail as a normal image error.
                ?: return chain.proceed()
            return chain.withRequest(chain.request.newBuilder().data(resolved).build()).proceed()
        }
    }
}
