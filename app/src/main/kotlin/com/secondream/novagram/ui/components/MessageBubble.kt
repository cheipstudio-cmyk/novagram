@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.secondream.novagram.ui.components
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Image
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
import com.secondream.novagram.R
import androidx.compose.ui.res.stringResource
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.theme.Ink
import com.secondream.novagram.ui.theme.bubbleFillFor
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: TdApi.Message,
    showSender: Boolean = false,
    /** Owner/admin label for this message's sender, e.g. "Proprietario" or
     *  "Amministratore", shown as a small chip next to the sender name in
     *  groups. Null = no badge (regular member or not a group). */
    adminLabel: String? = null,
    onLongPress: (TdApi.Message) -> Unit = {},
    onMediaTap: (String) -> Unit = {},
    onSwipeReply: (TdApi.Message) -> Unit = {},
    /** Fired when the user taps the sender avatar shown next to non-mine
     *  bubbles in group chats. Receives the sender's userId so the parent
     *  can open a profile sheet / start a chat. No-op by default for
     *  callers that don't need profile-on-tap. */
    onAvatarClick: (userId: Long) -> Unit = {},
    /** Fired when the user taps a Telegram link in the message text that
     *  points to a message in THIS SAME chat — caller scrolls the list
     *  to that message. Return true if it could jump (message is in the
     *  in-memory window), false to let the default Intent flow handle
     *  it as a fallback. */
    onJumpToMessage: (Long) -> Boolean = { false },
    /** Fired when the user taps a Telegram (t.me / telegram.me / tg:)
     *  link that does NOT point into the current chat. The caller
     *  resolves the username/invite via TDLib and opens the target chat
     *  INSIDE Nova — never bouncing out to a browser. */
    onOpenTelegramLink: (android.net.Uri) -> Unit = {},
    /**
     * Bumped by the parent each time TDLib pushes a new InteractionInfo
     * or new content for this message. This is the PRIMARY recompose
     * trigger for in-place mutations: TdApi.Message is marked stable
     * via the compose stability config, so strong-skipping would skip
     * the bubble body when the Message reference is reused (which is
     * how UpdateMessageContent / UpdateMessageInteractionInfo deliver
     * changes — mutate then re-assign same ref). Bumping this Int on
     * every such mutation forces strong-skipping to recompose because
     * the param value changed. If you add a new mutation path on
     * TdApi.Message, you MUST bump interactionRevisions[id] alongside
     * or the bubble will stall on stale data.
     */
    interactionRevision: Int = 0,
    /**
     * Query string from the in-chat search bar. When non-null all
     * case-insensitive substring matches in the message body get a
     * highlighted background so the user can spot what they searched for
     * as they cycle through results with the up/down arrows. Null when
     * search is closed.
     */
    highlightQuery: String? = null,
    /**
     * True for the brief window after a jumpToMessage (reply tap, pinned
     * tap, search arrows, deep link) lands on this message. The bubble
     * paints an accent overlay that fades to transparent over ~1s so the
     * user can see WHICH message they landed on — replaces the previous
     * animated scroll which jittered during history-loading.
     */
    flashing: Boolean = false,
    /**
     * Fired when the user taps an inline-keyboard button attached to a
     * bot message. The String is a stable per-button key (row:col:label)
     * the parent uses to drive [pendingInlineButtonKey] so the right
     * button shows its spinner while the callback round-trips through
     * the bot. The TdApi.InlineKeyboardButton carries the action — see
     * its `type` field for routing (Callback / Url / SwitchInline /
     * etc.).
     */
    onInlineButton: (TdApi.InlineKeyboardButton, String) -> Unit = { _, _ -> },
    /**
     * Per-bubble pending state for the inline keyboard. Non-null while
     * a callback round-trip is in flight; the matching button renders
     * a spinner in place of its label. Cleared by the parent once the
     * response (or error) arrives.
     */
    pendingInlineButtonKey: String? = null,
    /**
     * Reactive outbox-read marker (chat.lastReadOutboxMessageId) handed
     * down by the parent so the ✓✓ "read" tick on the user's own messages
     * in a 1-to-1 chat flips the INSTANT the peer reads — the parent
     * re-resolves this on every TdClient.chatUpdates emission (which fires
     * on UpdateChatReadOutbox). -1 = not supplied: callers that don't pass
     * it fall back to the one-shot cached value (old, non-reactive path).
     */
    readOutboxMaxId: Long = -1L
) {
    // Read the param so the composable observes it; the value itself isn't
    // used directly anywhere — it's purely a recompose trigger.
    @Suppress("UNUSED_EXPRESSION") interactionRevision
    val mine = message.isOutgoing
    val appearance by AppSettings.appearance.collectAsState(
        initial = com.secondream.novagram.settings.AppearancePrefs()
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

    // Register this message's media file ids with the global transfer tracker
    // so the persistent download panel can jump straight to the message a file
    // belongs to when the user taps its row. Cheap: a few map puts per bubble.
    LaunchedEffect(message.id) {
        val ids = when (val c = message.content) {
            is TdApi.MessagePhoto -> c.photo.sizes.map { it.photo.id }
            is TdApi.MessageVideo -> listOfNotNull(c.video.video.id, c.video.thumbnail?.file?.id)
            is TdApi.MessageDocument -> listOf(c.document.document.id)
            is TdApi.MessageAudio -> listOf(c.audio.audio.id)
            is TdApi.MessageVoiceNote -> listOf(c.voiceNote.voice.id)
            is TdApi.MessageAnimation -> listOf(c.animation.animation.id)
            else -> emptyList()
        }
        ids.forEach { fid ->
            com.secondream.novagram.transfer.TransferTracker.registerLocation(
                fid, message.chatId, message.id
            )
        }
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

    // Animated flash overlay for jump-to-message landing. When `flashing`
    // toggles true the accent paints behind the row at ~32% alpha and
    // animates back to transparent over ~900ms; the duration is chosen so
    // the user has time to register WHERE we scrolled them to without the
    // colour lingering. Easing is Linear here on purpose — a curved decay
    // looked too "designed", a hard linear fade reads as a quick blink.
    val flashColorBase = MaterialTheme.colorScheme.primary
    val flashAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (flashing) 0.32f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (flashing) 120 else 900,
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = "flash-fade"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (flashAlpha > 0f) Modifier.background(flashColorBase.copy(alpha = flashAlpha))
                else Modifier
            )
            .pointerInput("swipe-${message.id}", appearance.swapSwipeReply) {
                // Swipe direction is settings-controlled. By default
                // (swapSwipeReply=false) we accumulate positive deltas
                // → drag the bubble right → fire reply when offset
                // exceeds +triggerPx, matching official Telegram. When
                // the user flips the toggle in Settings → "Inverti
                // swipe per rispondere" we accept NEGATIVE deltas
                // instead, drag the bubble left, fire at -triggerPx.
                // The pointerInput key includes the flag so toggling
                // it in settings recreates the gesture detector with
                // the new range — without the key the existing
                // detector would keep the old range until the user
                // re-entered the chat.
                val swap = appearance.swapSwipeReply
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
                        val proposed = swipeOffset + delta
                        swipeOffset = if (swap) proposed.coerceIn(-maxPx, 0f)
                                      else proposed.coerceIn(0f, maxPx)
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
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var longPressFired = false
                    // 300ms timer fires the long press if the user hasn't lifted
                    // or moved past touchSlop by then. waitForUpOrCancellation()
                    // returns when either of those happens, at which point we
                    // cancel the pending timer.
                    val timerJob = tapScope.launch {
                        kotlinx.coroutines.delay(300L)
                        haptic.performHapticFeedback(
                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                        )
                        longPressFired = true
                        // Consume the original DOWN so any child gesture
                        // detector (notably ClickableText used by
                        // FormattedTextRendering for URL / email / phone
                        // annotation taps) treats the gesture as already
                        // handled and skips its onClick when the user
                        // eventually lifts the finger. Without this consume,
                        // long-pressing a message that contains a link fires
                        // BOTH the bubble's action sheet AND opens the link
                        // — exactly the double-action bug Eugenio kept
                        // hitting on long press over message URLs.
                        down.consume()
                        onLongPress(message)
                    }
                    try {
                        val up = waitForUpOrCancellation()
                        // Belt-and-suspenders: also consume the UP event so
                        // any downstream detector that only checks consumed
                        // state on UP (rather than DOWN) still treats the
                        // gesture as handled.
                        if (longPressFired) up?.consume()
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
                com.secondream.novagram.ui.icons.PhosphorIcons.Reply,
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
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start
        ) {
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
                                    if (!path.isNullOrBlank() && (latest.local.isDownloadingCompleted || runCatching { java.io.File(path).exists() }.getOrDefault(false))) {
                                        com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = false
                                        onMediaTap(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    it.isNotBlank() && (done.local.isDownloadingCompleted || runCatching { java.io.File(it).exists() }.getOrDefault(false))
                                                }?.let { p ->
                                                    com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = false
                                                    onMediaTap(p)
                                                }
                                            }
                                    }
                                }
                                is TdApi.MessageVideo -> {
                                    val f = c.video.video
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    if (!path.isNullOrBlank() && (latest.local.isDownloadingCompleted || runCatching { java.io.File(path).exists() }.getOrDefault(false))) {
                                        // Open in the embedded ExoPlayer-backed
                                        // viewer rather than firing an external
                                        // Intent — keeps the user inside the app.
                                        com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = true
                                        onMediaTap(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    it.isNotBlank() && (done.local.isDownloadingCompleted || runCatching { java.io.File(it).exists() }.getOrDefault(false))
                                                }?.let { p ->
                                                    com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = true
                                                    onMediaTap(p)
                                                }
                                            }
                                    }
                                }
                                is TdApi.MessageAnimation -> {
                                    val f = c.animation.animation
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    if (!path.isNullOrBlank() && (latest.local.isDownloadingCompleted || runCatching { java.io.File(path).exists() }.getOrDefault(false))) {
                                        com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = true
                                        onMediaTap(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    it.isNotBlank() && (done.local.isDownloadingCompleted || runCatching { java.io.File(it).exists() }.getOrDefault(false))
                                                }?.let { p ->
                                                    com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = true
                                                    onMediaTap(p)
                                                }
                                            }
                                    }
                                }
                                is TdApi.MessageDocument -> {
                                    val f = c.document.document
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    val open: (String) -> Unit = { p ->
                                        com.secondream.novagram.util.FileUtils.openDocument(
                                            ctx, p, c.document.mimeType, c.document.fileName
                                        )
                                    }
                                    if (!path.isNullOrBlank() && (latest.local.isDownloadingCompleted || runCatching { java.io.File(path).exists() }.getOrDefault(false))) {
                                        open(path)
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    it.isNotBlank() && (done.local.isDownloadingCompleted || runCatching { java.io.File(it).exists() }.getOrDefault(false))
                                                }?.let(open)
                                            }
                                    }
                                }
                                is TdApi.MessageAudio -> {
                                    val f = c.audio.audio
                                    val latest = runCatching { TdClient.getFile(f.id) }
                                        .getOrNull() ?: f
                                    val path = latest.local?.path
                                    if (!path.isNullOrBlank() && (latest.local.isDownloadingCompleted || runCatching { java.io.File(path).exists() }.getOrDefault(false))) {
                                        com.secondream.novagram.util.FileUtils.openDocument(
                                            ctx, path, c.audio.mimeType, c.audio.fileName
                                        )
                                    } else {
                                        runCatching { TdClient.downloadFile(latest.id) }
                                            .onSuccess { done ->
                                                done.local?.path?.takeIf {
                                                    it.isNotBlank() && (done.local.isDownloadingCompleted || runCatching { java.io.File(it).exists() }.getOrDefault(false))
                                                }?.let { p ->
                                                    com.secondream.novagram.util.FileUtils.openDocument(
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (adminLabel != null) {
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    adminLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
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
                    onBackground = fill.onBackground,
                    onTap = {
                        // Only jump if the quoted message is in this chat —
                        // cross-chat replies (forwarded with reply context)
                        // would need a different navigation path which we
                        // don't have wired in this scope. Falling through
                        // silently is acceptable since the rt.chatId of a
                        // normal in-chat reply always matches message.chatId.
                        if (rt.chatId == message.chatId) {
                            onJumpToMessage(rt.messageId)
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
            }
            MessageContent(message, fill.onBackground, onJumpToMessage, onOpenTelegramLink, highlightQuery, interactionRevision)
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
                    val effectiveReadMax =
                        if (readOutboxMaxId >= 0L) readOutboxMaxId
                        else (cachedChat?.lastReadOutboxMessageId ?: 0L)
                    val isRead = effectiveReadMax >= message.id
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
        // INLINE KEYBOARD: rendered as a sibling Column to the bubble,
        // so its background + rounded corners stay separate. Only the
        // InlineKeyboard variant is surfaced here — ShowKeyboard /
        // ForceReply / RemoveKeyboard are different UX surfaces (the
        // custom-keyboard replaces the user's keyboard, ForceReply
        // would intercept the input bar) that we don't support yet.
        val markup = message.replyMarkup
        if (markup is TdApi.ReplyMarkupInlineKeyboard && markup.rows.isNotEmpty()) {
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(4.dp)
            )
            com.secondream.novagram.ui.components.InlineKeyboard(
                markup = markup,
                pendingButtonKey = pendingInlineButtonKey,
                onClick = onInlineButton
            )
        }
        }
    }
    }
}

@Composable
private fun MessageContent(
    message: TdApi.Message,
    onBackground: androidx.compose.ui.graphics.Color,
    onJumpToMessage: (Long) -> Boolean = { false },
    onOpenTelegramLink: (android.net.Uri) -> Unit = {},
    highlightQuery: String? = null,
    /**
     * Bumped by the parent (via MessageBubble) every time TDLib pushes
     * a new content / reaction / view-count for this message. The value
     * is just an Int — its purpose is to act as a stable, value-compared
     * parameter that Compose's strong skipping CANNOT skip on. Without
     * this, an in-place mutation of `message.content` would not force
     * MessageContent to recompose: the parameter list `(message,
     * onBackground, ...)` is unchanged by reference, strong skipping
     * triggers, and the bubble keeps rendering the stale content until
     * the user backs out of the chat. Bumping this int on every
     * UpdateMessageContent forces the recomposition the in-place
     * mutation needs in order to be observable.
     */
    contentRevision: Int = 0
) {
    when (val c = message.content) {
        is TdApi.MessageText -> {
            val rawText = c.text.text
            val themePrefs = remember(rawText) {
                if (rawText.contains("nova://theme?data=")) {
                    com.secondream.novagram.ui.screens.parseThemeJson(rawText)
                } else null
            }
            if (themePrefs != null) {
                ThemeShareCard(prefs = themePrefs)
            } else {
                FormattedTextRendering(
                    formatted = c.text,
                    onBackground = onBackground,
                    linkColor = MaterialTheme.colorScheme.primary,
                    currentChatId = message.chatId,
                    onJumpToMessage = onJumpToMessage,
                    onOpenTelegramLink = onOpenTelegramLink,
                    highlightQuery = highlightQuery
                )
            }
        }
        is TdApi.MessagePhoto -> {
            val photo = c.photo.sizes.lastOrNull()?.photo
            DownloadingImage(
                initialFile = photo,
                placeholderIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Image, null, tint = Ink.Muted) },
                placeholderLabel = stringResource(R.string.media_photo)
            )
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageVideo -> {
            // We track the main video file's progress on top of the
            // thumbnail. Three visual states:
            //  - completed: show the play icon overlay
            //  - downloading (or auto-download is on): show a circular
            //    progress with % overlay
            //  - idle (auto-download off, user hasn't tapped): show a
            //    download icon overlay — tapping the bubble kicks the
            //    download via the existing onMediaTap path.
            val thumb = c.video.thumbnail?.file
            val videoFile = c.video.video
            var liveFile by remember(videoFile.id) {
                mutableStateOf(videoFile)
            }
            LaunchedEffect(videoFile.id) {
                runCatching { TdClient.getFile(videoFile.id) }.onSuccess { liveFile = it }
                TdClient.fileUpdates.collect { upd ->
                    if (upd.id == videoFile.id) liveFile = upd
                }
            }
            Box(contentAlignment = Alignment.Center) {
                DownloadingImage(
                    initialFile = thumb,
                    placeholderIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Play, null, tint = Ink.Cream) },
                    placeholderLabel = stringResource(R.string.media_video),
                    alwaysFetch = true,
                    miniThumb = c.video.minithumbnail
                )
                // Overlay state-aware progress / play / download icon.
                // We treat the video as ready-to-play when TDLib reports it
                // downloaded OR when the local path exists on disk — the
                // latter covers our own freshly-uploaded videos (not
                // "downloaded" but available locally).
                val vidPath = liveFile.local?.path
                val vidOnDisk = !vidPath.isNullOrBlank() &&
                    runCatching { java.io.File(vidPath).exists() }.getOrDefault(false)
                when {
                    liveFile.local.isDownloadingCompleted || vidOnDisk -> {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.Play,
                                null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    liveFile.local.isDownloadingActive -> {
                        val total = liveFile.size.coerceAtLeast(1).toFloat()
                        val done = liveFile.local.downloadedSize.coerceAtLeast(0).toFloat()
                        val progress = (done / total).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                progress = { progress },
                                strokeWidth = 3.dp,
                                color = androidx.compose.ui.graphics.Color.White,
                                trackColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                    else -> {
                        // Idle: tap the bubble to start the download (the
                        // outer onMediaTap branch already calls
                        // TdClient.downloadFile for non-completed video).
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.DownloadSimple,
                                null,
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageAnimation -> {
            // GIF: auto-playing, looping, muted — like Telegram.
            InlineGifPlayer(
                animationFile = c.animation.animation,
                thumbFile = c.animation.thumbnail?.file,
                width = c.animation.width,
                height = c.animation.height
            )
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyLarge, color = onBackground)
            }
        }
        is TdApi.MessageDocument -> {
            // Documents with an image mime (png/jpg/webp/heic) — common for
            // forwarded screenshots or files shared "as document" — get the
            // photo treatment: download + render inline so the user actually
            // sees the picture once it's on disk, instead of being stuck
            // with a generic file chip.
            val mime = c.document.mimeType.lowercase()
            val isImage = mime.startsWith("image/") && !mime.contains("gif")
            if (isImage) {
                DownloadingImage(
                    initialFile = c.document.document,
                    placeholderIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Image, null, tint = Ink.Muted) },
                    placeholderLabel = c.document.fileName.ifBlank {
                        stringResource(R.string.media_photo)
                    }
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(com.secondream.novagram.ui.icons.PhosphorIcons.FileText, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
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
                Icon(com.secondream.novagram.ui.icons.PhosphorIcons.FileAudio, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
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
        else -> {
            // Service / system messages (joined, left, pinned, renamed, video
            // chat, …) carry no text body. Render the name-aware description
            // ("X si è unito al gruppo"), centered + faded. Any content type we
            // don't draw inline yet (poll, location, contact, video note, call,
            // …) falls back to its labelled shorthand rather than a bare
            // "non supportato".
            val service = com.secondream.novagram.td.TdClient.serviceMessageText(message)
            if (service != null) {
                Text(
                    service,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onBackground.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    com.secondream.novagram.td.TdClient.buildPreview(message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = onBackground.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * Auto-playing, looping, muted GIF player for MessageAnimation bubbles.
 * Mirrors Telegram: the GIF plays in place. Downloads the animation file
 * on first composition (gated by the auto-download preference — when off,
 * shows the thumbnail with a tap-to-play overlay). Off-screen disposal in
 * the LazyColumn releases the ExoPlayer automatically.
 */
@Composable
private fun InlineGifPlayer(
    animationFile: TdApi.File,
    thumbFile: TdApi.File?,
    width: Int,
    height: Int
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val appearance by com.secondream.novagram.settings.AppSettings.appearance
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs(autoDownloadMedia = false))
    var localPath by remember(animationFile.id) {
        mutableStateOf(
            animationFile.local?.path?.takeIf { p ->
                p.isNotBlank() && (
                    animationFile.local.isDownloadingCompleted ||
                    runCatching { java.io.File(p).exists() }.getOrDefault(false)
                )
            }
        )
    }
    var requested by remember(animationFile.id) { mutableStateOf(false) }
    val aspect = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 1.5f

    // Trigger download only if we don't already have the file locally.
    LaunchedEffect(animationFile.id, requested, appearance.autoDownloadMedia) {
        if (localPath == null && (appearance.autoDownloadMedia || requested)) {
            val f = runCatching { TdClient.downloadFile(animationFile.id) }.getOrNull()
            val p = f?.local?.path
            if (!p.isNullOrBlank()) localPath = p
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .aspectRatio(aspect.coerceIn(0.6f, 2.2f))
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.Surface),
        contentAlignment = Alignment.Center
    ) {
        val path = localPath
        if (path != null) {
            AndroidView(
                factory = { c ->
                    androidx.media3.ui.PlayerView(c).apply {
                        useController = false
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        val exo = androidx.media3.exoplayer.ExoPlayer.Builder(c).build().apply {
                            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
                            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                            volume = 0f
                            playWhenReady = true
                            prepare()
                        }
                        player = exo
                        tag = exo
                    }
                },
                onRelease = { view ->
                    (view.tag as? androidx.media3.exoplayer.ExoPlayer)?.release()
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
            // Small GIF marker.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("GIF", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            // Thumbnail + tap to play (when auto-download is off).
            DownloadingImage(
                initialFile = thumbFile,
                placeholderIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Image, null, tint = Ink.Muted) },
                placeholderLabel = stringResource(R.string.media_gif)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { requested = true },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("▶  GIF", style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
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
    placeholderLabel: String,
    alwaysFetch: Boolean = false,
    miniThumb: TdApi.Minithumbnail? = null
) {
    // Instant blurry preview decoded from the embedded minithumbnail (a few
    // hundred bytes shipped inline with the message). Shown until the real
    // thumb arrives — this is what makes a video show its first frame right
    // away even with auto-download off.
    val mini = remember(miniThumb) {
        miniThumb?.data?.takeIf { it.isNotEmpty() }?.let { d ->
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(d, 0, d.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    if (initialFile == null) {
        if (mini != null) MiniThumb(mini) else ImagePlaceholder(placeholderIcon, placeholderLabel)
        return
    }
    var file by remember(initialFile.id) { mutableStateOf(initialFile) }
    val appearance by com.secondream.novagram.settings.AppSettings.appearance
        // Default to autoDownloadMedia=FALSE until DataStore delivers the
        // real value. Previously the initial defaulted to true, so every
        // image fired a download on its first frame — before the user's
        // "off" setting had loaded — which is exactly the "still downloads
        // while scrolling" bug. Biasing the pre-load value to false means
        // we never download until we KNOW auto-download is on.
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs(autoDownloadMedia = false))
    val autoDownload = appearance.autoDownloadMedia
    // Tracks whether the user has explicitly asked for this file via the
    // tap-to-download placeholder. Once requested, we keep downloading
    // (and don't fall back to placeholder) even if autoDownload is off
    // — the request is per-file and sticky for this composition.
    var userRequested by remember(initialFile.id) { mutableStateOf(false) }

    LaunchedEffect(initialFile.id) {
        runCatching { TdClient.getFile(initialFile.id) }.onSuccess { latest ->
            file = latest
        }
        TdClient.fileUpdates.collect { updated ->
            if (updated.id == initialFile.id) file = updated
        }
    }

    // Kick off the download when either (a) auto-download is on and the
    // file isn't already done/in-flight, or (b) the user explicitly asked
    // via the placeholder tap. Re-runs when those conditions change so a
    // tap from the placeholder triggers TDLib immediately.
    LaunchedEffect(initialFile.id, autoDownload, userRequested) {
        val current = file
        val pathNow = current.local?.path
        val alreadyOnDisk = !pathNow.isNullOrBlank() &&
            runCatching { java.io.File(pathNow).exists() }.getOrDefault(false)
        if ((autoDownload || userRequested || alwaysFetch) &&
            !current.local.isDownloadingCompleted &&
            !current.local.isDownloadingActive &&
            !alreadyOnDisk) {
            runCatching { TdClient.downloadFile(current.id) }
        }
    }

    val path = file.local?.path
    // A file is viewable if TDLib finished downloading it OR if the local
    // path exists on disk — which covers our own freshly-uploaded media
    // (sent photos/videos): the local copy is still on disk, but
    // isDownloadingCompleted is false because we didn't download, we
    // uploaded. Previously these were shown with a "tap to download"
    // placeholder even though the file was sitting right there.
    val locallyAvailable = !path.isNullOrBlank() && runCatching { java.io.File(path).exists() }.getOrDefault(false)
    val completed = file.local.isDownloadingCompleted || locallyAvailable
    val downloading = file.local.isDownloadingActive
    when {
        !path.isNullOrBlank() && completed -> {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(260.dp)
                    .heightIn(min = 120.dp, max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
        // Real thumb not ready yet but we have the inline minithumbnail →
        // show the instant blurry preview (with any caller overlay on top)
        // instead of a blank placeholder.
        mini != null -> MiniThumb(mini)
        // Show a progress overlay while bytes flow in (covers both auto
        // and user-initiated downloads).
        downloading || (autoDownload && !completed) || userRequested -> {
            DownloadProgressPlaceholder(
                file = file,
                placeholderIcon = placeholderIcon,
                placeholderLabel = placeholderLabel
            )
        }
        else -> {
            // Auto-download is off AND the user hasn't requested it yet:
            // show a tap-to-download placeholder. Tap flips userRequested
            // which triggers the LaunchedEffect above.
            TapToDownloadPlaceholder(
                file = file,
                onTap = { userRequested = true }
            )
        }
    }
}

/** Renders a decoded minithumbnail bitmap at the standard media bubble size. */
@Composable
private fun MiniThumb(bmp: androidx.compose.ui.graphics.ImageBitmap) {
    androidx.compose.foundation.Image(
        bitmap = bmp,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .width(260.dp)
            .heightIn(min = 120.dp, max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
    )
}

/**
 * Placeholder shown while a media file is actively downloading. Renders
 * the supplied icon centred plus a thin progress indicator + percentage
 * label so the user knows TDLib is working. Reads file.local.downloadedSize
 * against file.size to derive progress (TDLib mutates these fields in
 * place as bytes arrive; our caller already collects fileUpdates and
 * re-passes the latest snapshot via [file]).
 */
@Composable
private fun DownloadProgressPlaceholder(
    file: TdApi.File,
    placeholderIcon: @Composable () -> Unit,
    placeholderLabel: String
) {
    val total = file.size.coerceAtLeast(1).toFloat()
    val downloaded = file.local.downloadedSize.coerceAtLeast(0).toFloat()
    val progress = (downloaded / total).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(260.dp)
            .heightIn(min = 140.dp, max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            placeholderIcon()
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .width(180.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(progress * 100).toInt()}% · ${formatBytes(downloaded.toLong())} / ${formatBytes(total.toLong())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Placeholder shown when auto-download is off and the user hasn't yet
 * requested this file. Tap fires [onTap] which the caller wires to flip
 * `userRequested = true` and start the download.
 */
@Composable
private fun TapToDownloadPlaceholder(
    file: TdApi.File,
    onTap: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .heightIn(min = 140.dp, max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.DownloadSimple,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                androidx.compose.ui.res.stringResource(
                    com.secondream.novagram.R.string.media_tap_to_download
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (file.size > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    formatBytes(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    linkColor: androidx.compose.ui.graphics.Color,
    currentChatId: Long = 0L,
    onJumpToMessage: (Long) -> Boolean = { false },
    onOpenTelegramLink: (android.net.Uri) -> Unit = {},
    highlightQuery: String? = null
) {
    val text = formatted.text
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val highlightBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    val annotated = androidx.compose.runtime.remember(text, formatted.entities, highlightQuery) {
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
                        // @username mentions are how Telegram users
                        // *think* of t.me links — they expect tapping
                        // @claude_codes to open the chat, same as
                        // tapping t.me/claude_codes. The substring
                        // strip drops the leading "@" so the URL we
                        // synthesise resolves cleanly through
                        // openTelegramLink.
                        val username = text.substring(start, end).trimStart('@')
                        if (username.isNotBlank()) {
                            addStringAnnotation(
                                "URL",
                                "https://t.me/$username",
                                start, end
                            )
                        }
                    }
                    is TdApi.TextEntityTypeMentionName -> {
                        // Mention pointing at a user WITHOUT a public
                        // username (Telegram still renders the display
                        // name as a tappable mention). The entity
                        // carries a userId; we encode it in a tg:// URL
                        // so openTelegramLink can resolve via TDLib.
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(color = linkColor),
                            start, end
                        )
                        addStringAnnotation(
                            "URL",
                            "tg://user?id=${type.userId}",
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
            // Search highlight: paint a soft accent background behind
            // every case-insensitive occurrence of the active search
            // query. Done as a post-pass so it stacks on top of bold /
            // italic / link styles without overwriting them. Empty /
            // null query short-circuits — zero-cost when search is
            // closed.
            val q = highlightQuery?.trim().orEmpty()
            if (q.isNotEmpty() && q.length <= text.length) {
                val haystack = text.lowercase()
                val needle = q.lowercase()
                var from = 0
                while (true) {
                    val hit = haystack.indexOf(needle, from)
                    if (hit < 0) break
                    val hitEnd = hit + needle.length
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(background = highlightBg),
                        hit, hitEnd
                    )
                    from = hitEnd
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
                    val raw = if (it.item.startsWith("http", ignoreCase = true) || it.item.startsWith("tg:", ignoreCase = true)) it.item else "https://${it.item}"
                    val uri = android.net.Uri.parse(raw)
                    val host = uri.host?.lowercase().orEmpty()
                    val scheme = uri.scheme?.lowercase().orEmpty()
                    val isTelegramLink = scheme == "tg" ||
                        host == "t.me" || host == "telegram.me" || host == "telegram.dog"

                    // Same-chat shortcut: if this is a t.me/<chat>/<msgId>
                    // link pointing INTO the current chat, hand the message
                    // id to the screen so it can scroll the LazyColumn
                    // instead of dispatching an Intent. Without this every
                    // tap on an in-chat link routed through Android →
                    // MainActivity.onNewIntent → nav.navigate(chat/...),
                    // stacking a fresh ChatScreen each time and forcing
                    // the user to press Back N times to escape.
                    if (isTelegramLink && currentChatId != 0L) {
                        val targetMsgId = parseSameChatMessageLink(uri, currentChatId)
                        if (targetMsgId != null && onJumpToMessage(targetMsgId)) {
                            return@ClickableText
                        }
                    }

                    // Any other Telegram link: resolve + open the chat
                    // INSIDE Nova. We hand the Uri to the caller
                    // (ChatScreen) which uses TDLib to look up the
                    // username / invite and navigates in-app. Never an
                    // Intent, never the browser.
                    if (isTelegramLink) {
                        onOpenTelegramLink(uri)
                        return@ClickableText
                    }

                    // Non-Telegram URL: hand off to the system as before.
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
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
 * Render a "Tema Nova" card inside a message bubble whenever the
 * message text contains a nova://theme deeplink. Shows four colored
 * dots previewing the shared accent + background + bubble colors, the
 * label "Tema Nova", and an "Applica tema" button that writes the
 * full AppearancePrefs to DataStore.
 *
 * Apply is fire-and-forget on a local coroutine scope; AppSettings handles
 * its own thread context. We Toast the result so the user sees that the
 * tap did something even though the UI redraw is asynchronous.
 */
@Composable
private fun ThemeShareCard(prefs: com.secondream.novagram.settings.AppearancePrefs) {
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
                        // Don't auto-apply: that would overwrite the
                        // user's current theme silently. Instead add
                        // the imported theme to their saved list so it
                        // appears as a tappable row in Settings → Temi
                        // salvati. The toast confirms the addition;
                        // the user activates manually when they're
                        // ready.
                        com.secondream.novagram.settings.AppSettings.importAppearanceAsSavedTheme(
                            appearance = prefs,
                            baseName = ctx.getString(R.string.theme_imported_default_name)
                        )
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
    // showViewers state removed in v0.10.34: see the comment at the end
    // of this composable for why the inline viewers sheet was dropped.
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
                            // Single tap toggles your own reaction on
                            // this chip — direct, no confirmation
                            // sheet. If you already reacted with this
                            // emoji the chip removes it; otherwise it
                            // adds yours to whatever the chip already
                            // shows. Matches the official Telegram
                            // gesture and the simplest mental model:
                            // "tap = same as tapping the chip in real
                            // Telegram = on/off for me".
                            if (emoji != null) {
                                scope.launch {
                                    runCatching {
                                        if (chosen) com.secondream.novagram.td.TdClient
                                            .removeEmojiReaction(chatId, messageId, emoji)
                                        else com.secondream.novagram.td.TdClient
                                            .addEmojiReaction(chatId, messageId, emoji)
                                    }
                                }
                            }
                        },
                        onLongClick = {
                            // No-op. We deliberately don't open a separate
                            // ReactionViewersSheet from a long-press here:
                            // the "who reacted with what" list now lives
                            // INSIDE MessageActionsSheet (long-press the
                            // message itself), so a parallel surface from
                            // the chip would be redundant and easy to
                            // hit by accident while trying to scroll the
                            // chat. Kept as combinedClickable + empty
                            // long-press so the tap ripple stays bound
                            // to the chip surface (a single clickable
                            // would also work but switching modifiers
                            // here would re-create the gesture detector
                            // each composition).
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
    // ReactionViewersSheet removed from this strip — the same content
    // is now surfaced inside MessageActionsSheet at the top, alongside
    // forward / copy / edit etc., so the user has one entry point
    // (long-press the message) for all metadata actions.
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
    onTap: () -> Unit = {},
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
            .clickable { onTap() }
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

/**
 * Parse a `t.me/<chatRef>/<messageId>` link and return the messageId IFF
 * the link points into the same chat as [currentChatId]. Returns null
 * for cross-chat links (caller falls back to the normal Intent flow) and
 * for non-message t.me links (joinchat hashes, profile-only `t.me/user`,
 * etc.).
 *
 * Supported link shapes:
 *  - `t.me/c/<supergroupInternalId>/<messageId>` — private supergroup or
 *    channel. TDLib chat ids for these are `-100<supergroupInternalId>`
 *    (e.g. `-1001234567890` ↔ `1234567890` in the link). We reconstruct
 *    the expected internal id by stripping the `-100` prefix from the
 *    absolute chat id and compare against the path segment.
 *  - `t.me/<username>/<messageId>` — public chat / channel. We resolve
 *    the current chat's active username from TDLib's cached User/Chat
 *    object and compare case-insensitively.
 *
 * Topic links (`t.me/c/<id>/<topicId>/<messageId>`) currently take the
 * LAST segment as the message id and ignore the topic — Eugenio's chats
 * aren't using forum topics yet and TDLib's getMessage works regardless.
 */
private fun parseSameChatMessageLink(uri: android.net.Uri, currentChatId: Long): Long? {
    val segments = uri.pathSegments?.filter { it.isNotBlank() } ?: return null
    if (segments.size < 2) return null
    // Last segment must be a numeric message id.
    val msgId = segments.last().toLongOrNull() ?: return null
    val isPrivateRef = segments.first() == "c"
    if (isPrivateRef) {
        if (segments.size < 3) return null
        val internalId = segments[1].toLongOrNull() ?: return null
        // TDLib supergroup chat ids are -100<internalId>. abs(chatId) -
        // 1_000_000_000_000 == internalId when this is the same chat.
        val expected = kotlin.math.abs(currentChatId) - 1_000_000_000_000L
        return if (expected == internalId) msgId else null
    } else {
        val urlUsername = segments.first().lowercase()
        // Resolve the current chat's @username. TdApi.Chat itself has no
        // username field — it lives on the underlying user (for private
        // chats) or supergroup. We only have a user cache here, so we
        // resolve the private-chat case; public supergroups/channels
        // reached via t.me/<username>/<id> fall through to the Intent
        // path (rare, and the numeric t.me/c/<internal>/<id> path above
        // already covers the common same-supergroup link case).
        val cachedChat = TdClient.getCachedChat(currentChatId) ?: return null
        val currentUsername: String? =
            (cachedChat.type as? TdApi.ChatTypePrivate)?.userId?.let { uid ->
                TdClient.getCachedUser(uid)?.usernames?.activeUsernames
                    ?.firstOrNull()?.lowercase()
            }
        return if (currentUsername != null && currentUsername == urlUsername) msgId else null
    }
}
