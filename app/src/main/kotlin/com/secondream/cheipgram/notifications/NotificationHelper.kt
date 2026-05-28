package com.secondream.cheipgram.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.secondream.cheipgram.MainActivity
import com.secondream.cheipgram.R
import com.secondream.cheipgram.td.TdClient
import org.drinkless.tdlib.TdApi

object NotificationHelper {

    const val CHANNEL_MESSAGES = "cheipgram_messages"
    // Action + extras used by the inline-reply PendingIntent. Kept here
    // so ReplyReceiver can read them with the same names.
    const val ACTION_REPLY = "com.secondream.cheipgram.action.REPLY"
    const val KEY_REPLY_TEXT = "reply_text"
    const val EXTRA_CHAT_ID = "chat_id"
    const val CHANNEL_SERVICE = "cheipgram_service"
    const val SERVICE_NOTIF_ID = 9999
    private const val GROUP_KEY_MESSAGES = "cheipgram_messages_group"

    private lateinit var appContext: Context

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = appContext.getSystemService(NotificationManager::class.java)
            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messaggi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Nuovi messaggi CheipGram"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            val svcChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Servizio",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Connessione Telegram attiva"
                setShowBadge(false)
            }
            mgr.createNotificationChannels(listOf(msgChannel, svcChannel))
        }
    }

    fun buildServiceNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(appContext, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_messenger)
            .setContentTitle("CheipGram attivo")
            .setContentText("In ascolto per nuovi messaggi")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .build()
    }

    /**
     * Show an incoming-message notification.
     * Behaviour:
     * - Skip outgoing messages and messages from muted chats.
     * - For groups: prepend the sender's name ("Mario: ciao").
     * - For channels: just show the channel name + content preview.
     * - For private chats: title is the contact name, content is the message.
     * - All notifications share GROUP_KEY_MESSAGES so the system stacks them on the lock screen.
     */
    fun showMessage(message: TdApi.Message) {
        if (message.isOutgoing) return
        // If the user already has CheipGram open, don't fire a heads-up
        // notification — they will see the message right there in the
        // ChatScreen or in the chat list. Posting one anyway is just noise.
        if (com.secondream.cheipgram.AppForegroundState.isInForeground) return
        val chat = TdClient.getCachedChat(message.chatId)
        // Skip muted chats (notification settings come from TDLib)
        val muted = chat?.notificationSettings?.let {
            // muteFor > 0 means muted for that many seconds; 0 means active.
            it.muteFor > 0
        } ?: false
        if (muted) return

        val chatTitle = chat?.title?.takeIf { it.isNotBlank() } ?: "CheipGram"
        val isChannel = (chat?.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        val isGroup = chat?.type is TdApi.ChatTypeBasicGroup ||
            (chat?.type as? TdApi.ChatTypeSupergroup)?.isChannel == false
        val preview = previewOf(message)

        // For groups (not channels) prepend the sender name to the preview.
        val content = if (isGroup && !isChannel) {
            val sender = TdClient.resolveSenderName(message)
            if (sender.isNotBlank()) "$sender: $preview" else preview
        } else {
            preview
        }

        val openIntent = PendingIntent.getActivity(
            appContext,
            message.chatId.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("chatId", message.chatId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Inline-reply action: tapping "Rispondi" expands a text field inside
        // the notification shade. The text gets delivered to ReplyReceiver
        // via RemoteInput, which forwards it to TdClient on a background
        // coroutine — we never reopen the app.
        val replyLabel = appContext.getString(R.string.notification_reply_label)
        val remoteInput = androidx.core.app.RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(replyLabel)
            .build()
        val replyPendingIntent = PendingIntent.getBroadcast(
            appContext,
            message.chatId.hashCode(),
            Intent(appContext, ReplyReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra(EXTRA_CHAT_ID, message.chatId)
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_messenger, replyLabel, replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        val notif = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_messenger)
            .setContentTitle(chatTitle)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup(GROUP_KEY_MESSAGES)
            .setContentIntent(openIntent)
            .addAction(replyAction)
            .build()

        NotificationManagerCompat.from(appContext).runCatching {
            notify(message.chatId.hashCode(), notif)
        }
    }

    private fun previewOf(msg: TdApi.Message): String = when (val c = msg.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> "📷 " + c.caption.text.ifBlank { "Foto" }
        is TdApi.MessageVoiceNote -> "🎙 Nota vocale"
        is TdApi.MessageDocument -> "📎 " + c.document.fileName
        is TdApi.MessageAudio -> "🎵 " + (c.audio.title.ifBlank { "Audio" })
        is TdApi.MessageVideo -> "🎬 Video"
        is TdApi.MessageVideoNote -> "🎬 Video circle"
        is TdApi.MessageSticker -> c.sticker.emoji.ifBlank { "Sticker" } + " Sticker"
        is TdApi.MessageAnimation -> "GIF"
        is TdApi.MessageLocation -> "📍 Posizione"
        is TdApi.MessageContact -> "👤 Contatto"
        is TdApi.MessagePoll -> "📊 Sondaggio"
        is TdApi.MessageCall -> "📞 Chiamata"
        else -> "Nuovo messaggio"
    }
}
