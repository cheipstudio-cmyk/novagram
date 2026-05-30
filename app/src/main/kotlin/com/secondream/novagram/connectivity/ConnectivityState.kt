package com.secondream.novagram.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Realtime online/offline indicator backed by Android's
 * [ConnectivityManager.NetworkCallback]. The callback fires the
 * moment a usable network is registered or the last one is lost —
 * faster and cheaper than any polling loop, since the OS itself
 * dispatches the event from the kernel-level network state machine.
 *
 * Singleton, initialized once from [App.onCreate] via [start]. Any
 * composable can subscribe via `collectAsState()` on [isOnline]; the
 * value is `true` when at least one network with the INTERNET
 * capability is active and validated, `false` otherwise.
 *
 * "Validated" here matters: Android distinguishes between connected-
 * but-no-internet (e.g. a captive-portal Wi-Fi you haven't logged
 * into yet) and connected-and-validated. We only treat the latter as
 * online so the in-app indicator matches the user's real-world
 * "can I send a message right now" state.
 */
object ConnectivityState {

    private const val TAG = "ConnectivityState"

    private val _isOnline = MutableStateFlow(true)
    val isOnline = _isOnline.asStateFlow()

    private var started = false

    /**
     * Register the network callback. Idempotent — calling more than
     * once is a no-op so it's safe to invoke unconditionally from
     * App.onCreate even on Activity recreate paths.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as? ConnectivityManager ?: return

        // Seed the initial value before the callback wires up so the
        // UI doesn't flash "offline" on cold start when we are in fact
        // online — getActiveNetwork + capabilities check tells us the
        // current state synchronously.
        _isOnline.value = isCurrentlyOnline(cm)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // registerDefaultNetworkCallback would only watch the "main"
        // network; registerNetworkCallback with our INTERNET filter
        // tracks any network the system might fall back to, which is
        // closer to the user's actual reachability. The callback fires
        // on the main looper because we don't supply a Handler — fine
        // for a Flow .value write, which is thread-safe internally.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                _isOnline.value = isCurrentlyOnline(cm)
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost")
                _isOnline.value = isCurrentlyOnline(cm)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                // Captive-portal transitions arrive here: a network can
                // be available but NOT validated (no real internet
                // yet), then flip to validated once the user logs in.
                // Re-evaluate on every capabilities update so the chip
                // tracks the actual reachable state, not just the
                // connection-up signal.
                _isOnline.value = isCurrentlyOnline(cm)
            }
        }

        runCatching {
            cm.registerNetworkCallback(request, callback)
        }.onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    /**
     * Synchronous read of the current online state. Used to seed the
     * StateFlow before the callback is wired up, and also as the
     * inside-callback source of truth (the network passed to the
     * callback is the one whose state changed, but other networks
     * might still be available — we want the OVERALL availability).
     */
    private fun isCurrentlyOnline(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
