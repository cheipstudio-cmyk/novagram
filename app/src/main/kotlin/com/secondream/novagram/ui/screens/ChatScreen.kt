@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.secondream.novagram.R
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.components.MessageBubble
import com.secondream.novagram.ui.theme.Ink
import com.secondream.novagram.util.FileUtils
import com.secondream.novagram.util.VoiceRecorder
import org.drinkless.tdlib.TdApi

@Composable
fun ChatScreen(
    chatId: Long,
    onBack: () -> Unit,
    onOpenMediaViewer: () -> Unit = {},
    /** Navigate to another chat by id. Used by the avatar profile sheet's
     *  "Inizia chat" button and by t.me deep-link handling: a second optional
     *  message id is used to land on a specific message in the destination
     *  chat (e.g. t.me/canalegruppo/1234). */
    onOpenChat: (Long, Long?) -> Unit = { _, _ -> },
    /** When non-null, ChatScreen scrolls to this message id on first load.
     *  Used by the navigation arg msg=… for in-app t.me deep-links. */
    targetMessageId: Long? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val messages = remember { mutableStateListOf<TdApi.Message>() }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    // The input is keyed on chatId so switching between chats wipes the
    // text field instead of letting the previous chat's typing bleed into
    // the new one. The draft loader below repopulates it from TDLib.
    var input by remember(chatId) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    // FocusRequester for the message input — the chat screen can poke it
    // when the user picks a reply (swipe or button) so the IME pops up
    // immediately and they can start typing without an extra tap on the
    // field. Held at this scope so it survives recomposition of the
    // input bar; paired with the LocalSoftwareKeyboardController call
    // below because requestFocus() alone doesn't reliably show the IME
    // on every Android build.
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val inputKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Track whether we've already loaded the draft for this chat. Without
    // this guard the draft loader could fight the user: imagine you type
    // "ciao", we save it to TDLib, TDLib emits UpdateChatDraftMessage,
    // then on the next composition we'd re-read it and overwrite whatever
    // you typed in between.
    var draftLoaded by remember(chatId) { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var needMicPermission by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<TdApi.Message?>(null) }
    // Message the user has swiped on (or null if not replying). Cleared on
    // send and on tap of the "x" in the ReplyPreview.
    var replyTarget by remember { mutableStateOf<TdApi.Message?>(null) }
    // When the user picks a reply via swipe-to-reply or the long-press
    // sheet, fire focus + IME so they can start typing without an extra
    // tap on the field. Only triggers on null → non-null transitions
    // (we don't want a re-focus every time the message object recomposes
    // because TDLib mutated interactionInfo). 50ms gives the focus path
    // a beat before we shout for the keyboard.
    LaunchedEffect(replyTarget?.id) {
        if (replyTarget != null) {
            kotlinx.coroutines.delay(50)
            runCatching { inputFocus.requestFocus() }
            runCatching { inputKeyboard?.show() }
        }
    }
    // When non-null, the user has tapped Modifica on one of their own
    // messages. The input bar pre-populates with the existing text and a
    // banner above it shows "Modifica messaggio" — same visual language
    // as the reply banner. On send we route to editMessageText (or
    // editMessageCaption for media) instead of sendText.
    var editTarget by remember { mutableStateOf<TdApi.Message?>(null) }
    // Forward picker target: the message the user wants to share elsewhere.
    // When non-null we render the picker sheet; tapping a destination chat
    // fires forwardMessages and clears this back to null.
    var forwardTarget by remember { mutableStateOf<TdApi.Message?>(null) }
    // Profile sheet target: userId of a sender whose avatar was tapped in
    // a group chat. When non-null we render UserProfileSheet on top of the
    // chat; the sheet handles its own create-private-chat flow.
    var profileSheetUserId by remember(chatId) { mutableStateOf<Long?>(null) }
    // Pinned-list sheet visibility. Set true when the user taps the
    // pinned banner; the sheet itself fetches the full list of pinned
    // messages via searchPinnedMessages and lets the user jump to any.
    var pinnedSheetOpen by remember(chatId) { mutableStateOf(false) }
    // AI sheet target: the message the user picked the AI tile on. The
    // sheet itself takes the message body + context and routes preset
    // prompts through Anthropic. Cleared on dismiss.
    var aiTarget by remember(chatId) { mutableStateOf<TdApi.Message?>(null) }
    // Whether the current user may pin in this chat (private/secret always;
    // groups/channels require creator or admin-with-canPinMessages).
    var canPinHere by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(chatId) {
        canPinHere = runCatching { TdClient.canPinMessages(chatId) }.getOrDefault(false)
    }
    // Tracks the most-recently pinned message id so the actions tile can
    // flip between "Fissa"/"Rimuovi pin". Updated optimistically on the
    // user's own pin/unpin; the common single-pin case stays correct.
    var pinnedMessageId by remember(chatId) { mutableStateOf(0L) }
    // The message shown in the pinned banner at the top of the chat.
    // Lifted to screen scope (was local to the Scaffold content) so the
    // pin/unpin action can update it OPTIMISTICALLY and in real time —
    // previously the banner only refreshed on chatUpdates, which doesn't
    // fire for a pin, so a freshly pinned message appeared only after
    // leaving and re-entering the chat.
    var pinned by remember(chatId) { mutableStateOf<TdApi.Message?>(null) }
    val appearance by com.secondream.novagram.settings.AppSettings.appearance
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs())
    // Cached list of chat members for the @-mention picker. Loaded lazily
    // the first time the user types "@" in a non-private chat.
    var mentionMembers by remember(chatId) { mutableStateOf<List<TdApi.User>>(emptyList()) }
    var mentionLoaded by remember(chatId) { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val recorder = remember { VoiceRecorder(context) }

    val defaultChatTitle = stringResource(R.string.chat_default_title)
    val chatTitle by produceState(initialValue = defaultChatTitle, chatId) {
        value = withContext(Dispatchers.IO) {
            runCatching { TdClient.getChat(chatId).title }.getOrDefault(defaultChatTitle)
        }
    }
    // Non-private chats (groups, supergroups, channels) show sender name +
    // avatar above each incoming bubble. Cached chat type is reliable once
    // TDLib has streamed UpdateNewChat for this id, which happens before the
    // chat list ever renders, so we read it synchronously.
    val isGroupChat = remember(chatId) {
        TdClient.getCachedChat(chatId)?.type !is TdApi.ChatTypePrivate
    }

    // Online status of the other participant in a private chat. Used to
    // light up the green dot on the title-bar avatar and to render the
    // "online" subtitle under the chat name (gated by the user's own
    // showLastSeen pref so it can be turned off globally). We poll on a
    // 30s tick because TDLib doesn't expose a Compose-friendly user
    // status flow — close enough to feel real-time without a custom
    // subscription pipeline.
    var peerOnline by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(chatId) {
        if (isGroupChat) return@LaunchedEffect
        val cached = TdClient.getCachedChat(chatId)
        val privateType = cached?.type as? TdApi.ChatTypePrivate ?: return@LaunchedEffect
        val userId = privateType.userId
        while (true) {
            val user = runCatching { TdClient.getUser(userId) }.getOrNull()
            peerOnline = user?.status is TdApi.UserStatusOnline
            kotlinx.coroutines.delay(30_000)
        }
    }

    // Am I an admin/creator of this group? Drives whether admin actions
    // (kick / mute / "delete for everyone of someone else's message") show
    // up in the message sheet. For private chats this stays false and the
    // admin block never renders.
    var isAdmin by remember(chatId) { mutableStateOf(false) }
    // Cached own user id, used to gate admin actions (you can't kick
    // yourself) and to filter the slash-commands picker for self-replies.
    var myUserId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(chatId) {
        val me = runCatching { TdClient.getMe() }.getOrNull() ?: return@LaunchedEffect
        myUserId = me.id
        if (!isGroupChat) return@LaunchedEffect
        val member = runCatching { TdClient.getMyChatMember(chatId, me.id) }.getOrNull()
        isAdmin = when (member?.status) {
            is TdApi.ChatMemberStatusCreator,
            is TdApi.ChatMemberStatusAdministrator -> true
            else -> false
        }
    }

    // Bot command suggestions for the slash menu. Loaded once per chat
    // entry; in private chats with a bot we pull from UserFullInfo.botInfo
    // because that's the authoritative source for that bot's command list.
    var botCommands by remember(chatId) { mutableStateOf<List<TdApi.BotCommand>>(emptyList()) }
    LaunchedEffect(chatId) {
        val cmds = runCatching {
            val cached = TdClient.getCachedChat(chatId)
            val privateType = cached?.type as? TdApi.ChatTypePrivate
            val botUserId = privateType?.userId
            val privateBot = botUserId?.let { runCatching { TdClient.getUser(it) }.getOrNull() }
            if (privateBot?.type is TdApi.UserTypeBot) {
                runCatching { TdClient.getUserFullInfo(privateBot.id) }
                    .getOrNull()
                    ?.botInfo
                    ?.commands
                    ?.toList()
                    ?: emptyList()
            } else if (isGroupChat) {
                TdClient.getBotCommandsForChat(chatId)
            } else emptyList()
        }.getOrDefault(emptyList())
        botCommands = cmds
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) needMicPermission = false }

    // Selected-but-not-yet-sent media. Once the user picks something it
    // sits here while they type an optional caption; pressing send
    // dispatches it together with the caption text (Telegram-style flow).
    // We copy the URI's content into the cache up-front so the InputBar can
    // show a thumbnail and we don't have to keep the SAF permission alive.
    var pendingMedia by remember(chatId) { mutableStateOf<PendingMediaItem?>(null) }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        showAttach = false
        uri?.let { picked ->
            scope.launch(Dispatchers.IO) {
                val file = FileUtils.copyUriToCache(context, picked) ?: return@launch
                val isVideo = isVideoFile(file.name)
                // Compress photos before upload — a 12MP camera shot is
                // several MB and uploads slowly at full size. We downscale
                // to max 1600px and re-encode JPEG ~82%, which is visually
                // indistinguishable in chat but uploads in a fraction of
                // the time. Videos are left as-is (handled by TDLib).
                val finalFile = if (!isVideo) {
                    FileUtils.compressImageForUpload(file) ?: file
                } else file
                pendingMedia = PendingMediaItem(
                    file = finalFile,
                    kind = if (isVideo) PendingMediaKind.Video else PendingMediaKind.Photo,
                    displayName = finalFile.name
                )
            }
        }
    }

    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        showAttach = false
        uri?.let { picked ->
            scope.launch(Dispatchers.IO) {
                val file = FileUtils.copyUriToCache(context, picked) ?: return@launch
                pendingMedia = PendingMediaItem(
                    file = file,
                    kind = PendingMediaKind.Document,
                    displayName = file.name
                )
            }
        }
    }

    DisposableEffect(chatId) {
        scope.launch { runCatching { TdClient.openChat(chatId) } }
        // Tell the global notification gate which chat is on screen so
        // NotificationHelper can skip heads-up only for THIS chat. Other
        // incoming chats still fire normally.
        com.secondream.novagram.AppForegroundState.currentChatId = chatId
        onDispose {
            scope.launch { runCatching { TdClient.closeChat(chatId) } }
            // Only clear if still pointing to us — if the user nav'd to
            // another chat the new ChatScreen has already overwritten this.
            if (com.secondream.novagram.AppForegroundState.currentChatId == chatId) {
                com.secondream.novagram.AppForegroundState.currentChatId = 0L
            }
            // Flush the final draft. We use a fire-and-forget launch on the
            // process-wide application scope because the screen-scoped
            // coroutine is about to be cancelled and a launch here would die
            // before reaching TDLib. Capturing input/replyTarget by value
            // means even if the user typed a frame before backing out we
            // still persist the last state.
            val finalText = input.text
            val finalReply = replyTarget?.id
            val inEditMode = editTarget != null
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                // If the user was editing when they backed out, abandon
                // the edit but don't repurpose the editor text as a draft
                // — that would surprise them next visit. The original
                // message stays unchanged on the server.
                if (!inEditMode) {
                    runCatching { TdClient.setChatDraft(chatId, finalText, finalReply) }
                }
            }
        }
    }

    // ── Edit-mode prefill ────────────────────────────────────────────
    // When the user taps Modifica we drop the existing text/caption into
    // the input bar so they can edit in place — same UX as Telegram.
    // The launcher key includes the message id so jumping between two
    // edit-able messages overwrites cleanly.
    LaunchedEffect(editTarget?.id) {
        val target = editTarget ?: return@LaunchedEffect
        val existing = when (val c = target.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> c.caption.text
            is TdApi.MessageVideo -> c.caption.text
            is TdApi.MessageDocument -> c.caption.text
            is TdApi.MessageAnimation -> c.caption.text
            is TdApi.MessageAudio -> c.caption.text
            else -> ""
        }
        input = existing
    }

    // ── Draft persistence ────────────────────────────────────────────
    // Load any saved draft when this chat opens. We only do this once per
    // chatId; the guard protects against subsequent recompositions
    // overwriting the user's live typing. If the user has nothing yet in
    // the input (which is the normal case on first open), we transplant
    // the draft into it.
    LaunchedEffect(chatId) {
        if (draftLoaded) return@LaunchedEffect
        val saved = TdClient.getChatDraftText(chatId)
        if (!saved.isNullOrEmpty() && input.text.isEmpty()) {
            input = androidx.compose.ui.text.input.TextFieldValue(saved, androidx.compose.ui.text.TextRange(saved.length))
        }
        draftLoaded = true
    }
    // Debounced save while the user is typing. snapshotFlow turns the
    // mutable `input` state into a flow; debounce(400) ensures we only
    // call SetChatDraftMessage when the user pauses for ~400ms instead of
    // on every keystroke. Combined with the dispose-time flush above this
    // gives the same UX as Telegram: come back any time and find your text.
    LaunchedEffect(chatId, draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { Triple(input.text, replyTarget?.id, editTarget?.id) }
            .debounce(400)
            .distinctUntilChanged()
            .collect { (text, replyId, editId) ->
                // Skip draft persistence while the user is editing an
                // existing message — the input represents the in-progress
                // edit, not a new outgoing draft. Saving it would mean
                // they'd find the edited text waiting as a "new message"
                // when they next opened the chat, which is confusing.
                if (editId != null) return@collect
                runCatching { TdClient.setChatDraft(chatId, text, replyId) }
            }
    }

    // Unread state carried from the moment the chat was opened. We snapshot
    // it here (not from a live flow) because the moment is the only one
    // that matters: Telegram lands you on the FIRST unread message when
    // you open a chat with backlog, and the floating "scroll to bottom"
    // FAB pulses accent until you actually reach the bottom. While the
    // user is in the chat, new messages get marked-read on arrival as
    // before — so this counter only reflects what was already there.
    var chatUnreadOnOpen by remember(chatId) { mutableStateOf(0) }
    // Set true once we've landed on the first-unread message during
    // initial load, OR explicitly false if the chat had no unread on
    // open. Drives the FAB accent + the "mark all read" behaviour when
    // the user reaches the bottom either by FAB or by manual scroll.
    var unreadModeActive by remember(chatId) { mutableStateOf(false) }

    // Initial history load.
    // TDLib's getChatHistory first call returns only what's already in the
    // local DB cache, even with onlyLocal=false. For a chat never opened
    // before, that cache is just lastMessage. We iterate (and let TDLib
    // backfill from the server between calls) until we have ~20 messages
    // or the server stops returning more — but if the chat has 100 unread
    // we keep iterating until that first unread is in the window, capped
    // so we don't try to backfill a thousand-message channel.
    LaunchedEffect(chatId) {
        messages.clear()
        noMore = false
        loading = true
        runCatching {
            // Snapshot the unread state BEFORE loading history so the
            // "land on first unread" decision is based on what the server
            // told us, not on what we've already pulled. Falls back to
            // the cached ChatSummary if a full getChat round-trip fails.
            val chatInfo = runCatching { TdClient.getChat(chatId) }.getOrNull()
            val initialUnread = chatInfo?.unreadCount ?: 0
            val lastReadId = chatInfo?.lastReadInboxMessageId ?: 0L
            chatUnreadOnOpen = initialUnread

            // Target window: cover all unread messages plus a small
            // pre-read buffer (~10 messages) so the first unread isn't
            // glued to the very top edge of the viewport. Cap at 200 so
            // we don't sit there spinning on a backlog channel.
            val target = if (initialUnread > 0) minOf(initialUnread + 10, 200) else 20
            var fromId = 0L
            var attempts = 0
            var consecutiveEmpty = 0
            val attemptCap = if (initialUnread > 0) 10 else 6
            while (messages.size < target && attempts < attemptCap && consecutiveEmpty < 2) {
                val res = TdClient.getChatHistory(chatId, fromId, 50)
                if (res.messages.isEmpty()) {
                    consecutiveEmpty++
                    if (consecutiveEmpty < 2) delay(350)
                } else {
                    consecutiveEmpty = 0
                    messages.addAll(res.messages.toList())
                    fromId = res.messages.last().id
                }
                attempts++
            }

            // If there's no unread backlog we keep the original behaviour:
            // mark loaded incoming messages as viewed and stay anchored at
            // the bottom. With unread > 0 we deliberately do NOT mark the
            // unread ones — that's what reading them implies, and the
            // server will get the viewMessages call when the user actually
            // scrolls down to them (or taps the FAB).
            if (initialUnread > 0 && lastReadId > 0L) {
                // First unread = the message immediately after the last
                // read inbox id. In our newest-first list we want the one
                // with the *smallest* id that's still > lastReadId.
                val firstUnread = messages
                    .filter { !it.isOutgoing && it.id > lastReadId }
                    .minByOrNull { it.id }
                val idx = firstUnread?.let { tgt ->
                    messages.indexOfFirst { it.id == tgt.id }
                } ?: -1
                if (idx >= 0) {
                    // Land with the first-unread message ~near the top of
                    // the viewport so the user reads downward into the
                    // newer content (same affordance Telegram uses).
                    val viewportH = listState.layoutInfo.viewportSize.height
                    val topOffset = if (viewportH > 0) (viewportH * 7) / 10 else 1000
                    runCatching { listState.scrollToItem(idx, topOffset) }
                    unreadModeActive = true
                }
                // Already-read incoming messages we DID load: those can be
                // safely re-viewed, the call is idempotent and keeps the
                // unread counter on the server in sync with what's now in
                // our window.
                val alreadyReadIds = messages
                    .filter { !it.isOutgoing && it.id <= lastReadId }
                    .map { it.id }
                    .toLongArray()
                if (alreadyReadIds.isNotEmpty()) {
                    runCatching { TdClient.viewMessages(chatId, alreadyReadIds) }
                }
            } else {
                val ids = messages.filter { !it.isOutgoing }.map { it.id }.toLongArray()
                if (ids.isNotEmpty()) runCatching { TdClient.viewMessages(chatId, ids) }
            }
        }
        loading = false
    }

    // While unread mode is active, watch the scroll position: as soon as
    // the user reaches the bottom (manually or via the FAB) we mark all
    // currently-loaded incoming messages as read and clear the accent
    // affordance. This is what makes the "tap FAB or just scroll down"
    // experience feel symmetric with the official client.
    LaunchedEffect(unreadModeActive, chatId) {
        if (!unreadModeActive) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .filter { it == 0 }
            .collect {
                val ids = messages.filter { !it.isOutgoing }.map { it.id }.toLongArray()
                if (ids.isNotEmpty()) runCatching { TdClient.viewMessages(chatId, ids) }
                chatUnreadOnOpen = 0
                unreadModeActive = false
            }
    }

    // Listen for new messages in this chat.
    LaunchedEffect(chatId) {
        TdClient.newMessages.collect { msg ->
            if (msg.chatId == chatId) {
                messages.add(0, msg)
                if (!msg.isOutgoing) {
                    runCatching { TdClient.viewMessages(chatId, longArrayOf(msg.id)) }
                }
            }
        }
    }

    // React to server-side deletions: drop the matching ids from the
    // local list so the bubble disappears as soon as TDLib confirms.
    LaunchedEffect(chatId) {
        TdClient.deletedMessages.collect { event ->
            if (event.chatId == chatId) {
                val toDrop = event.messageIds.toHashSet()
                messages.removeAll { it.id in toDrop }
            }
        }
    }

    // Per-message revision counter. We bump this each time TDLib pushes
    // a new InteractionInfo (reactions / views / forwards) so the
    // MessageBubble for that id recomposes — mutating the Java Message
    // in place doesn't trigger SnapshotStateList because it's still the
    // same reference. Keyed by message id.
    val interactionRevisions = remember(chatId) {
        androidx.compose.runtime.mutableStateMapOf<Long, Int>()
    }
    // Per-message content override map. The flow for edited messages goes
    // through here instead of mutating Message.content directly on the
    // TdClient coroutine thread: a snapshot-state write is the only way
    // to guarantee Compose observes the change AND that the new content
    // is published to the rendering thread before recomposition reads it.
    // The items() lambda below applies pending overrides onto the Message
    // (in-place) during composition on the Main thread, right before
    // MessageBubble is invoked — so all downstream readers of msg.content
    // (MessageContent, FormattedTextRendering, etc.) see the fresh value
    // in the same composition pass.
    val messageContentOverrides = remember(chatId) {
        androidx.compose.runtime.mutableStateMapOf<Long, TdApi.MessageContent>()
    }
    LaunchedEffect(chatId) {
        TdClient.interactionInfoUpdates.collect { upd ->
            if (upd.chatId != chatId) return@collect
            val idx = messages.indexOfFirst { it.id == upd.messageId }
            if (idx >= 0) {
                // Mutate in place (TDLib Message is a plain Java class) so
                // any non-keyed read sees the new info immediately,
                // then bump the revision so Compose recomposes the bubble.
                messages[idx].interactionInfo = upd.info
                interactionRevisions[upd.messageId] =
                    (interactionRevisions[upd.messageId] ?: 0) + 1
            }
        }
    }
    // Listen for content updates so edited messages refresh in place.
    // We route the new content through a SnapshotStateMap (not via direct
    // JVM field mutation on the IO/main boundary) so Compose's snapshot
    // system actually observes the change and ensures the write is
    // published to the recomposition pass. The items() lambda picks the
    // override up and applies it onto the Message just-in-time before
    // MessageBubble renders, so the bubble's MessageContent / FormattedText
    // composition reads the fresh content in the same frame as the update.
    LaunchedEffect(chatId) {
        TdClient.messageContentUpdates.collect { upd ->
            if (upd.chatId != chatId) return@collect
            val idx = messages.indexOfFirst { it.id == upd.messageId }
            if (idx >= 0) {
                // Mutate the message's content in place (other readers in
                // the screen like the copy-text resolver at line ~1796 and
                // the reply-preview at line ~3076 read msg.content
                // directly, not via the override map — so the field has
                // to hold the truth too).
                messages[idx].content = upd.newContent
                // Re-assign the same reference back into the
                // SnapshotStateList slot. This records a write on the
                // list's snapshot, which the LazyColumn items() iteration
                // tracker DOES observe (unlike the absent-key reads on
                // mutableStateMapOf, whose subscription semantics are
                // inconsistent across Compose versions). Result: the
                // item content lambda for this msg.id reliably re-runs,
                // which re-evaluates msg.content fresh and calls
                // MessageBubble — whose `message` param is unstable, so
                // Compose recomposes its body and MessageContent picks
                // up the new bytes. This is the actual mechanism that
                // makes the bubble update without close+reopen.
                messages[idx] = messages[idx]
                // Keep the override map + revision counter wired too,
                // belt-and-suspenders for any downstream reader (e.g.
                // search highlighting, share previews) that subscribes
                // to either path.
                messageContentOverrides[upd.messageId] = upd.newContent
                interactionRevisions[upd.messageId] =
                    (interactionRevisions[upd.messageId] ?: 0) + 1
            }
        }
    }
    // Listen for send-state confirmations from TDLib. When we send a
    // message TDLib returns a local-only placeholder (negative-id, with
    // sendingState=Pending so the bubble shows the ⏱ tick). Later it
    // emits UpdateMessageSendSucceeded carrying the same chatId, the old
    // (placeholder) id, and the new server-confirmed message. We splice
    // the new one into the list in the placeholder's position so the
    // tick flips to ✓ inline, without the user having to back out and
    // reopen the chat to trigger a full history reload. Failures stay in
    // place but flip to the "!" sendingState so the user sees the retry.
    LaunchedEffect(chatId) {
        TdClient.messageSendUpdates.collect { upd ->
            if (upd.newMessage.chatId != chatId) return@collect
            val idx = messages.indexOfFirst { it.id == upd.oldMessageId }
            if (idx >= 0) {
                // SnapshotStateList observes element replacement (the new
                // Message is a different reference), so this triggers a
                // recomposition of the affected row. We also bump the
                // revision under BOTH the old and new ids so anything
                // observing either still recomposes cleanly.
                messages[idx] = upd.newMessage
                interactionRevisions[upd.newMessage.id] =
                    (interactionRevisions[upd.newMessage.id] ?: 0) + 1
                interactionRevisions[upd.oldMessageId] =
                    (interactionRevisions[upd.oldMessageId] ?: 0) + 1
            }
        }
    }

    // Auto-scroll behaviour:
    //   - When the user sends a message (outgoing) → always scroll to bottom.
    //   - When an incoming message arrives → only scroll if the user is
    //     already near the bottom, otherwise we'd yank them out of history.
    LaunchedEffect(listState, chatId) {
        // We track the previously-seen first id locally. snapshotFlow always
        // emits the current value to a fresh collector, and distinctUntilChanged
        // does NOT skip that first emission — so naively scrolling on every
        // emit means re-entering ChatScreen (e.g. popping back from
        // MediaViewer) replays the auto-scroll-to-bottom check against a
        // momentarily-empty layoutInfo, sees firstVisibleItemIndex=0, and
        // yanks the user to the bottom. By gating on prev != null we let
        // the first emission seed the tracker and only auto-scroll when an
        // ACTUAL new message arrives.
        var previousFirstId: Long? = null
        snapshotFlow { messages.firstOrNull()?.id }
            .filter { it != null }
            .collect { newFirstId ->
                val prev = previousFirstId
                previousFirstId = newFirstId
                if (prev == null) return@collect
                if (prev != newFirstId) {
                    val first = messages.firstOrNull() ?: return@collect
                    if (first.isOutgoing || listState.firstVisibleItemIndex <= 2) {
                        // Instant snap rather than animateScrollToItem.
                        // The new bubble already animates in via
                        // Modifier.animateItem(); a simultaneous viewport
                        // animation fought with it and produced the
                        // full-chat "flash" the user saw. A snap of one
                        // bubble's height is invisible, so the user only
                        // perceives the bubble sliding up from the
                        // bottom — same effect as the official client.
                        runCatching { listState.scrollToItem(0) }
                    }
                }
            }
    }

    // Auto load older when scroll near top of reversed list (i.e. bottom of memory).
    LaunchedEffect(listState, chatId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= messages.size - 5 && messages.isNotEmpty() && !loadingMore && !noMore }
            .collect {
                loadingMore = true
                val oldest = messages.lastOrNull()?.id ?: 0L
                runCatching {
                    val res = TdClient.getChatHistory(chatId, oldest, 30)
                    if (res.messages.isEmpty()) noMore = true
                    else messages.addAll(res.messages.toList())
                }
                loadingMore = false
            }
    }

    // Briefly highlights a single message after the user lands on it via
    // jumpToMessage (reply tap, pinned tap, in-chat search arrows, deep
    // link). Set to the targetId right after the instant scroll, cleared
    // after ~1.2s by a delayed coroutine. Threaded down through
    // MessageBubble so the matching bubble paints an animated accent
    // overlay that fades out — Telegram-style "this is the message".
    var flashMessageId by remember(chatId) { mutableStateOf<Long?>(null) }

    // Jump to a message by id, loading older history in bounded batches
    // if it isn't in the in-memory window yet (used by same-chat link
    // taps AND the pinned-messages list). Without the load loop, tapping
    // a pinned message older than the loaded window did nothing.
    val jumpToMessage: (Long) -> Unit = { targetId ->
        scope.launch {
            var idx = messages.indexOfFirst { it.id == targetId }
            // The previous guard of 8×50=400 messages was insufficient: in
            // long chats search hits older than that simply silently
            // failed (which felt like "the arrow doesn't work"). 40×50 =
            // 2000 messages of headroom catches almost every realistic
            // case while still bounding the network traffic.
            var guard = 0
            while (idx < 0 && !noMore && guard < 40) {
                guard++
                val oldest = messages.lastOrNull()?.id ?: break
                val res = runCatching { TdClient.getChatHistory(chatId, oldest, 50) }.getOrNull()
                if (res == null || res.messages.isEmpty()) { noMore = true; break }
                messages.addAll(res.messages.toList())
                idx = messages.indexOfFirst { it.id == targetId }
            }
            // Fallback when normal pagination didn't surface the target —
            // typically because the message is far older than the loaded
            // window AND noMore got tripped. Pull the message directly via
            // TDLib + a small surrounding window, splice it into our list,
            // then scroll. Without this, jumping to a 6-month-old search
            // hit or to a t.me deep-link target would silently no-op.
            if (idx < 0) {
                runCatching {
                    val ctx = TdClient.getChatHistory(chatId, targetId + 1, 25)
                    val toAdd = ctx.messages.toList().filter { m ->
                        messages.none { it.id == m.id }
                    }
                    if (toAdd.isNotEmpty()) messages.addAll(toAdd)
                }
                idx = messages.indexOfFirst { it.id == targetId }
            }
            if (idx >= 0) {
                // Two-step jump (Telegram-style): SNAP to the target instantly
                // (no animation) so the user doesn't see jitter from the
                // history-loading addAlls + a sudden scroll on top of that,
                // then pulse the target bubble briefly so it's obvious which
                // message we landed on. animateScrollToItem here would
                // animate from whatever transient position the list is in
                // mid-load, which is the "scatto bruttissimo" the user
                // reported.
                val viewportH = listState.layoutInfo.viewportSize.height
                val topOffset = if (viewportH > 0) (viewportH * 6) / 10 else 800
                runCatching { listState.scrollToItem(idx, topOffset) }
                flashMessageId = targetId
                // Clear the flash a beat later so the highlight fades out
                // and the bubble settles back to its normal colour.
                scope.launch {
                    kotlinx.coroutines.delay(1200)
                    if (flashMessageId == targetId) flashMessageId = null
                }
            }
        }
    }

    // Deep-link target: when ChatScreen is reached via t.me/<user>/<msgId>
    // (or the avatar-sheet equivalent) the route carries a targetMessageId,
    // and we should land on that message rather than the latest. We wait
    // for the initial history page to settle so jumpToMessage has something
    // to search against, then perform the jump exactly once.
    var didJumpToTarget by remember(chatId, targetMessageId) { mutableStateOf(false) }
    LaunchedEffect(messages.size, targetMessageId, chatId) {
        if (targetMessageId != null && !didJumpToTarget && messages.isNotEmpty()) {
            didJumpToTarget = true
            jumpToMessage(targetMessageId)
        }
    }

    // Chat-search state: open/close, current query, list of matching message
    // ids (newest-first as TDLib returns them), and the current pointer
    // cycled via the up/down arrows.
    var searchOpen by remember(chatId) { mutableStateOf(false) }
    var searchQuery by remember(chatId) { mutableStateOf("") }
    var searchResults by remember(chatId) { mutableStateOf<List<Long>>(emptyList()) }
    var searchIndex by remember(chatId) { mutableStateOf(0) }
    var searchLoading by remember(chatId) { mutableStateOf(false) }

    // Debounced chat search: whenever the query or open state changes, kick
    // off a search after a short pause so the user isn't hitting TDLib on
    // every keystroke. Results are sorted newest-first (as TDLib returns
    // them); the up arrow goes to the previous (newer) match.
    LaunchedEffect(searchOpen, searchQuery, chatId) {
        if (!searchOpen) {
            searchResults = emptyList()
            searchIndex = 0
            searchLoading = false
            return@LaunchedEffect
        }
        val q = searchQuery.trim()
        if (q.isBlank()) {
            searchResults = emptyList()
            searchIndex = 0
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        kotlinx.coroutines.delay(220)
        val matches = runCatching { TdClient.searchInChat(chatId, q) }.getOrNull().orEmpty()
        searchResults = matches.map { it.id }
        searchIndex = 0
        searchLoading = false
        if (searchResults.isNotEmpty()) jumpToMessage(searchResults[0])
    }

    // Resolve a tapped Telegram link to a chat and open it INSIDE the app.
    // Mirrors MainActivity.handleTmeDeeplink's parsing but navigates via
    // onOpenChat instead of an Intent, so a t.me link in a message never
    // bounces the user out to a browser.
    val openTelegramLink: (android.net.Uri) -> Unit = { uri ->
        scope.launch {
            val scheme = uri.scheme.orEmpty()
            val host = uri.host.orEmpty()
            var username: String? = null
            var invite: String? = null
            var directUserId: Long? = null
            // Optional Telegram message id encoded in t.me/<user>/<id>.
            // TDLib message ids are not the raw integer in the URL — they
            // need to be shifted (<<20) to map onto a chat-internal id.
            // Without this shift, getMessageLinkInfo / our scrollers would
            // never find the target.
            var targetMsg: Long? = null
            if (scheme == "tg") {
                when (host) {
                    "resolve" -> {
                        username = uri.getQueryParameter("domain")
                        uri.getQueryParameter("post")?.toLongOrNull()?.let { targetMsg = it shl 20 }
                    }
                    "join" -> uri.getQueryParameter("invite")?.let { invite = "https://t.me/+$it" }
                    // Synthesised by @mention clicks where the user has no
                    // public username — we have only the numeric userId.
                    "user" -> directUserId = uri.getQueryParameter("id")?.toLongOrNull()
                }
            } else {
                val segs = uri.pathSegments.orEmpty()
                val first = segs.firstOrNull()
                when {
                    first.isNullOrBlank() -> {}
                    first == "joinchat" && segs.size >= 2 -> invite = "https://t.me/joinchat/${segs[1]}"
                    first.startsWith("+") -> invite = "https://t.me/$first"
                    first == "s" && segs.size >= 2 -> {
                        username = segs[1]
                        segs.getOrNull(2)?.toLongOrNull()?.let { targetMsg = it shl 20 }
                    }
                    else -> {
                        username = first
                        segs.getOrNull(1)?.toLongOrNull()?.let { targetMsg = it shl 20 }
                    }
                }
            }
            val resolvedId: Long? = when {
                directUserId != null -> {
                    // tg://user?id=N from a nameless @mention → open the
                    // private chat directly.
                    runCatching { TdClient.createPrivateChat(directUserId!!).id }.getOrNull()
                }
                invite != null -> {
                    val info = runCatching { TdClient.checkChatInviteLink(invite!!) }.getOrNull()
                    val existing = info?.chatId?.takeIf { it != 0L }
                    existing ?: runCatching { TdClient.joinChatByInviteLink(invite!!).id }.getOrNull()
                }
                username != null -> runCatching { TdClient.searchPublicChat(username!!).id }.getOrNull()
                else -> null
            }
            if (resolvedId != null && resolvedId != 0L) {
                // Same-chat case: don't replace the screen — just scroll.
                // Otherwise the user gets a stack of identical ChatScreens
                // and the back button feels broken.
                if (resolvedId == chatId && targetMsg != null) {
                    jumpToMessage(targetMsg!!)
                } else {
                    onOpenChat(resolvedId, targetMsg)
                }
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.link_open_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    // Controls visibility of the self-destruct timer chooser dialog.
    // Set true by the menu item; the dialog itself clears it.
    var ttlDialogOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var leaveOpen by remember { mutableStateOf(false) }
    val cachedChatLive = TdClient.getCachedChat(chatId)
    val isMuted = (cachedChatLive?.notificationSettings?.muteFor ?: 0) > 0

    // Swipe-from-left-to-right closes the chat. Mirrors the Telegram /
    // iOS pattern of "swipe right to pop". We hook a horizontal drag
    // detector on the Scaffold container and fire onBack once the total
    // horizontal drag passes a screen-fraction threshold. Vertical drag
    // is ignored, and the inner message-list scroll is on a separate
    // pointerInput inside the LazyColumn so this doesn't capture it.
    var backDragAmount by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val backTriggerPx = with(density) { 120.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput("back-swipe-$chatId") {
                detectHorizontalDragGestures(
                    onDragStart = { backDragAmount = 0f },
                    onDragEnd = {
                        if (backDragAmount > backTriggerPx) onBack()
                        backDragAmount = 0f
                    },
                    onDragCancel = { backDragAmount = 0f },
                    onHorizontalDrag = { change, delta ->
                        // Only accumulate positive (left→right) horizontal
                        // motion that *starts* near the left edge — same
                        // gesture grammar as Android's edge-back gesture, so
                        // we don't fight the message-list horizontal swipe
                        // on individual bubbles.
                        // Restricted to the leftmost 24dp so it doesn't
                        // race with the reply-swipe gesture on incoming
                        // bubbles (which sit close to the left edge).
                        // Same grammar as iOS's edge-back gesture.
                        if (change.position.x < 24.dp.toPx() || backDragAmount > 0f) {
                            if (delta > 0f) backDragAmount += delta
                            else if (backDragAmount > 0f) {
                                backDragAmount = (backDragAmount + delta).coerceAtLeast(0f)
                            }
                        }
                    }
                )
            }
    ) {

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { infoOpen = true }
                    ) {
                        // Avatar with optional online-presence dot. The
                        // dot is rendered as a tiny green circle anchored
                        // at the avatar's bottom-right, with a ring in
                        // the topbar's background colour so it reads as
                        // an overlay rather than blending into the
                        // photo. Gated by the showLastSeen pref AND by
                        // it being a private chat — groups never get a
                        // dot.
                        Box {
                            com.secondream.novagram.ui.components.Avatar(
                                file = cachedChatLive?.photo?.small,
                                fallbackText = chatTitle,
                                bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(chatId),
                                size = 36.dp
                            )
                            if (!isGroupChat && peerOnline && appearance.showLastSeen) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(12.dp)
                                        .background(
                                            MaterialTheme.colorScheme.background,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                        .padding(2.dp)
                                        .background(
                                            Color(0xFF22C55E),
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    chatTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isMuted) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.BellSlash,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(start = 6.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // "online" subtitle for private chats only,
                            // gated by the same pref as the dot. Lives
                            // below the chat name like the official
                            // client; we don't currently render the
                            // "last seen ago" string when offline — that
                            // would need TDLib's friendlier formatting.
                            if (!isGroupChat && peerOnline && appearance.showLastSeen) {
                                Text(
                                    stringResource(R.string.chat_status_online),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft, null)
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass, null)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.DotsThreeVertical, null)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Text(stringResource(
                                    if (isMuted) R.string.action_unmute_chat
                                    else R.string.action_mute_chat
                                ))
                            },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching { TdClient.setChatMuted(chatId, !isMuted) }
                                }
                            }
                        )
                        // Self-destruct timer. Works for any chat type but is
                        // most useful in secret chats — TDLib still applies
                        // the setting to regular chats (server-side message
                        // auto-delete). Tap opens a small dialog with the
                        // standard Telegram presets.
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_set_ttl)) },
                            onClick = {
                                menuOpen = false
                                ttlDialogOpen = true
                            }
                        )
                        // For groups/channels show "Leave" instead — you
                        // can't delete a chat you don't own. Private chats
                        // keep "Delete chat" which wipes history + removes
                        // from the list.
                        val isChannel = cachedChatLive?.type is TdApi.ChatTypeSupergroup &&
                            (cachedChatLive.type as TdApi.ChatTypeSupergroup).isChannel
                        if (isGroupChat) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (isChannel) R.string.action_leave_channel
                                            else R.string.action_leave_group
                                        ),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    leaveOpen = true
                                }
                            )
                        } else {
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_delete_chat),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    deleteOpen = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            // Chat search bar: collapsible, sits under the title. Up/down
            // arrows cycle through TDLib search results; tapping a result
            // (or the bar's prev/next) re-uses jumpToMessage so the target
            // lands near the top of the visible area.
            androidx.compose.animation.AnimatedVisibility(
                visible = searchOpen,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                ChatSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    results = searchResults,
                    index = searchIndex,
                    loading = searchLoading,
                    onPrev = {
                        if (searchResults.isNotEmpty()) {
                            searchIndex = (searchIndex - 1 + searchResults.size) % searchResults.size
                            jumpToMessage(searchResults[searchIndex])
                        }
                    },
                    onNext = {
                        if (searchResults.isNotEmpty()) {
                            searchIndex = (searchIndex + 1) % searchResults.size
                            jumpToMessage(searchResults[searchIndex])
                        }
                    },
                    onClose = {
                        searchOpen = false
                        searchQuery = ""
                    }
                )
            }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Pinned message banner. Reads the most-recently-pinned message
            // from TDLib on chat open and re-fetches when the chat list
            // updates. `pinned` now lives at screen scope so the pin action
            // can update it instantly. Tapping the banner scrolls to it.
            LaunchedEffect(chatId) {
                pinned = TdClient.getChatPinnedMessage(chatId)
                pinnedMessageId = pinned?.id ?: 0L
            }
            LaunchedEffect(chatId) {
                TdClient.chatUpdates.collect { id ->
                    if (id == chatId) pinned = TdClient.getChatPinnedMessage(chatId)
                }
            }
            pinned?.let { pin ->
                val preview = remember(pin.id) {
                    when (val c = pin.content) {
                        is TdApi.MessageText -> c.text.text
                        is TdApi.MessagePhoto -> c.caption.text.ifBlank { "📷 Foto" }
                        is TdApi.MessageVideo -> c.caption.text.ifBlank { "🎬 Video" }
                        is TdApi.MessageDocument -> c.caption.text.ifBlank { c.document.fileName.ifBlank { "📄 File" } }
                        is TdApi.MessageVoiceNote -> "🎤 Vocale"
                        is TdApi.MessageSticker -> c.sticker.emoji.ifBlank { "🖼" }
                        else -> ""
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            // Open the full pinned-messages list. Same UX as
                            // Telegram: a single tap on the banner reveals
                            // every pinned message in the chat so the user
                            // can pick which one to jump to. Scrolling
                            // directly to the topmost pinned still happens
                            // by long-pressing the pin icon below.
                            pinnedSheetOpen = true
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(36.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.chat_pinned_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(messages, key = { _, m -> m.id }) { _, msg ->
                        // Apply any pending content override BEFORE rendering.
                        // The override map is snapshot-state — reading it here
                        // subscribes this item's scope, so when an edit lands
                        // (collector above writes to the map) this scope
                        // invalidates and reruns. The in-place assignment then
                        // propagates the fresh content to MessageBubble, which
                        // reads message.content downstream. Guarded by a
                        // reference check to avoid pointless writes.
                        messageContentOverrides[msg.id]?.let { fresh ->
                            if (msg.content !== fresh) msg.content = fresh
                        }
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.animateItem()
                        ) {
                            MessageBubble(
                                message = msg,
                                showSender = isGroupChat,
                                onLongPress = { deleteTarget = it },
                                onMediaTap = { path ->
                                    com.secondream.novagram.ui.screens.MediaViewerHolder.currentPath = path
                                    onOpenMediaViewer()
                                },
                                onSwipeReply = { replyTarget = it },
                                onAvatarClick = { uid -> profileSheetUserId = uid },
                                onJumpToMessage = { targetId ->
                                    // Scroll to the same-chat target, loading
                                    // older history if needed (jumpToMessage
                                    // handles the not-in-window case). We
                                    // claim success so the link never falls
                                    // back to an Intent / browser.
                                    jumpToMessage(targetId)
                                    true
                                },
                                onOpenTelegramLink = openTelegramLink,
                                interactionRevision = interactionRevisions[msg.id] ?: 0,
                                highlightQuery = if (searchOpen) searchQuery else null,
                                flashing = (flashMessageId == msg.id)
                            )
                        }
                    }
                }
                // Scroll-to-bottom button. Appears as soon as the user has
                // scrolled up at least a few messages from the bottom of
                // the chat (reverseLayout=true means firstVisibleItemIndex
                // grows as they go back in time). Tapping snaps the list
                // straight to the newest message via animateScrollToItem(0).
                // We read firstVisibleItemIndex through a derivedStateOf so
                // recomposition only fires when the visibility threshold
                // actually flips, not on every pixel of scroll. We also
                // force-show it when there's an unread backlog so the user
                // always has a clear path back to "I've read everything".
                val showJumpToBottom by remember(listState, chatUnreadOnOpen) {
                    androidx.compose.runtime.derivedStateOf {
                        listState.firstVisibleItemIndex > 3 || chatUnreadOnOpen > 0
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showJumpToBottom,
                    enter = androidx.compose.animation.fadeIn() +
                        androidx.compose.animation.scaleIn(initialScale = 0.8f),
                    exit = androidx.compose.animation.fadeOut() +
                        androidx.compose.animation.scaleOut(targetScale = 0.8f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                ) {
                    // When there's an unread backlog the FAB lights up in
                    // accent so it reads as a CTA — "tap me to clear the
                    // unread". Otherwise it stays as a neutral surface
                    // button. A small numeric badge in the top-right
                    // corner shows the count, capped visually at 999+.
                    val unread = chatUnreadOnOpen
                    val hasUnread = unread > 0
                    val fabContainer = if (hasUnread)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                    val fabContent = if (hasUnread)
                        MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface
                    Box {
                        androidx.compose.material3.SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    runCatching { listState.animateScrollToItem(0) }
                                    // Reaching the bottom is the user's
                                    // explicit "I've seen everything"
                                    // gesture — mark all loaded incoming
                                    // messages read and drop unread mode.
                                    if (hasUnread) {
                                        val ids = messages
                                            .filter { !it.isOutgoing }
                                            .map { it.id }
                                            .toLongArray()
                                        if (ids.isNotEmpty()) runCatching {
                                            TdClient.viewMessages(chatId, ids)
                                        }
                                        chatUnreadOnOpen = 0
                                        unreadModeActive = false
                                    }
                                }
                            },
                            containerColor = fabContainer,
                            contentColor = fabContent,
                            elevation = androidx.compose.material3.FloatingActionButtonDefaults
                                .elevation(defaultElevation = 3.dp)
                        ) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.CaretDown,
                                contentDescription = stringResource(R.string.chat_jump_to_bottom),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        // Unread count badge, anchored at the FAB's
                        // top-right with a small pop-out offset. Lives
                        // inside the same Box so it inherits the FAB's
                        // enter/exit animation. The outer ring uses the
                        // chat background colour so the badge reads as a
                        // sticker sitting on top of the accent FAB —
                        // same affordance as Telegram's stock client.
                        if (hasUnread) {
                            val badgeText = if (unread > 999) "999+" else unread.toString()
                            // Badge styled as an inverted-colour sticker:
                            // FAB is primary, so the badge body uses
                            // onPrimary (typically white) with the accent
                            // colour for the number. Matches Telegram's
                            // own scroll-down counter exactly.
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .background(
                                        MaterialTheme.colorScheme.onPrimary,
                                        shape = androidx.compose.foundation.shape.CircleShape
                                    )
                                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    badgeText,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Mention picker (popup just above the input bar). The detection
            // logic computes the @-query at the cursor; if null the picker
            // is hidden. Members are loaded lazily the first time the user
            // types '@' so we don't pay the round-trip cost on chat open.
            val mentionQuery = remember(input) { detectMentionQuery(input.text) }
            LaunchedEffect(mentionQuery, isGroupChat) {
                if (mentionQuery != null && isGroupChat && !mentionLoaded) {
                    mentionMembers = loadChatMembers(chatId)
                    mentionLoaded = true
                }
            }
            if (mentionQuery != null && isGroupChat && mentionMembers.isNotEmpty()) {
                MentionPicker(
                    query = mentionQuery,
                    members = mentionMembers,
                    onPick = { user ->
                        run {
                            val newText = applyMentionPick(input.text, user)
                            // Cursor at end so the next character the user
                            // types lands after the mention token, not in
                            // the middle of the previous mid-input position
                            // where they had tapped the @.
                            input = androidx.compose.ui.text.input.TextFieldValue(
                                newText,
                                androidx.compose.ui.text.TextRange(newText.length)
                            )
                        }
                    }
                )
            }

            // Slash-command picker. Surfaces /commands the bot in this
            // chat (private or group) exposes, filtered live by what the
            // user is typing. Hidden when the input doesn't start with /.
            val slashQuery = remember(input) { detectSlashQuery(input.text) }
            if (slashQuery != null && botCommands.isNotEmpty()) {
                val filtered = botCommands.filter {
                    it.command.startsWith(slashQuery, ignoreCase = true)
                }
                if (filtered.isNotEmpty()) {
                    BotCommandPicker(
                        commands = filtered,
                        onPick = { cmd ->
                            val cmdText = "/" + cmd.command + " "
                            input = androidx.compose.ui.text.input.TextFieldValue(
                                cmdText,
                                androidx.compose.ui.text.TextRange(cmdText.length)
                            )
                        }
                    )
                }
            }

            if (replyTarget != null && editTarget == null) {
                // Hide reply preview during edit — the input represents the
                // edited message, not a reply. (Replying to a message you
                // then choose to edit isn't a real scenario, but if it
                // happens we drop the reply pin so the UI stays unambiguous.)
                ReplyPreview(
                    message = replyTarget!!,
                    onCancel = { replyTarget = null }
                )
            }
            if (editTarget != null) {
                EditPreview(
                    message = editTarget!!,
                    onCancel = {
                        editTarget = null
                        input = androidx.compose.ui.text.input.TextFieldValue("")
                    }
                )
            }
            pendingMedia?.let { media ->
                PendingMediaPreview(
                    media = media,
                    onCancel = { pendingMedia = null }
                )
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                placeholderText = if (pendingMedia != null)
                    stringResource(R.string.media_caption_hint)
                else null,
                onSend = {
                    val text = input.text.trim()
                    val media = pendingMedia
                    val rid = replyTarget?.id
                    val editing = editTarget
                    when {
                        editing != null -> {
                            // Edit branch: dispatch EditMessageText for plain
                            // text bodies, EditMessageCaption for media. We
                            // don't allow swapping the underlying media file
                            // here — that would be a different TDLib call
                            // (EditMessageMedia) and a bigger UX. Telegram
                            // itself only supports caption edits via the
                            // inline editor; new media goes as a new message.
                            val isTextMsg = editing.content is TdApi.MessageText
                            // Telegram allows clearing a caption (returns
                            // text/photo to "no caption" state) but never
                            // an empty text-message body. Reflect that.
                            if (isTextMsg && text.isBlank()) {
                                // Treat empty-on-text as cancel rather than
                                // surfacing a TDLib error.
                                editTarget = null
                                input = androidx.compose.ui.text.input.TextFieldValue("")
                            } else {
                                val captured = editing
                                editTarget = null
                                input = androidx.compose.ui.text.input.TextFieldValue("")
                                // OPTIMISTIC UPDATE: write the new content into
                                // the per-message override map immediately, so
                                // the bubble redraws on the next frame without
                                // waiting for TDLib's UpdateMessageContent
                                // roundtrip (which can be 100-800ms over slow
                                // links). If the server rejects the edit later
                                // TDLib will emit a corrective update and the
                                // bubble will rewind to the original.
                                if (isTextMsg) {
                                    val idx = messages.indexOfFirst { it.id == captured.id }
                                    val newContent = TdApi.MessageText(
                                        TdApi.FormattedText(text, emptyArray()),
                                        null,
                                        null
                                    )
                                    if (idx >= 0) {
                                        // Apply via the same path as the
                                        // TDLib echo handler so this code
                                        // and that one stay in lockstep:
                                        // mutate field + slot reassign +
                                        // override map + revision bump.
                                        // The slot reassign is what
                                        // forces the items() iteration
                                        // to invalidate (see the
                                        // UpdateMessageContent collector
                                        // above for the full rationale).
                                        messages[idx].content = newContent
                                        messages[idx] = messages[idx]
                                    }
                                    messageContentOverrides[captured.id] = newContent
                                    interactionRevisions[captured.id] =
                                        (interactionRevisions[captured.id] ?: 0) + 1
                                }
                                scope.launch {
                                    runCatching {
                                        if (isTextMsg) {
                                            TdClient.editMessageText(chatId, captured.id, text)
                                        } else {
                                            TdClient.editMessageCaption(chatId, captured.id, text)
                                        }
                                    }
                                }
                            }
                        }
                        media != null -> {
                            // Sending media with optional caption — caption may
                            // be blank, that's fine. Always clear local state
                            // before launching so a double-tap doesn't double-send.
                            input = androidx.compose.ui.text.input.TextFieldValue("")
                            pendingMedia = null
                            replyTarget = null
                            val caption = text.ifBlank { null }
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    when (media.kind) {
                                        PendingMediaKind.Photo ->
                                            TdClient.sendPhoto(chatId, media.file.absolutePath, caption, rid)
                                        PendingMediaKind.Video ->
                                            TdClient.sendVideo(chatId, media.file.absolutePath, caption, rid)
                                        PendingMediaKind.Document ->
                                            TdClient.sendDocument(chatId, media.file.absolutePath, caption, rid)
                                    }
                                }
                            }
                        }
                        text.isNotEmpty() -> {
                            input = androidx.compose.ui.text.input.TextFieldValue("")
                            replyTarget = null
                            scope.launch { runCatching { TdClient.sendText(chatId, text, rid) } }
                        }
                    }
                },
                onAttach = { showAttach = true },
                onMicDown = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        needMicPermission = true
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        runCatching {
                            recorder.start()
                            recording = true
                        }
                    }
                },
                onMicUp = { send ->
                    if (recording) {
                        recording = false
                        if (send) {
                            val res = recorder.stop()
                            if (res != null) {
                                val rid = replyTarget?.id
                                replyTarget = null
                                scope.launch {
                                    runCatching {
                                        TdClient.sendVoiceNote(chatId, res.file.absolutePath, res.durationSeconds, rid)
                                    }
                                }
                            }
                        } else {
                            recorder.cancel()
                        }
                    }
                },
                recording = recording,
                hasPendingMedia = pendingMedia != null || editTarget != null,
                onContentReceived = { uri ->
                    // Keyboard inserted a GIF or sticker. Mime-sniff via the
                    // ContentResolver, copy off the content:// URI into our
                    // cache (since the keyboard's permission may expire) and
                    // send: animation path for GIF / mp4 so it auto-plays in
                    // the chat, photo path for static images.
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val mime = (context.contentResolver.getType(uri) ?: "").lowercase()
                        val file = com.secondream.novagram.util.FileUtils.copyUriToCache(context, uri) ?: return@launch
                        runCatching {
                            if (mime == "image/gif" || mime == "video/mp4") {
                                TdClient.sendAnimation(chatId, file.absolutePath)
                            } else {
                                TdClient.sendPhoto(chatId, file.absolutePath)
                            }
                        }
                    }
                },
                focusRequester = inputFocus
            )
        }
    }

    var showStickerPicker by remember { mutableStateOf(false) }

    if (showAttach) {
        AttachSheet(
            onDismiss = { showAttach = false },
            onPickPhoto = {
                photoLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
            },
            onPickDocument = { docLauncher.launch(arrayOf("*/*")) },
            onPickSticker = {
                showAttach = false
                showStickerPicker = true
            }
        )
    }

    if (showStickerPicker) {
        StickerPickerSheet(
            onDismiss = { showStickerPicker = false },
            onPick = { sticker ->
                val rid = replyTarget?.id
                showStickerPicker = false
                replyTarget = null
                scope.launch { runCatching { TdClient.sendSticker(chatId, sticker, rid) } }
            }
        )
    }

    // Forward flow: appears when the user taps "Forward" in the message
    // actions sheet. Two-step bottom sheet — first pick a destination chat,
    // then preview the message and optionally add a caption before sending.
    // The forward goes out as a TDLib ForwardMessages call (which preserves
    // the "Forwarded from" header); when a caption was entered we follow up
    // with a plain sendText to the same chat so it lands right after, the
    // same UX as adding a note when sharing in Telegram.
    forwardTarget?.let { msg ->
        com.secondream.novagram.ui.components.ForwardChatPickerSheet(
            sourceMessage = msg,
            onDismiss = { forwardTarget = null },
            onForward = { destChatId, caption ->
                forwardTarget = null
                scope.launch {
                    runCatching {
                        TdClient.forwardMessages(destChatId, msg.chatId, longArrayOf(msg.id))
                    }
                    if (!caption.isNullOrBlank()) {
                        // Best-effort: even if the forward call failed we
                        // still try the caption so the user's typed note
                        // isn't lost silently. If TDLib also rejects the
                        // text, the user can retry from the sent-failed UI.
                        runCatching { TdClient.sendText(destChatId, caption) }
                    }
                }
            }
        )
    }

    // Profile preview sheet: shown when the user taps a sender's avatar
    // in a group chat. The sheet handles the start-private-chat flow
    // itself (we just need to navigate when it tells us to). Dismissing
    // the sheet clears profileSheetUserId so it doesn't reopen on
    // recomposition.
    profileSheetUserId?.let { uid ->
        com.secondream.novagram.ui.components.UserProfileSheet(
            userId = uid,
            onDismiss = { profileSheetUserId = null },
            onStartChat = { newChatId ->
                profileSheetUserId = null
                onOpenChat(newChatId, null)
            }
        )
    }

    // Pinned-messages list sheet. Tapping a row jumps the LazyColumn to
    // that message if it's already in the in-memory window; we don't yet
    // re-load history around messages beyond the window — most pinned
    // messages users care about are recent enough to be in scope.
    if (pinnedSheetOpen) {
        com.secondream.novagram.ui.components.PinnedListSheet(
            chatId = chatId,
            onDismiss = { pinnedSheetOpen = false },
            onJumpToMessage = { targetId ->
                pinnedSheetOpen = false
                jumpToMessage(targetId)
            }
        )
    }

    // AI actions sheet — opens when the user picks the AI tile in the
    // message actions grid. Builds context from the surrounding ~12
    // messages (newest first since reverseLayout) so prompts like
    // "Riassumi il thread" have something to chew on. The "Usa come
    // risposta" action populates the input bar (preserving the user's
    // ability to edit before sending); "Invia" fires sendText directly.
    aiTarget?.let { target ->
        val text = when (val c = target.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> c.caption.text.ifBlank { "[foto]" }
            is TdApi.MessageVideo -> c.caption.text.ifBlank { "[video]" }
            is TdApi.MessageDocument -> c.caption.text.ifBlank { c.document.fileName.ifBlank { "[file]" } }
            else -> "[messaggio]"
        }
        val senderName = when (val s = target.senderId) {
            is TdApi.MessageSenderUser -> {
                val u = TdClient.getCachedUser(s.userId)
                "${u?.firstName.orEmpty()} ${u?.lastName.orEmpty()}".trim().ifBlank { null }
            }
            is TdApi.MessageSenderChat -> TdClient.getCachedChat(s.chatId)?.title
            else -> null
        }
        // Last ~12 messages around the target as plain "Sender: text" lines.
        val targetIdx = messages.indexOfFirst { it.id == target.id }
        val contextLines = if (targetIdx >= 0) {
            val from = (targetIdx - 6).coerceAtLeast(0)
            val to = (targetIdx + 6).coerceAtMost(messages.lastIndex)
            messages.subList(from, to + 1).asReversed().mapNotNull { m ->
                val mText = when (val c = m.content) {
                    is TdApi.MessageText -> c.text.text
                    is TdApi.MessagePhoto -> "[foto] ${c.caption.text}".trim()
                    is TdApi.MessageVideo -> "[video] ${c.caption.text}".trim()
                    is TdApi.MessageVoiceNote -> "[vocale]"
                    is TdApi.MessageSticker -> "[sticker] ${c.sticker.emoji}".trim()
                    else -> null
                } ?: return@mapNotNull null
                val sName = when (val s = m.senderId) {
                    is TdApi.MessageSenderUser -> {
                        val u = TdClient.getCachedUser(s.userId)
                        "${u?.firstName.orEmpty()}".trim().ifBlank { "Utente" }
                    }
                    is TdApi.MessageSenderChat -> TdClient.getCachedChat(s.chatId)?.title ?: "Chat"
                    else -> "Utente"
                }
                "$sName: $mText"
            }
        } else emptyList()
        com.secondream.novagram.ai.AiActionsSheet(
            messageText = text,
            senderName = senderName,
            context = contextLines,
            onDismiss = { aiTarget = null },
            onUseAsReply = { aiReply ->
                aiTarget = null
                run {
                    val replyText = aiReply
                    input = androidx.compose.ui.text.input.TextFieldValue(
                        replyText,
                        androidx.compose.ui.text.TextRange(replyText.length)
                    )
                }
                replyTarget = target
            },
            onSendDirect = { aiReply ->
                aiTarget = null
                scope.launch {
                    runCatching { TdClient.sendText(chatId, aiReply, target.id) }
                }
            }
        )
    }

    deleteTarget?.let { msg ->
        val copyableText: String? = when (val c = msg.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> c.caption.text.ifBlank { null }
            is TdApi.MessageVideo -> c.caption.text.ifBlank { null }
            is TdApi.MessageDocument -> c.caption.text.ifBlank { null }
            is TdApi.MessageAnimation -> c.caption.text.ifBlank { null }
            else -> null
        }
        val senderUserId = (msg.senderId as? TdApi.MessageSenderUser)?.userId
        // Only build the onEdit callback for messages where editing makes
        // sense at all: outgoing, and either pure text OR media with a
        // caption. TDLib's MessageProperties.canBeEdited inside the sheet
        // is the authoritative gate (handles time window, channel admin
        // rules, etc.); this is just the content-type prefilter so we
        // never offer edit on something structurally un-editable like a
        // voice note or a poll.
        val isEditableContent = when (msg.content) {
            is TdApi.MessageText -> true
            is TdApi.MessagePhoto,
            is TdApi.MessageVideo,
            is TdApi.MessageDocument,
            is TdApi.MessageAnimation,
            is TdApi.MessageAudio -> true
            else -> false
        }
        val onEdit: (() -> Unit)? = if (msg.isOutgoing && isEditableContent) {
            {
                editTarget = msg
                deleteTarget = null
            }
        } else null
        // Compute downloadable media info for the Save action. We only
        // surface it when the message actually has a downloaded file on
        // disk — saving an undownloaded photo would mean copying nothing.
        // Display name folds in a sensible suffix when TDLib doesn't
        // carry one (photos from camera land without a name).
        data class SaveSpec(val path: String, val name: String, val mime: String,
                            val category: com.secondream.novagram.util.FileUtils.SaveCategory)
        val saveSpec: SaveSpec? = run {
            // A file is "available to save" if TDLib finished downloading
            // it OR the local path exists on disk (covers our own sent
            // media: not downloaded but right there locally).
            fun TdApi.File.availableLocally(): Boolean {
                val p = local?.path
                return !p.isNullOrBlank() && (
                    local.isDownloadingCompleted ||
                    runCatching { java.io.File(p).exists() }.getOrDefault(false)
                )
            }
            when (val c = msg.content) {
                is TdApi.MessagePhoto -> {
                    val biggest = c.photo.sizes.maxByOrNull { it.photo.size }
                    val p = biggest?.photo?.local?.path
                    if (!p.isNullOrBlank() && biggest.photo.availableLocally())
                        SaveSpec(p, "photo_${msg.id}.jpg", "image/jpeg",
                            com.secondream.novagram.util.FileUtils.SaveCategory.Media)
                    else null
                }
                is TdApi.MessageVideo -> {
                    val p = c.video.video.local?.path
                    if (!p.isNullOrBlank() && c.video.video.availableLocally())
                        SaveSpec(p, c.video.fileName.ifBlank { "video_${msg.id}.mp4" },
                            c.video.mimeType.ifBlank { "video/mp4" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.Media)
                    else null
                }
                is TdApi.MessageAnimation -> {
                    val p = c.animation.animation.local?.path
                    if (!p.isNullOrBlank() && c.animation.animation.availableLocally())
                        SaveSpec(p, c.animation.fileName.ifBlank { "anim_${msg.id}.mp4" },
                            c.animation.mimeType.ifBlank { "video/mp4" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.Media)
                    else null
                }
                is TdApi.MessageDocument -> {
                    val p = c.document.document.local?.path
                    if (!p.isNullOrBlank() && c.document.document.availableLocally())
                        SaveSpec(p, c.document.fileName.ifBlank { "file_${msg.id}" },
                            c.document.mimeType.ifBlank { "application/octet-stream" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.File)
                    else null
                }
                is TdApi.MessageAudio -> {
                    val p = c.audio.audio.local?.path
                    if (!p.isNullOrBlank() && c.audio.audio.availableLocally())
                        SaveSpec(p, c.audio.fileName.ifBlank { "audio_${msg.id}.mp3" },
                            c.audio.mimeType.ifBlank { "audio/mpeg" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.File)
                    else null
                }
                else -> null
            }
        }
        val onSaveToDownloads: (() -> Unit)? = saveSpec?.let { spec ->
            {
                scope.launch(Dispatchers.IO) {
                    val ok = com.secondream.novagram.util.FileUtils.saveToDownloads(
                        context = context,
                        sourcePath = spec.path,
                        displayName = spec.name,
                        mimeType = spec.mime,
                        category = spec.category
                    )
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(
                                if (ok) R.string.media_save_success else R.string.media_save_error
                            ),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                deleteTarget = null
            }
        }
        MessageActionsSheet(
            message = msg,
            isAdmin = isAdmin,
            senderUserId = senderUserId,
            myUserId = myUserId,
            onDismiss = { deleteTarget = null },
            onCopy = if (!copyableText.isNullOrBlank()) {
                {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(copyableText))
                    deleteTarget = null
                }
            } else null,
            onReply = {
                replyTarget = msg
                deleteTarget = null
            },
            onForward = {
                forwardTarget = msg
                deleteTarget = null
            },
            onEdit = onEdit,
            onSaveToDownloads = onSaveToDownloads,
            onAi = if (!appearance.anthropicApiKey.isNullOrBlank()) {
                {
                    aiTarget = msg
                    deleteTarget = null
                }
            } else null,
            onTogglePin = if (canPinHere) {
                {
                    val wasPinned = msg.id == pinnedMessageId
                    scope.launch {
                        runCatching {
                            if (wasPinned) TdClient.unpinChatMessage(chatId, msg.id)
                            else TdClient.pinChatMessage(chatId, msg.id)
                        }
                    }
                    pinnedMessageId = if (wasPinned) 0L else msg.id
                    // Update the banner immediately — don't wait for a
                    // server round trip or a chat reload.
                    pinned = if (wasPinned) null else msg
                    deleteTarget = null
                }
            } else null,
            isPinned = msg.id == pinnedMessageId,
            onReact = { emoji ->
                val chosenSame = msg.interactionInfo?.reactions?.reactions?.any {
                    it.isChosen && (it.type as? TdApi.ReactionTypeEmoji)?.emoji == emoji
                } == true
                // Optimistic local update: mutate the message's reactions
                // in place and bump the interactionRevision so the bubble
                // recomposes RIGHT NOW. Without this the user taps an emoji,
                // the sheet closes, and the chip only pops in once TDLib
                // sends back UpdateMessageInteractionInfo a moment later —
                // which felt laggy because the visual feedback lagged the
                // tap. When the server response eventually arrives it
                // overwrites this with the canonical state, so any
                // discrepancy (e.g. another reactor counted) self-corrects.
                msg.interactionInfo = applyReactionLocally(
                    msg.interactionInfo,
                    emoji,
                    add = !chosenSame
                )
                interactionRevisions[msg.id] = (interactionRevisions[msg.id] ?: 0) + 1
                scope.launch {
                    runCatching {
                        if (chosenSame) {
                            TdClient.removeEmojiReaction(chatId, msg.id, emoji)
                        } else {
                            TdClient.addEmojiReaction(chatId, msg.id, emoji)
                        }
                    }
                }
                deleteTarget = null
            },
            onDeleteForMe = {
                scope.launch {
                    runCatching {
                        TdClient.deleteMessages(chatId, longArrayOf(msg.id), revoke = false)
                    }
                }
                deleteTarget = null
            },
            onDeleteForEveryone = {
                scope.launch {
                    runCatching {
                        TdClient.deleteMessages(chatId, longArrayOf(msg.id), revoke = true)
                    }
                }
                deleteTarget = null
            },
            onMuteAuthor = {
                if (senderUserId != null) {
                    scope.launch {
                        runCatching { TdClient.muteGroupUser(chatId, senderUserId) }
                    }
                }
                deleteTarget = null
            },
            onKickAuthor = {
                if (senderUserId != null) {
                    scope.launch {
                        runCatching { TdClient.kickGroupUser(chatId, senderUserId) }
                    }
                }
                deleteTarget = null
            }
        )
    }

    // TTL chooser dialog. Five presets matching Telegram's stock UI:
    // off, 1 minute, 1 hour, 1 day, 1 week. Tap dispatches
    // SetChatMessageAutoDeleteTime and dismisses; works for any chat
    // type (the call is a no-op on chats where TDLib doesn't allow it,
    // wrapped in runCatching).
    if (ttlDialogOpen) {
        val ttlOptions = listOf(
            0 to stringResource(R.string.ttl_off),
            60 to stringResource(R.string.ttl_1m),
            3600 to stringResource(R.string.ttl_1h),
            86400 to stringResource(R.string.ttl_1d),
            604800 to stringResource(R.string.ttl_1w)
        )
        AlertDialog(
            onDismissRequest = { ttlDialogOpen = false },
            title = { Text(stringResource(R.string.chat_set_ttl)) },
            text = {
                Column {
                    ttlOptions.forEach { (seconds, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ttlDialogOpen = false
                                    scope.launch {
                                        runCatching {
                                            TdClient.setMessageAutoDeleteTime(chatId, seconds)
                                        }
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 14.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ttlDialogOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (needMicPermission) {
        AlertDialog(
            onDismissRequest = { needMicPermission = false },
            title = { Text(stringResource(R.string.mic_permission_title)) },
            text = { Text(stringResource(R.string.mic_permission_body)) },
            confirmButton = {
                TextButton(onClick = { needMicPermission = false }) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    if (infoOpen) {
        ChatInfoDialog(
            chatId = chatId,
            onDismiss = { infoOpen = false }
        )
    }

    if (deleteOpen) {
        val isPrivate = cachedChatLive?.type is TdApi.ChatTypePrivate
        var alsoRevoke by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(R.string.delete_chat_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_chat_confirm_body))
                    if (isPrivate) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { alsoRevoke = !alsoRevoke }
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = alsoRevoke,
                                onCheckedChange = { alsoRevoke = it }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.delete_chat_for_everyone))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val revoke = alsoRevoke && isPrivate
                    deleteOpen = false
                    scope.launch {
                        runCatching {
                            TdClient.deleteChatHistory(chatId, removeFromChatList = true, revoke = revoke)
                        }
                        onBack()
                    }
                }) {
                    Text(stringResource(R.string.action_delete_chat), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text(stringResource(R.string.delete_chat_cancel))
                }
            }
        )
    }
    if (leaveOpen) {
        val cachedNow = TdClient.getCachedChat(chatId)
        val isChan = cachedNow?.type is TdApi.ChatTypeSupergroup &&
            (cachedNow.type as TdApi.ChatTypeSupergroup).isChannel
        AlertDialog(
            onDismissRequest = { leaveOpen = false },
            title = { Text(stringResource(R.string.leave_group_confirm, chatTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    leaveOpen = false
                    scope.launch {
                        runCatching { TdClient.leaveChat(chatId) }
                        onBack()
                    }
                }) {
                    Text(
                        stringResource(
                            if (isChan) R.string.action_leave_channel
                            else R.string.action_leave_group
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveOpen = false }) {
                    Text(stringResource(R.string.delete_chat_cancel))
                }
            }
        )
    }
    } // close back-swipe Box
}

/**
 * Read-only info dialog shown when the user taps the chat title.
 *
 * Picks what to display based on the chat type:
 *  - Private:      user name, bio (from getUserFullInfo), phone if known.
 *  - Group/super:  title, description, member count.
 *  - Channel:      title, description, subscriber count.
 * Falls back to just the title if any of the *FullInfo calls fail (e.g. the
 * user is no longer accessible). Doesn't navigate anywhere — this is a quick
 * peek, not a profile screen, which we'll add as its own route in a later
 * round.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatInfoDialog(chatId: Long, onDismiss: () -> Unit) {
    val chat = remember(chatId) { TdClient.getCachedChat(chatId) }
    val title = chat?.title ?: stringResource(R.string.chat_default_title)

    var subtitle by remember(chatId) { mutableStateOf<String?>(null) }
    var description by remember(chatId) { mutableStateOf<String?>(null) }
    var phone by remember(chatId) { mutableStateOf<String?>(null) }
    var username by remember(chatId) { mutableStateOf<String?>(null) }

    LaunchedEffect(chatId) {
        val c = chat ?: return@LaunchedEffect
        when (val t = c.type) {
            is TdApi.ChatTypePrivate -> {
                val user = TdClient.getCachedUser(t.userId)
                    ?: runCatching { TdClient.getUser(t.userId) }.getOrNull()
                username = user?.usernames?.editableUsername
                phone = user?.phoneNumber?.takeIf { it.isNotBlank() }?.let { "+$it" }
                subtitle = username?.let { "@$it" }
                description = runCatching { TdClient.getUserFullInfo(t.userId).bio?.text }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            }
            is TdApi.ChatTypeBasicGroup -> {
                val info = runCatching { TdClient.getBasicGroupFullInfo(t.basicGroupId) }.getOrNull()
                subtitle = info?.members?.size?.let { "$it ${labelMembers(it)}" }
                description = info?.description?.takeIf { it.isNotBlank() }
            }
            is TdApi.ChatTypeSupergroup -> {
                val info = runCatching { TdClient.getSupergroupFullInfo(t.supergroupId) }.getOrNull()
                subtitle = info?.memberCount?.let { "$it ${labelMembers(it, channel = t.isChannel)}" }
                description = info?.description?.takeIf { it.isNotBlank() }
                username = TdClient.getCachedChat(chatId)?.let {
                    (it.type as? TdApi.ChatTypeSupergroup)?.let { _ -> null } // username not on Chat; fetch via supergroup
                }
            }
            else -> {}
        }
    }

    // Full-bleed modal sheet — replaces the tiny AlertDialog with something
    // that feels like a profile screen: big circular avatar, name, subtitle,
    // detail rows (bio/description, username, phone). Dismissed by swiping
    // down or tapping outside.
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Big circular avatar — 120dp, centered.
            com.secondream.novagram.ui.components.Avatar(
                file = chat?.photo?.small,
                fallbackText = title,
                bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(chatId),
                size = 120.dp
            )
            Spacer(Modifier.height(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))

            description?.let {
                ProfileDetailRow(
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Info,
                    label = stringResource(R.string.chat_info_bio_label),
                    value = it
                )
            }
            username?.let {
                ProfileDetailRow(
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.At,
                    label = stringResource(R.string.chat_info_username_label),
                    value = "@$it"
                )
            }
            phone?.let {
                ProfileDetailRow(
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Phone,
                    label = stringResource(R.string.chat_info_phone_label),
                    value = it
                )
            }
            if (description == null && username == null && phone == null && subtitle == null) {
                Text(
                    stringResource(R.string.chat_info_no_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ProfileDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun labelMembers(count: Int, channel: Boolean = false): String =
    if (channel) "iscritti" else "membri"

@Composable
private fun InputBar(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    placeholderText: String?,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: (sendIt: Boolean) -> Unit,
    recording: Boolean,
    // When there's pending media (photo/video/document staged for send via
    // the attach sheet) we want the SEND button to be active even with an
    // empty text field — captions are optional. Without this the user
    // would see the mic button on an empty caption and have no way to
    // actually push the media out.
    hasPendingMedia: Boolean = false,
    // Called when the IME (Gboard, SwiftKey, etc.) inserts rich content
    // — typically a GIF or sticker. We get a content:// URI from the
    // keyboard and forward it to the chat for upload.
    onContentReceived: (android.net.Uri) -> Unit = {},
    // Allows the chat screen to programmatically pop the IME — used when
    // the user replies via swipe or button so they can start typing
    // immediately instead of having to tap the field first.
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    // Pull the custom input-bar color if the user has set one in the
    // theme builder. Falls back to MaterialTheme.colorScheme.background
    // so the bar tracks whatever surface the chat is on.
    val appearance by com.secondream.novagram.settings.AppSettings.appearance.collectAsState(
        initial = com.secondream.novagram.settings.AppearancePrefs()
    )
    val cs = MaterialTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    // Input bar always paints with the chat background colour now —
    // the separate `customInputBarArgb` knob was removed from the
    // theme builder since picking a different colour for just the
    // strip below the messages made the surface feel disjoint. The
    // pref still exists for backward compat (old saved themes) but
    // we ignore it at render time.
    val inputBg = cs.background
    // Bubble that wraps the text field. On light themes we go pure white
    // with black text — matches Telegram's light skin and what Eugenio
    // explicitly asked for. On dark themes we keep the dark elevated
    // surface (Ink.SurfaceHi) so the bubble still reads against the chat
    // backdrop instead of disappearing into it.
    val bubbleBg = if (isLight) androidx.compose.ui.graphics.Color.White else Ink.SurfaceHi
    val bubbleBorder = if (isLight) cs.outline.copy(alpha = 0.35f) else Ink.SurfaceLine
    val textColor = if (isLight) androidx.compose.ui.graphics.Color.Black else Ink.Cream
    val placeholderColor = if (isLight) {
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)
    } else Ink.Faint
    val iconTint = if (isLight) cs.onSurfaceVariant else Ink.Muted
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(inputBg)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // IMPORTANT: the trailing MicButton must stay the SAME composable
        // instance whether or not we're recording. Previously the whole
        // row was swapped by `if (recording)`, which disposed the mic
        // button mid-press the instant recording started — its gesture
        // detector got cancelled, tryAwaitRelease never returned, and
        // onMicUp never fired, so releasing sent nothing. Now we keep one
        // Row; only the LEADING content swaps (input fields vs the live
        // recording indicator), and the trailing button is a single
        // persistent MicButton (or the Send button when there's text).
        Row(
            verticalAlignment = if (recording) Alignment.CenterVertically else Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            if (recording) {
                var elapsed by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) {
                    while (true) { kotlinx.coroutines.delay(1000); elapsed += 1 }
                }
                val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec-pulse")
                    .animateFloat(
                        initialValue = 0.5f, targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(700),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "rec-alpha"
                    )
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = pulse))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    formatRecDuration(elapsed),
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = iconTint,
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
            } else {
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Paperclip, null, tint = iconTint)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 150.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(bubbleBg)
                        .border(0.5.dp, bubbleBorder, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.text.isEmpty()) {
                        Text(
                            placeholderText ?: stringResource(R.string.input_placeholder),
                            color = placeholderColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                        // maxLines=6 caps the field at ~6 lines worth of
                        // height (matches Telegram); above that the field
                        // scrolls INTERNALLY which is what makes the
                        // cursor stay visible while typing. The previous
                        // outer Modifier.verticalScroll wrapped the whole
                        // text — that wrapper's scroll state has no idea
                        // where the caret is, so for long edits the cursor
                        // could drift off-screen at the bottom while the
                        // visible area stayed at the top. Removing it lets
                        // BasicTextField's built-in cursor follow do its
                        // job.
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (focusRequester != null)
                                    Modifier.focusRequester(focusRequester)
                                else Modifier
                            )
                            .contentReceiver { transferableContent ->
                                // Gboard/SwiftKey hand us a content:// URI for the
                                // inserted GIF or image. Forward the URI to the
                                // chat screen which copies + sends it. Any
                                // non-URI items (raw text) we let the system
                                // handle as a normal paste.
                                transferableContent.consume { item ->
                                    val u = item.uri
                                    if (u != null) {
                                        onContentReceived(u)
                                        true
                                    } else false
                                }
                            }
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            // Trailing slot. Send button only when NOT recording and there's
            // something to send; otherwise the persistent MicButton.
            if (!recording && (value.text.isNotBlank() || hasPendingMedia)) {
                IconButton(
                    onClick = onSend,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                MicButton(recording = recording, onDown = onMicDown, onUp = onMicUp)
            }
        }
    }
}

@Composable
private fun MicButton(recording: Boolean, onDown: () -> Unit, onUp: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (recording) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        val released = tryAwaitRelease()
                        onUp(released)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            com.secondream.novagram.ui.icons.PhosphorIcons.Microphone,
            null,
            tint = if (recording) MaterialTheme.colorScheme.onError
                   else MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * In-chat search bar. Compact row: close button, text field, result count
 * (i / n), prev (↑), next (↓). Up moves to the previous (newer) match,
 * down to the next (older) match — matches what most chat clients do.
 */
@Composable
private fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<Long>,
    index: Int,
    loading: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Two-step "show keyboard": requestFocus() alone moves the
        // cursor into the TextField but on some Android builds the
        // IME doesn't pop up until a real touch event. Calling
        // `.show()` on the keyboard controller right after the focus
        // event forces it open — same trick stock Telegram uses for
        // its in-chat search field. The 30ms delay gives the focus
        // event time to land before we shout for the IME.
        runCatching { focus.requestFocus() }
        kotlinx.coroutines.delay(30)
        runCatching { keyboard?.show() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(com.secondream.novagram.ui.icons.PhosphorIcons.X, null)
        }
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.chat_search_placeholder)) },
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            shape = RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
        )
        Spacer(Modifier.width(6.dp))
        // i / n counter — hidden when there's nothing to count.
        if (query.isNotBlank()) {
            Text(
                when {
                    loading -> "…"
                    results.isEmpty() -> "0"
                    else -> "${index + 1}/${results.size}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onPrev, enabled = results.isNotEmpty()) {
            Icon(com.secondream.novagram.ui.icons.PhosphorIcons.CaretUp, null)
        }
        IconButton(onClick = onNext, enabled = results.isNotEmpty()) {
            Icon(com.secondream.novagram.ui.icons.PhosphorIcons.CaretDown, null)
        }
    }
}

@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickDocument: () -> Unit,
    onPickSticker: () -> Unit
) {
    val state = rememberModalBottomSheetState()
    // Hardcoded Ink.* tokens were dark-theme only — on light themes the
    // attach sheet was reading as a dark slab over the white chat list.
    // Route everything through MaterialTheme.colorScheme so the sheet
    // tracks whichever skin is active.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(20.dp).navigationBarsPadding()) {
            Text(
                stringResource(R.string.attach_title),
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))
            // 3 equal tiles in a row — same visual language as the message
            // actions grid (icon over label, soft rounded square, press
            // animation). Eugenio wanted these to match.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AttachTile(
                    label = stringResource(R.string.attach_photo_or_video),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Image,
                    onClick = onPickPhoto,
                    modifier = Modifier.weight(1f)
                )
                AttachTile(
                    label = stringResource(R.string.attach_document_or_file),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.FileText,
                    onClick = onPickDocument,
                    modifier = Modifier.weight(1f)
                )
                AttachTile(
                    label = stringResource(R.string.attach_sticker),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Smiley,
                    onClick = onPickSticker,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AttachTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh
        ),
        label = "attach-tile-press"
    )
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 20.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.Medium,
            maxLines = 2
        )
    }
}

