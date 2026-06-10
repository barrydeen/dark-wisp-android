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
import com.darkwisp.app.repo.DiagnosticLogger
import com.darkwisp.app.repo.ExchangeRateRepository
import com.darkwisp.app.repo.ZapSender
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WispApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        DiagnosticLogger.init(this)
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
                add(OkHttpNetworkFetcherFactory(callFactory = { HttpClientFactory.getImageClient() }))
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
