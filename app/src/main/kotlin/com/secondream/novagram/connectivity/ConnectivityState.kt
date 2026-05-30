package com.secondream.novagram.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Realtime online/offline indicator backed by Android's
 * [ConnectivityManager.NetworkCallback]. The callback fires the
 * moment the application's default network changes (becomes available,
 * is lost, or its capabilities change) — faster and cheaper than any
 * polling loop, since the OS itself dispatches the event from the
 * kernel-level network state machine.
 *
 * Singleton, initialized once from [App.onCreate] via [start]. Any
 * composable can subscribe via `collectAsState()` on [isOnline]; the
 * value is `true` when the system has a default network with the
 * INTERNET capability validated, `false` otherwise.
 *
 * "Validated" matters: Android distinguishes between connected-but-no-
 * internet (e.g. a captive-portal Wi-Fi you haven't logged into yet)
 * and connected-and-validated. We only treat the latter as online so
 * the in-app indicator matches "can I send a message right now".
 *
 * IMPORTANT IMPLEMENTATION NOTE — why registerDefaultNetworkCallback:
 *
 * The previous implementation used `registerNetworkCallback(request)`
 * filtered on INTERNET capability. On many Android builds this does
 * NOT reliably fire `onLost` when the user toggles wifi off mid-
 * session (the OS reports the network capability transition rather
 * than removal, and our filter still considers the now-internet-less
 * network as "matching"). The symptom Eugenio reported in v0.10.55 was
 * exactly that: the banner only appeared when launching the app
 * already offline; turning off wifi while inside the app didn't flip
 * the indicator. `registerDefaultNetworkCallback` watches the SYSTEM's
 * effective default network for the app — when wifi drops and no
 * cellular is available, the default becomes null and `onLost` fires
 * promptly with the right semantics.
 */
object ConnectivityState {

    private const val TAG = "ConnectivityState"

    private val _isOnline = MutableStateFlow(true)
    val isOnline = _isOnline.asStateFlow()

    private var started = false
    @Volatile private var cmRef: ConnectivityManager? = null

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
        cmRef = cm

        // Seed the initial value before the callback wires up so the
        // UI doesn't flash "offline" on cold start when we are in fact
        // online — getActiveNetwork + capabilities check tells us the
        // current state synchronously.
        _isOnline.value = isCurrentlyOnline(cm)

        // registerDefaultNetworkCallback watches the application's
        // current default network. onAvailable fires when a new
        // default appears, onLost when the default goes away, and
        // onCapabilitiesChanged when the same network's reachability
        // properties (validated, internet, captive-portal) change.
        // This is the correct API for an "am I online right now"
        // indicator — see the kdoc on this object for why
        // registerNetworkCallback with a filter was unreliable for
        // mid-session loss detection.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Default network available")
                _isOnline.value = isCurrentlyOnline(cm)
            }

            override fun onLost(network: Network) {
                // The application's default network was lost. There is
                // no remaining default until / unless another network
                // is selected by the system. Mark offline immediately
                // and let onAvailable un-flip us when (if) a fallback
                // network becomes default.
                Log.i(TAG, "Default network lost")
                _isOnline.value = false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                // Captive-portal transitions arrive here: a network can
                // be available but NOT validated (no real internet yet)
                // then flip to validated once the user logs in. Also
                // covers the case where a wifi network keeps the link
                // up but loses internet reachability mid-session — the
                // OS clears NET_CAPABILITY_VALIDATED and fires this.
                val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _isOnline.value = internet && validated
            }
        }

        runCatching {
            cm.registerDefaultNetworkCallback(callback)
        }.onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
    }

    /**
     * Force a re-read of the system's current online state. Call from
     * a ProcessLifecycleOwner.ON_START observer so we recover the
     * correct value if the callback missed any transition while the
     * app was in the background (some OEM ROMs throttle background
     * callbacks aggressively). No-op if [start] hasn't been called.
     */
    fun recheck() {
        val cm = cmRef ?: return
        _isOnline.value = isCurrentlyOnline(cm)
    }

    /**
     * Synchronous read of the current online state. Used to seed the
     * StateFlow before the callback is wired up, and also as the
     * inside-callback source of truth.
     */
    private fun isCurrentlyOnline(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
