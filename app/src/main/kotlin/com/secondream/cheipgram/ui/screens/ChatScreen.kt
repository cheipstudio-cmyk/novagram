@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
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
import com.secondream.cheipgram.R
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.components.MessageBubble
import com.secondream.cheipgram.ui.theme.Ink
import com.secondream.cheipgram.util.FileUtils
import com.secondream.cheipgram.util.VoiceRecorder
import org.drinkless.tdlib.TdApi

@Composable
fun ChatScreen(
    chatId: Long,
    onBack: () -> Unit,
    onOpenMediaViewer: () -> Unit = {},
    /** Navigate to another chat by id. Used by the avatar profile sheet's
     *  "Inizia chat" button so tapping a sender's avatar in a group can
     *  spin up (or open) the corresponding private chat. */
    onOpenChat: (Long) -> Unit = {}
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
    var input by remember(chatId) { mutableStateOf("") }
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
                pendingMedia = PendingMediaItem(
                    file = file,
                    kind = if (isVideo) PendingMediaKind.Video else PendingMediaKind.Photo,
                    displayName = file.name
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
        com.secondream.cheipgram.AppForegroundState.currentChatId = chatId
        onDispose {
            scope.launch { runCatching { TdClient.closeChat(chatId) } }
            // Only clear if still pointing to us — if the user nav'd to
            // another chat the new ChatScreen has already overwritten this.
            if (com.secondream.cheipgram.AppForegroundState.currentChatId == chatId) {
                com.secondream.cheipgram.AppForegroundState.currentChatId = 0L
            }
            // Flush the final draft. We use a fire-and-forget launch on the
            // process-wide application scope because the screen-scoped
            // coroutine is about to be cancelled and a launch here would die
            // before reaching TDLib. Capturing input/replyTarget by value
            // means even if the user typed a frame before backing out we
            // still persist the last state.
            val finalText = input
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
        if (!saved.isNullOrEmpty() && input.isEmpty()) {
            input = saved
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
        snapshotFlow { Triple(input, replyTarget?.id, editTarget?.id) }
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

    // Initial history load.
    // TDLib's getChatHistory first call returns only what's already in the
    // local DB cache, even with onlyLocal=false. For a chat never opened
    // before, that cache is just lastMessage. We iterate (and let TDLib
    // backfill from the server between calls) until we have ~20 messages
    // or the server stops returning more.
    LaunchedEffect(chatId) {
        messages.clear()
        noMore = false
        loading = true
        runCatching {
            var fromId = 0L
            var attempts = 0
            var consecutiveEmpty = 0
            while (messages.size < 20 && attempts < 6 && consecutiveEmpty < 2) {
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
            val ids = messages.filter { !it.isOutgoing }.map { it.id }.toLongArray()
            if (ids.isNotEmpty()) runCatching { TdClient.viewMessages(chatId, ids) }
        }
        loading = false
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
    // Same in-place-mutate + bump-revision pattern as interaction info —
    // the bubble re-reads message.content on recompose, and bumping
    // interactionRevisions on this id is enough to trigger one.
    LaunchedEffect(chatId) {
        TdClient.messageContentUpdates.collect { upd ->
            if (upd.chatId != chatId) return@collect
            val idx = messages.indexOfFirst { it.id == upd.messageId }
            if (idx >= 0) {
                messages[idx].content = upd.newContent
                interactionRevisions[upd.messageId] =
                    (interactionRevisions[upd.messageId] ?: 0) + 1
            }
        }
    }

    // Auto-scroll behaviour:
    //   - When the user sends a message (outgoing) → always scroll to bottom.
    //   - When an incoming message arrives → only scroll if the user is
    //     already near the bottom, otherwise we'd yank them out of history.
    LaunchedEffect(listState) {
        snapshotFlow { messages.firstOrNull()?.id }
            .distinctUntilChanged()
            .filter { it != null }
            .collect {
                val first = messages.firstOrNull() ?: return@collect
                if (first.isOutgoing || listState.firstVisibleItemIndex <= 2) {
                    runCatching { listState.animateScrollToItem(0) }
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

    var menuOpen by remember { mutableStateOf(false) }
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
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { infoOpen = true }
                    ) {
                        // Chat avatar on the left of the title — matches
                        // Telegram's native client. Uses the chat photo
                        // from TDLib's cached chat object; falls back to a
                        // colored circle with the first letter via Avatar
                        // when no photo is present.
                        com.secondream.cheipgram.ui.components.Avatar(
                            file = cachedChatLive?.photo?.small,
                            fallbackText = chatTitle,
                            bgColor = com.secondream.cheipgram.ui.screens.avatarBackgroundFor(chatId),
                            size = 36.dp
                        )
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
                                        Icons.Outlined.NotificationsOff,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(start = 6.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, null)
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
            // updates (a new pin fires UpdateChatLastMessage / UpdateNewMessage
            // which bumps chatUpdates → recompose). Tapping the banner
            // scrolls the LazyColumn to the pinned message's index.
            var pinned by remember(chatId) { mutableStateOf<TdApi.Message?>(null) }
            LaunchedEffect(chatId) {
                pinned = TdClient.getChatPinnedMessage(chatId)
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
                        Icons.Outlined.PushPin,
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
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.animateItem()
                        ) {
                            MessageBubble(
                                message = msg,
                                showSender = isGroupChat,
                                onLongPress = { deleteTarget = it },
                                onMediaTap = { path ->
                                    com.secondream.cheipgram.ui.screens.MediaViewerHolder.currentPath = path
                                    onOpenMediaViewer()
                                },
                                onSwipeReply = { replyTarget = it },
                                onAvatarClick = { uid -> profileSheetUserId = uid },
                                onJumpToMessage = { targetId ->
                                    // Same-chat link tapped — try to scroll to
                                    // it in the in-memory window. We claim
                                    // success even when the message isn't
                                    // loaded yet: the alternative is the
                                    // default Intent flow which causes the
                                    // chat-relaunch loop Eugenio reported.
                                    // Better silent than stacking duplicates.
                                    val idx = messages.indexOfFirst { it.id == targetId }
                                    if (idx >= 0) {
                                        scope.launch { listState.animateScrollToItem(idx) }
                                    }
                                    true
                                },
                                interactionRevision = interactionRevisions[msg.id] ?: 0
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
                // actually flips, not on every pixel of scroll.
                val showJumpToBottom by remember(listState) {
                    androidx.compose.runtime.derivedStateOf {
                        listState.firstVisibleItemIndex > 3
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
                    androidx.compose.material3.SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                runCatching { listState.animateScrollToItem(0) }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        elevation = androidx.compose.material3.FloatingActionButtonDefaults
                            .elevation(defaultElevation = 3.dp)
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.chat_jump_to_bottom),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Mention picker (popup just above the input bar). The detection
            // logic computes the @-query at the cursor; if null the picker
            // is hidden. Members are loaded lazily the first time the user
            // types '@' so we don't pay the round-trip cost on chat open.
            val mentionQuery = remember(input) { detectMentionQuery(input) }
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
                        input = applyMentionPick(input, user)
                    }
                )
            }

            // Slash-command picker. Surfaces /commands the bot in this
            // chat (private or group) exposes, filtered live by what the
            // user is typing. Hidden when the input doesn't start with /.
            val slashQuery = remember(input) { detectSlashQuery(input) }
            if (slashQuery != null && botCommands.isNotEmpty()) {
                val filtered = botCommands.filter {
                    it.command.startsWith(slashQuery, ignoreCase = true)
                }
                if (filtered.isNotEmpty()) {
                    BotCommandPicker(
                        commands = filtered,
                        onPick = { cmd ->
                            input = "/${cmd.command} "
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
                        input = ""
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
                    val text = input.trim()
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
                                input = ""
                            } else {
                                val captured = editing
                                editTarget = null
                                input = ""
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
                            input = ""
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
                            input = ""
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
                hasPendingMedia = pendingMedia != null || editTarget != null
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
        com.secondream.cheipgram.ui.components.ForwardChatPickerSheet(
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
        com.secondream.cheipgram.ui.components.UserProfileSheet(
            userId = uid,
            onDismiss = { profileSheetUserId = null },
            onStartChat = { newChatId ->
                profileSheetUserId = null
                onOpenChat(newChatId)
            }
        )
    }

    // Pinned-messages list sheet. Tapping a row jumps the LazyColumn to
    // that message if it's already in the in-memory window; we don't yet
    // re-load history around messages beyond the window — most pinned
    // messages users care about are recent enough to be in scope.
    if (pinnedSheetOpen) {
        com.secondream.cheipgram.ui.components.PinnedListSheet(
            chatId = chatId,
            onDismiss = { pinnedSheetOpen = false },
            onJumpToMessage = { targetId ->
                pinnedSheetOpen = false
                val idx = messages.indexOfFirst { it.id == targetId }
                if (idx >= 0) {
                    scope.launch { listState.animateScrollToItem(idx) }
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
            com.secondream.cheipgram.ui.components.Avatar(
                file = chat?.photo?.small,
                fallbackText = title,
                bgColor = com.secondream.cheipgram.ui.screens.avatarBackgroundFor(chatId),
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
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.chat_info_bio_label),
                    value = it
                )
            }
            username?.let {
                ProfileDetailRow(
                    icon = Icons.Outlined.AlternateEmail,
                    label = stringResource(R.string.chat_info_username_label),
                    value = "@$it"
                )
            }
            phone?.let {
                ProfileDetailRow(
                    icon = Icons.Outlined.Phone,
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
    value: String,
    onValueChange: (String) -> Unit,
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
    hasPendingMedia: Boolean = false
) {
    // Pull the custom input-bar color if the user has set one in the
    // theme builder. Falls back to MaterialTheme.colorScheme.background
    // so the bar tracks whatever surface the chat is on.
    val appearance by com.secondream.cheipgram.settings.AppSettings.appearance.collectAsState(
        initial = com.secondream.cheipgram.settings.AppearancePrefs()
    )
    val cs = MaterialTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    val inputBg = appearance.customInputBarArgb?.let { androidx.compose.ui.graphics.Color(it) }
        ?: cs.background
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
        if (recording) {
            // Count-up timer driven by a LaunchedEffect that ticks every
            // second while the user is holding the mic. Doesn't try to be
            // sample-accurate — it's a UI hint, not the official duration
            // (that's measured by VoiceRecorder via SystemClock.elapsedRealtime).
            var elapsed by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    elapsed += 1
                }
            }
            // Pulsing red dot for the live indicator.
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "rec-pulse")
                .animateFloat(
                    initialValue = 0.5f, targetValue = 1f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(700),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "rec-alpha"
                )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Ink.Error.copy(alpha = pulse))
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
                MicButton(recording = true, onDown = onMicDown, onUp = onMicUp)
            }
        } else {
            // Alignment.Bottom keeps attach + send pinned at the foot of
            // the row. Without this the buttons stay vertically centered
            // and visibly creep upward each time the text wraps to a new
            // line — a behaviour Eugenio specifically called out.
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onAttach,
                    // Manual sizing so the button has the same 48dp tap
                    // target as MicButton/Send below; default IconButton
                    // is 48dp anyway but being explicit makes the
                    // alignment math obvious.
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Outlined.AttachFile, null, tint = iconTint)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // Cap how tall the bubble can grow. ~6 lines at
                        // bodyLarge gives roughly 150dp; beyond that the
                        // BasicTextField scrolls internally instead of
                        // pushing the layout upward.
                        .heightIn(min = 44.dp, max = 150.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(bubbleBg)
                        .border(0.5.dp, bubbleBorder, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholderText ?: stringResource(R.string.input_placeholder),
                            color = placeholderColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                        // Internal scrolling kicks in once the content
                        // grows past the 150dp ceiling above. Up to that
                        // point the field expands naturally so a 1-2
                        // line message doesn't waste vertical space.
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (value.isNotBlank() || hasPendingMedia) {
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
                            // Plain paper-plane vector — single-color so it
                            // tints cleanly with the accent's onPrimary.
                            // (We used to render ic_cheipgram_logo here, but
                            // tinting a multi-color PNG flattens it to a
                            // solid silhouette and looked terrible.)
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    MicButton(recording = false, onDown = onMicDown, onUp = onMicUp)
                }
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
            Icons.Outlined.Mic,
            null,
            tint = if (recording) MaterialTheme.colorScheme.onError
                   else MaterialTheme.colorScheme.onPrimary
        )
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
            AttachOption(stringResource(R.string.attach_photo_or_video), Icons.Outlined.Image, onPickPhoto)
            Spacer(Modifier.height(4.dp))
            AttachOption(stringResource(R.string.attach_document_or_file), Icons.Outlined.Description, onPickDocument)
            Spacer(Modifier.height(4.dp))
            AttachOption(stringResource(R.string.attach_sticker), Icons.Outlined.Mood, onPickSticker)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AttachOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        // The TextButton was redundant — the whole row is now clickable.
        // We keep an arrow-style label so the affordance is still visible
        // without doubling up on tap targets.
        Text(
            stringResource(R.string.action_open),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
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
    val showAdminActions = isAdmin &&
        cachedChat?.type !is TdApi.ChatTypePrivate &&
        senderUserId != null &&
        senderUserId != myUserId

    val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Ink.Surface
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
                    .background(Ink.SurfaceHi)
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

            DeleteOption(stringResource(R.string.action_reply), onReply, icon = Icons.Outlined.Reply)
            Spacer(Modifier.height(4.dp))
            // Modifica appears only when (a) the parent wired the callback
            // (gated by content-type heuristic on outgoing messages) AND
            // (b) TDLib confirms the message is still inside the edit
            // window via MessageProperties.canBeEdited. The latter is
            // optimistic-shown when the round trip hasn't returned yet so
            // the option doesn't visibly pop in late on common cases.
            val editAllowed = canEditFromServer ?: message.isOutgoing
            if (onEdit != null && editAllowed) {
                DeleteOption(
                    stringResource(R.string.action_edit),
                    onEdit,
                    icon = Icons.Outlined.Edit
                )
                Spacer(Modifier.height(4.dp))
            }
            DeleteOption(stringResource(R.string.action_forward), onForward,
                icon = Icons.AutoMirrored.Outlined.Forward)
            Spacer(Modifier.height(4.dp))
            if (onCopy != null) {
                DeleteOption(stringResource(R.string.action_copy), onCopy, icon = Icons.Outlined.ContentCopy)
                Spacer(Modifier.height(4.dp))
            }
            DeleteOption(stringResource(R.string.delete_for_me), onDeleteForMe)
            if (canRevoke) {
                Spacer(Modifier.height(4.dp))
                DeleteOption(stringResource(R.string.delete_for_everyone), onDeleteForEveryone, destructive = true)
            }
            if (showAdminActions) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.actions_admin_section),
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.Muted,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Spacer(Modifier.height(6.dp))
                DeleteOption(stringResource(R.string.action_mute_author), onMuteAuthor, icon = Icons.Outlined.VolumeOff)
                Spacer(Modifier.height(4.dp))
                DeleteOption(stringResource(R.string.action_kick_author), onKickAuthor, icon = Icons.Outlined.PersonRemove, destructive = true)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DeleteOption(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Delete
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
                Icons.Outlined.Close,
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
            Icons.Outlined.Edit,
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
                Icons.Outlined.Close,
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
            .take(6)
            .toList()
    }
    if (filtered.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(MaterialTheme.colorScheme.surface)
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
                com.secondream.cheipgram.ui.components.Avatar(
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
                        Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                PendingMediaKind.Document -> Icon(
                    Icons.Outlined.Description,
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
                Icons.Outlined.Close,
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
