package com.secondream.novagram.notifications

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.td.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

/**
 * Firebase Cloud Messaging entry point. Handles two callbacks:
 *
 *  - [onNewToken]: every time Firebase issues a fresh FCM token (first
 *    install, token rotation, reinstall after clear-data) we hand it
 *    to TDLib via [TdApi.RegisterDevice] so the Telegram backend knows
 *    where to push notifications for this device.
 *  - [onMessageReceived]: each FCM payload arrives here and is
 *    converted to a JSON string, which we pass to TDLib via
 *    [TdApi.ProcessPushNotification]. TDLib then decrypts the payload,
 *    fetches the missed message and emits an [TdApi.UpdateNewMessage]
 *    — at which point our existing [NotificationHelper] takes over
 *    and posts the local notification through the normal pipeline.
 *
 * Both paths are wrapped in try/catch and guarded against TDLib not
 * being initialized yet: if FCM lands a message before the user has
 * logged in (e.g. install + power-cycle, no app open in between),
 * TDLib's process call fails silently and the user re-receives the
 * message via FGS once they open the app. Same applies if
 * google-services.json is missing — Firebase doesn't init, FCM never
 * delivers anything to this service, the class effectively becomes
 * dead code without affecting anything else.
 *
 * Manifest registration is in AndroidManifest.xml under the
 * `notifications` package alongside [ReplyReceiver].
 */
class NovagramFcmService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "onNewToken received, registering with TDLib")
        scope.launch {
            runCatching {
                TdClient.registerDeviceForFcm(token)
            }.onFailure { Log.w(TAG, "registerDeviceForFcm failed", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Build a JSON-shaped string of the FCM data payload — TDLib's
        // ProcessPushNotification accepts a string in the same shape
        // Telegram's official client passes. Each FCM data field is a
        // String/String pair on Android; converting back to JSON
        // preserves the original payload structure.
        val json = JSONObject()
        runCatching {
            message.data.forEach { (k, v) -> json.put(k, v) }
            // Some payload fields are namespaced under "loc_key" /
            // "loc_args" / "custom" which TDLib expects literally —
            // the above already preserves them, but documenting the
            // shape here so future changes don't strip them.
        }
        val payload = json.toString()
        // Novagram no longer runs a foreground service, so a closed app is
        // woken here by FCM but the OS reclaims the process the instant this
        // method returns — the old fire-and-forget launch posted nothing.
        // FirebaseMessagingService holds a wakelock while onMessageReceived
        // runs, so we BLOCK instead: wait for TDLib to be authorized (a cold
        // start reloads it from disk), hand it the push, then hold briefly
        // while TDLib emits UpdateNewMessage and the app-level collector in
        // TdClient posts the notification. Every wait is bounded so the
        // service can never hang.
        runCatching {
            runBlocking {
                withTimeoutOrNull(12_000) {
                    withTimeoutOrNull(8_000) {
                        TdClient.authState.first { it is AuthState.Ready }
                    }
                    runCatching { TdClient.processPushNotification(payload) }
                    delay(3_500)
                }
            }
        }.onFailure { Log.w(TAG, "processPushNotification failed", it) }
    }

    companion object {
        private const val TAG = "NovagramFcmService"
    }
}
