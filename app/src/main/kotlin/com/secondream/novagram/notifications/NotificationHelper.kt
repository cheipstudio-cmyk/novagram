package com.secondream.novagram.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.secondream.novagram.MainActivity
import com.secondream.novagram.R
import com.secondream.novagram.td.TdClient
import org.drinkless.tdlib.TdApi

object NotificationHelper {

    const val CHANNEL_MESSAGES = "novagram_messages"
    // Action + extras used by the inline-reply PendingIntent. Kept here
    // so ReplyReceiver can read them with the same names.
    const val ACTION_REPLY = "com.secondream.novagram.action.REPLY"
    const val KEY_REPLY_TEXT = "reply_text"
    const val EXTRA_CHAT_ID = "chat_id"
    const val CHANNEL_SERVICE = "novagram_service"
    const val SERVICE_NOTIF_ID = 9999
    private const val GROUP_KEY_MESSAGES = "novagram_messages_group"
    private const val MAX_BUNDLED_PER_CHAT = 8

    /**
     * Per-chat queue of pending MessagingStyle.Message entries — the
     * data the system shows when the user expands a notification with
     * MessagingStyle. We accumulate here so consecutive new messages in
     * the same chat appear stacked (Telegram-style) rather than the
     * latest one overwriting all previous content. Capped at
     * [MAX_BUNDLED_PER_CHAT] so a noisy group doesn't unboundedly grow
     * the in-memory list before dismissForChat clears it.
     *
     * Concurrent access is guarded by per-deque synchronized blocks at
     * the call sites — the outer ConcurrentHashMap handles get/put
     * races between simultaneous newMessages emissions for different
     * chats, and the per-deque synchronized handles same-chat races.
     */
    private val pendingMessagesByChat =
        java.util.concurrent.ConcurrentHashMap<Long, ArrayDeque<NotificationCompat.MessagingStyle.Message>>()

    private lateinit var appContext: Context

    /**
     * Cached self-userId, used by [isReplyToMyMessage] to decide whether
     * an incoming reply targets me (and therefore deserves to bypass the
     * chat mute). Populated lazily on the first call; refreshed only on
     * TDLib re-init since the value can change after a logout/login.
     */
    @Volatile private var cachedSelfUserId: Long = 0L

    /**
     * True when [message] is a reply to a message that the current user
     * sent. We compare the replied-to sender against our own cached
     * userId. For [TdApi.MessageReplyToMessage] TDLib carries a nested
     * `origin` describing who originally posted the replied-to message
     * — when it's [TdApi.MessageOriginUser] with the same userId as
     * ours, it's a ping for us.
     *
     * Best-effort: if the origin info isn't filled in (older TDLib
     * records, deleted messages with sparse metadata) we return false
     * rather than guess. That's fine — the worst case is the user
     * misses one notification, not one that fires incorrectly.
     */
    private fun isReplyToMyMessage(message: TdApi.Message): Boolean {
        val rt = message.replyTo as? TdApi.MessageReplyToMessage ?: return false
        val selfId = ensureSelfUserId()
        if (selfId == 0L) return false
        val origin = rt.origin as? TdApi.MessageOriginUser ?: return false
        return origin.senderUserId == selfId
    }

    private fun ensureSelfUserId(): Long {
        val current = cachedSelfUserId
        if (current != 0L) return current
        return runCatching {
            kotlinx.coroutines.runBlocking { TdClient.getMe().id }
        }.getOrDefault(0L).also { cachedSelfUserId = it }
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = appContext.getSystemService(NotificationManager::class.java)
            val msgChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Messaggi",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Nuovi messaggi Nova"
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
            .setContentTitle(appContext.getString(R.string.service_notif_title))
            .setContentText(appContext.getString(R.string.service_notif_body))
            // Long-form expansion: the body line is intentionally
            // detailed because users see this notification in their
            // status bar permanently while the app is alive. A clear
            // explanation of WHY the process is running ("delivering
            // notifications in real time") prevents the "this app is
            // tracking me" panic that vague ongoing-service notices
            // tend to provoke.
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(appContext.getString(R.string.service_notif_body))
            )
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
        // Suppress heads-up only if the user is currently viewing THIS exact
        // chat in ChatScreen — they will see the message arrive in-line and a
        // separate notification would be redundant noise. If the app is open
        // but they're on another chat / the chat list / settings, we DO want
        // them to be notified so they don't miss the incoming message.
        if (com.secondream.novagram.AppForegroundState.isInForeground &&
            com.secondream.novagram.AppForegroundState.currentChatId == message.chatId
        ) return
        val chat = TdClient.getCachedChat(message.chatId)
        // Is this an @-mention OR a reply to one of my messages? Either
        // signal puts the notification on a different track: even if the
        // chat is globally muted, the user explicitly asked to be pinged
        // for these. The only override is the per-chat
        // disableMentionNotifications toggle (Telegram's "@mentions"
        // sub-switch inside notification settings) — when THAT is on,
        // mentions stay silent.
        val containsMention = message.containsUnreadMention
        val isReplyToMe = isReplyToMyMessage(message)
        val notifySettings = chat?.notificationSettings
        val mentionsAllowed = notifySettings?.disableMentionNotifications != true
        val isPersonalPing = (containsMention || isReplyToMe) && mentionsAllowed