@Composable
private fun MessageActionsSheet(
    message: TdApi.Message,
    isAdmin: Boolean,
    senderUserId: Long?,
    myUserId: Long?,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    onReply: () -> Unit,
    onForward: () -> Unit,
    /** Triggered when the user taps "Modifica" — only ever wired by the
     *  parent when the message is one of theirs AND the content type is
     *  editable (text body or media-with-caption). null hides the option
     *  entirely so we never offer it on someone else's message. */
    onEdit: (() -> Unit)?,
    onSaveToDownloads: (() -> Unit)?,
    /** Open the AI actions sheet for this message. null hides the AI tile
     *  (e.g. user hasn't configured an Anthropic API key in settings). */
    onAi: (() -> Unit)?,
    /** Pin/unpin this message. null hides the pin tile (no permission). */
    onTogglePin: (() -> Unit)?,
    /** Whether this message is currently pinned (controls tile label/icon). */
    isPinned: Boolean,
    onReact: (String) -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onMuteAuthor: () -> Unit,
    onKickAuthor: () -> Unit
) {
    val state = rememberModalBottomSheetState()
    val cachedChat = TdClient.getCachedChat(message.chatId)
    // Authoritative "can edit" + "delete for everyone" flags from TDLib's
    // MessageProperties. TDLib applies the actual server-side rules — the
    // 48h edit window, bot vs user, channel admin permissions — and gives
    // us booleans we can trust. Both come from the same call so we only
    // pay one round trip per sheet open.
    var canRevokeFromServer by remember(message.id) { mutableStateOf<Boolean?>(null) }
    var canEditFromServer by remember(message.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(message.id) {
        runCatching {
            TdClient.getMessageProperties(message.chatId, message.id)
        }.onSuccess { props ->
            canRevokeFromServer = props.canBeDeletedForAllUsers
            canEditFromServer = props.canBeEdited
        }
    }
    // While the round trip is in flight we fall back to the conservative
    // heuristic (outgoing || private || isAdmin) so the button is shown
    // immediately rather than popping in late.
    val canRevoke = canRevokeFromServer ?: (
        message.isOutgoing ||
            cachedChat?.type is TdApi.ChatTypePrivate ||
            isAdmin
    )
    // Admin actions are only meaningful in groups, only against someone
    // who isn't you and isn't yourself the sender. We hide the entire
    // block otherwise to keep the sheet uncluttered.
    // Detect whether the sender is the group CREATOR. Admins must never
    // see ban/mute against the owner — TDLib would reject it anyway, but
    // showing the option is confusing. Fetched lazily on sheet open; until
    // it resolves we assume "not owner" so the (rare) race just briefly
    // shows the actions, never hides them incorrectly for normal members.
    var senderIsOwner by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(message.id, senderUserId) {
        val uid = senderUserId
        if (uid != null && cachedChat?.type !is TdApi.ChatTypePrivate) {
            val status = TdClient.getChatMemberStatus(message.chatId, uid)
            senderIsOwner = status is TdApi.ChatMemberStatusCreator
        }
    }
    val showAdminActions = isAdmin &&
        cachedChat?.type !is TdApi.ChatTypePrivate &&
        senderUserId != null &&
        senderUserId != myUserId &&
        !senderIsOwner

    val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(20.dp).navigationBarsPadding()) {
            // Quick reactions bar at the top. The 6 most universally used
            // emojis on Telegram. Tapping fires onReact and dismisses the
            // sheet; the chip already appears on the message because of
            // the interaction-info flow.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (emoji in quickReactions) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onReact(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Action tile grid (3 columns) ──────────────────────────
            // Eugenio asked for a tile grid instead of the linear list of
            // rows — faster to scan, more visual, fewer taps to think
            // about. Each tile is an icon + label in a soft-coloured
            // rounded square. Destructive actions go bottom-right with
            // the error tint so they read as separate from neutral ops.
            val tiles = buildList<ActionTile> {
                val editAllowed = canEditFromServer ?: message.isOutgoing
                // AI sits first so it's the most prominent tile when
                // configured — the feature we want users to discover.
                if (onAi != null) {
                    add(ActionTile(
                        stringResource(R.string.action_ai),
                        com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                        onAi
                    ))
                }
                add(ActionTile(stringResource(R.string.action_reply), com.secondream.novagram.ui.icons.PhosphorIcons.Reply, onReply))
                if (onTogglePin != null) {
                    add(ActionTile(
                        stringResource(if (isPinned) R.string.action_unpin else R.string.action_pin),
                        com.secondream.novagram.ui.icons.PhosphorIcons.PushPin,
                        onTogglePin
                    ))
                }
                if (onEdit != null && editAllowed) {
                    add(ActionTile(stringResource(R.string.action_edit), com.secondream.novagram.ui.icons.PhosphorIcons.PencilSimple, onEdit))
                }
                add(ActionTile(stringResource(R.string.action_forward),
                    com.secondream.novagram.ui.icons.PhosphorIcons.Forward, onForward))
                if (onCopy != null) {
                    add(ActionTile(stringResource(R.string.action_copy),
                        com.secondream.novagram.ui.icons.PhosphorIcons.Copy, onCopy))
                }
                if (onSaveToDownloads != null) {
                    add(ActionTile(stringResource(R.string.action_save),
                        com.secondream.novagram.ui.icons.PhosphorIcons.DownloadSimple, onSaveToDownloads))
                }
                add(ActionTile(stringResource(R.string.delete_for_me),
                    com.secondream.novagram.ui.icons.PhosphorIcons.Trash, onDeleteForMe, destructive = true))
                if (canRevoke) {
                    add(ActionTile(stringResource(R.string.delete_for_everyone),
                        com.secondream.novagram.ui.icons.PhosphorIcons.Trash, onDeleteForEveryone, destructive = true))
                }
                if (showAdminActions) {
                    add(ActionTile(stringResource(R.string.action_mute_author),
                        com.secondream.novagram.ui.icons.PhosphorIcons.SpeakerSlash, onMuteAuthor))
                    add(ActionTile(stringResource(R.string.action_kick_author),
                        com.secondream.novagram.ui.icons.PhosphorIcons.UserMinus, onKickAuthor, destructive = true))
                }
            }
            // Grid: 3 columns. We row-chunk manually instead of using
            // LazyVerticalGrid because the sheet has finite height and
            // LazyVerticalGrid in a bottom-sheet measures awkwardly with
            // intrinsic-size parents.
            tiles.chunked(3).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { tile ->
                        ActionTileButton(
                            tile = tile,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Pad short final rows so the last tiles don't stretch
                    // to fill all 3 columns — they keep their natural
                    // square aspect.
                    repeat(3 - row.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Single entry in the action grid. */
private data class ActionTile(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false
)

/**
 * One square-ish tile in the MessageActionsSheet grid. Icon top, label
 * below, soft tinted background. Tapping triggers a tiny scale-down
 * spring so the press feels responsive even when the parent sheet is
 * about to dismiss. Animation is the *only* lifecycle on the tile so
 * scaling adds basically zero overhead, even on low-end devices.
 */
@Composable
private fun ActionTileButton(
    tile: ActionTile,
    modifier: Modifier = Modifier
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh
        ),
        label = "tile-press"
    )
    val cs = MaterialTheme.colorScheme
    val bg = if (tile.destructive) cs.errorContainer.copy(alpha = 0.4f)
             else cs.surfaceVariant
    val iconTint = if (tile.destructive) cs.error else cs.primary
    val labelColor = if (tile.destructive) cs.error else cs.onSurface
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = tile.onClick
            )
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            tile.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tile.label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DeleteOption(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector = com.secondream.novagram.ui.icons.PhosphorIcons.Trash
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.SurfaceHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Ink.Bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = if (destructive) Ink.Error else Ink.Amber,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) Ink.Error else Ink.Cream
        )
    }
}

