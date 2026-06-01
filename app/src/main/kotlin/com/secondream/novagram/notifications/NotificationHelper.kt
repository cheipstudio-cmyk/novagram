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

    /**
     * Resolve the avatar file we want to render on this notification.
     * - Private/secret chats and channels → the chat's own photo
     * - Groups (basic + non-channel supergroup) → the sender's photo
     *   (chat avatar would be the group photo, less informative than
     *   knowing WHO posted)
     *
     * Returns null when no file is available (chat with no photo,
     * sender unknown) — the caller falls back to the small-icon-only
     * notification, which renders the system app icon in the avatar
     * circle. Better than a half-resolved letter circle.
     */
    private fun pickAvatarFile(
        chat: TdApi.Chat?,
        message: TdApi.Message,
        isGroup: Boolean,
        isChannel: Boolean
    ): TdApi.File? {
        if (isGroup && !isChannel) {
            val sender = message.senderId
            return when (sender) {
                is TdApi.MessageSenderUser ->
                    TdClient.getCachedUser(sender.userId)?.profilePhoto?.small
                is TdApi.MessageSenderChat ->
                    TdClient.getCachedChat(sender.chatId)?.photo?.small
                else -> null
            }
        }
        return chat?.photo?.small
    }

    /**
     * Load and circular-crop an avatar bitmap from a TDLib file. Returns
     * null when the file isn't downloaded yet — TDLib auto-downloads
     * chat photos as they enter the cache so the path is usually
     * available by the time a notification fires, but we don't block
     * on the download (notifications must be fast).
     *
     * The crop is required because Android's MessagingStyle puts the
     * Person icon in a SQUARE slot by default on some launchers,
     * which makes profile photos look chunky. A pre-cropped circular
     * bitmap renders correctly everywhere — and we wrap it as an
     * AdaptiveBitmap icon so launchers that do their own masking
     * apply a circular mask too.
     */
    private fun loadAvatarBitmap(file: TdApi.File?): android.graphics.Bitmap? {
        val path = file?.local?.path
        if (path.isNullOrBlank()) return null
        if (file.local?.isDownloadingCompleted != true) return null
        val src = runCatching { android.graphics.BitmapFactory.decodeFile(path) }
            .getOrNull() ?: return null
        // Square-crop to the smaller side first, then circular-mask.
        // Notification large icons sit at 64dp = ~192px on a 3x device,
        // so we don't need huge dimensions; downscale to 256px max for
        // memory hygiene.
        val side = minOf(src.width, src.height)
        val xOff = (src.width - side) / 2
        val yOff = (src.height - side) / 2
        val square = runCatching {
            android.graphics.Bitmap.createBitmap(src, xOff, yOff, side, side)
        }.getOrNull() ?: return null
        val targetSide = minOf(256, side)
        val scaled = if (targetSide < side) {
            android.graphics.Bitmap.createScaledBitmap(square, targetSide, targetSide, true)
        } else square
        // Circular mask via porter-duff. Cheaper than going through
        // RoundedBitmapDrawable + intermediate Drawable conversion.
        val out = android.graphics.Bitmap.createBitmap(
            targetSide, targetSide, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rect = android.graphics.Rect(0, 0, targetSide, targetSide)
        val rectF = android.graphics.RectF(rect)
        paint.color = android.graphics.Color.BLACK
        canvas.drawOval(rectF, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(
            android.graphics.PorterDuff.Mode.SRC_IN
        )
        canvas.drawBitmap(scaled, rect, rect, paint)
        return out
    }

    /**
     * Resolve an avatar to a circular bitmap, downloading the thumb first if
     * TDLib hasn't cached it yet.
     *
     * The old approach merely *kicked off* a download and then immediately
     * tried to load the file — which had almost always not finished, so the
     * very first notification for a chat (and every notification for chats
     * whose avatar TDLib hadn't pre-fetched) fell back to the letter circle.
     * That's the "it keeps showing the initial" bug.
     *
     * TDLib's DownloadFile is synchronous in this client (see
     * TdClient.downloadFile → the trailing `true`), so we can simply await it.
     * Avatar thumbs are a few KB, so this resolves in well under a second on
     * the common path; we still cap it with a timeout so a stalled transfer
     * can never hang the notification. Blocking here is safe: showMessage runs
     * off the main thread on the newMessages collector and already uses
     * runBlocking for self-id resolution. Once a thumb is cached the
     * isDownloadingCompleted fast-path makes every later notification instant.
     */
    private fun resolveAvatarBitmap(file: TdApi.File?): android.graphics.Bitmap? {
        if (file == null) return null
        if (file.local?.isDownloadingCompleted == true) return loadAvatarBitmap(file)
        val downloaded = runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(8000) { TdClient.downloadFile(file.id) }
            }
        }.getOrNull()
        return loadAvatarBitmap(downloaded ?: file)
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

        // Skip notifications for chats the user is not actually a member of.
        // TDLib pushes UpdateNewMessage for every chat we hold a reference
        // to — including public groups / channels the user has only
        // "viewed" via search (no join). Without this gate, opening any
        // public chat starts streaming notifications from it forever.
        // Heuristic: a chat with no positions in any chat list is not in
        // the user's chat-list view → they haven't joined. Private chats
        // and Saved Messages always have positions when active, so this
        // doesn't false-positive on normal conversations.
        val isMember = chat?.positions?.isNotEmpty() == true
        if (!isMember) return

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
        // Resolve the right avatar (sender for groups, chat for everything
        // else) and load it as a circular bitmap. Secret chats deliberately
        // skip this — the notification is intentionally obfuscated, leaking
        // the peer's photo would defeat the point.
        val avatarFile = if (isSecretChat) null
            else pickAvatarFile(chat, message, isGroup, isChannel)
        val avatarBitmap = resolveAvatarBitmap(avatarFile)
        // Plain bitmap icon, NOT adaptive: the bitmap is already circular-
        // cropped, and adaptive icons get mask-cropped to the inner ~66% in
        // notification slots, which over-zoomed the face or rendered blank on
        // several launchers — part of why the avatar "wasn't showing".
        val avatarIcon = avatarBitmap?.let {
            androidx.core.graphics.drawable.IconCompat.createWithBitmap(it)
        }
        val person = androidx.core.app.Person.Builder()
            .setName(displayName)
            .setKey(message.senderId.let { s ->
                when (s) {
                    is TdApi.MessageSenderUser -> "user:${s.userId}"
                    is TdApi.MessageSenderChat -> "chat:${s.chatId}"
                    else -> "u:${message.chatId}"
                }
            })
            .also { b -> if (avatarIcon != null) b.setIcon(avatarIcon) }
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

        // The collapsed notification's circular slot (top-right thumb on
        // most launchers, top-left on others) takes a single largeIcon.
        // We prefer the CHAT photo here even in groups — at-a-glance the
        // user wants to know "which conversation pinged me", which is
        // the group photo. The sender's photo is already shown inline
        // via the Person.icon in the MessagingStyle row.
        val largeIconFile = if (isSecretChat) null else chat?.photo?.small
        val largeIconBitmap = resolveAvatarBitmap(largeIconFile)
            ?: avatarBitmap  // fall back to per-message avatar if chat has none
        val notif = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_messenger)
            .also { b -> if (largeIconBitmap != null) b.setLargeIcon(largeIconBitmap) }
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

    private fun previewOf(msg: TdApi.Message): String {
        com.secondream.novagram.td.TdClient.serviceMessageText(msg)?.let { return it }
        return when (val c = msg.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> "📷 " + c.caption.text.ifBlank { "Foto" }
            is TdApi.MessageVoiceNote -> "🎙 Nota vocale"
            is TdApi.MessageDocument -> "📎 " + c.document.fileName
            is TdApi.MessageAudio -> "🎵 " + (c.audio.title.ifBlank { "Audio" })
            is TdApi.MessageVideo -> "🎬 " + c.caption.text.ifBlank { "Video" }
            is TdApi.MessageVideoNote -> "📹 Video messaggio"
            is TdApi.MessageSticker -> c.sticker.emoji.ifBlank { "Sticker" } + " Sticker"
            is TdApi.MessageAnimation -> "GIF"
            is TdApi.MessageLocation -> "📍 Posizione"
            is TdApi.MessageVenue -> "📍 " + c.venue.title.ifBlank { "Luogo" }
            is TdApi.MessageContact -> "👤 " + "${c.contact.firstName} ${c.contact.lastName}".trim().ifBlank { "Contatto" }
            is TdApi.MessagePoll -> "📊 " + c.poll.question.text.ifBlank { "Sondaggio" }
            is TdApi.MessageGame -> "🎮 " + c.game.title.ifBlank { "Gioco" }
            is TdApi.MessageDice -> c.emoji.ifBlank { "🎲" }
            is TdApi.MessageStory -> "📖 Storia"
            is TdApi.MessageInvoice -> "🧾 Fattura"
            is TdApi.MessageCall -> "📞 Chiamata"
            else -> "Nuovo messaggio"
        }
    }
}
