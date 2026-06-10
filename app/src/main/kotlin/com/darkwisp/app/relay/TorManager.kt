package com.darkwisp.app.relay

import android.util.Log
import com.darkwisp.app.BuildConfig
import com.darkwisp.app.R
import com.darkwisp.app.repo.TorPreferences
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.TorState
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.service.TorServiceConfig
import io.matthewnelson.kmp.tor.runtime.service.TorServiceUI
import io.matthewnelson.kmp.tor.runtime.service.ui.KmpTorServiceUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide owner of the embedded Tor daemon (kmp-tor). Tor runs inside a
 * foreground TorService, so it survives Activity recreation and backgrounding.
 *
 * [state] is the single source of truth observed by the drawer toggle, the
 * pre-login corner button, and the toggle orchestration in StartupCoordinator.
 */
object TorManager {

    sealed interface State {
        data object Off : State
        data class Starting(val bootstrapPercent: Int) : State
        data class On(val socksPort: Int) : State
        data object Stopping : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var daemon: TorState.Daemon = TorState.Daemon.Off
    @Volatile private var socksPort: Int? = null
    @Volatile private var runtime: TorRuntime? = null

    fun init() {
        if (runtime != null) return

        val uiFactory = KmpTorServiceUI.Factory(
            iconReady = R.drawable.ic_onion,
            iconNotReady = R.drawable.ic_onion,
            info = TorServiceUI.NotificationInfo(
                notificationId = 4242.toShort(),
                channelId = "wisp_tor",
                channelName = R.string.tor_channel_name,
                channelDescription = R.string.tor_channel_description,
                channelShowBadge = false,
            ),
            block = {
                // No notification actions: the in-app toggle is the single control
                // point, so HttpClientFactory's proxy state can never drift from
                // the daemon state.
                defaultConfig {
                    enableActionRestart = false
                    enableActionStop = false
                }
            },
        )

        val serviceConfig = TorServiceConfig.Foreground.Builder(uiFactory) {
            // Keep tor alive across task removal — re-bootstrapping on every
            // app swipe takes tens of seconds.
            stopServiceOnTaskRemoved = false
        }

        val environment = serviceConfig.newEnvironment(ResourceLoaderTorExec::getOrCreate)
        environment.debug = BuildConfig.DEBUG

        runtime = TorRuntime.Builder(environment) {
            required(TorEvent.ERR)
            required(TorEvent.WARN)

            config {
                // Let tor pick a free port; RuntimeEvent.LISTENERS reports it.
                TorOption.__SocksPort.configure { auto() }
            }

            observerStatic(RuntimeEvent.STATE, OnEvent.Executor.Immediate) { torState ->
                daemon = torState.daemon
                recompute()
            }
            observerStatic(RuntimeEvent.LISTENERS, OnEvent.Executor.Immediate) { listeners ->
                socksPort = listeners.socks.firstOrNull()?.port?.value
                recompute()
            }
            observerStatic(RuntimeEvent.ERROR, OnEvent.Executor.Immediate) { t ->
                Log.w("TorManager", "Tor runtime error", t)
            }
        }
    }

    private fun recompute() {
        val d = daemon
        val port = socksPort
        // Don't clobber a surfaced Error with Off — the daemon settles to Off
        // after a failed start, but the user should keep seeing the error.
        if (_state.value is State.Error && d.isOff) return
        _state.value = when {
            d.isOff -> State.Off
            d.isStopping -> State.Stopping
            d.isBootstrapped && port != null -> State.On(port)
            else -> State.Starting(d.bootstrap.toInt())
        }
    }

    /**
     * Starts the daemon and waits for full bootstrap + SOCKS listener.
     * Returns the SOCKS port, or null on timeout/error (caller stays fail-closed).
     */
    suspend fun start(timeoutMs: Long = 120_000): Int? {
        val rt = runtime ?: return null
        _state.value = State.Starting(0)
        return try {
            val port = withTimeoutOrNull(timeoutMs) {
                // kmp-tor's service bind has a fixed 1s internal timeout, which a
                // busy main thread misses during cold app start — retry; by the
                // second or third attempt init has settled and the bind succeeds.
                var attempt = 0
                while (true) {
                    try {
                        rt.startDaemonAsync()
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (++attempt >= 5) throw e
                        Log.w("TorManager", "Tor start attempt $attempt failed, retrying", e)
                        delay(1_000)
                    }
                }
                (state.first { it is State.On } as State.On).socksPort
            }
            if (port == null) _state.value = State.Error("Tor bootstrap timed out")
            port
        } catch (e: Exception) {
            Log.w("TorManager", "Tor start failed", e)
            _state.value = State.Error(e.message ?: "Tor failed to start")
            null
        }
    }

    suspend fun stop() {
        socksPort = null
        val rt = runtime ?: return
        _state.value = State.Stopping
        try {
            rt.stopDaemonAsync()
        } catch (e: Exception) {
            Log.w("TorManager", "Tor stop failed", e)
        }
        _state.value = State.Off
    }

    /**
     * Gate for cold-start relay init: suspends until Tor is usable when the
     * pref is on, no-ops otherwise. Also unblocks if the user turns the pref
     * off mid-wait. Returns false on timeout (caller stays fail-closed).
     */
    suspend fun awaitOnIfEnabled(timeoutMs: Long = 120_000): Boolean {
        if (!TorPreferences.isEnabled()) return true
        return withTimeoutOrNull(timeoutMs) {
            combine(state, TorPreferences.enabled) { s, enabled -> s is State.On || !enabled }
                .first { it }
        } != null
    }
}