private fun handlePickedMedia(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    chatId: Long,
    uri: Uri,
    asPhoto: Boolean,
    replyToMessageId: Long? = null
) {
    scope.launch(Dispatchers.IO) {
        val file = FileUtils.copyUriToCache(context, uri) ?: return@launch
        if (asPhoto && isImage(file.name)) {
            runCatching { TdClient.sendPhoto(chatId, file.absolutePath, replyToMessageId = replyToMessageId) }
        } else {
            runCatching { TdClient.sendDocument(chatId, file.absolutePath, replyToMessageId = replyToMessageId) }
        }
    }
}

private fun isImage(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".heic")
}

/**
 * Tiny banner that sits above the InputBar while the user is replying to a
 * specific message. Tap on the X clears the reply target; sending a message
 * with this banner visible attaches reply_to to the SendMessage call.
 */
@Composable
private fun ReplyPreview(message: TdApi.Message, onCancel: () -> Unit) {
    val preview = remember(message.id) {
        when (val c = message.content) {
            is TdApi.MessageText -> c.text.text.take(80)
            is TdApi.MessagePhoto -> "📷 Foto" + (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            is TdApi.MessageVideo -> "🎬 Video"
            is TdApi.MessageVoiceNote -> "🎤 Vocale"
            is TdApi.MessageDocument -> "📎 ${c.document.fileName}"
            is TdApi.MessageAnimation -> "GIF"
            is TdApi.MessageSticker -> "Sticker"
            else -> "Messaggio"
        }
    }
    val senderName = remember(message.senderId) {
        when (val s = message.senderId) {
            is TdApi.MessageSenderUser -> {
                val u = TdClient.getCachedUser(s.userId)
                "${u?.firstName.orEmpty()} ${u?.lastName.orEmpty()}".trim().ifBlank { "Utente" }
            }
            is TdApi.MessageSenderChat -> TdClient.getCachedChat(s.chatId)?.title ?: "Chat"
            else -> ""
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Rispondi a $senderName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.X,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Twin of [ReplyPreview], shown above the InputBar while the user is
 * editing one of their own messages. Visually it mirrors the reply
 * banner (same 3dp accent stripe + two-line layout) but the heading
 * reads "Modifica messaggio" so the affordance is unmistakable, and
 * the preview shows the ORIGINAL text/caption — useful when the user
 * has already started typing changes and wants a reference of what
 * the message looked like before.
 *
 * Tapping the X clears editTarget and resets the input back to empty
 * (handled by the caller). Sending while this banner is visible routes
 * to EditMessageText / EditMessageCaption in the chat's onSend handler.
 */
@Composable
private fun EditPreview(message: TdApi.Message, onCancel: () -> Unit) {
    val originalPreview = remember(message.id) {
        when (val c = message.content) {
            is TdApi.MessageText -> c.text.text.take(120)
            is TdApi.MessagePhoto -> "📷 Foto" +
                (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            is TdApi.MessageVideo -> "🎬 Video" +
                (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            is TdApi.MessageDocument -> "📎 ${c.document.fileName}" +
                (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            is TdApi.MessageAnimation -> "GIF" +
                (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            is TdApi.MessageAudio -> "🎵 " +
                c.audio.title.ifBlank { c.audio.fileName.ifBlank { "Audio" } }
            else -> "Messaggio"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            com.secondream.novagram.ui.icons.PhosphorIcons.PencilSimple,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.edit_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                originalPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.X,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Returns the @-mention query (text after the last '@' at or before the
 * cursor) if the user is currently typing one, or null otherwise. The '@'
 * must be at the start of the input or preceded by whitespace, and the
 * query may not contain spaces/newlines.
 */
private fun detectMentionQuery(text: String): String? {
    val atIndex = text.lastIndexOf('@')
    if (atIndex < 0) return null
    if (atIndex > 0 && !text[atIndex - 1].isWhitespace()) return null
    val between = text.substring(atIndex + 1)
    if (between.any { it.isWhitespace() }) return null
    return between
}

/**
 * Replace the @query at the end of the input with @username plus a trailing
 * space, leaving everything before the @ untouched. Falls back to firstName
 * if the user has no username.
 */
private fun applyMentionPick(text: String, user: TdApi.User): String {
    val atIndex = text.lastIndexOf('@')
    if (atIndex < 0) return text
    val before = text.substring(0, atIndex)
    val username = user.usernames?.editableUsername
    val token = if (!username.isNullOrBlank()) "@$username" else "@${user.firstName.trim()}"
    return "$before$token "
}

/**
 * Return the partial /command being typed, or null if the input isn't a
 * slash-command at all.
 *
 * Telegram convention: a /command picker triggers only when the first
 * character of the message is `/` and the user hasn't yet inserted a
 * space (after the space, the user is typing arguments, not the command
 * name). So "/star" → "star", "/start hello" → null, "hello /world" →
 * null.
 */
private fun detectSlashQuery(text: String): String? {
    if (!text.startsWith("/")) return null
    val rest = text.substring(1)
    if (rest.any { it.isWhitespace() }) return null
    return rest
}

/**
 * Compact dropdown rendered above the InputBar listing matching /commands
 * (up to 8). Tapping picks the command, replacing the input with
 * "/command " ready for arguments.
 */
@Composable
private fun BotCommandPicker(
    commands: List<TdApi.BotCommand>,
    onPick: (TdApi.BotCommand) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp)
    ) {
        commands.take(8).forEach { cmd ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "/${cmd.command}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (cmd.description.isNotBlank()) {
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lazily load chat members for the @-mention picker. Returns up to 100
 * members. For BasicGroups we use FullInfo (it ships the member list
 * directly); for Supergroups we call GetSupergroupMembers.
 */
private suspend fun loadChatMembers(chatId: Long): List<TdApi.User> {
    val chat = TdClient.getCachedChat(chatId) ?: return emptyList()
    return when (val t = chat.type) {
        is TdApi.ChatTypeBasicGroup -> {
            val info = runCatching { TdClient.getBasicGroupFullInfo(t.basicGroupId) }.getOrNull()
                ?: return emptyList()
            info.members.mapNotNull { m ->
                val uid = (m.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
                TdClient.getCachedUser(uid) ?: runCatching { TdClient.getUser(uid) }.getOrNull()
            }
        }
        is TdApi.ChatTypeSupergroup -> {
            if (t.isChannel) return emptyList()
            val res = runCatching { TdClient.getSupergroupMembers(t.supergroupId, 100) }.getOrNull()
                ?: return emptyList()
            res.members.mapNotNull { m ->
                val uid = (m.memberId as? TdApi.MessageSenderUser)?.userId ?: return@mapNotNull null
                TdClient.getCachedUser(uid) ?: runCatching { TdClient.getUser(uid) }.getOrNull()
            }
        }
        else -> emptyList()
    }
}

/**
 * Floating list of members matching the @-query. Appears just above the
 * input bar. Shows up to 5 hits; tapping one substitutes the partial @query
 * in the input with the chosen @username.
 */
@Composable
private fun MentionPicker(
    query: String,
    members: List<TdApi.User>,
    onPick: (TdApi.User) -> Unit
) {
    val filtered = remember(query, members) {
        val q = query.lowercase()
        members
            .asSequence()
            .filter { u ->
                val full = "${u.firstName} ${u.lastName}".lowercase()
                val uname = u.usernames?.editableUsername?.lowercase().orEmpty()
                q.isBlank() || full.contains(q) || uname.contains(q)
            }
            .take(30)
            .toList()
    }
    if (filtered.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.colorScheme.surface)
            // Vertical scroll so groups with many @-matches don't clip
            // the rows beyond the 240dp cap — the user can flick the
            // list to reach members further down.
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
    ) {
        filtered.forEach { user ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(user) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.secondream.novagram.ui.components.Avatar(
                    file = user.profilePhoto?.small,
                    fallbackText = user.firstName,
                    size = 32.dp
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${user.firstName} ${user.lastName}".trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val uname = user.usernames?.editableUsername
                    if (!uname.isNullOrBlank()) {
                        Text(
                            "@$uname",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * What kind of staged media is in the compose area. Drives both the
 * thumbnail rendering and which TdClient.send* method to use on dispatch.
 */
enum class PendingMediaKind { Photo, Video, Document }

/**
 * One piece of media the user has selected but not yet sent. The file is
 * already in our cache directory (FileUtils.copyUriToCache) so we don't
 * need to keep the SAF URI permission alive, and the cache file gets
 * cleaned up by the OS LRU eviction policy.
 */
data class PendingMediaItem(
    val file: java.io.File,
    val kind: PendingMediaKind,
    val displayName: String
)

/**
 * Sits between the reply banner and the InputBar while a piece of media is
 * pending dispatch. Shows a thumbnail (photo/video) or a file icon, the
 * filename, and an X to cancel. The user types the caption directly in
 * the InputBar — InputBar swaps its placeholder when pendingMedia != null.
 */
@Composable
private fun PendingMediaPreview(media: PendingMediaItem, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when (media.kind) {
                PendingMediaKind.Photo -> coil.compose.AsyncImage(
                    model = media.file,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                PendingMediaKind.Video -> {
                    // Video thumbnail extraction needs MediaMetadataRetriever
                    // which is heavy; for now show a play icon overlay over
                    // the surface so the user sees "this is a video".
                    coil.compose.AsyncImage(
                        model = media.file,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.Play,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                PendingMediaKind.Document -> Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.FileText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                when (media.kind) {
                    PendingMediaKind.Photo -> stringResource(R.string.pending_media_photo)
                    PendingMediaKind.Video -> stringResource(R.string.pending_media_video)
                    PendingMediaKind.Document -> stringResource(R.string.pending_media_document)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                media.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.X,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun isVideoFile(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") ||
        n.endsWith(".webm") || n.endsWith(".3gp") || n.endsWith(".avi")
}

private fun formatRecDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format(java.util.Locale.US, "%d:%02d", m, s)
}

/**
 * Apply a reaction toggle to a [TdApi.MessageInteractionInfo] in pure Kotlin,
 * returning a new InteractionInfo object that reflects the change. Used to
 * give an instant-feedback chip the moment the user taps an emoji, before
 * TDLib echoes the authoritative update back.
 *
 * Rules mirror Telegram's:
 *  - add: if the emoji is already in the reactions, increment totalCount and
 *    mark isChosen=true; otherwise prepend a new MessageReaction with
 *    totalCount=1.
 *  - remove (add=false): decrement totalCount and clear isChosen; if the
 *    new totalCount would be 0 we drop the reaction from the array entirely
 *    so the empty chip doesn't linger.
 *
 * We always allocate a fresh MessageInteractionInfo / MessageReactions
 * because the parent code mutates message.interactionInfo and we want the
 * snapshot graph to see a different reference. The inner MessageReaction
 * we touch is also reconstructed so we never mutate an object TDLib could
 * still hold a reference to.
 */
private fun applyReactionLocally(
    info: TdApi.MessageInteractionInfo?,
    emoji: String,
    add: Boolean
): TdApi.MessageInteractionInfo {
    val baseViews = info?.viewCount ?: 0
    val baseForwards = info?.forwardCount ?: 0
    val baseReply = info?.replyInfo
    val currentList = info?.reactions?.reactions?.toMutableList() ?: mutableListOf()
    val idx = currentList.indexOfFirst {
        (it.type as? TdApi.ReactionTypeEmoji)?.emoji == emoji
    }
    // We build new MessageReaction objects via field assignment rather than
    // the all-args constructor: TDLib's tl_writer occasionally appends new
    // fields between versions (e.g. usedSenderId, recentSenderIds), and the
    // no-arg-then-assign style stays compatible with all of those without
    // having to track the schema.
    if (add) {
        if (idx >= 0) {
            val existing = currentList[idx]
            currentList[idx] = TdApi.MessageReaction().apply {
                type = existing.type
                totalCount = (existing.totalCount + if (existing.isChosen) 0 else 1).coerceAtLeast(1)
                isChosen = true
                usedSenderId = existing.usedSenderId
                recentSenderIds = existing.recentSenderIds
            }
        } else {
            // New reaction goes to the front so the user immediately sees
            // their own chip on the leading edge of the strip — same as
            // real Telegram clients.
            currentList.add(
                0,
                TdApi.MessageReaction().apply {
                    type = TdApi.ReactionTypeEmoji(emoji)
                    totalCount = 1
                    isChosen = true
                    recentSenderIds = emptyArray()
                }
            )
        }
    } else if (idx >= 0) {
        val existing = currentList[idx]
        val newCount = (existing.totalCount - 1).coerceAtLeast(0)
        if (newCount == 0) {
            currentList.removeAt(idx)
        } else {
            currentList[idx] = TdApi.MessageReaction().apply {
                type = existing.type
                totalCount = newCount
                isChosen = false
                usedSenderId = existing.usedSenderId
                recentSenderIds = existing.recentSenderIds
            }
        }
    }
    val newReactions = if (currentList.isEmpty()) null else TdApi.MessageReactions().apply {
        reactions = currentList.toTypedArray()
        // Carry across the rest of the prior reactions metadata so any flags
        // (tagged reactions, paid reactors) survive the optimistic update.
        info?.reactions?.let { prev ->
            areTags = prev.areTags
            paidReactors = prev.paidReactors
            canGetAddedReactions = prev.canGetAddedReactions
        }
    }
    return TdApi.MessageInteractionInfo().apply {
        viewCount = baseViews
        forwardCount = baseForwards
        replyInfo = baseReply
        reactions = newReactions
    }
}
