package com.darkwisp.app

import android.app.Application
import android.util.Log
import androidx.profileinstaller.ProfileVerifier
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.darkwisp.app.db.WispObjectBox
import com.darkwisp.app.relay.HttpClientFactory
import com.darkwisp.app.relay.TorManager
import com.darkwisp.app.repo.DiagnosticLogger
import com.darkwisp.app.repo.ExchangeRateRepository
import com.darkwisp.app.repo.TorPreferences
import com.darkwisp.app.repo.ZapSender
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        ExchangeRateRepository.init(this)
        reportBaselineProfileStatus()
    }

    // Sideloaded installs get no install-time AOT from the store, so surface whether the
    // bundled baseline profile was actually compiled (adb logcat -s ProfileVerifier)
    private fun reportBaselineProfileStatus() {
        Executors.newSingleThreadExecutor().execute {
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
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
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
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.15)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
