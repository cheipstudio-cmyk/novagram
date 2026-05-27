package com.secondream.cheipgram.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.secondream.cheipgram.td.TdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receiver for the inline-reply action attached to message notifications.
 *
 * The Android system delivers RemoteInput results as extras on the Intent;
 * we pull the typed reply, look up the chatId we stuffed in earlier, and
 * forward both to TdClient on a background scope. After sending we cancel
 * the notification (the user has dealt with it).
 *
 * No app process restart: if TdClient is already initialised in this
 * process (because the foreground service is up) the send goes out
 * immediately; if not, the next NotificationHelper.init() / TdClient.init()
 * call from App.onCreate handles delivery the next time the process wakes.
 */
class ReplyReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationHelper.ACTION_REPLY) return
        val chatId = intent.getLongExtra(NotificationHelper.EXTRA_CHAT_ID, 0L)
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.KEY_REPLY_TEXT)
            ?.toString()
            ?.trim()
        if (chatId == 0L || text.isNullOrBlank()) return

        scope.launch {
            runCatching { TdClient.sendText(chatId, text) }
            // Dismiss the notification once the reply is on its way so the
            // user doesn't see a stale "new message" indicator.
            runCatching {
                NotificationManagerCompat.from(context).cancel(chatId.hashCode())
            }
        }
    }
}