        // Skip muted chats (notification settings come from TDLib), unless
        // this specific message is a personal ping for the user.
        //
        // We use TdClient.isChatMuted() rather than reading muteFor
        // straight off the chat — that helper handles useDefaultMuteFor
        // by falling back to the SCOPE notification settings (Private /
        // Group / Channel). Without that, muting "all groups" from
        // Telegram settings was silently bypassed here because each
        // affected chat's per-chat muteFor stays 0 with useDefault=true.
        val muted = TdClient.isChatMuted(chat)
        if (muted && !isPersonalPing) return

        // Skip ALL notifications for archived chats: the archive folder
        // is the user's "out of sight, out of mind" pile and should not
        // ping. The position carries an `order > 0` in ChatListArchive
        // when the chat is actually living in archive (a 0 means TDLib
        // still has the row but it's not actively listed).
        val isArchived = chat?.positions?.any { pos ->
            pos.list is TdApi.ChatListArchive && pos.order != 0L
        } == true
        if (isArchived) return

        // Secret chats get an obfuscated notification — Telegram shows
        // "New message" with no sender / no body / no preview. We mirror
        // that: the channel still pings + vibrates, but neither the
        // title bar nor the expanded text leaks the chat name or the
        // message content. Tapping still opens the right chat because
        // we keep the chatId on the openIntent.
        val isSecretChat = chat?.type is TdApi.ChatTypeSecret

        val chatTitle = if (isSecretChat) "Novagram"
            else chat?.title?.takeIf { it.isNotBlank() } ?: "Novagram"
        val isChannel = (chat?.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        val isGroup = chat?.type is TdApi.ChatTypeBasicGroup ||
            (chat?.type as? TdApi.ChatTypeSupergroup)?.isChannel == false
        val preview = if (isSecretChat) "1 nuovo messaggio" else previewOf(message)

        // For groups (not channels) prepend the sender name to the preview.
        // Secret chats deliberately skip this — the preview is already the
        // obfuscated "1 nuovo messaggio" and we don't want to leak even
        // the sender name into the lockscreen.
        val content = if (isSecretChat) {
            preview
        } else if (isGroup && !isChannel) {
            val sender = TdClient.resolveSenderName(message)
            if (sender.isNotBlank()) "$sender: $preview" else preview
        } else {
            preview
        }

        // MESSAGING STYLE: rather than overwriting the visible body with
        // each new message (the old BigTextStyle path lost everything but
        // the latest), we accumulate per-chat in `pendingMessagesByChat`
        // and rebuild a MessagingStyle that lists ALL recent unread
        // messages. The user sees the most recent line in the collapsed
        // notification and can swipe down to read the full thread —
        // identical to Telegram and what audit #4 was asking for.
        val displayName = if (isSecretChat) "Novagram"
            else if (isGroup && !isChannel) TdClient.resolveSenderName(message).ifBlank { "Novagram" }
            else chatTitle
        val person = androidx.core.app.Person.Builder()
            .setName(displayName)
            .setKey(message.senderId.let { s ->
                when (s) {
                    is TdApi.MessageSenderUser -> "user:${s.userId}"
                    is TdApi.MessageSenderChat -> "chat:${s.chatId}"
                    else -> "u:${message.chatId}"
                }
            })
            .build()
        val newMsg = NotificationCompat.MessagingStyle.Message(
            preview,
            message.date.toLong() * 1000L,
            person
        )
        val queue = pendingMessagesByChat.getOrPut(message.chatId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(newMsg)
            while (queue.size > MAX_BUNDLED_PER_CHAT) queue.removeFirst()
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

        // "You" is the Person representing the LOCAL user in the
        // MessagingStyle thread — required by the API. We don't expose
        // it as a sender (we only ever receive in notifications, never
        // send), but the style asks for a self-Person regardless.
        val selfPerson = androidx.core.app.Person.Builder()
            .setName(appContext.getString(R.string.notification_self_label))
            .setKey("self")
            .build()
        val style = NotificationCompat.MessagingStyle(selfPerson)
            .setGroupConversation(isGroup || isChannel)
            .also { s ->
                if (isGroup || isChannel) s.conversationTitle = chatTitle
                synchronized(queue) {
                    queue.forEach { s.addMessage(it) }
                }
            }

        val notif = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_messenger)
            .setStyle(style)
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

    /**
     * Clear any active heads-up / tray notification for the given chat.
     * Telegram's stock behaviour: when you open a chat and the unread
     * messages get marked read, the related notification disappears on
     * its own — no need to swipe it away. We mirror that by cancelling
     * the notification keyed by chatId.hashCode() (the same id we used
     * in [notify]) whenever ChatScreen opens, AND on every
     * UpdateChatReadInbox where unreadCount drops to zero (so a read
     * from another device clears the notification on this one too).
     *
     * Wrapped in runCatching because some launchers / OEMs reject
     * cancel() in edge cases and we don't want a missing notification
     * cancel to crash the chat scroll.
     */
    fun dismissForChat(chatId: Long) {
        // Drop the in-memory MessagingStyle history for this chat so the
        // next incoming notification starts fresh — without this the
        // user would re-see the just-read messages stacked back when a
        // new one lands.
        pendingMessagesByChat.remove(chatId)
        NotificationManagerCompat.from(appContext).runCatching {
            cancel(chatId.hashCode())
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
