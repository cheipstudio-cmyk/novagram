@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.secondream.cheipgram.ui.components
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.secondream.cheipgram.R
import androidx.compose.ui.res.stringResource
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.theme.Ink
import com.secondream.cheipgram.ui.theme.bubbleFillFor
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: TdApi.Message,
    showSender: Boolean = false,
    onLongPress: (TdApi.Message) -> Unit = {},
    onMediaTap: (String) -> Unit = {},
    onSwipeReply: (TdApi.Message) -> Unit = {},
    /** Fired when the user taps the sender avatar shown next to non-mine
     *  bubbles in group chats. Receives the sender's userId so the parent
     *  can open a profile sheet / start a chat. No-op by default for
     *  callers that don't need profile-on-tap. */
    onAvatarClick: (userId: Long) -> Unit = {},
    /**
     * Bumped by the parent each time TDLib pushes a new InteractionInfo
     * for this message. Reading it here pulls this composable into the
     * snapshot graph so the bubble recomposes when reactions / views /
     * forwards change, even though the underlying Java Message is
     * mutated in place rather than re-emitted.
     */
    interactionRevision: Int = 0
) {
    // Read the param so the composable observes it; the value itself isn't
    // used directly anywhere — it's purely a recompose trigger.
    @Suppress("UNUSED_EXPRESSION") interactionRevision
    val mine = message.isOutgoing
    val appearance by AppSettings.appearance.collectAsState(
        initial = com.secondream.cheipgram.settings.AppearancePrefs()
    )
    val bubbleColorPref = if (mine) appearance.myBubbleColor else appearance.othersBubbleColor
    val customBubbleArgb = if (mine) appearance.customMyBubbleArgb else appearance.customOthersBubbleArgb
    val fill = bubbleFillFor(bubbleColorPref, mine, customBubbleArgb)
    val align = if (mine) Alignment.End else Alignment.Start
    val shape = if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    // Resolve sender user once (groups/supergroups, non-outgoing).
    var senderUser by remember(message.id) { mutableStateOf<TdApi.User?>(null) }
    LaunchedEffect(message.id, message.senderId) {
        if (!showSender || mine) return@LaunchedEffect
        val sid = message.senderId
        if (sid is TdApi.MessageSenderUser) {
            senderUser = TdClient.getCachedUser(sid.userId)
                ?: runCatching { TdClient.getUser(sid.userId) }.getOrNull()
        }
    }

    // Swipe-to-reply gesture. We drag the bubble horizontally; if the user
    // releases past `triggerPx` we fire onSwipeReply and snap back. During
    // the drag a small reply arrow fades in behind the bubble on the
    // opposite side (the side the bubble is being pulled FROM). For
    // outgoing messages we swipe LEFT (so the arrow appears on the right),
    // for incoming we swipe RIGHT (arrow on the left) — matching Telegram.
    var swipeOffset by remember(message.id) { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val triggerPx = with(density) { 64.dp.toPx() }
    val maxPx = with(density) { 120.dp.toPx() }
    val animatedOffset by animateFloatAsState(targetValue = swipeOffset, label = "swipe-reply")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var hapticFired by remember(message.id) { mutableStateOf(false) }
    // Coroutine scope + Android context for the bubble click handler:
    // we need to ask TDLib for the *current* file state at tap time (in
    // case the closure-captured TdApi.File reference is stale) and we
    // need a Context to launch system Intents for non-image content.
    val tapScope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput("swipe-${message.id}") {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(swipeOffset) >= triggerPx) {
                            onSwipeReply(message)
                        }
                        swipeOffset = 0f
                        hapticFired = false
                    },
                    onDragCancel = {
                        swipeOffset = 0f
                        hapticFired = false
                    },
                    onHorizontalDrag = { _, delta ->
                        // Reverted to left-to-right (positive offset).
                        // For both mine and incoming bubbles the gesture
                        // is the same direction now — feels more like
                        // Telegram's native behavior than the right-to-left
                        // version we briefly had.
                        val proposed = swipeOffset + delta
                        swipeOffset = proposed.coerceIn(0f, maxPx)
                        if (!hapticFired && kotlin.math.abs(swipeOffset) >= triggerPx) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            hapticFired = true
                        }
                    }
                )
            }
            // Custom long-press detector with shorter trigger time
            // (300ms vs the default detectTapGestures 500ms) and explicit
            // touchSlop tolerance. Many users were tapping-and-holding for
            // ~400ms expecting the menu to appear — now it does. Also
            // gives a haptic on trigger so the user knows it landed.
            .pointerInput("longpress-${message.id}") {
                // We launch the long-press timer from the composable-level
                // CoroutineScope (`tapScope`) rather than from the
                // PointerInputScope: in current Compose-foundation that
                // scope is NOT a CoroutineScope, so .launch{} won't resolve
                // here. Going through tapScope also keeps the timer's
                // lifecycle tied to the bubble's composition, which is
                // exactly what we want (cancelled if the bubble scrolls
                // off-screen and recomposes elsewhere).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 300ms timer fires the long press if the user hasn't lifted
                    // or moved past touchSlop by then. waitForUpOrCancellation()
                    // returns when either of those happens, at which point we
                    // cancel the pending timer.
                    val timerJob = tapScope.launch {
                        kotlinx.coroutines.delay(300L)
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        onLongPress(message)
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        timerJob.cancel()
                    }
                }
            }
    ) {
        // Reply arrow indicator behind the bubble — fades in as the user
        // approaches the trigger threshold. Now rendered on the LEFT
        // side (gesture is left-to-right, so the arrow appears to be
        // "pulled out" from the start of the row).
        val revealAlpha = (kotlin.math.abs(animatedOffset) / triggerPx).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = revealAlpha),
                modifier = Modifier.size(22.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { androidx.compose.ui.unit.IntOffset(animatedOffset.toInt(), 0) }
                .padding(horizontal = 12.dp, vertical = 3.dp),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (showSender && !mine) {
                val senderUserId = (message.senderId as? TdApi.MessageSenderUser)?.userId
                Avatar(
                    file = senderUser?.profilePhoto?.small,
                    fallbackText = senderUser?.firstName ?: "?",
                    size = 28.dp,
                    // Tap-on-avatar opens the profile sheet in the parent.
                    // We only wire the click when we actually have a userId
                    // to send back; chat-author messages (admin announcements
                    // posted as the group itself) leave the avatar inert.
                    modifier = if (senderUserId != null) {
                        Modifier.clickable { onAvatarClick(senderUserId) }
                    } else Modifier
                )
                Spacer(Modifier.width(6.dp))
            }
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(fill.background)
                .combinedClickable(
                    onClick = {
                        // The TdApi.File references inside message.content are
                        // captured at compose time. TDLib mutates them in
                        // place when downloads finish, but Compose doesn't
                        // observe the field-level change so by the time the
                        // user taps, local.isDownloadingCompleted on the
                        // captured object may still read false even though
                        // the file IS actually on disk. We launch a small
                        // coroutine that re-asks TDLib for the latest state,
                        // then routes to MediaViewer for images / system
                        // viewer for documents.
                        tapScope.launch {
                            when (val c = message.content) {
                                is TdApi.MessagePhoto -> {
                                    val biggest = c.photo.sizes.lastOrNull()?.photo
                                        ?: return@launch
                                    val latest = runCatching { TdClient.getFile(biggest.id) }
                                        .getOrNull() ?: biggest
                                    val path = latest.local?.path
                                    if (latest.local.isDownloadingCompleted && !path.isNullOrBlank()) {
                                        onMediaTap(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    done.local.isDownloadingCompleted && it.isNotBlank()
                                                }?.let(onMediaTap)
                                            }
                                    }
                                }
                                is TdApi.MessageVideo -> {
                                    val f = c.video.video
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    if (latest.local.isDownloadingCompleted && !path.isNullOrBlank()) {
                                        // Videos go through the system viewer:
                                        // MediaViewerScreen is image-only.
                                        com.secondream.cheipgram.util.FileUtils.openDocument(
                                            ctx, path, c.video.mimeType, c.video.fileName
                                        )
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    done.local.isDownloadingCompleted && it.isNotBlank()
                                                }?.let { p ->
                                                    com.secondream.cheipgram.util.FileUtils.openDocument(
                                                        ctx, p, c.video.mimeType, c.video.fileName
                                                    )
                                                }
                                            }
                                    }
                                }
                                is TdApi.MessageAnimation -> {
                                    val f = c.animation.animation
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    if (latest.local.isDownloadingCompleted && !path.isNullOrBlank()) {
                                        onMediaTap(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    done.local.isDownloadingCompleted && it.isNotBlank()
                                                }?.let(onMediaTap)
                                            }
                                    }
                                }
                                is TdApi.MessageDocument -> {
                                    val f = c.document.document
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    val open: (String) -> Unit = { p ->
                                        com.secondream.cheipgram.util.FileUtils.openDocument(
                                            ctx, p, c.document.mimeType, c.document.fileName
                                        )
                                    }
                                    if (latest.local.isDownloadingCompleted && !path.isNullOrBlank()) {
                                        open(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    done.local.isDownloadingCompleted && it.isNotBlank()
                                                }?.let(open)
                                            }
                                    }
                                }
                                is TdApi.MessageAudio -> {
                                    val f = c.audio.audio
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    if (latest.local.isDownloadingCompleted && !path.isNullOrBlank()) {
                                        com.secondream.cheipgram.util.FileUtils.openDocument(
                                            ctx, path, c.audio.mimeType, c.audio.fileName
                                        )
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    done.local.isDownloadingCompleted && it.isNotBlank()
                                                }?.let { p ->
                                                    com.secondream.cheipgram.util.FileUtils.openDocument(
                                                        ctx, p, c.audio.mimeType, c.audio.fileName
                                                    )
                                                }
                                            }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    },
                    onLongClick = { onLongPress(message) }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = align
        ) {
            if (showSender && !mine) {
                val name = senderUser?.let { "${it.firstName} ${it.lastName}".trim() }.orEmpty()
                if (name.isNotBlank()) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
            // If this message is a reply to another one, render the
            // quoted-preview bar just above the body. Matches Telegram's
            // visual: a tinted strip with a left accent stripe carrying
            // the original sender's name + a one-line preview of the
            // original content (or the manually-quoted slice when the
            // user picked a quote range with TDLib's quote feature).
            (message.replyTo as? TdApi.MessageReplyToMessage)?.let { rt ->
                ReplyQuoteBar(
                    replyTo = rt,
                    accent = MaterialTheme.colorScheme.primary,
                    onBackground = fill.onBackground
                )
                Spacer(Modifier.height(6.dp))
            }
            MessageContent(message, fill.onBackground)
            // Existing reactions strip. We surface every reaction the
            // message currently carries; tapping toggles your own reaction
            // (add if missing, remove if you've already used it). Updates
            // come through via TdClient.interactionInfoUpdates so the chip
            // counts stay live without a list refresh.
            val reactions = message.interactionInfo?.reactions?.reactions
            if (!reactions.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                ReactionStrip(
                    chatId = message.chatId,
                    messageId = message.id,
                    reactions = reactions.toList(),
                    onBackground = fill.onBackground,
                    accent = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(2.dp))
            // Outgoing messages in private chats show a tick marker next to
            // the timestamp: a clock when still sending, an exclamation on
            // failure, a single tick when delivered, double tick when read.
            // Groups don't get ticks because TDLib doesn't expose per-member
            // read state cheaply.
            val cachedChat = TdClient.getCachedChat(message.chatId)
            val isPrivateChat = cachedChat?.type is TdApi.ChatTypePrivate
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "modificato" tag — only on messages TDLib has flagged as
                // edited (editDate > 0). Sits to the LEFT of the timestamp
                // so it reads naturally as a property of the time mark, the
                // same place Telegram puts its "edited" indicator.
                if (message.editDate > 0) {
                    Text(
                        text = stringResource(R.string.bubble_edited_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = fill.onBackground.copy(alpha = 0.55f),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = formatHHmm(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = fill.onBackground.copy(alpha = 0.55f)
                )
                if (mine && isPrivateChat) {
                    Spacer(Modifier.width(4.dp))
                    val sendingState = message.sendingState
                    val isRead = (cachedChat?.lastReadOutboxMessageId ?: 0L) >= message.id
                    val ticks = when {
                        sendingState is TdApi.MessageSendingStatePending -> "⏱"
                        sendingState is TdApi.MessageSendingStateFailed -> "!"
                        isRead -> "✓✓"
                        else -> "✓"
                    }
                    val tint = when {
                        sendingState is TdApi.MessageSendingStateFailed ->
                            MaterialTheme.colorScheme.error
                        isRead -> MaterialTheme.colorScheme.primary
                        else -> fill.onBackground.copy(alpha = 0.55f)
                    }
                    Text(
                        ticks,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun MessageContent(message: TdApi.Message, onBackground: androidx.compose.ui.graphics.Color) {
    when (val c = message.content) {
        is TdApi.MessageText -> {
            val rawText = c.text.text
            // Theme-share detection: if the body contains a cheipgram://theme
            // deeplink, parse it and render a card with an "Applica tema"
            // button instead of plain text. Falls back to the normal
            // FormattedTextRendering when parsing fails or the message is
            // a regular link.
            val themePrefs = remember(rawText) {
                if (rawText.contains("cheipgram://theme?data=")) {
                    com.secondream.cheipgram.ui.screens.parseThemeJson(rawText)
                } else null
            }
            if (themePrefs != null) {
                ThemeShareCard(prefs = themePrefs)
            } else {
                FormattedTextRendering(
                    formatted = c.text,
                    onBackground = onBackground,
                    linkColor = MaterialTheme.colorScheme.primary
                )
            }
        }
        is TdApi.MessagePhoto -> {
            val photo = c.photo.sizes.lastOrNull()?.photo
            DownloadingImage(
                initialFile = photo,
                placeholderIcon = { Icon(Icons.Outlined.Image, null, tint = Ink.Muted) },
                placeholderLabel = stringResource(R.string.media_photo)
            )
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageVideo -> {
            // Show the thumbnail file (cheap, usually auto-downloaded by TDLib).
            // Tap-to-play would belong in a dedicated player screen, Round 2+.
            val thumb = c.video.thumbnail?.file
            Box(contentAlignment = Alignment.Center) {
                DownloadingImage(
                    initialFile = thumb,
                    placeholderIcon = { Icon(Icons.Outlined.PlayArrow, null, tint = Ink.Cream) },
                    placeholderLabel = stringResource(R.string.media_video)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Ink.Bg.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        null,
                        tint = Ink.Cream,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageAnimation -> {
            // GIF: show thumbnail, autoplay belongs to Round 2.
            val thumb = c.animation.thumbnail?.file
            DownloadingImage(
                initialFile = thumb,
                placeholderIcon = { Icon(Icons.Outlined.Image, null, tint = Ink.Muted) },
                placeholderLabel = stringResource(R.string.media_gif)
            )
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageDocument -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        c.document.fileName.ifBlank { stringResource(R.string.media_document) },
                        style = MaterialTheme.typography.titleSmall,
                        color = onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatBytes(c.document.document.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBackground.copy(alpha = 0.6f)
                    )
                }
            }
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageVoiceNote -> {
            VoiceNotePlayer(
                voiceNote = c.voiceNote,
                accent = MaterialTheme.colorScheme.primary,
                onBackground = onBackground
            )
        }
        is TdApi.MessageAudio -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AudioFile, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        c.audio.title.ifBlank { c.audio.fileName }.ifBlank { stringResource(R.string.media_audio) },
                        style = MaterialTheme.typography.titleSmall,
                        color = onBackground
                    )
                    Text("${c.audio.duration}s", style = MaterialTheme.typography.labelSmall, color = onBackground.copy(alpha = 0.6f))
                }
            }
        }
        is TdApi.MessageSticker -> {
            val st = c.sticker
            when (st.format) {
                is TdApi.StickerFormatWebp -> {
                    DownloadingImage(
                        initialFile = st.sticker,
                        placeholderIcon = { Text(st.emoji.ifBlank { "🖼" }, style = MaterialTheme.typography.headlineMedium) },
                        placeholderLabel = ""
                    )
                }
                is TdApi.StickerFormatTgs -> {
                    AnimatedTgsSticker(
                        file = st.sticker,
                        fallbackEmoji = st.emoji.ifBlank { "🖼" }
                    )
                }
                is TdApi.StickerFormatWebm -> {
                    WebmVideoSticker(
                        file = st.sticker,
                        fallbackEmoji = st.emoji.ifBlank { "🖼" }
                    )
                }
                else -> Text(
                    st.emoji.ifBlank { "🖼" },
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
        is TdApi.MessageContactRegistered -> Text(
            stringResource(R.string.service_contact_joined),
            style = MaterialTheme.typography.bodyLarge,
            color = onBackground.copy(alpha = 0.7f)
        )
        is TdApi.MessageChatJoinByLink, is TdApi.MessageChatAddMembers -> Text(
            stringResource(R.string.service_chat_join),
            style = MaterialTheme.typography.bodyLarge,
            color = onBackground.copy(alpha = 0.7f)
        )
        is TdApi.MessageChatDeleteMember -> Text(
            stringResource(R.string.service_chat_leave),
            style = MaterialTheme.typography.bodyLarge,
            color = onBackground.copy(alpha = 0.7f)
        )
        is TdApi.MessageChatChangePhoto -> Text(
            stringResource(R.string.service_chat_photo_changed),
            style = MaterialTheme.typography.bodyLarge,
            color = onBackground.copy(alpha = 0.7f)
        )
        is TdApi.MessageChatChangeTitle -> Text(
            stringResource(R.string.service_chat_title_changed),
            style = MaterialTheme.typography.bodyLarge,
            color = onBackground.copy(alpha = 0.7f)
        )
        is TdApi.MessagePinMessage -> Text(
            stringResource(R.string.service_pinned_message),
            style = MaterialTheme.typography.bodyLarge,
            color = onBackground.copy(alpha = 0.7f)
        )
        else -> Text(stringResource(R.string.media_unsupported), style = MaterialTheme.typography.bodyLarge, color = onBackground.copy(alpha = 0.6f))
    }
}

/**
 * Renders a TDLib file (photo / video thumb / animation thumb) and progresses
 * through "request download" → "downloading" → "show image" without forcing
 * the parent to re-fetch the chat history. Reacts to UpdateFile events in real
 * time via TdClient.fileUpdates.
 */
@Composable
private fun DownloadingImage(
    initialFile: TdApi.File?,
    placeholderIcon: @Composable () -> Unit,
    placeholderLabel: String
) {
    if (initialFile == null) {
        ImagePlaceholder(placeholderIcon, placeholderLabel)
        return
    }
    var file by remember(initialFile.id) { mutableStateOf(initialFile) }

    LaunchedEffect(initialFile.id) {
        // Snapshot the latest file state from TDLib first. When a LazyColumn
        // row scrolls off-screen this LaunchedEffect is cancelled, so any
        // UpdateFile that fired during the off-screen window was missed.
        // When the row scrolls back in, `initialFile` is still the stale
        // reference captured by the parent recomposition; without this
        // snapshot the bubble would sit on the placeholder forever even
        // though the download has actually completed.
        runCatching { TdClient.getFile(initialFile.id) }.onSuccess { latest ->
            file = latest
        }
        val current = file
        if (!current.local.isDownloadingCompleted && !current.local.isDownloadingActive) {
            runCatching { TdClient.downloadFile(current.id) }
        }
        TdClient.fileUpdates.collect { updated ->
            if (updated.id == initialFile.id) file = updated
        }
    }

    val path = file.local?.path
    if (!path.isNullOrBlank() && file.local.isDownloadingCompleted) {
        AsyncImage(
            model = path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(260.dp)
                .heightIn(min = 120.dp, max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        ImagePlaceholder(placeholderIcon, placeholderLabel)
    }
}

@Composable
private fun ImagePlaceholder(
    icon: @Composable () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .heightIn(min = 140.dp, max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.SurfaceHi),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon()
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        }
    }
}

private fun formatHHmm(epochSec: Int): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSec.toLong() * 1000L))

private fun formatBytes(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digit = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(3)
    val value = size / Math.pow(1024.0, digit.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digit])
}

/**
 * Render a TdApi.FormattedText as a Compose Text with the right inline styles
 * (bold/italic/underline/strikethrough) and clickable URL spans. Each entity
 * coming from TDLib has an offset+length pointing into formatted.text; we
 * convert those into Compose SpanStyles and String annotations, then route
 * taps inside the URL ranges to ACTION_VIEW so OS picks a browser.
 *
 * Why bother with annotations: ClickableText delivers a tap by character
 * offset, not by entity. The annotation lookup at that offset is how we map
 * back from "user tapped here" to "user tapped this URL".
 */
@Composable
private fun FormattedTextRendering(
    formatted: TdApi.FormattedText,
    onBackground: androidx.compose.ui.graphics.Color,
    linkColor: androidx.compose.ui.graphics.Color
) {
    val text = formatted.text
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val annotated = androidx.compose.runtime.remember(text, formatted.entities) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            val len = text.length
            formatted.entities?.forEach { e ->
                val start = e.offset.coerceIn(0, len)
                val end = (e.offset + e.length).coerceIn(start, len)
                if (start == end) return@forEach
                when (val type = e.type) {
                    is TdApi.TextEntityTypeUrl -> {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = linkColor,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            ),
                            start, end
                        )
                        addStringAnnotation("URL", text.substring(start, end), start, end)
                    }
                    is TdApi.TextEntityTypeTextUrl -> {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = linkColor,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            ),
                            start, end
                        )
                        addStringAnnotation("URL", type.url, start, end)
                    }
                    is TdApi.TextEntityTypeEmailAddress -> {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = linkColor,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                            ),
                            start, end
                        )
                        addStringAnnotation("EMAIL", text.substring(start, end), start, end)
                    }
                    is TdApi.TextEntityTypePhoneNumber -> {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(color = linkColor),
                            start, end
                        )
                        addStringAnnotation("PHONE", text.substring(start, end), start, end)
                    }
                    is TdApi.TextEntityTypeMention -> {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(color = linkColor),
                            start, end
                        )
                    }
                    is TdApi.TextEntityTypeHashtag -> {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(color = linkColor),
                            start, end
                        )
                    }
                    is TdApi.TextEntityTypeBold ->
                        addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), start, end)
                    is TdApi.TextEntityTypeItalic ->
                        addStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), start, end)
                    is TdApi.TextEntityTypeUnderline ->
                        addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), start, end)
                    is TdApi.TextEntityTypeStrikethrough ->
                        addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough), start, end)
                    is TdApi.TextEntityTypeCode, is TdApi.TextEntityTypePre ->
                        addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), start, end)
                    else -> {}
                }
            }
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(color = onBackground),
        onClick = { offset ->
            // Try URL, then EMAIL, then PHONE annotation at that offset.
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                runCatching {
                    val raw = if (it.item.startsWith("http", ignoreCase = true)) it.item else "https://${it.item}"
                    val uri = android.net.Uri.parse(raw)
                    val host = uri.host?.lowercase().orEmpty()
                    val isTelegramLink = host == "t.me" || host == "telegram.me" || host == "telegram.dog"
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    // For t.me / telegram.me / telegram.dog links we force
                    // the intent to resolve inside CheipGram by setting the
                    // package — MainActivity's handleTmeDeeplink then opens
                    // the chat natively instead of bouncing to the system
                    // chooser (which would offer Telegram nativo + browser).
                    if (isTelegramLink) {
                        intent.setPackage(ctx.packageName)
                    }
                    runCatching { ctx.startActivity(intent) }.recoverCatching {
                        // Fallback: if for any reason our own activity refused
                        // (e.g. unusual t.me sub-path), drop setPackage and
                        // let the system chooser handle it instead of failing.
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                return@ClickableText
            }
            annotated.getStringAnnotations("EMAIL", offset, offset).firstOrNull()?.let {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${it.item}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return@ClickableText
            }
            annotated.getStringAnnotations("PHONE", offset, offset).firstOrNull()?.let {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${it.item}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    )
}

/**
 * Render a "Tema CheipGram" card inside a message bubble whenever the
 * message text contains a cheipgram://theme deeplink. Shows four colored
 * dots previewing the shared accent + background + bubble colors, the
 * label "Tema CheipGram", and an "Applica tema" button that writes the
 * full AppearancePrefs to DataStore.
 *
 * Apply is fire-and-forget on a local coroutine scope; AppSettings handles
 * its own thread context. We Toast the result so the user sees that the
 * tap did something even though the UI redraw is asynchronous.
 */
@Composable
private fun ThemeShareCard(prefs: com.secondream.cheipgram.settings.AppearancePrefs) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val accent = prefs.customAccentArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: MaterialTheme.colorScheme.primary
    val bg = prefs.customBgArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: MaterialTheme.colorScheme.background
    val myBubble = prefs.customMyBubbleArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: accent
    val othersBubble = prefs.customOthersBubbleArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Mini-preview: a chat-like row with the four colors stacked.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(othersBubble)
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(myBubble)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.theme_card_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.theme_card_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    scope.launch {
                        com.secondream.cheipgram.settings.AppSettings.applyAppearance(prefs)
                        android.widget.Toast.makeText(
                            ctx,
                            ctx.getString(R.string.theme_paste_success),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.theme_card_apply),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Strip of reaction chips below a message. One chip per emoji used on the
 * message; each shows the emoji + total count. The chip you've added
 * yourself is filled with the accent color and counts you. Tap to toggle.
 *
 * We only handle ReactionTypeEmoji — custom emoji and paid reactions are
 * still rendered as their fallback string but tapping them is a no-op for
 * now (the API requires custom_emoji_id which we don't expose yet).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReactionStrip(
    chatId: Long,
    messageId: Long,
    reactions: List<org.drinkless.tdlib.TdApi.MessageReaction>,
    onBackground: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showViewers by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (r in reactions) {
            val emoji = (r.type as? org.drinkless.tdlib.TdApi.ReactionTypeEmoji)?.emoji
            val label = emoji ?: "★"
            val chosen = r.isChosen
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (chosen) accent.copy(alpha = 0.25f)
                        else onBackground.copy(alpha = 0.08f)
                    )
                    .combinedClickable(
                        enabled = emoji != null,
                        onClick = {
                            // Single tap opens the "who reacted" sheet —
                            // the user explicitly asked for this gesture
                            // mapping. They expect to read who reacted
                            // first, then optionally remove their own.
                            showViewers = true
                        },
                        onLongClick = {
                            // Long-press toggles your own reaction (the
                            // quick action). If you already reacted with
                            // this emoji it's removed, otherwise added.
                            if (emoji != null) {
                                scope.launch {
                                    runCatching {
                                        if (chosen) com.secondream.cheipgram.td.TdClient
                                            .removeEmojiReaction(chatId, messageId, emoji)
                                        else com.secondream.cheipgram.td.TdClient
                                            .addEmojiReaction(chatId, messageId, emoji)
                                    }
                                }
                            }
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                if (r.totalCount > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        r.totalCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chosen) accent else onBackground.copy(alpha = 0.7f),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
            }
        }
    }
    if (showViewers) {
        com.secondream.cheipgram.ui.components.ReactionViewersSheet(
            chatId = chatId,
            messageId = messageId,
            onDismiss = { showViewers = false }
        )
    }
}

/**
 * Quote-style preview of the message we're replying to, rendered inline
 * inside the bubble (above the new message's content). Visually it's a
 * 3dp accent stripe + sender name + 1-line preview, same language we
 * already use in ReplyPreview in the input bar — so the same affordance
 * reads consistently whether you're seeing it before sending OR seeing
 * it on a delivered message.
 *
 * The TDLib MessageReplyToMessage carries either an inline `content`
 * snapshot (recent enough TDLib versions) or a `quote.text` slice the
 * sender hand-picked. If neither is available we fetch the full message
 * lazily via TdClient.getMessage as a best-effort fallback. When the
 * source message has been deleted server-side everything stays null
 * and we render a generic "Messaggio originale" placeholder so the user
 * still sees the bar instead of an awkward empty space.
 */
@Composable
private fun ReplyQuoteBar(
    replyTo: TdApi.MessageReplyToMessage,
    accent: androidx.compose.ui.graphics.Color,
    onBackground: androidx.compose.ui.graphics.Color,
) {
    // The quote text wins when present (manual user-picked quote slice);
    // otherwise we derive a preview from the inline content TDLib gave us,
    // and only fall back to the network as a last resort.
    val inlinePreview = remember(replyTo) { previewFromContentOrQuote(replyTo) }
    var fetchedPreview by remember(replyTo.messageId) { mutableStateOf<String?>(null) }
    var fetchedSender by remember(replyTo.messageId) { mutableStateOf<String?>(null) }

    LaunchedEffect(replyTo.chatId, replyTo.messageId) {
        if (inlinePreview != null) return@LaunchedEffect
        val msg = runCatching {
            TdClient.getMessage(replyTo.chatId, replyTo.messageId)
        }.getOrNull() ?: return@LaunchedEffect
        fetchedPreview = previewFromContent(msg.content)
        fetchedSender = resolveReplySenderName(msg.senderId, replyTo.origin)
    }

    val senderName = resolveReplySenderName(null, replyTo.origin)
        ?: fetchedSender
        ?: "Messaggio"
    val preview = inlinePreview ?: fetchedPreview ?: "Messaggio originale"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(onBackground.copy(alpha = 0.06f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = onBackground.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Pull a 1-line preview out of the inline reply-content snapshot if TDLib
 * carried one. Returns null if it didn't, so the caller knows to fetch the
 * source message instead. We deliberately don't read the manually-picked
 * `quote` slice here — its nested shape (TextQuote.text vs FormattedText)
 * varies between TDLib releases, and the inline content path covers the
 * common case while staying schema-stable.
 */
private fun previewFromContentOrQuote(replyTo: TdApi.MessageReplyToMessage): String? {
    val inline = replyTo.content ?: return null
    return previewFromContent(inline)
}

/**
 * Short text preview for any [TdApi.MessageContent]. Mirrors the mapping
 * NotificationHelper / ForwardChatPickerSheet use so the user sees the
 * same shorthand everywhere replies and notifications appear.
 */
private fun previewFromContent(content: TdApi.MessageContent): String = when (content) {
    is TdApi.MessageText -> content.text.text.take(120)
    is TdApi.MessagePhoto -> "📷 Foto" +
        (content.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    is TdApi.MessageVideo -> "🎬 Video"
    is TdApi.MessageVoiceNote -> "🎙 Nota vocale"
    is TdApi.MessageDocument -> "📎 ${content.document.fileName}"
    is TdApi.MessageAnimation -> "GIF"
    is TdApi.MessageSticker -> content.sticker.emoji.ifBlank { "Sticker" } + " Sticker"
    is TdApi.MessageAudio -> "🎵 " + content.audio.title.ifBlank { content.audio.fileName.ifBlank { "Audio" } }
    is TdApi.MessageLocation -> "📍 Posizione"
    is TdApi.MessageContact -> "👤 Contatto"
    else -> "Messaggio"
}

/**
 * Best-effort sender-name lookup for a quoted reply. We try the message's
 * own senderId first (when the caller fetched the full message), then fall
 * back to the [TdApi.MessageOrigin] TDLib stores on the reply target so the
 * name still resolves even if the original message has been deleted.
 */
private fun resolveReplySenderName(
    senderId: TdApi.MessageSender?,
    origin: TdApi.MessageOrigin?
): String? {
    val fromSender = when (senderId) {
        is TdApi.MessageSenderUser -> {
            val u = TdClient.getCachedUser(senderId.userId)
            "${u?.firstName.orEmpty()} ${u?.lastName.orEmpty()}".trim()
                .ifBlank { null }
        }
        is TdApi.MessageSenderChat -> TdClient.getCachedChat(senderId.chatId)?.title
        else -> null
    }
    if (!fromSender.isNullOrBlank()) return fromSender
    return when (origin) {
        is TdApi.MessageOriginUser -> {
            val u = TdClient.getCachedUser(origin.senderUserId)
            "${u?.firstName.orEmpty()} ${u?.lastName.orEmpty()}".trim()
                .ifBlank { null }
        }
        is TdApi.MessageOriginChat -> TdClient.getCachedChat(origin.senderChatId)?.title
        is TdApi.MessageOriginHiddenUser -> origin.senderName.ifBlank { null }
        is TdApi.MessageOriginChannel -> TdClient.getCachedChat(origin.chatId)?.title
        else -> null
    }
}
