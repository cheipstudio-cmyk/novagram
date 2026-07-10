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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
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
import androidx.compose.animation.togetherWith
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

/**
 * Unread-count threshold for chat-open positioning. At or below this many
 * unread messages we treat you as "essentially caught up" and land at the
 * bottom (newest); above it we treat it as a backlog and pull the first-unread
 * separator to the top. Count-based so the decision never depends on a fragile
 * layout-timing measurement. Tunable.
 */
private const val UNREAD_FITS_SCREEN = 8

/**
 * Places the "non letti" separator (which renders at the TOP of the
 * first-unread message's item) at the very top of the chat viewport, so the
 * unread messages read downward from there — what we want when opening a chat
 * with a backlog from a notification or the chat list.
 *
 * Why a dedicated routine and not an inline scrollToItem: the first frame after
 * a freshly-populated list (or a media bubble) reports a PLACEHOLDER height, so
 * computing the offset immediately lands the separator in the wrong spot
 * ("sempre troppo sotto"). We mirror the proven jumpToMessage recipe: settle
 * the target's height (3 identical frames), place once with the real height,
 * then re-assert the spot for a short window so a late image decode or more
 * history paginating in can't drag it off — bailing the instant the user
 * scrolls.
 *
 * reverseLayout geometry (confirmed in jumpToMessage): scrollToItem(idx, S)
 * leaves S px between the item's BOTTOM and the viewport bottom, so the item's
 * TOP sits at vp - (S + height). To put the TOP at K px: S = vp - height - K.
 */
private suspend fun anchorFirstUnread(
    listState: LazyListState,
    messages: List<TdApi.Message>,
    targetMsgId: Long,
) {
    if (targetMsgId == 0L) return
    val topMargin = 8 // px: separator sits just inside the 8dp content padding
    var li = messages.indexOfFirst { it.id == targetMsgId }
    if (li < 0) return
    // First-unread IS the newest message (you were caught up and one/a few new
    // ones arrived): nothing newer to read below it, so just sit at the bottom
    // — no separator-at-top dance. reverseLayout would clamp to the bottom
    // anyway; doing it explicitly keeps it crisp and skips the settle loop.
    if (li == 0) { runCatching { listState.scrollToItem(0, 0) }; return }

    val vp0 = listState.layoutInfo.viewportSize.height
    if (vp0 <= 0) { runCatching { listState.scrollToItem(li, 0) }; return }

    // Rough pre-place near the top using the best height estimate so the target
    // is roughly where it'll end up while the (possibly still-composing) list
    // lays out and the heights settle.
    run {
        val est = listState.layoutInfo.visibleItemsInfo.find { it.index == li }?.size
            ?: (vp0 * 18 / 100)
        runCatching { listState.scrollToItem(li, (vp0 - est - topMargin).coerceAtLeast(0)) }
    }

    // Settle the target height: hold for 3 identical frames, cap ~40 frames so
    // a heavy first layout or a slow-loading photo can't leave us measuring a
    // placeholder. Measured WITHOUT scrolling, so nothing jitters here.
    var lastH = -1
    var stable = 0
    var settledH = -1
    var frame = 0
    while (frame < 40 && settledH <= 0) {
        kotlinx.coroutines.delay(16)
        li = messages.indexOfFirst { it.id == targetMsgId }
        val h = listState.layoutInfo.visibleItemsInfo.find { it.index == li }?.size ?: -1
        if (h > 0 && h == lastH) {
            stable++
            if (stable >= 3) { settledH = h; break }
        } else {
            stable = 0
            lastH = h
        }
        frame++
    }
    if (settledH <= 0) settledH = if (lastH > 0) lastH else (vp0 * 18 / 100).coerceAtLeast(1)

    li = messages.indexOfFirst { it.id == targetMsgId }.takeIf { it >= 0 } ?: return
    val vp = listState.layoutInfo.viewportSize.height.let { if (it > 0) it else vp0 }
    val placeOffset = (vp - settledH - topMargin).coerceAtLeast(0)
    if (com.secondream.novagram.BuildConfig.DEBUG) android.util.Log.d(
        "NovaScroll",
        "anchorTop id=$targetMsgId li=$li vp=$vp settledH=$settledH offset=$placeOffset frames=$frame"
    )
    // Glide (not snap) onto the exact mark so the assestamento reads as smooth,
    // never a jolt ("uno scatto", Eugenio). The rough pre-place above already
    // sat the target near the top, so this is a SHORT, drift-free hop.
    runCatching { listState.animateScrollToItem(li, placeOffset) }

    // SUSTAINED ANCHOR — re-assert the top spot every frame until the target's
    // measured height holds for ~10 frames (catches a late image decode or a
    // pagination reflow that would otherwise drag the separator off the top),
    // bailing the instant the user starts scrolling so we never fight them.
    // Re-resolved BY ID each frame so a reindex can't pin the wrong row. Hard
    // cap ~1.4s so we never spin forever on a perpetually-loading item.
    var f2 = 0
    var lastSize = -1
    var stableSize = 0
    while (f2 < 90) {
        kotlinx.coroutines.delay(16)
        if (listState.isScrollInProgress) break
        val cur = messages.indexOfFirst { it.id == targetMsgId }
        if (cur < 0) break
        val vis = listState.layoutInfo.visibleItemsInfo.find { it.index == cur } ?: break
        val vpN = listState.layoutInfo.viewportSize.height.let { if (it > 0) it else vp }
        runCatching { listState.scrollToItem(cur, (vpN - vis.size - topMargin).coerceAtLeast(0)) }
        if (vis.size == lastSize) {
            stableSize++
            if (stableSize >= 10) break
        } else {
            stableSize = 0
            lastSize = vis.size
        }
        f2++
    }
}

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
    val haptics = com.secondream.novagram.util.rememberHaptics()
    val scope = rememberCoroutineScope()
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val messages = remember(chatId) { ChatMessageCache.forChat(chatId) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    // True while `messages` is showing the live tail (the newest messages are
    // loaded). Goes false once jumpToMessage parks us in an OLD, disjoint
    // window (e.g. a pinned-message tap to July). It gates two things that
    // would otherwise stitch a far window onto the latest and leave a hidden
    // "burned middle" gap you'd silently scroll through: (1) injecting a
    // freshly-arrived message at the top, (2) the go-to-bottom button's plain
    // scroll. Initialised from the cached window so it stays correct across a
    // MediaViewer round-trip (which re-enters with the cached list intact).
    var atLatestWindow by remember(chatId) {
        mutableStateOf(
            messages.isEmpty() ||
                messages.firstOrNull()?.id == TdClient.getCachedChat(chatId)?.lastMessage?.id
        )
    }
    // TRUE for the whole duration of a search-arrow jump (load + land + a short
    // settle). Declared up here, before the message collectors and scroll
    // effects, because ALL of them must stand down while a jump is placing its
    // window: the load-older / load-newer pagination flows, AND the
    // auto-scroll-to-bottom / new-message collectors that would otherwise yank
    // the viewport to index 0 the instant the jump clobbers `messages` (the
    // "scrolla scrolla" + imprecise landing). It also serializes against the
    // concurrent SnapshotStateList mutation that crashed LazyColumn.
    var jumpSettling by remember(chatId) { mutableStateOf(false) }
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
    var showPollComposer by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    // Voice-note recording LOCKED (user slid the mic up): recording continues
    // after the finger lifts; trash/send buttons replace the mic.
    var recordingLocked by remember { mutableStateOf(false) }
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
    // Last non-null pinned message — kept so the banner's collapse animation
    // renders its content through the exit instead of blanking instantly.
    var lastPinnedBanner by remember(chatId) { mutableStateOf<TdApi.Message?>(null) }
    // Message awaiting the group pin confirmation (with the notify-all toggle).
    var pinNotifyTarget by remember(chatId) { mutableStateOf<TdApi.Message?>(null) }
    // User just banned via a message's admin menu, awaiting the "also delete
    // all their messages?" prompt.
    var banDeleteAllUid by remember(chatId) { mutableStateOf<Long?>(null) }
    val appearance by com.secondream.novagram.settings.AppSettings.appearance
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs())
    // Cached list of chat members for the @-mention picker. Loaded lazily
    // the first time the user types "@" in a non-private chat.
    var mentionMembers by remember(chatId) { mutableStateOf<List<TdApi.User>>(emptyList()) }
    var mentionLoaded by remember(chatId) { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val recorder = remember { VoiceRecorder(context) }

    val defaultChatTitle = stringResource(R.string.chat_default_title)
    val savedMessagesLabel = stringResource(R.string.saved_messages)
    val chatTitle by produceState(initialValue = defaultChatTitle, chatId) {
        value = withContext(Dispatchers.IO) {
            runCatching { TdClient.getChat(chatId).title }.getOrDefault(defaultChatTitle)
        }
    }
    // Non-private chats (groups, supergroups, channels) show sender name +
    // avatar above each incoming bubble. Cached chat type is reliable once
    // TDLib has streamed UpdateNewChat for this id, which happens before the
    // chat list ever renders, so we read it synchronously.
    // The cached type is null when a chat is opened cold (from search or a
    // t.me link). The old `type !is ChatTypePrivate` test then defaulted to
    // TRUE — misclassifying a private bot chat as a group and sending
    // "/cmd@bot" instead of bare "/cmd", which many bots IGNORE in a private
    // chat (the long-standing "i comandi non fanno nulla" bug). Start from a
    // precise cache read (only basic/supergroup count as a group; null →
    // false → private/safe) and correct it with a real fetch.
    var isGroupChat by remember(chatId) {
        mutableStateOf(
            TdClient.getCachedChat(chatId)?.type.let {
                it is TdApi.ChatTypeBasicGroup || it is TdApi.ChatTypeSupergroup
            }
        )
    }
    LaunchedEffect(chatId) {
        val t = runCatching { TdClient.getChat(chatId) }.getOrNull()?.type
            ?: TdClient.getCachedChat(chatId)?.type
        isGroupChat = t is TdApi.ChatTypeBasicGroup || t is TdApi.ChatTypeSupergroup
    }

    // Secret-chat handshake gating. For a ChatTypeSecret we track the live
    // SecretChatState so we can (a) show a "waiting for them to join" empty
    // state and (b) lock the composer until the peer completes the key
    // exchange — TDLib rejects every send while the chat is Pending, so
    // letting the user type would just silently drop messages. For any
    // non-secret chat secretChatId stays null and secretState stays null,
    // leaving the normal composer untouched.
    val secretChatId = remember(chatId) {
        (TdClient.getCachedChat(chatId)?.type as? TdApi.ChatTypeSecret)?.secretChatId
    }
    var secretState by remember(chatId) {
        mutableStateOf<TdApi.SecretChatState?>(null)
    }
    if (secretChatId != null) {
        val sid = secretChatId
        LaunchedEffect(chatId, sid) {
            secretState = TdClient.getSecretChat(sid)?.state
            TdClient.secretChatUpdates.collect { sc ->
                if (sc.id == sid) secretState = sc.state
            }
        }
    }
    val secretPending = secretState is TdApi.SecretChatStatePending
    val secretClosed = secretState is TdApi.SecretChatStateClosed

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
    // Whether the current user is NOT a member of this chat — true for
    // public supergroups / channels we landed on via a t.me link, a
    // search hit, or a forwarded mention, without having joined yet.
    // While true the input bar disappears and a "Unisciti al gruppo"
    // CTA takes its place. Flips back to false the moment TDLib echoes
    // back the join via UpdateChatMember (which the chatUpdates flow
    // surfaces), so the chat unlocks immediately after we press join.
    var isNonMember by remember(chatId) { mutableStateOf(false) }
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
        // Non-member = Left or Banned. TDLib surfaces Restricted with
        // sending blocked separately (which we don't gate the input on
        // here — chat permissions handle restrictions, this is purely
        // about "you haven't joined yet").
        isNonMember = when (member?.status) {
            is TdApi.ChatMemberStatusLeft,
            is TdApi.ChatMemberStatusBanned -> true
            null -> {
                // getMyChatMember frequently returns null for channels
                // EVEN WHEN we're a subscriber — TDLib gates the call
                // behind permissions some channel types don't expose
                // to regular members. Falling back to "true → show
                // Join" was producing the bug Eugenio hit where the
                // Join CTA appeared inside channels he was already in.
                //
                // Safer signal: chat.positions. If the cached chat has
                // ANY position with order > 0 (Main or Archive list),
                // it surfaces in our chat list — which by definition
                // means TDLib considers us a participant. Only treat
                // as non-member when there's no position AND we
                // couldn't read the member status either (real public
                // preview).
                val chat = TdClient.getCachedChat(chatId)
                val hasListPosition = chat?.positions?.any { it.order != 0L } == true
                !hasListPosition
            }
            else -> false
        }
    }
    // Owner/admin labels for group message bubbles. Fetched once per group:
    // supergroups via the Administrators filter, basic groups from the full
    // member list. userId → "Proprietario" / "Amministratore". Empty for
    // private chats or on any fetch failure (bubbles just show no badge).
    var adminLabels by remember(chatId) { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(chatId) {
        if (!isGroupChat) return@LaunchedEffect
        val chat = TdClient.getCachedChat(chatId) ?: return@LaunchedEffect
        val out = mutableMapOf<Long, String>()
        val members: List<TdApi.ChatMember> = when (val t = chat.type) {
            is TdApi.ChatTypeSupergroup -> runCatching {
                TdClient.getSupergroupMembersFiltered(
                    t.supergroupId,
                    TdApi.SupergroupMembersFilterAdministrators(),
                    200
                )
            }.getOrNull()?.members?.toList().orEmpty()
            is TdApi.ChatTypeBasicGroup -> runCatching {
                TdClient.getBasicGroupFullInfo(t.basicGroupId)
            }.getOrNull()?.members?.toList().orEmpty()
            else -> emptyList()
        }
        for (m in members) {
            val uid = (m.memberId as? TdApi.MessageSenderUser)?.userId ?: continue
            when (m.status) {
                is TdApi.ChatMemberStatusCreator -> out[uid] = context.getString(R.string.member_role_owner)
                is TdApi.ChatMemberStatusAdministrator -> out[uid] = context.getString(R.string.member_role_admin)
                else -> {}
            }
        }
        adminLabels = out
    }

    // Re-evaluate membership whenever TDLib echoes a status change on
    // this chat — covers our own join landing (status flips from Left
    // → Member) AND being kicked while in the chat (Member → Banned,
    // input goes back to a CTA).
    LaunchedEffect(chatId) {
        var lastAdminRefresh = 0L
        TdClient.chatUpdates.collect { cid ->
            if (cid != chatId) return@collect
            val me = myUserId ?: return@collect
            if (!isGroupChat) return@collect
            val member = runCatching { TdClient.getMyChatMember(chatId, me) }.getOrNull()
            isNonMember = when (member?.status) {
                is TdApi.ChatMemberStatusLeft,
                is TdApi.ChatMemberStatusBanned -> true
                null -> {
                    val chat = TdClient.getCachedChat(chatId)
                    val hasListPosition = chat?.positions?.any { it.order != 0L } == true
                    !hasListPosition
                }
                else -> false
            }
            // Keep owner/admin bubble labels live: re-pull the admin roster
            // when this chat echoes a change (promote / demote / ban) so a
            // status change reflects in the bubbles immediately, not only on
            // re-open. Throttled to once / 3s so a busy chat's stream of
            // updates doesn't spam GetSupergroupMembers.
            val now = System.currentTimeMillis()
            if (now - lastAdminRefresh > 3000) {
                lastAdminRefresh = now
                val chatNow = TdClient.getCachedChat(chatId)
                val refreshed = mutableMapOf<Long, String>()
                val mem: List<TdApi.ChatMember> = when (val t = chatNow?.type) {
                    is TdApi.ChatTypeSupergroup -> runCatching {
                        TdClient.getSupergroupMembersFiltered(
                            t.supergroupId,
                            TdApi.SupergroupMembersFilterAdministrators(),
                            200
                        )
                    }.getOrNull()?.members?.toList().orEmpty()
                    is TdApi.ChatTypeBasicGroup -> runCatching {
                        TdClient.getBasicGroupFullInfo(t.basicGroupId)
                    }.getOrNull()?.members?.toList().orEmpty()
                    else -> emptyList()
                }
                for (m in mem) {
                    val uid = (m.memberId as? TdApi.MessageSenderUser)?.userId ?: continue
                    when (m.status) {
                        is TdApi.ChatMemberStatusCreator -> refreshed[uid] = context.getString(R.string.member_role_owner)
                        is TdApi.ChatMemberStatusAdministrator -> refreshed[uid] = context.getString(R.string.member_role_admin)
                        else -> {}
                    }
                }
                adminLabels = refreshed
            }
        }
    }

    // Bot command suggestions for the slash menu. Loaded once per chat
    // entry; in private chats with a bot we pull from UserFullInfo.botInfo
    // because that's the authoritative source for that bot's command list.
    var botCommands by remember(chatId) { mutableStateOf<List<com.secondream.novagram.td.BotCommandItem>>(emptyList()) }
    LaunchedEffect(chatId) {
        val cmds = runCatching {
            // Resolve the chat with a real fetch — don't trust the cache being
            // warm. Opening a bot from search or a t.me link lands here before
            // the chat is cached; a null cache would misclassify the private
            // bot as a group (getCachedChat?.type !is Private == true) and load
            // nothing, which is exactly the "comandi non vanno" case.
            val chat = runCatching { TdClient.getChat(chatId) }.getOrNull()
                ?: TdClient.getCachedChat(chatId)
            when (val type = chat?.type) {
                is TdApi.ChatTypePrivate -> {
                    val bot = runCatching { TdClient.getUser(type.userId) }.getOrNull()
                    if (bot?.type is TdApi.UserTypeBot) {
                        val uname = bot.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }
                            ?: bot.usernames?.editableUsername?.takeIf { it.isNotBlank() }
                        runCatching { TdClient.getUserFullInfo(bot.id) }.getOrNull()
                            ?.botInfo?.commands
                            ?.map {
                                com.secondream.novagram.td.BotCommandItem(
                                    it.command, it.description, bot.id, uname
                                )
                            }
                            ?: emptyList()
                    } else emptyList()
                }
                is TdApi.ChatTypeBasicGroup, is TdApi.ChatTypeSupergroup ->
                    TdClient.getBotCommandsForChat(chatId)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        botCommands = cmds
    }
    // Toggled by the "/" list button in the input bar to show the full command
    // list (vs the live-filtered picker that appears while typing a /command).
    var showAllCommands by remember(chatId) { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) needMicPermission = false }

    // Selected-but-not-yet-sent media. Once the user picks something it
    // sits here while they type an optional caption; pressing send
    // dispatches it together with the caption text (Telegram-style flow).
    // We copy the URI's content into the cache up-front so the InputBar can
    // show a thumbnail and we don't have to keep the SAF permission alive.
    //
    // List, not nullable single: Telegram supports up to 10 items per
    // media group (album). Empty list = nothing pending; 1 item = single
    // send (uses dedicated InputMessagePhoto/Video/Document); 2+ items =
    // album send via SendMessageAlbum. The picker caps at 10 to match
    // TDLib's server-side limit so we never construct a payload that gets
    // rejected.
    var pendingMedia by remember(chatId) { mutableStateOf<List<PendingMediaItem>>(emptyList()) }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        showAttach = false
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            // Process each URI in parallel: copy into cache, compress
            // photos, leave videos as-is. We collect into a temporary
            // local list and then assign once at the end so the
            // PendingMediaPreview doesn't flicker through intermediate
            // states as items appear one by one.
            val items = mutableListOf<PendingMediaItem>()
            for (picked in uris) {
                val file = FileUtils.copyUriToCache(context, picked) ?: continue
                val isVideo = isVideoFile(file.name)
                val finalFile = if (!isVideo) {
                    FileUtils.compressImageForUpload(file) ?: file
                } else file
                items.add(
                    PendingMediaItem(
                        file = finalFile,
                        kind = if (isVideo) PendingMediaKind.Video else PendingMediaKind.Photo,
                        displayName = finalFile.name
                    )
                )
            }
            if (items.isNotEmpty()) {
                // Append to whatever's already pending — lets the user
                // pick a batch, then tap the attach button again and
                // pick more without losing the first batch. Capped at
                // 10 total so an over-eager second pick doesn't exceed
                // TDLib's album limit.
                pendingMedia = (pendingMedia + items).take(10)
            }
        }
    }

    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        showAttach = false
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val items = mutableListOf<PendingMediaItem>()
            for (picked in uris) {
                val file = FileUtils.copyUriToCache(context, picked) ?: continue
                items.add(
                    PendingMediaItem(
                        file = file,
                        kind = PendingMediaKind.Document,
                        displayName = file.name
                    )
                )
            }
            if (items.isNotEmpty()) {
                pendingMedia = (pendingMedia + items).take(10)
            }
        }
    }

    DisposableEffect(chatId) {
        scope.launch { runCatching { TdClient.openChat(chatId) } }
        // currentChatId (the notification foreground gate) is driven by THIS
        // screen's resume/pause lifecycle in the gate DisposableEffect below —
        // not set here on compose — so it's non-zero only while the chat is
        // actually on screen AND resumed.
        // Clear any pending heads-up / tray notification for THIS chat
        // the moment we land here. The user is now reading the chat,
        // so a notification still sitting in the tray is stale — same
        // behaviour as the official Telegram client. Cancelling is
        // safe even if no notification was active.
        com.secondream.novagram.notifications.NotificationHelper.dismissForChat(chatId)
        // SCREENSHOT PROTECTION. If the chat's admin has flagged it as
        // hasProtectedContent (Telegram's "Restrict saving content"
        // toggle) we set FLAG_SECURE on the window so Android blocks
        // screenshots / screen recording / Recents preview. We also
        // honour secret chats unconditionally — they're meant to be
        // private. The flag is window-wide, so we restore on dispose
        // to avoid blocking screenshots on OTHER chats (the user
        // navigates back to a non-protected chat).
        val cachedForSecure = TdClient.getCachedChat(chatId)
        val isSecret = cachedForSecure?.type is TdApi.ChatTypeSecret
        val isProtected = cachedForSecure?.hasProtectedContent == true
        val activity = (context as? android.app.Activity)
        val priorSecureFlag = activity?.window?.attributes?.flags
            ?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) ?: 0
        if (activity != null && (isProtected || isSecret)) {
            activity.window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        onDispose {
            // Restore the secure-flag to whatever it was before we
            // entered (usually unset). Doing a blanket clearFlags is
            // safe because the new screen will reapply if it also
            // wants protection.
            if (activity != null && priorSecureFlag == 0 && (isProtected || isSecret)) {
                activity.window.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE
                )
            }
            // CloseChat MUST go through a durable scope: launching it on the
            // composition's rememberCoroutineScope here cancels it on the way
            // out (the scope dies with the composition) so it never reaches
            // TDLib, leaving the chat open and its firehose persisting forever.
            TdClient.closeChatDetached(chatId)
            // (currentChatId is cleared by the gate DisposableEffect's ON_PAUSE
            // and its onDispose below, plus App.onStop — not managed here.)
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
        // input is a TextFieldValue (refactored earlier so mention/slash
        // pickers can move the caret to end). Build one explicitly with
        // the cursor placed after the loaded text so the user can keep
        // typing where the previous content ended.
        input = androidx.compose.ui.text.input.TextFieldValue(
            existing,
            androidx.compose.ui.text.TextRange(existing.length)
        )
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

    // Outbound typing-status emitter. While the user is composing in this
    // chat (input.text non-empty) and the privacy toggle is on, we
    // announce ChatActionTyping every ~4 seconds — TDLib keeps the peer's
    // "sta scrivendo" indicator alive for ~5s after each push so 4s gives
    // a safe overlap. The moment input goes empty (sent, cleared, switched
    // chat) we fire ChatActionCancel so the peer's indicator drops
    // immediately instead of waiting for the 5s timeout. Gated on
    // appearance.sendTypingStatus so users who flip the privacy toggle
    // disappear from peers' typing UI completely.
    LaunchedEffect(chatId) {
        var lastEmit = 0L
        var lastNonEmpty = false
        snapshotFlow { input.text.isNotEmpty() to appearance.sendTypingStatus }
            .collect { (nonEmpty, enabled) ->
                if (nonEmpty && enabled) {
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 4000) {
                        lastEmit = now
                        runCatching {
                            TdClient.sendChatAction(chatId, TdApi.ChatActionTyping())
                        }
                    }
                    lastNonEmpty = true
                } else if (lastNonEmpty) {
                    // Transitioned from typing → not typing (or the user
                    // just flipped the privacy toggle while typing). Tell
                    // the peer to drop the indicator now.
                    lastNonEmpty = false
                    runCatching {
                        TdClient.sendChatAction(chatId, TdApi.ChatActionCancel())
                    }
                }
            }
    }
    // Continuous re-pulse: snapshotFlow above only fires on transitions
    // (empty↔non-empty). For sustained typing we also need a heartbeat
    // that re-emits every ~4 seconds while text remains non-empty.
    LaunchedEffect(chatId) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            if (input.text.isNotEmpty() && appearance.sendTypingStatus) {
                runCatching {
                    TdClient.sendChatAction(chatId, TdApi.ChatActionTyping())
                }
            }
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
    // Id of the first unread INCOMING message at the moment the chat is
    // opened. Drives the "Messaggi non letti" divider rendered above that
    // bubble in the list. Snapshotted ONCE per chat open (remember keyed on
    // chatId) and deliberately NOT recomputed as messages get read — like
    // Telegram, the separator stays anchored where it was on open and only
    // disappears when you leave and re-enter the chat with nothing unread.
    // 0L = no separator (chat was fully read on open).
    var unreadSeparatorId by remember(chatId) { mutableStateOf(0L) }

    // Initial history load.
    // TDLib's getChatHistory first call returns only what's already in the
    // local DB cache, even with onlyLocal=false. For a chat never opened
    // before, that cache is just lastMessage. We iterate (and let TDLib
    // backfill from the server between calls) until we have ~20 messages
    // or the server stops returning more — but if the chat has 100 unread
    // we keep iterating until that first unread is in the window, capped
    // so we don't try to backfill a thousand-message channel.
    LaunchedEffect(chatId) {
        // Cache hit: ChatScreen was on this chat before — either popped
        // back from MediaViewer / a sub-screen, or re-entered from the
        // chat list. Skip the full clear/reload + scroll-to-unread
        // dance (running it again would yank the user to a different
        // position because by now unreadCount has hit zero, so we'd
        // snap to the bottom and lose their scroll anchor).
        //
        // BUT we DO need to top up any messages that arrived while
        // ChatScreen wasn't composed. The TdClient.newMessages flow
        // has no replay, so any UpdateNewMessage emitted while we were
        // offscreen is lost to the in-chat collector. Without this
        // top-up, the user opens a chat with the "unread" badge and
        // sees stale content with no new bubbles — exactly the
        // regression Eugenio hit after the cache landed.
        //
        // The top-up is cheap: one getChatHistory(fromId=0, limit=30)
        // returns the latest backwards from the newest. We prepend any
        // ids strictly newer than our cached head; the LazyListState
        // anchor stays glued to the existing items since they keep
        // their relative position, scroll only "ticks down" by the
        // new-message count which is the same behaviour Telegram has.
        //
        // NOTE — a stale JUMPED window is NOT topped up here; it's reloaded.
        // See the guard just below.
        // Cache-hit fast path (top-up + reposition, no full reload) is taken
        // ONLY when the cached window is the LIVE TAIL. If the cache holds a
        // stale JUMPED window (atLatestWindow == false — the user tapped an old
        // pinned / a far reply and then left), do NOT preserve it: a fresh
        // reopen reaching this point means the user is back and expects the
        // PRESENT, not the old slice ("riapro e mi trovo nel passato"). Fall
        // through to the full tail reload below, which also lands on the first
        // unread when there is one. The in-chat media viewer is a hoisted
        // dialog that never disposes this screen, so it does NOT re-run this
        // effect — there is no sub-screen pop-back relying on the old window.
        if (messages.isNotEmpty() && atLatestWindow) {
            loading = false
            runCatching {
                val cachedNewestId = messages.firstOrNull()?.id ?: 0L
                // Authoritative server state FIRST so we can tell whether a
                // single history fetch actually surfaced the unread tail.
                // ALWAYS read it — not just when we appended fresh messages:
                // a chat opened from a notification may have its unread
                // messages ALREADY in cache (toAdd empty) yet the user still
                // expects to land on the first unread; gating this on
                // toAdd.isNotEmpty() dumped them wherever listState last sat.
                val freshChatInfo = runCatching { TdClient.getChat(chatId) }.getOrNull()
                val freshUnread = freshChatInfo?.unreadCount ?: 0
                val freshLastReadId = freshChatInfo?.lastReadInboxMessageId ?: 0L
                // getChatHistory is local-first. After the app's been in the
                // background/offline, TDLib can know unreadCount from a server
                // sync BEFORE the new message bodies have landed in the local
                // DB, so this fetch comes back WITHOUT them — the user opens a
                // chat showing "N unread" and sees no new bubbles, stuck on
                // the old tail (exactly the report). When the server says
                // there's unread but the fetch hasn't surfaced it, retry a few
                // times with a short delay to let TDLib backfill from the
                // server before we prepend + position. Purely additive: when
                // the messages are already local (the common case) the first
                // fetch satisfies the guard and there is no retry, so the
                // positioning path below is reached with identical timing.
                var res = TdClient.getChatHistory(chatId, 0L, 30)
                if (freshUnread > 0 && freshLastReadId > 0L) {
                    var tries = 0
                    while (
                        tries < 4 &&
                        res.messages.none { !it.isOutgoing && it.id > freshLastReadId } &&
                        res.messages.none { it.id > cachedNewestId }
                    ) {
                        kotlinx.coroutines.delay(300)
                        res = TdClient.getChatHistory(chatId, 0L, 30)
                        tries++
                    }
                }
                val toAdd = res.messages.filter { it.id > cachedNewestId }
                for (m in toAdd.reversed()) {
                    if (messages.none { it.id == m.id }) {
                        messages.add(0, m)
                    }
                }

                if (freshUnread > 0 && freshLastReadId > 0L) {
                    // First-unread = smallest id strictly greater than
                    // lastReadInboxMessageId among incoming messages currently in
                    // our window. May be ABSENT (null) when TDLib already knows
                    // "N unread" from a server sync but the message body hasn't
                    // landed in the local DB yet.
                    val firstUnreadEntry = messages
                        .withIndex()
                        .filter { (_, m) -> !m.isOutgoing && m.id > freshLastReadId }
                        .minByOrNull { it.value.id }
                    val firstUnreadIdx = firstUnreadEntry?.index
                    unreadSeparatorId = firstUnreadEntry?.value?.id ?: 0L
                    // DECIDE BY COUNT, never by a layout-timing race or a stale
                    // read marker. A SMALL unread count = essentially caught up →
                    // sit at the bottom (newest), like Telegram: instant, no race.
                    // CRUCIAL fix: this no longer requires having LOCATED the
                    // first-unread bubble. When TDLib reports "1 unread" but the
                    // body is still landing locally (firstUnreadIdx == null), the
                    // old code fell through and did NOTHING — stranding the user on
                    // whatever sat on screen, usually their own last-sent bubble
                    // ("mi posiziona sotto l'ultimo messaggio che ho inviato").
                    // Going to the bottom is always right when caught up; the body
                    // paints there the instant it arrives. Only a real backlog
                    // (> threshold) AND a located separator pulls the "non letti"
                    // divider to the top to read downward.
                    val doAnchor = freshUnread > UNREAD_FITS_SCREEN && firstUnreadIdx != null
                    if (doAnchor) {
                        anchorFirstUnread(listState, messages, unreadSeparatorId)
                    } else {
                        runCatching { listState.scrollToItem(0, 0) }
                    }
                    if (com.secondream.novagram.BuildConfig.DEBUG) android.util.Log.d(
                        "NovaScroll",
                        "open(cache) unread=$freshUnread lastRead=$freshLastReadId " +
                            "firstUnreadId=$unreadSeparatorId idx=$firstUnreadIdx total=${messages.size} " +
                            "path=" + (if (doAnchor) "ANCHOR_TOP" else "BOTTOM")
                    )
                    chatUnreadOnOpen = freshUnread
                    unreadModeActive = true
                    return@runCatching
                }
                if (toAdd.isNotEmpty()) {
                    // No first-unread (chat fully read on another device)
                    // but we DID receive new content offscreen — mark it
                    // read and glide to the bottom so the user sees it.
                    runCatching {
                        TdClient.viewMessages(
                            chatId,
                            toAdd.filter { !it.isOutgoing }.map { it.id }.toLongArray()
                        )
                    }
                    runCatching { listState.animateScrollToItem(0) }
                }
                // toAdd empty AND no fresh-unread: pop-from-subscreen
                // with nothing changed. Preserve scroll position exactly
                // as the user left it — the listState already has the
                // right anchor, doing nothing is the right answer.
            }
            return@LaunchedEffect
        }
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
                // First pass is OFFLINE-only (onlyLocal): a chat you've synced
                // before paints instantly from TDLib's local DB instead of
                // waiting on a possible network round-trip. Subsequent passes go
                // online to fill anything the cache was missing (cold chats).
                val res = TdClient.getChatHistory(chatId, fromId, 0, 50, onlyLocal = attempts == 0)
                if (res.messages.isEmpty()) {
                    consecutiveEmpty++
                    if (consecutiveEmpty < 2) delay(350)
                } else {
                    consecutiveEmpty = 0
                    // Dedupe against in-memory messages — a concurrent
                    // newMessages emission could have inserted one of these
                    // ids already, and LazyColumn items(key = m.id) crashes
                    // hard on duplicate keys. We compute the lookup set once
                    // per batch instead of per-message contains() to avoid
                    // O(N²) on big channels.
                    val existing = messages.mapTo(HashSet()) { it.id }
                    val toAppend = res.messages.filter { it.id !in existing }
                    if (toAppend.isNotEmpty()) messages.addAll(toAppend)
                    fromId = res.messages.last().id
                }
                attempts++
            }
            // Initial load fetches from the newest end, so the list IS the
            // live tail — new messages may inject at the top from here on.
            atLatestWindow = true

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
                unreadSeparatorId = firstUnread?.id ?: 0L
                val idx = firstUnread?.let { tgt ->
                    messages.indexOfFirst { it.id == tgt.id }
                } ?: -1
                if (idx >= 0) {
                    // Same count-based rule as the cache-hit path: a small unread
                    // count means you were essentially caught up → land at the
                    // bottom (newest); a real backlog pulls the "non letti"
                    // separator to the top to read downward. Count-based so the
                    // decision never rides on a fragile layout-timing check.
                    if (initialUnread <= UNREAD_FITS_SCREEN) {
                        runCatching { listState.scrollToItem(0, 0) }
                    } else {
                        anchorFirstUnread(listState, messages, unreadSeparatorId)
                    }
                    if (com.secondream.novagram.BuildConfig.DEBUG) android.util.Log.d(
                        "NovaScroll",
                        "open(full) unread=$initialUnread lastRead=$lastReadId " +
                            "firstUnreadId=$unreadSeparatorId idx=$idx total=${messages.size} " +
                            "path=" + (if (initialUnread <= UNREAD_FITS_SCREEN) "BOTTOM" else "ANCHOR_TOP")
                    )
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
    // the user reaches the bottom (manually or via the FAB) we drop the
    // accent affordance on the jump-to-bottom button. The chat-list
    // unread badge is now driven by the separate per-visible-item
    // viewMessages flow below, so it clears progressively as the user
    // scrolls — not all-or-nothing on hitting index 0.
    LaunchedEffect(unreadModeActive, chatId) {
        if (!unreadModeActive) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .filter { it == 0 }
            .collect {
                chatUnreadOnOpen = 0
                unreadModeActive = false
            }
    }

    // Per-visible-message read receipts. Telegram's stock behaviour is
    // to mark messages as read AS THEY ENTER the viewport (not when the
    // user reaches the bottom). Without this, opening a chat with
    // unread, reading the first few in the viewport, and then leaving
    // without scrolling to the very bottom left the unread badge stuck
    // on the chat list at the original count — exactly the bug Eugenio
    // hit on non-silenced groups and channels.
    //
    // snapshotFlow on visibleItemsInfo emits when the visible window
    // changes (scroll or list mutation). distinctUntilChanged on the
    // *index list* dedupes redundant calls during a single frame /
    // micro-scroll. We map indices → message ids, filter for
    // non-outgoing, and call ViewMessages. TDLib idempotently marks
    // them and then fires UpdateChatReadInbox, which our handler
    // applies to the chatCache → chat-list badge drops in real time.
    LaunchedEffect(chatId) {
        snapshotFlow {
            // Key on the IDs of the visible messages, NOT just their index
            // numbers. When a new message arrives while we're pinned at the
            // bottom, the visible index range stays the same ([0,1,2,…]) but the
            // content at those positions shifts — keying on indices alone made
            // distinctUntilChanged swallow that emission, so an incoming
            // @-mention (or reaction) that landed while you were already sitting
            // at the bottom didn't clear until you nudged the scroll. IDs change
            // with content, so the read fires immediately.
            listState.layoutInfo.visibleItemsInfo.mapNotNull { messages.getOrNull(it.index)?.id }
        }
            .distinctUntilChanged()
            // Debounced: while you're actively scrolling, marking every
            // transiently-visible message read fired a ViewMessages PER FRAME.
            // With online=true the server echoes UpdateChatReadInbox back
            // immediately, so each frame triggered a chatUpdates storm that
            // recomposed the unread/mention/reaction badges — that's what made
            // fast scroll lag. Waiting for the scroll to settle (150ms) marks
            // read what you actually dwell on and fires ONE batch, not dozens.
            .debounce(150)
            .collect { visibleIds ->
                if (visibleIds.isEmpty()) return@collect
                val idSet = visibleIds.toHashSet()
                val visible = messages.filter { it.id in idSet }
                val ids = visible.mapNotNull { m -> if (!m.isOutgoing) m.id else null }.toLongArray()
                if (ids.isNotEmpty()) {
                    runCatching { TdClient.viewMessages(chatId, ids) }
                }
                // Mentions: reading a mention by scrolling it into view must
                // clear the @ badge. readAllChatMentions tells the server, but
                // its UpdateChatUnreadMentionCount echo lags / is sometimes
                // never delivered in this TDLib build — so on its own the badge
                // stayed stuck even after scrolling the whole chat (only the
                // TAP cleared it, because tap ALSO zeroes the count locally).
                // Mirror that here: fire the server read AND zero the cached
                // count locally (clearChatMentionCountLocal emits chatUpdates →
                // chip hides immediately). The count guard + the local zero make
                // this fire exactly once.
                if (visible.any { it.containsUnreadMention } &&
                    (TdClient.getCachedChat(chatId)?.unreadMentionCount ?: 0) > 0
                ) {
                    runCatching { TdClient.readAllChatMentions(chatId) }
                    TdClient.clearChatMentionCountLocal(chatId)
                }
                // Reactions: identical story and identical fix — server read
                // plus a local zero so the ♥ badge disappears on read, not only
                // on tap.
                if (visible.any { m -> m.unreadReactions?.isNotEmpty() == true } &&
                    (TdClient.getCachedChat(chatId)?.unreadReactionCount ?: 0) > 0
                ) {
                    runCatching { TdClient.readAllChatReactions(chatId) }
                    TdClient.clearChatReactionCountLocal(chatId)
                }
            }
    }

    // Listen for new messages in this chat.
    LaunchedEffect(chatId) {
        TdClient.newMessages.collect { msg ->
            if (msg.chatId == chatId) {
                // A jump is clobbering and placing its window right now. Touching
                // `messages` here would race that mutation (the documented
                // LazyColumn crash) and any scroll-to-bottom would fight the
                // landing. The message is not lost: an old-window jump reloads
                // the live tail on close / go-to-bottom, and a live-tail jump
                // picks up the next arrival normally once settling ends.
                if (jumpSettling) return@collect
                if (atLatestWindow) {
                    // Capture BEFORE inserting: are we pinned to (or within a hair
                    // of) the newest? In reverseLayout index 0 == the bottom. Using
                    // <= 2 instead of a strict == 0 tolerates the brief window right
                    // after opening where scroll-to-bottom hasn't fully applied, and
                    // the "I sent one, another arrives" case — without it the
                    // arrival stays hidden just below the fold under my last sent
                    // bubble. Read before add(0, msg) shifts every index up by one.
                    val wasAtBottom = listState.firstVisibleItemIndex <= 2
                    // On the live tail — inject at the top (adjacent, no gap).
                    // Dedupe: getChatHistory and UpdateNewMessage can race.
                    if (messages.none { it.id == msg.id }) {
                        messages.add(0, msg)
                    }
                    if (!msg.isOutgoing) {
                        runCatching { TdClient.viewMessages(chatId, longArrayOf(msg.id)) }
                        // In-app receive blip ("ding"), only while foregrounded
                        // on this chat — backgrounded, the system notification
                        // carries its own sound. Toggleable via messageSounds.
                        if (appearance.messageSounds &&
                            com.secondream.novagram.AppForegroundState.isInForeground
                        ) {
                            com.secondream.novagram.util.SoundFx.playReceive(context)
                        }
                    }
                    // Follow the new message down — but ONLY if we were already
                    // pinned to the bottom (or WE sent it). Without this, a
                    // prepend at index 0 anchors on the previously-newest item's
                    // key, so the arriving message lands BELOW the fold and you
                    // stay stuck on your own last-sent bubble while the new one
                    // is hidden (exactly the "mi posiziona sotto l'ultimo
                    // messaggio che ho inviato" report). If you'd scrolled UP to
                    // read history we leave you put: the go-to-bottom FAB /
                    // unread counter signals the new message instead of yanking
                    // you away from what you're reading.
                    if (wasAtBottom || msg.isOutgoing) {
                        runCatching { listState.animateScrollToItem(0) }
                    }
                } else if (msg.isOutgoing) {
                    // The user sent a message while parked in an OLD jumped
                    // window — reload the live tail so their message shows up
                    // contiguous at the bottom, instead of injecting it next
                    // to a months-old message (which would forge a gap).
                    runCatching {
                        val res = TdClient.getChatHistory(chatId, 0L, 50)
                        val fresh = res.messages.toList().sortedByDescending { it.id }
                        if (fresh.isNotEmpty()) {
                            messages.clear()
                            messages.addAll(fresh)
                            noMore = false
                            atLatestWindow = true
                        }
                    }
                    runCatching { listState.scrollToItem(0) }
                }
                // else: incoming message while reading old history. We do NOT
                // inject it (not adjacent to this window) and do NOT mark it
                // read (unseen). The unread count rises → the go-to-bottom FAB
                // lights up; tapping it reloads the tail and shows it.
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
                // Force a SnapshotStateList write. `messages[idx] =
                // messages[idx]` is a NO-OP because PersistentList.set
                // returns the same list when newRef === oldRef and
                // Compose's snapshot transaction bails on identity-equal
                // updates — so the items() iteration never invalidates
                // and the bubble keeps rendering the stale content until
                // the screen re-mounts. remove+add at the same index
                // is two distinct snapshot writes that always register;
                // the stable `key = m.id` on itemsIndexed means the
                // item slot is reused, no remount.
                run { val m = messages[idx]; messages.removeAt(idx); messages.add(idx, m) }
                // Override map: alternate read path for downstream
                // components like search highlighting / share previews
                // that subscribe to it directly. The revision bump is
                // the AUTHORITATIVE signal for MessageBubble recompose.
                messageContentOverrides[upd.messageId] = upd.newContent
                interactionRevisions[upd.messageId] =
                    (interactionRevisions[upd.messageId] ?: 0) + 1
            }
        }
    }
    // Poll vote sync: UpdatePoll carries only the fresh poll (keyed by
    // poll.id), so we find the message whose MessagePoll holds that id and
    // swap in a new MessagePoll content (preserving description / media /
    // can-add-option), then apply it through the SAME in-place + override +
    // revision-bump path the content collector uses. This is what makes the
    // vote bars and percentages update live the instant anyone votes — our
    // own taps included, since setPollAnswer round-trips back as UpdatePoll.
    LaunchedEffect(chatId) {
        TdClient.pollUpdates.collect { poll ->
            val idx = messages.indexOfFirst {
                (it.content as? TdApi.MessagePoll)?.poll?.id == poll.id
            }
            if (idx >= 0) {
                val msgId = messages[idx].id
                val old = messages[idx].content as TdApi.MessagePoll
                val updated = TdApi.MessagePoll(poll, old.description, old.media, old.canAddOption)
                messages[idx].content = updated
                run { val m = messages[idx]; messages.removeAt(idx); messages.add(idx, m) }
                messageContentOverrides[msgId] = updated
                interactionRevisions[msgId] =
                    (interactionRevisions[msgId] ?: 0) + 1
            }
        }
    }
    // UpdateMessageContent, carrying editDate (epoch seconds when the
    // edit happened) and the latest replyMarkup. We mutate both fields
    // in place and bump the revision counter using the same pattern as
    // the content collector above. Without this, edits would update the
    // body but the "modificato" tag — which keys off message.editDate > 0
    // in MessageBubble — would never appear, and a reader of the chat
    // would have no visual signal that the message was edited.
    LaunchedEffect(chatId) {
        TdClient.messageEdited.collect { upd ->
            if (upd.chatId != chatId) return@collect
            val idx = messages.indexOfFirst { it.id == upd.messageId }
            if (idx >= 0) {
                messages[idx].editDate = upd.editDate
                messages[idx].replyMarkup = upd.replyMarkup
                run { val m = messages[idx]; messages.removeAt(idx); messages.add(idx, m) }
                interactionRevisions[upd.messageId] =
                    (interactionRevisions[upd.messageId] ?: 0) + 1
                // Real-time content sync for the OPEN chat. TDLib reliably
                // fires UpdateMessageEdited for every edit, but it does NOT
                // always fire UpdateMessageContent — own-message text and
                // caption edits frequently skip it, so the content collector
                // above never runs and the visible bubble stays stale until
                // the user leaves and re-enters (the chat-list preview still
                // updates via UpdateChatLastMessage, which is exactly why
                // Eugenio saw it "in anteprima" but not in the open chat).
                // Fix: fetch the authoritative message and REPLACE the list
                // element with that fresh TdApi.Message reference. A new
                // reference under the same id key is the strongest possible
                // recompose signal — MessageBubble can't strong-skip a
                // changed `message` param, so it re-reads content / editDate
                // / markup from scratch. We retry a few times because TDLib
                // occasionally returns the pre-edit content if queried in the
                // same instant the edit lands; each swap is idempotent once
                // the content settles.
                scope.launch {
                    repeat(3) { attempt ->
                        kotlinx.coroutines.delay(if (attempt == 0) 80L else 220L)
                        val fresh = runCatching {
                            TdClient.getMessage(upd.chatId, upd.messageId)
                        }.getOrNull() ?: return@repeat
                        val curIdx = messages.indexOfFirst { it.id == upd.messageId }
                        if (curIdx < 0) return@launch
                        messages[curIdx] = fresh
                        messageContentOverrides[upd.messageId] = fresh.content
                        interactionRevisions[upd.messageId] =
                            (interactionRevisions[upd.messageId] ?: 0) + 1
                    }
                }
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
                // A search-arrow jump owns the scroll position: it clobbers
                // `messages` (firstId changes) then places the hit itself. If we
                // animate to index 0 here we fight that placement — the hit
                // flashes then the list snaps to the bottom ("scrolla scrolla",
                // imprecise). Tracker is already updated above, so once the jump
                // settles we resume correctly.
                if (jumpSettling) return@collect
                if (prev != newFirstId) {
                    val first = messages.firstOrNull() ?: return@collect
                    if (first.isOutgoing || listState.firstVisibleItemIndex <= 2) {
                        // Animate the viewport down by one bubble's worth
                        // when a new message lands and the user is
                        // already anchored at (or very near) the
                        // bottom. animateScrollToItem rides the same
                        // spring rhythm as Modifier.animateItem so the
                        // bubble entry and the viewport shift land in
                        // sync — that's what makes new messages feel
                        // like they FLOW in rather than appearing
                        // through a "flash". A fixed-duration animate
                        // would fight the placement spring; this
                        // shares clock with it.
                        runCatching { listState.animateScrollToItem(0) }
                    }
                }
            }
    }

    // (jumpSettling is declared near the top of the composable so the message
    // collectors above can also read it.)
    // Shared with the load-NEWER flow below so the two never mutate the
    // SnapshotStateList at the same time (concurrent addAll crashed LazyColumn).
    var loadingNewer by remember(chatId) { mutableStateOf(false) }
    // Auto load older when scroll near top of reversed list (i.e. bottom
    // of memory). Gated on `!loading` so it cannot fire while the initial
    // chat load is still iterating getChatHistory — two concurrent
    // `messages.addAll(...)` flows on the same SnapshotStateList tripped
    // an IndexOutOfBounds inside LazyColumn under fast scroll on chats
    // never opened before (cache-miss path), bringing the activity down
    // with it. `!loadingMore` still guards against re-entry of THIS flow.
    LaunchedEffect(listState, chatId) {
        // Emit on scroll AND on scroll-settle (the isScrollInProgress half):
        // prefetching 18 rows before the edge gives the fetch time to land
        // before a fast fling reaches the bottom of memory, and the settle
        // re-check tops up the instant a fling that outran the fetch stops —
        // so it no longer dead-ends on an old message until a manual nudge.
        // Tracks consecutive empty older-history fetches so a single transient
        // empty (which TDLib is allowed to return) can't permanently seal the
        // window — see the collect below.
        var olderEmptyStreak = 0
        snapshotFlow {
            (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to
                listState.isScrollInProgress
        }
            .distinctUntilChanged()
            .filter { (lastIdx, _) ->
                lastIdx >= messages.size - 18 &&
                    messages.isNotEmpty() &&
                    !loading &&
                    !loadingMore &&
                    !loadingNewer &&
                    !jumpSettling &&
                    !noMore
            }
            .collect {
                loadingMore = true
                val oldest = messages.lastOrNull()?.id ?: 0L
                runCatching {
                    val res = TdClient.getChatHistory(chatId, oldest, 50)
                    if (res.messages.isEmpty()) {
                        // TDLib can return an empty page transiently even when
                        // older history still exists (the result may be smaller
                        // than the limit, including 0). Treating the FIRST empty
                        // as end-of-history permanently dead-ended scroll-up on a
                        // transient miss. Require TWO empties in a row before
                        // sealing the window; any non-empty fetch resets it.
                        olderEmptyStreak++
                        if (olderEmptyStreak >= 2) noMore = true
                    } else {
                        olderEmptyStreak = 0
                        // Filter out anything already in the window — fast
                        // scroll + concurrent newMessages emissions can
                        // produce overlap with the in-memory tail; without
                        // this dedupe the list ends up with duplicate-id
                        // bubbles which then crash inside items(key = id)
                        // because the LazyList contract requires unique
                        // keys per item.
                        val existing = messages.mapTo(HashSet()) { it.id }
                        val toAppend = res.messages.filter { it.id !in existing }
                        if (toAppend.isNotEmpty()) messages.addAll(toAppend)
                    }
                }
                loadingMore = false
            }
    }

    // Auto-load NEWER when scrolling toward the bottom of a non-latest window.
    // After jumpToMessage parks us on an old message (pinned tap, "see in
    // chat" from a file/photo, reply or in-chat-search jump) the window is a
    // contiguous slice centred on the target with atLatestWindow=false. In a
    // reverseLayout list index 0 is the newest loaded; scrolling DOWN walks
    // toward it and previously DEAD-ENDED at the window edge — the timeline
    // "finished" mid-history and only the go-to-bottom arrow or a reopen
    // pulled the rest. Here we extend the window FORWARD, contiguously, by
    // fetching the page immediately newer than our current head until we
    // reconnect to the live tail (then atLatestWindow flips back on and the
    // newMessages collector resumes real-time appends). Fetching the
    // immediately-newer page keeps the slice contiguous — no burned-middle gap.
    LaunchedEffect(listState, chatId) {
        // Emit on scroll AND on scroll-settle (isScrollInProgress) and prefetch
        // 18 rows ahead, same as the load-older flow: a fast fling toward the
        // present gets the next page in place before it reaches the head of the
        // window, and a fling that outran the fetch tops up the moment it stops.
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { (idx, _) ->
                idx <= 18 &&
                    messages.isNotEmpty() &&
                    !atLatestWindow &&
                    !loading &&
                    !loadingMore &&
                    !loadingNewer &&
                    !jumpSettling
            }
            .collect {
                loadingNewer = true
                val newestId = messages.firstOrNull()?.id ?: 0L
                runCatching {
                    // offset = -limit ⇒ TDLib returns the `limit` messages
                    // strictly NEWER than newestId (fromMessageId sits at the
                    // given negative offset in the date-descending list).
                    val res = TdClient.getChatHistory(chatId, newestId, -50, 50)
                    val existing = messages.mapTo(HashSet()) { it.id }
                    val toPrepend = res.messages
                        .filter { it.id > newestId && it.id !in existing }
                        .sortedByDescending { it.id }
                    if (toPrepend.isNotEmpty()) messages.addAll(0, toPrepend)
                    // Reconnected to the present? Flip back to live mode so new
                    // messages inject at the top again.
                    val serverLast = TdClient.getCachedChat(chatId)?.lastMessage?.id ?: 0L
                    if (toPrepend.isEmpty() ||
                        (serverLast != 0L && messages.firstOrNull()?.id == serverLast)
                    ) {
                        atLatestWindow = true
                    }
                }
                loadingNewer = false
            }
    }

    // Briefly highlights a single message after the user lands on it via
    // jumpToMessage (reply tap, pinned tap, in-chat search arrows, deep
    // link). Set to the targetId right after the instant scroll, cleared
    // after ~1.2s by a delayed coroutine. Threaded down through
    // MessageBubble so the matching bubble paints an animated accent
    // overlay that fades out — Telegram-style "this is the message".
    var flashMessageId by remember(chatId) { mutableStateOf<Long?>(null) }
    // Cancels an in-flight jump so two quick taps can't run competing
    // placement/anchor loops that fight over the scroll position.
    var jumpJob by remember(chatId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // True only while a jump is pulling history for an off-window target —
    // drives a brief centered spinner so the round-trip + teleport doesn't
    // read as a frozen tap.
    var jumpLoading by remember(chatId) { mutableStateOf(false) }
    // While TRUE, message items drop their placement animation. We flip it on
    // around a jump that REPLACES the window (clear + addAll a fresh slice):
    // without it, animateItem tries to animate every bubble from its old
    // position to the new one, which reads as the bubbles "sliding down one by
    // one and settling" — the lag/glitch on far and pinned jumps. With it, the
    // new window simply appears and we land instantly.
    var jumpSuppressAnim by remember(chatId) { mutableStateOf(false) }

    // Jump to a message by id, loading older history in bounded batches
    // if it isn't in the in-memory window yet (used by same-chat link
    // taps AND the pinned-messages list). Without the load loop, tapping
    // a pinned message older than the loaded window did nothing.
    val jumpToMessage: (Long) -> Unit = { targetId ->
        jumpJob?.cancel()
        jumpJob = scope.launch {
            // Remember whether the target had to be fetched: if it's
            // already in the loaded window we can ANIMATE the scroll
            // (which is what the user expects when cycling through
            // search hits — they want to SEE the chat moving), and
            // only fall back to the instant snap when we had to pull
            // history mid-flight, where animating would mean animating
            // *through* a list whose size has just doubled.
            val wasAlreadyLoaded = messages.indexOfFirst { it.id == targetId } >= 0
            var idx = messages.indexOfFirst { it.id == targetId }
            // When the target ISN'T in the loaded window — typically a
            // pinned-message tap or a t.me deep-link to a very old
            // message — pull the surrounding context in ONE TDLib
            // round-trip instead of looping getChatHistory dozens of
            // times to backfill page by page. The old loop iterated up
            // to 40 times (each one a network call) and took multiple
            // seconds for old targets — exactly the "scroll a vita e
            // impreciso" Eugenio kept hitting on pinned taps and far
            // t.me links.
            //
            // TDLib's GetChatHistory(fromMessageId, offset, limit)
            // returns `limit` messages starting from fromMessageId with
            // `offset` newer messages included. offset = -limit/2 means
            // half-newer / half-older, so the target lands centered in
            // the returned batch. We splice the missing ones into
            // `messages`, then scroll.
            if (idx < 0) {
                jumpLoading = true
                // Kill the per-item placement animation BEFORE we swap the
                // window, so the fresh slice appears in place instead of every
                // bubble sliding/settling from its old spot.
                jumpSuppressAnim = true
                runCatching {
                    val limit = 60
                    // getChatHistory around an OLD message (an old pinned, a far
                    // t.me link) returns FEW or ZERO messages on the FIRST call:
                    // with onlyLocal=false TDLib pulls the surrounding history
                    // from the SERVER in the background, so the first response is
                    // often a stub that doesn't even contain the target. A single
                    // call was the "non lo trova / cascata che cerca"; the old
                    // 40-call page-by-page backfill was the "scroll a vita". This
                    // is the bounded middle: poll a few times until we actually
                    // have the target PLUS enough context to fill the screen,
                    // capped so it can never hang. The spinner stays up for the
                    // whole poll, then we land in one shot.
                    var window: List<TdApi.Message> = emptyList()
                    var attempt = 0
                    while (attempt < 10) {
                        val got = runCatching {
                            TdClient.getChatHistory(chatId, targetId, -(limit / 2), limit)
                                .messages.toList()
                        }.getOrDefault(emptyList())
                        if (got.size > window.size) window = got   // keep the best so far
                        val hasTarget = got.any { it.id == targetId }
                        // Enough context = target present AND a screenful around it
                        // so it doesn't immediately paginate in a visible cascade.
                        if (hasTarget && got.size >= 14) { window = got; break }
                        attempt++
                        kotlinx.coroutines.delay(160)
                    }
                    // Swap to the fetched window ONLY if it actually CONTAINS the
                    // target. For far-back results in a huge chat (e.g. searching
                    // "moto g53" in a busy group and stepping toward older hits)
                    // TDLib often returns a stub slice that does NOT include the
                    // target even after polling. Clobbering `messages` with that
                    // slice stranded the user in a WRONG old window with
                    // atLatestWindow=false — new messages stopped appearing until
                    // the chat was reopened. If we don't have the target, leave the
                    // current view untouched and bail below.
                    if (window.any { it.id == targetId }) {
                        messages.clear()
                        messages.addAll(window.sortedByDescending { it.id })
                        noMore = false
                        // Still "on the tail" only if this window reaches the newest
                        // message; otherwise we've parked in old history and new
                        // messages must NOT inject at the top.
                        atLatestWindow =
                            messages.firstOrNull()?.id == TdClient.getCachedChat(chatId)?.lastMessage?.id
                    }
                    idx = messages.indexOfFirst { it.id == targetId }
                }
            }
            // Defensive: if the single round-trip somehow didn't return
            // the target (e.g. message was deleted server-side between
            // the link being created and us trying to load it), bail
            // gracefully without flashing or scrolling. The caller
            // already returned true to short-circuit the Intent
            // fallback, so there's nothing more to do here.
            if (idx < 0) { jumpLoading = false; jumpSuppressAnim = false; return@launch }
            // Round-trip (if any) done and target located — drop the spinner.
            jumpLoading = false
            // Light the accent flash IMMEDIATELY so the highlight rides in
            // together with the jump, instead of appearing only after the
            // placement + anchor loop finishes (that delay was why it
            // "didn't always light up"). A newer jump cancels this job, so
            // it can never clear a later target's flash.
            flashMessageId = targetId
            launch {
                kotlinx.coroutines.delay(1500)
                if (flashMessageId == targetId) flashMessageId = null
            }
            run {
                // One repeatable, glitch-free placement. The two failure modes
                // Eugenio kept hitting:
                //   (a) "animazione estremamente glitchosa" — a long
                //       animateScrollToItem across a variable-height list flings,
                //       re-estimates the distance halfway, then hard-snaps at the
                //       end. Unavoidable for far targets.
                //   (b) "70% delle volte qualche messaggio sopra al target" — the
                //       resting offset was computed from the bubble height measured
                //       BEFORE its async image/text finished laying out, so the
                //       real (taller) bubble pushed the target below the 23% mark.
                // The cure for both:
                //   • ANIMATE only when the target is already painted on screen — a
                //     short hop that is always smooth. For an off-screen or freshly
                //     paginated target we do a SINGLE INSTANT placement instead of
                //     gliding through hundreds of bubbles; the flash highlight makes
                //     that teleport read as intentional (this is what Telegram does).
                //   • Before fixing the offset, WAIT for the target height to stop
                //     changing (3 identical frames) so the 23% math uses the real
                //     bubble height, never a placeholder — kills (b).
                //   • The resting position is ALWAYS set with an INSTANT
                //     scrollToItem (pixel-exact). After a short animated hop it is a
                //     sub-pixel no-op; on the instant path it is the placement
                //     itself. Either way the final spot is identical every jump.
                //
                // Geometry (reverseLayout=true): scrollToItem(idx, K) leaves K px
                // between the item's bottom edge and the viewport bottom, so its
                // top sits at vp-(K+size). K = vp*77/100 - size lands the top at
                // ~23% (the spot Eugenio approved). coerceAtLeast(0) so a bubble
                // taller than 0.77*vp simply anchors to the viewport bottom.
                suspend fun preciseRefine(animate: Boolean) {
                    // Resolve fresh by ID — the window may have been spliced or
                    // paginated since the jump started.
                    val li = messages.indexOfFirst { it.id == targetId }
                    if (li < 0) return
                    val vp = listState.layoutInfo.viewportSize.height
                    if (vp <= 0) { runCatching { listState.scrollToItem(li, 0) }; return }

                    // HEIGHT-INDEPENDENT placement. reverseLayout: scrollToItem(idx, S)
                    // leaves S px between the item's BOTTOM and the viewport bottom.
                    // S = 40% of the viewport puts the bubble's bottom at ~60% down —
                    // a touch above the middle (0.30 read "troppo in basso", 0.45 read
                    // "un po' troppo in alto", so 0.40 splits them). No height
                    // measurement, so it lands on the EXACT same spot every time and
                    // can NEVER miss the row.
                    val rest = (vp * 0.32f).toInt()

                    // Animate the glide ONLY for a SHORT hop (target within a couple
                    // of screens of where we are). For anything far — an old pinned,
                    // a reply deep in history — animateScrollToItem would FLING through
                    // hundreds of bubbles, paginating as it scrolls ("carica un po' per
                    // volta, lentissimo, a volte un target lontano"). So we teleport
                    // INSTANTLY; the flash highlight (lit by the caller) makes the jump
                    // read as intentional, exactly like Telegram.
                    val firstVis = listState.firstVisibleItemIndex
                    val near = kotlin.math.abs(li - firstVis) <= 12
                    if (animate && near) {
                        runCatching { listState.animateScrollToItem(li, rest) }
                        // Pixel-exact correction. animateScrollToItem on a
                        // variable-height reverseLayout list estimates item
                        // heights mid-flight and lands a little HIGH (the
                        // "freccette ricerca / badge atterrano troppo in alto"
                        // case). After the smooth hop, snap to the exact offset
                        // — a sub-pixel no-op visually, but it locks the target
                        // on the SAME 0.40*vp spot the instant path uses.
                        runCatching { listState.scrollToItem(li, rest) }
                    } else {
                        runCatching { listState.scrollToItem(li, rest) }
                    }
                }

                // Already in the loaded window → animation is PERMITTED; whether
                // it actually glides is decided inside (only for a target already
                // on screen — a short, smooth hop). Freshly paginated in → never
                // animate, just teleport precisely. (Flash already lit above.)
                preciseRefine(animate = wasAlreadyLoaded)
            }
            // Re-enable item placement animation once the jump has settled
            // (only if we suppressed it for a window swap above).
            if (jumpSuppressAnim) {
                kotlinx.coroutines.delay(80)
                jumpSuppressAnim = false
            }
        }
    }

    // In-chat SEARCH navigation (the up/down arrows). The job is exactly what
    // the user expects from Telegram: a brief spinner while the window around
    // the hit loads, then land precisely ON the hit, with no scrolling-through.
    // Getting there means dodging the two traps that produced "scrolla scrolla,
    // cerca cerca":
    //   1) Landing on a TINY window. Dropping onto a 1-6 message slice makes
    //      BOTH pagination flows fire at once (load-older sees lastIndex past the
    //      end, load-newer sees firstIndex<=18) and the list thrashes. So we load
    //      a proper centred window from the server first, patiently, spinner up.
    //      The hit object we already hold from searchInChat is GRAFTED only as a
    //      last resort, so the arrow can never become a no-op.
    //   2) Pagination firing mid-landing. Even a full window sits within the
    //      18-row prefetch edge, so a load can kick off the instant we scroll.
    //      jumpSettling pauses both pagination flows for the whole jump plus a
    //      short settle, keeping the view put.
    // Placement is an INSTANT, exact scrollToItem (never animated): the spinner
    // already covered the wait, so there is nothing left to scroll through.
    val jumpToSearchResult: (TdApi.Message) -> Unit = { target ->
        jumpJob?.cancel()
        jumpJob = scope.launch {
            val targetId = target.id
            jumpSettling = true
            try {
                if (messages.indexOfFirst { it.id == targetId } < 0) {
                    jumpLoading = true
                    jumpSuppressAnim = true
                    runCatching {
                        val limit = 60
                        var window: List<TdApi.Message> = emptyList()
                        var stable = 0
                        var attempt = 0
                        // TDLib returns a local stub first and pulls the
                        // surrounding history from the server in the background,
                        // so early calls are short or miss the hit. Poll until we
                        // have the hit WITH a screenful around it, the window stops
                        // growing (short chat / start of history), or we time out
                        // (~2.4s). The spinner stays up the whole time.
                        while (attempt < 16) {
                            val got = runCatching {
                                TdClient.getChatHistory(chatId, targetId, -(limit / 2), limit, false)
                                    .messages.toList()
                            }.getOrDefault(emptyList())
                            if (got.size > window.size) { window = got; stable = 0 } else stable++
                            val hasTarget = window.any { it.id == targetId }
                            if (hasTarget && (window.size >= 16 || stable >= 2)) break
                            attempt++
                            kotlinx.coroutines.delay(150)
                        }
                        val finalWin = when {
                            window.any { it.id == targetId } -> window
                            window.isNotEmpty() -> window + target
                            else -> listOf(target)
                        }
                        messages.clear()
                        messages.addAll(finalWin.distinctBy { it.id }.sortedByDescending { it.id })
                        noMore = false
                        atLatestWindow =
                            messages.firstOrNull()?.id == TdClient.getCachedChat(chatId)?.lastMessage?.id
                    }
                    jumpLoading = false
                }
                val li = messages.indexOfFirst { it.id == targetId }
                if (li >= 0) {
                    flashMessageId = targetId
                    launch {
                        kotlinx.coroutines.delay(1500)
                        if (flashMessageId == targetId) flashMessageId = null
                    }
                    // Exact landing: hit bubble ~60% down the viewport, instant.
                    val vp = listState.layoutInfo.viewportSize.height
                    val off = if (vp > 0) (vp * 0.40f).toInt() else 0
                    runCatching { listState.scrollToItem(li, off) }
                }
                // Hold the pagination guard a beat so the freshly placed window
                // settles before load-older/newer can react to the new position.
                kotlinx.coroutines.delay(400)
                jumpSuppressAnim = false
            } finally {
                jumpSettling = false
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
    // Full TdApi.Message objects (not just ids): we keep them so the search
    // arrows can graft a hit into the window when TDLib can't re-center it,
    // instead of bailing to a no-op. See jumpToSearchResult.
    var searchResults by remember(chatId) { mutableStateOf<List<TdApi.Message>>(emptyList()) }
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
        searchResults = matches
        searchIndex = 0
        searchLoading = false
        if (searchResults.isNotEmpty()) jumpToSearchResult(searchResults[0])
    }

    // Resolve a tapped Telegram link to a chat and open it INSIDE the app.
    // Mirrors MainActivity.handleTmeDeeplink's parsing but navigates via
    // onOpenChat instead of an Intent, so a t.me link in a message never
    // bounces the user out to a browser.
    // True while openTelegramLink is in flight resolving a username /
    // invite via TDLib. First-time resolutions can take 1-3 seconds
    // (TDLib has to round-trip through the Telegram servers and then
    // wait for a complete chat record to be emitted) and without
    // visible feedback the tap looked broken. Renders a centered
    // spinner overlay (built far below) while true; cleared in a
    // finally{} so an exception in the resolution doesn't leave the
    // spinner stuck. MUST be declared before `openTelegramLink`
    // because the lambda closure captures it — Kotlin doesn't allow
    // forward references within the same function body.
    var linkResolving by remember { mutableStateOf(false) }

    // Per-message pending state for inline keyboard taps. Key = message
    // id, value = the row:col:label key of the button whose callback is
    // currently in flight. Cleared once TDLib answers (or fails). Map
    // not nullable single because TDLib doesn't enforce serialization
    // across messages — the user can tap "Sbloccami" on one bot's
    // notification while another's button is still resolving.
    var pendingInlineButtonKeys by remember(chatId) {
        mutableStateOf<Map<Long, String>>(emptyMap())
    }

    // Toast-like banner shown when an inline button's callback returns
    // text (most bots return a short confirmation or error message).
    // isAlert=true would map to a modal dialog on official Telegram;
    // we render both the same way for now — a transient bottom banner.
    var inlineButtonResult by remember { mutableStateOf<InlineButtonResult?>(null) }
    LaunchedEffect(inlineButtonResult) {
        val r = inlineButtonResult ?: return@LaunchedEffect
        if (r.text.isBlank()) {
            inlineButtonResult = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(if (r.isAlert) 4500 else 2400)
        inlineButtonResult = null
    }

    // Generic spinner overlay for slow user-facing actions whose
    // result we have to wait on (no optimistic UI possible). Set to a
    // localized label when an admin moderation call is in flight,
    // cleared in a finally{} so an exception doesn't leave it stuck.
    // Renders the same scrim + spinner as linkResolving below, with
    // an optional caption underneath the spinner so the user knows
    // WHICH action is pending ("Silenziando...", "Bannando..." etc.)
    var pendingActionLabel by remember { mutableStateOf<String?>(null) }

    val openTelegramLink: (android.net.Uri) -> Unit = { uri ->
        scope.launch {
            // Flip the resolving flag so the overlay spinner draws.
            // Wrapped in try/finally so even if TDLib throws (network
            // hiccup, blocked invite) the spinner doesn't get stuck.
            linkResolving = true
            try {
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
            } finally {
                linkResolving = false
            }
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var aiModalOpen by remember { mutableStateOf(false) }
    // Controls visibility of the self-destruct timer chooser dialog.
    // Set true by the menu item; the dialog itself clears it.
    var ttlDialogOpen by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    // Drives the in-app photo/video viewer that opens from the info dialog and
    // the profile sheet. It's rendered as its own full-screen Dialog window
    // (below, near the info dialog) that STACKS ON TOP of those surfaces — so
    // opening media no longer tears the info dialog down and rebuilds it on
    // return (the source of the bare-chat flash + the "torna in chat" race).
    var viewerOpen by remember { mutableStateOf(false) }
    // Remembers which info tab the user was on, so that reopening the info
    // dialog after closing a photo/video viewer (see the reopen flags on
    // MediaViewerHolder) lands back on the same tab (e.g. "Foto") rather
    // than snapping to "Info". Kept here in ChatScreen because the dialog
    // itself is torn down between opens.
    var infoInitialPage by remember(chatId) { mutableStateOf(0) }
    // Deferred media-viewer open requested from the chat-info dialog.
    // Navigating to the viewer route AND dismissing the info Dialog in the
    // same frame races the Dialog-window teardown against the NavHost push,
    // so the freshly-pushed viewer was getting popped straight back and the
    // user landed at the chat ("il pulsante apri foto/file/video... molte
    // volte mi porta indietro"). The dialog now stashes the path in
    // MediaViewerHolder, dismisses itself, and flips this flag; the effect
    // below fires once the Dialog is gone and navigates cleanly.
    var pendingViewerOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pendingViewerOpen) {
        if (pendingViewerOpen) {
            onOpenMediaViewer()
            pendingViewerOpen = false
        }
    }
    // When the media viewer closes and this chat comes back to the front,
    // restore the surface it was launched from (the info dialog or the
    // profile sheet). Driven by flags on MediaViewerHolder + ChatScreen's
    // OWN lifecycle, so it's immune to the navigation timing that used to
    // drop the user on the bare chat / pop too far ("torna in home"). We
    // react on ON_START — which fires at the START of the back-pop, before
    // the viewer has finished sliding away — so the surface reappears
    // immediately and the user never sees a flash of bare chat first. On
    // first open / app-foreground the flags are false → no-op.
    val viewerReturnOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(viewerReturnOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            // ON_START fires at the start of the back-pop (no bare-chat flash);
            // ON_RESUME is the fallback in case the entry stayed STARTED while
            // the viewer was up so ON_START didn't re-fire. The flags below are
            // cleared on the first of the two ⇒ the reopen happens exactly once.
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START ||
                event == androidx.lifecycle.Lifecycle.Event.ON_RESUME
            ) {
                if (com.secondream.novagram.ui.screens.MediaViewerHolder.reopenInfo) {
                    com.secondream.novagram.ui.screens.MediaViewerHolder.reopenInfo = false
                    infoOpen = true
                }
                com.secondream.novagram.ui.screens.MediaViewerHolder.reopenProfileUid?.let { uid ->
                    com.secondream.novagram.ui.screens.MediaViewerHolder.reopenProfileUid = null
                    profileSheetUserId = uid
                }
            }
        }
        viewerReturnOwner.lifecycle.addObserver(obs)
        onDispose { viewerReturnOwner.lifecycle.removeObserver(obs) }
    }
    // Notification foreground gate. currentChatId reflects the chat the user is
    // ACTIVELY looking at, driven by THIS screen's resume/pause rather than the
    // process-wide isInForeground flag — that flag could momentarily read false
    // while the screen was plainly on top (notification-shade pull, quick app
    // switch), which let a heads-up slip through for the chat in view (Eugenio:
    // "in gruppo arrivavano anche se ero già in fondo"). ON_RESUME ⇒ this chat
    // is on screen and resumed → suppress its notifications; ON_PAUSE (background,
    // overlay, navigate away) clears it → notifications resume. addObserver
    // replays to the current state, so entering a resumed chat sets it at once.
    DisposableEffect(viewerReturnOwner, chatId) {
        val gateObs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    com.secondream.novagram.AppForegroundState.currentChatId = chatId
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    if (com.secondream.novagram.AppForegroundState.currentChatId == chatId) {
                        com.secondream.novagram.AppForegroundState.currentChatId = 0L
                    }
                else -> {}
            }
        }
        viewerReturnOwner.lifecycle.addObserver(gateObs)
        onDispose {
            viewerReturnOwner.lifecycle.removeObserver(gateObs)
            if (com.secondream.novagram.AppForegroundState.currentChatId == chatId) {
                com.secondream.novagram.AppForegroundState.currentChatId = 0L
            }
        }
    }
    var deleteOpen by remember { mutableStateOf(false) }
    var leaveOpen by remember { mutableStateOf(false) }
    // Non-reactive snapshot of the cached chat. Used for header
    // display reads — photo, type, channel-vs-supergroup — none of
    // which need to flip mid-screen. The isMuted state below IS
    // reactive (subscribes to chatUpdates) so muting from the action
    // sheet flips the bell icon live without losing the rest of this
    // header snapshot.
    // Live cached chat: re-resolves on every chatUpdates emission for
    // this chatId. Drives the title-bar avatar/name/online state, mute
    // bell, protected-content flag, and every other "current state of
    // the chat" read in this composable. Previously this was a plain
    // `val = getCachedChat(...)` snapshot taken once at compose time —
    // muting from another device, peer-photo change, hasProtectedContent
    // flip etc. wouldn't reflect until the user left the chat and came
    // back. The pulseTick state forces a re-read on every chatUpdates
    // event without going through a flow (the cached Chat is already
    // mutated in place inside TdClient; we just need to retrigger the
    // read so the Composable observes it).
    var cachedChatPulse by remember(chatId) { mutableStateOf(0) }
    LaunchedEffect(chatId) {
        // Debounced: cachedChatLive feeds the header (photo / type), which
        // changes rarely. Bumping it on EVERY chatUpdate meant a busy group
        // (especially with online=true streaming read receipts) recomposed
        // ChatScreen on every single update — janky during scroll. Debounce
        // coalesces a burst into one refresh ~100ms after activity settles.
        TdClient.chatUpdates
            .filter { it == chatId }
            .debounce(100)
            .collect { cachedChatPulse++ }
    }
    val cachedChatLive = remember(chatId, cachedChatPulse) {
        TdClient.getCachedChat(chatId)
    }
    // Read-receipt tick (✓✓) source, DECOUPLED from cachedChatPulse so the
    // message list recomposes ONLY when the peer's read position actually
    // advances — not on every unrelated chat update. The "assign on change"
    // guard is what keeps scrolling smooth: scrolling fires viewMessages →
    // read-INBOX echoes, which would otherwise storm a shared pulse.
    var readOutboxMax by remember(chatId) {
        mutableStateOf(TdClient.getCachedChat(chatId)?.lastReadOutboxMessageId ?: 0L)
    }
    LaunchedEffect(chatId) {
        TdClient.chatUpdates.collect { cid ->
            if (cid == chatId) {
                val v = TdClient.getCachedChat(chatId)?.lastReadOutboxMessageId ?: 0L
                if (v != readOutboxMax) readOutboxMax = v
            }
        }
    }
    // Live mute state: subscribes to chatUpdates so toggling mute from
    // the action sheet (or from the chat-list swipe action, or from
    // another device) flips the BellSlash icon in the title immediately
    // — no more "leave the chat and come back to see the change".
    // Reads getCachedChat which is kept in sync by the
    // UpdateChatNotificationSettings handler in TdClient.
    var isMuted by remember(chatId) {
        mutableStateOf(
            (TdClient.getCachedChat(chatId)?.notificationSettings?.muteFor ?: 0) > 0
        )
    }
    LaunchedEffect(chatId) {
        TdClient.chatUpdates.collect { cid ->
            if (cid == chatId) {
                isMuted = (TdClient.getCachedChat(chatId)?.notificationSettings?.muteFor ?: 0) > 0
            }
        }
    }

    // Interactive swipe-from-left-edge to close the chat, with a predictive
    // parallax like the Android back gesture: the chat follows your finger to
    // the right and you control the close — drag back left to cancel, or
    // release past the threshold to pop. The old version tracked the drag but
    // never moved anything (no feedback) AND lost the gesture to the
    // LazyColumn's vertical scroll, so it only fired on the input bar.
    //
    // We intercept in the INITIAL pointer pass for drags that START in the left
    // edge zone, so we win the gesture before the list can claim it for
    // scrolling. Outside the edge zone (or for a clearly-vertical drag) we don't
    // claim, and the list / bubble reply-swipe behave exactly as before.
    // Live back-swipe offset in px, written SYNCHRONOUSLY from the gesture loop
    // so the chat follows the finger frame-perfectly — no coroutine spawned per
    // pointer event (that was both a stutter source and, with Animatable.snapTo,
    // illegal inside the restricted gesture scope). Animation is used ONLY on
    // release: a velocity-carrying glide to commit, a springy retreat to cancel.
    var backX by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val backTriggerPx = with(density) { 96.dp.toPx() }
    val edgeZonePx = with(density) { 28.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(chatId) {
                val slop = viewConfiguration.touchSlop
                val initial = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                // Holds the in-flight release animation so a re-grab during the
                // spring-back cancels it and hands off cleanly to the new drag.
                var releaseBackJob: kotlinx.coroutines.Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = initial)
                    // Only an edge swipe (started near the left edge) arms it.
                    if (down.position.x > edgeZonePx) return@awaitEachGesture
                    var claimed = false
                    var totalX = 0f
                    var totalY = 0f
                    // Track fling velocity so a quick flick commits the close even
                    // before the distance threshold — the responsive feel of the
                    // real predictive-back gesture.
                    val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    while (true) {
                        val event = awaitPointerEvent(initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break // released
                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        totalX += dx
                        totalY += dy
                        if (!claimed) {
                            if (kotlin.math.abs(totalY) > slop && kotlin.math.abs(totalY) > totalX) {
                                // Vertical intent wins → let the list scroll.
                                return@awaitEachGesture
                            }
                            if (totalX > slop) { claimed = true; releaseBackJob?.cancel() }
                        }
                        if (claimed) {
                            // Consume so the list never scrolls underneath us.
                            change.consume()
                            velocity.addPosition(change.uptimeMillis, change.position)
                            // Synchronous state write — legal in the restricted
                            // gesture scope and the smoothest finger-follow there
                            // is (lands on the very next frame, in order, no
                            // coroutine overhead).
                            backX = (backX + dx).coerceIn(0f, size.width.toFloat())
                        }
                    }
                    // Released.
                    if (claimed) {
                        val w = size.width.toFloat()
                        val vx = runCatching { velocity.calculateVelocity().x }.getOrDefault(0f)
                        // Commit on EITHER a past-threshold drag OR a clear rightward
                        // flick, so a fast short swipe still closes the chat.
                        if (backX > backTriggerPx || vx > 800f) {
                            // Commit: shoot the rest of the way out CARRYING the
                            // flick velocity, so a fast swipe flies off naturally
                            // instead of decelerating to a fixed-duration crawl.
                            releaseBackJob = scope.launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = backX,
                                    targetValue = w,
                                    initialVelocity = vx,
                                    animationSpec = androidx.compose.animation.core.tween(160)
                                ) { v, _ -> backX = v }
                                onBack()
                            }
                        } else {
                            // Cancel: springy retreat to closed (a touch of bounce)
                            // so it reads as alive, not a flat linear snap-back.
                            releaseBackJob = scope.launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = backX,
                                    targetValue = 0f,
                                    initialVelocity = vx,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.72f,
                                        stiffness = 420f
                                    )
                                ) { v, _ -> backX = v }
                            }
                        }
                    }
                }
            }
    ) {

    Scaffold(
        // Predictive back-swipe parallax: the whole chat follows the finger to
        // the right and shrinks a touch (reads as a card being dismissed), then
        // either glides out (commit) or springs back (cancel). Driven by
        // backX above.
        modifier = Modifier.graphicsLayer {
            translationX = backX
            val p = if (size.width > 0f) (backX / size.width).coerceIn(0f, 1f) else 0f
            val s = 1f - 0.06f * p
            scaleX = s
            scaleY = s
            alpha = 1f - 0.10f * p
        },
        // The bottom navigation-bar inset is owned by the InputBar (and the
        // join-chat / non-member boxes), each of which applies
        // navigationBarsPadding() itself. If we ALSO let the Scaffold add the
        // system bottom inset to `padding`, the nav bar is counted twice — a
        // barely-visible gap with gesture nav but a huge dead band with the
        // 3-button bar. Zeroing contentWindowInsets makes the InputBar the
        // single source of truth for the bottom inset. (The status-bar inset
        // at the top is still handled by TopAppBar's own windowInsets, so the
        // header is unaffected.)
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column {
            // Saved Messages pseudo-chat detection: re-derives on every
            // composition, so as soon as the LaunchedEffect above
            // populates `myUserId` (Long?) we flip to the bookmark
            // identity. While myUserId is still null (first frame, race
            // with the TdClient.getMe() round-trip), renders normally as
            // a regular avatar + chat title — the chat will never
            // visually flicker because getMe() resolves in <50ms from
            // the local TDLib cache.
            val isSavedMessages = myUserId != null &&
                myUserId != 0L &&
                chatId == myUserId
            // When Saved Messages, replace the title text with the
            // localized "Messaggi salvati" label so the header reads
            // the same way as the list row.
            val effectiveTitle = if (isSavedMessages) savedMessagesLabel else chatTitle
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
                            if (isSavedMessages) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.BookmarkSimple,
                                        contentDescription = savedMessagesLabel,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else {
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
                                                MaterialTheme.colorScheme.primary,
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    effectiveTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isMuted) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.BellSlash,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(20.dp)
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
                    IconButton(onClick = { aiModalOpen = true }) {
                        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle, contentDescription = "Novagram AI")
                    }
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass, null)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.DotsThreeVertical, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            // Chat-topbar action menu rendered as an ActionBottomSheet
            // for visual consistency with the long-press / MessageActionsSheet
            // pattern. Tiles built from the same conditional logic as the
            // legacy DropdownMenu: mute/unmute is always present; TTL is
            // gated by isSecretChat; leave/delete swaps based on
            // group/channel vs private; the destructive tile gets the
            // destructive treatment automatically.
            if (menuOpen) {
                val isSecretChat = remember(chatId) {
                    TdClient.getCachedChat(chatId)?.type is TdApi.ChatTypeSecret
                }
                val isChannel = cachedChatLive?.type is TdApi.ChatTypeSupergroup &&
                    (cachedChatLive.type as TdApi.ChatTypeSupergroup).isChannel
                // Peer user id for a 1-to-1 chat (private chat id == user id;
                // secret carries it on the type). Drives block / unblock.
                val blockPeerId = when (val t = cachedChatLive?.type) {
                    is TdApi.ChatTypePrivate -> t.userId
                    is TdApi.ChatTypeSecret -> t.userId
                    else -> null
                }
                var peerBlocked by remember(chatId) { mutableStateOf(false) }
                LaunchedEffect(blockPeerId, menuOpen) {
                    peerBlocked = blockPeerId?.let {
                        runCatching { TdClient.isUserBlocked(it) }.getOrDefault(false)
                    } ?: false
                }
                val menuTiles = buildList {
                    if (isNonMember) {
                        // Public group/channel we're only previewing — Join not
                        // tapped yet — so mute / leave are meaningless (we're not
                        // members). Placeholder "Annulla" tile until there are
                        // real preview-state actions to offer here.
                        add(
                            com.secondream.novagram.ui.components.ActionTile(
                                label = stringResource(R.string.action_cancel),
                                icon = com.secondream.novagram.ui.icons.PhosphorIcons.X,
                                onClick = { menuOpen = false }
                            )
                        )
                    } else {
                    add(
                        com.secondream.novagram.ui.components.ActionTile(
                            label = stringResource(
                                if (isMuted) R.string.action_unmute_chat
                                else R.string.action_mute_chat
                            ),
                            icon = if (isMuted)
                                com.secondream.novagram.ui.icons.PhosphorIcons.Bell
                            else com.secondream.novagram.ui.icons.PhosphorIcons.BellSlash,
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    runCatching { TdClient.setChatMuted(chatId, !isMuted) }
                                }
                            }
                        )
                    )
                    if (isSecretChat) {
                        add(
                            com.secondream.novagram.ui.components.ActionTile(
                                label = stringResource(R.string.chat_set_ttl),
                                icon = com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
                                onClick = {
                                    menuOpen = false
                                    ttlDialogOpen = true
                                }
                            )
                        )
                    }
                    if (isGroupChat) {
                        add(
                            com.secondream.novagram.ui.components.ActionTile(
                                label = stringResource(
                                    if (isChannel) R.string.action_leave_channel
                                    else R.string.action_leave_group
                                ),
                                icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                                destructive = true,
                                onClick = {
                                    menuOpen = false
                                    leaveOpen = true
                                }
                            )
                        )
                    } else {
                        if (blockPeerId != null) {
                            add(
                                com.secondream.novagram.ui.components.ActionTile(
                                    label = stringResource(
                                        if (peerBlocked) R.string.action_unblock_user
                                        else R.string.action_block_user
                                    ),
                                    icon = if (peerBlocked)
                                        com.secondream.novagram.ui.icons.PhosphorIcons.Check
                                    else com.secondream.novagram.ui.icons.PhosphorIcons.UserMinus,
                                    destructive = !peerBlocked,
                                    onClick = {
                                        menuOpen = false
                                        val uid = blockPeerId
                                        val wasBlocked = peerBlocked
                                        scope.launch {
                                            runCatching {
                                                if (wasBlocked) TdClient.unblockUser(uid)
                                                else TdClient.blockUser(uid)
                                            }.onSuccess {
                                                com.secondream.novagram.ui.components.NovaSnackbar.show(
                                                    if (wasBlocked) R.string.snack_user_unblocked
                                                    else R.string.snack_user_blocked,
                                                    if (wasBlocked) com.secondream.novagram.ui.icons.PhosphorIcons.Check
                                                    else com.secondream.novagram.ui.icons.PhosphorIcons.UserMinus
                                                )
                                            }
                                        }
                                    }
                                )
                            )
                        }
                        add(
                            com.secondream.novagram.ui.components.ActionTile(
                                label = stringResource(R.string.action_delete_chat),
                                icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                                destructive = true,
                                onClick = {
                                    menuOpen = false
                                    deleteOpen = true
                                }
                            )
                        )
                    }
                    }
                }
                com.secondream.novagram.ui.components.ActionBottomSheet(
                    title = chatTitle,
                    onDismiss = { menuOpen = false },
                    tiles = menuTiles
                )
            }
            com.secondream.novagram.ui.components.OfflineBanner()
            // Chat search bar: collapsible, sits under the title. Up/down
            // arrows cycle through TDLib search results; tapping a result
            // (or the bar's prev/next) re-uses jumpToMessage so the target
            // lands near the top of the visible area.
            androidx.compose.animation.AnimatedVisibility(
                visible = searchOpen,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 320,
                        easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
                    )
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(280, delayMillis = 40)
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 260,
                        easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
                    )
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(180)
                )
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
                            jumpToSearchResult(searchResults[searchIndex])
                        }
                    },
                    onNext = {
                        if (searchResults.isNotEmpty()) {
                            searchIndex = (searchIndex + 1) % searchResults.size
                            jumpToSearchResult(searchResults[searchIndex])
                        }
                    },
                    onClose = {
                        searchOpen = false
                        searchQuery = ""
                        // Returning from search must never leave the user stuck in
                        // the past. If stepping through old hits parked us in an
                        // old slice (atLatestWindow=false, a disjoint window whose
                        // bottom is NOT the live tail — the "bloccato in una
                        // finestra vecchia" case), reload the latest page and drop
                        // to the bottom. When we were already on the tail this is a
                        // no-op, so closing search after only viewing recent hits
                        // doesn't yank the view.
                        scope.launch {
                            if (!atLatestWindow) {
                                runCatching {
                                    val fresh = TdClient.getChatHistory(chatId, 0L, 50)
                                        .messages.toList().sortedByDescending { it.id }
                                    if (fresh.isNotEmpty()) {
                                        messages.clear()
                                        messages.addAll(fresh)
                                        noMore = false
                                        atLatestWindow = true
                                    }
                                }
                                runCatching { listState.scrollToItem(0) }
                            }
                        }
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
            // Pinned-message banner. Restored in v0.10.57 after being
            // removed in v0.10.55 — without it the chat lost its most
            // efficient affordance for reaching a pinned message
            // without scrolling. Sits flush under the TopAppBar,
            // ~48dp tall, fully clickable to open the pinned-messages
            // list sheet. Renders only when `pinned` is non-null;
            // disappears cleanly when the chat has nothing pinned (or
            // the last pinned message gets unpinned mid-session).
            if (pinned != null) lastPinnedBanner = pinned
            androidx.compose.animation.AnimatedVisibility(
                visible = pinned != null,
                // Smooth expand/collapse so the banner doesn't POP in (and jolt
                // the list down) the beat after the chat opens — the pinned
                // message loads async, so we animate its arrival/removal.
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = 1f,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                    )
                ) + androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(200)
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(180)
                ) + androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(140)
                )
            ) {
                lastPinnedBanner?.let { pinnedMsg ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { pinnedSheetOpen = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left accent stripe: 3dp wide, rounded, primary
                            // colour — Telegram-convention "this is a pin" marker.
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.chat_pinned_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    TdClient.buildPreview(pinnedMsg).ifBlank { " " },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            thickness = 1.dp
                        )
                    }
                }
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // animateItem(): default would fade new bubbles
                                // in from alpha 0 over 220ms, which reads as a
                                // flash when a new message lands or when the
                                // list paginates older history mid-scroll. We
                                // nuke the fade specs entirely AND drop the
                                // bouncy ratio in favour of critically-damped
                                // (NoBouncy) so the bubble slides into place
                                // without overshoot. Hoisted onto the Column so
                                // the optional unread separator above the bubble
                                // travels with it as one animated unit.
                                .animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    // Reduce-animations escape hatch for weak
                                    // devices: null placement spec = items snap
                                    // to position with no animation at all.
                                    placementSpec = if (appearance.reduceAnimations || jumpSuppressAnim) null
                                    else androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                    )
                                )
                        ) {
                            if (unreadSeparatorId != 0L && msg.id == unreadSeparatorId) {
                                UnreadMessagesSeparator()
                            }
                            androidx.compose.foundation.layout.Box {
                            MessageBubble(
                                message = msg,
                                showSender = isGroupChat,
                                // Reactive read marker — flips the ✓✓ on my
                                // private-chat messages the moment the peer
                                // reads (readOutboxMax advances only when the
                                // peer's read position actually moves).
                                readOutboxMaxId = readOutboxMax,
                                adminLabel = (msg.senderId as? TdApi.MessageSenderUser)
                                    ?.userId?.let { adminLabels[it] },
                                onLongPress = { deleteTarget = it },
                                onMediaTap = { path ->
                                    // Opened straight from a chat bubble → on
                                    // close just return to the chat; there's no
                                    // info/profile surface to restore.
                                    com.secondream.novagram.ui.screens.MediaViewerHolder.reopenInfo = false
                                    com.secondream.novagram.ui.screens.MediaViewerHolder.reopenProfileUid = null
                                    com.secondream.novagram.ui.screens.MediaViewerHolder.currentPath = path
                                    // Open the viewer as a hoisted dialog OVER this
                                    // chat instead of navigating to a separate route.
                                    // A nav push disposed ChatScreen; on return it
                                    // reloaded only the recent page, so the restored
                                    // scroll index couldn't reach a spot deep in old
                                    // history and dumped the user near the bottom.
                                    // Keeping ChatScreen composed preserves both the
                                    // loaded history and the exact scroll offset.
                                    viewerOpen = true
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
                                flashing = (flashMessageId == msg.id),
                                onInlineButton = { btn, key ->
                                    handleInlineKeyboardButton(
                                        chatId = chatId,
                                        message = msg,
                                        button = btn,
                                        buttonKey = key,
                                        context = context,
                                        scope = scope,
                                        setPendingKey = { newKey ->
                                            pendingInlineButtonKeys = if (newKey == null) {
                                                pendingInlineButtonKeys - msg.id
                                            } else {
                                                pendingInlineButtonKeys + (msg.id to newKey)
                                            }
                                        },
                                        showCallbackResult = { txt, isAlert ->
                                            inlineButtonResult = InlineButtonResult(txt, isAlert)
                                        },
                                        openLink = openTelegramLink
                                    )
                                },
                                pendingInlineButtonKey = pendingInlineButtonKeys[msg.id]
                            )
                            }
                        }
                    }
                }
                // Secret-chat handshake notice. A freshly created secret chat
                // sits in Pending until the peer's device comes online and
                // finishes the key exchange; TDLib refuses every send until
                // then. Rather than let the user type into a void (sends were
                // silently swallowed before), show a centered "waiting for
                // them to join" empty state over the still-empty list. It
                // fades out the instant secretChatUpdates flips to Ready.
                androidx.compose.animation.AnimatedVisibility(
                    visible = secretPending,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.secret_waiting_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.secret_waiting_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                // above the scroll-to-bottom FAB. Each chip is shown
                // independently when its respective count > 0, mirrors
                // Telegram's @N / ♥N affordances. Tapping jumps to the
                // first unread occurrence (TDLib's
                // SearchMessagesFilterUnreadMention / UnreadReaction)
                // and marks all read so the chip auto-dismisses.
                //
                // Reads live from chatUpdates so the chips appear/hide
                // in real-time as reactions/mentions land or get read
                // from another device. We keep the chip's enter/exit
                // separate from the FAB's because the user can have a
                // pending reaction while sitting at the bottom of the
                // chat (no FAB) — we still want the chip visible.
                val liveMentions by remember(chatId) {
                    kotlinx.coroutines.flow.flow {
                        emit(TdClient.getCachedChat(chatId)?.unreadMentionCount ?: 0)
                        TdClient.chatUpdates.collect { cid ->
                            if (cid == chatId) {
                                emit(TdClient.getCachedChat(chatId)?.unreadMentionCount ?: 0)
                            }
                        }
                    }
                }.collectAsState(initial = TdClient.getCachedChat(chatId)?.unreadMentionCount ?: 0)
                val liveReactions by remember(chatId) {
                    kotlinx.coroutines.flow.flow {
                        emit(TdClient.getCachedChat(chatId)?.unreadReactionCount ?: 0)
                        TdClient.chatUpdates.collect { cid ->
                            if (cid == chatId) {
                                emit(TdClient.getCachedChat(chatId)?.unreadReactionCount ?: 0)
                            }
                        }
                    }
                }.collectAsState(initial = TdClient.getCachedChat(chatId)?.unreadReactionCount ?: 0)
                val hasMentions = liveMentions > 0
                val hasReactions = liveReactions > 0
                androidx.compose.animation.AnimatedVisibility(
                    visible = hasMentions || hasReactions,
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(280)
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.6f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.55f,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        )
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ) + androidx.compose.animation.scaleOut(
                        targetScale = 0.6f,
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 80dp lifts the chip above the FAB (which is 40dp
                        // SmallFAB + 14dp bottom padding + a 26dp gap).
                        .padding(end = 14.dp, bottom = 80.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasMentions) {
                            androidx.compose.material3.SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        val msg = runCatching {
                                            TdClient.findFirstUnreadMention(chatId)
                                        }.getOrNull()
                                        // Hide the chip the instant it's tapped, exactly
                                        // like the reaction chip does. Tapping reads ALL
                                        // mentions, so we optimistically ZERO the cached
                                        // count instead of waiting on the
                                        // UpdateChatUnreadMentionCount echo — that echo
                                        // lags and is sometimes never delivered, which is
                                        // why the "@" badge "non sparisce" on tap. The
                                        // server-side readAllChatMentions still runs and
                                        // its echo confirms 0.
                                        TdClient.clearChatMentionCountLocal(chatId)
                                        if (msg != null) jumpToMessage(msg.id)
                                        runCatching { TdClient.readAllChatMentions(chatId) }
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                elevation = androidx.compose.material3.FloatingActionButtonDefaults
                                    .elevation(defaultElevation = 3.dp)
                            ) {
                                Text(
                                    "@",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (hasReactions) {
                            androidx.compose.material3.SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        val msg = runCatching {
                                            TdClient.findFirstUnreadReaction(chatId)
                                        }.getOrNull()
                                        if (msg != null) {
                                            TdClient.decrementChatReactionCount(chatId)
                                            jumpToMessage(msg.id)
                                            runCatching {
                                                TdClient.viewMessages(chatId, longArrayOf(msg.id))
                                            }
                                        }
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                elevation = androidx.compose.material3.FloatingActionButtonDefaults
                                    .elevation(defaultElevation = 3.dp)
                            ) {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.Smiley,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
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
                    enter = androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(280)
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.6f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.55f,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        )
                    ),
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ) + androidx.compose.animation.scaleOut(
                        targetScale = 0.6f,
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                ) {
                    // When there's an unread backlog the FAB lights up in
                    // accent so it reads as a CTA — "tap me to clear the
                    // unread". Otherwise it stays as a neutral surface
                    // button. A small numeric badge in the top-right
                    // corner shows the count, capped visually at 999+.
                    //
                    // We derive `unread` from the LIVE chat unreadCount
                    // (re-read on every TdClient.chatUpdates emission for
                    // this chatId) rather than the static
                    // `chatUnreadOnOpen` snapshot. As the per-visible-item
                    // viewMessages flow marks messages read progressively
                    // while the user scrolls, TDLib pushes
                    // UpdateChatReadInbox → chatCache.unreadCount drops →
                    // this state updates → the FAB un-colors and the
                    // badge hides the moment the backlog clears, without
                    // forcing the user to actually reach index 0.
                    val liveUnread by remember(chatId) {
                        kotlinx.coroutines.flow.flow {
                            emit(TdClient.getCachedChat(chatId)?.unreadCount ?: 0)
                            TdClient.chatUpdates.collect { cid ->
                                if (cid == chatId) {
                                    emit(TdClient.getCachedChat(chatId)?.unreadCount ?: 0)
                                }
                            }
                        }
                    }.collectAsState(initial = TdClient.getCachedChat(chatId)?.unreadCount ?: 0)
                    val unread = liveUnread
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
                                    // If we're parked in an OLD jumped window
                                    // (atLatestWindow=false) the newest messages
                                    // aren't loaded — reload the live tail FIRST
                                    // so the list is contiguous. Otherwise
                                    // scrolling up from here would re-traverse
                                    // the old window (the burned-middle gap).
                                    if (!atLatestWindow) {
                                        runCatching {
                                            val res = TdClient.getChatHistory(chatId, 0L, 50)
                                            val fresh = res.messages.toList()
                                                .sortedByDescending { it.id }
                                            if (fresh.isNotEmpty()) {
                                                messages.clear()
                                                messages.addAll(fresh)
                                                noMore = false
                                                atLatestWindow = true
                                            }
                                        }
                                        runCatching { listState.scrollToItem(0) }
                                    } else {
                                        runCatching { listState.animateScrollToItem(0) }
                                    }
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
                // Brief centered spinner while a far jump pulls history, so
                // the round-trip + teleport doesn't read as a frozen tap.
                if (jumpLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
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

            // Full command list, opened by the "/" list button in the input
            // bar. Shown only on an empty field — the moment the user types a
            // "/" the live-filtered picker below takes over instead.
            if (showAllCommands && input.text.isBlank() && botCommands.isNotEmpty()) {
                BotCommandPicker(
                    commands = botCommands,
                    onPick = { cmd ->
                        // In a group a bare "/cmd" reaches no bot — it must be
                        // "/cmd@botusername". The username is resolved when the
                        // commands load, but re-resolve here if it's still
                        // missing so a transient miss doesn't send a dead bare
                        // command. 1-to-1 bot chats use the plain form.
                        input = androidx.compose.ui.text.input.TextFieldValue("")
                        val replyId = replyTarget?.id
                        replyTarget = null
                        showAllCommands = false
                        scope.launch {
                            var uname = cmd.botUsername
                            if (isGroupChat && uname.isNullOrBlank()) {
                                uname = runCatching {
                                    TdClient.getUser(cmd.botUserId).usernames
                                        ?.let { it.activeUsernames.firstOrNull() ?: it.editableUsername }
                                }.getOrNull()?.takeIf { it.isNotBlank() }
                            }
                            val cmdText =
                                if (isGroupChat && !uname.isNullOrBlank()) "/${cmd.command}@$uname"
                                else "/${cmd.command}"
                            runCatching { TdClient.sendBotCommand(chatId, cmdText, replyId) }
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
                            // Fire on tap (matches Telegram: picking from the
                            // list IS sending). In a group address it as
                            // "/cmd@botusername" so the owning bot receives it;
                            // re-resolve the username here if it wasn't captured
                            // at load time.
                            input = androidx.compose.ui.text.input.TextFieldValue("")
                            val replyId = replyTarget?.id
                            replyTarget = null
                            scope.launch {
                                var uname = cmd.botUsername
                                if (isGroupChat && uname.isNullOrBlank()) {
                                    uname = runCatching {
                                        TdClient.getUser(cmd.botUserId).usernames
                                            ?.let { it.activeUsernames.firstOrNull() ?: it.editableUsername }
                                    }.getOrNull()?.takeIf { it.isNotBlank() }
                                }
                                val cmdText =
                                    if (isGroupChat && !uname.isNullOrBlank()) "/${cmd.command}@$uname"
                                    else "/${cmd.command}"
                                runCatching { TdClient.sendBotCommand(chatId, cmdText, replyId) }
                            }
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
            if (pendingMedia.isNotEmpty()) {
                PendingMediaPreview(
                    media = pendingMedia,
                    onCancelAll = { pendingMedia = emptyList() },
                    onRemove = { idx ->
                        pendingMedia = pendingMedia.toMutableList().apply { removeAt(idx) }
                    }
                )
            }
            if (isNonMember) {
                // Not a member yet: the chat is being previewed via a
                // t.me link, a search hit, or a deep link. The official
                // Telegram client hides the input bar in this state and
                // shows a full-width "Unisciti al gruppo" CTA at the
                // bottom — matching that here. Tapping fires JoinChat
                // and the chatUpdates listener above flips isNonMember
                // back to false the instant TDLib echoes the new
                // status, so the InputBar swaps back in without the
                // user having to back out of the chat. The system
                // "X si è unito" message arrives in the chat scroll on
                // the same TDLib echo.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    var joining by remember(chatId) { mutableStateOf(false) }
                    androidx.compose.material3.Button(
                        onClick = {
                            if (joining) return@Button
                            joining = true
                            scope.launch {
                                runCatching { TdClient.joinChat(chatId) }
                                    .onFailure {
                                        joining = false
                                        android.widget.Toast.makeText(
                                            context,
                                            it.message ?: "Impossibile unirsi",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                // On success we leave `joining` true; the
                                // chatUpdates collector flips isNonMember
                                // and this whole Box leaves composition
                                // (replaced by the InputBar), so the
                                // spinner state doesn't matter.
                            }
                        },
                        enabled = !joining,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (joining) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            // Channel vs group CTA. Telegram users expect
                            // the right word for the right surface — a
                            // broadcast supergroup with isChannel=true is
                            // "Canale" in the UI everywhere else; the join
                            // button should match.
                            val cachedForJoin = TdClient.getCachedChat(chatId)
                            val isChannelHere = cachedForJoin?.type is TdApi.ChatTypeSupergroup &&
                                (cachedForJoin.type as TdApi.ChatTypeSupergroup).isChannel
                            Text(
                                stringResource(
                                    if (isChannelHere) R.string.action_join_channel
                                    else R.string.action_join_group
                                ),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
            // Stack the typing badge above the input bar. We wrap the
            // two in a Column so the indicator animates the bar
            // upward when it appears (instead of overlaying). For
            // private chats we hide avatars: the chat header already
            // shows the peer large at the top, so re-rendering the
            // same small avatar above the input is redundant chrome.
            val isPrivateOrSecret = remember(chatId) {
                val t = TdClient.getCachedChat(chatId)?.type
                t is TdApi.ChatTypePrivate || t is TdApi.ChatTypeSecret
            }
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (secretPending || secretClosed) {
                    SecretChatLockedBar(closed = secretClosed)
                } else {
                com.secondream.novagram.ui.components.TypingIndicator(
                    chatId = chatId,
                    showAvatars = !isPrivateOrSecret
                )
            InputBar(
                value = input,
                onValueChange = { input = it },
                autoCapitalize = appearance.autoCapitalize,
                placeholderText = if (pendingMedia.isNotEmpty())
                    stringResource(R.string.media_caption_hint)
                else null,
                onSend = {
                    val text = input.text.trim()
                    val media = pendingMedia
                    val rid = replyTarget?.id
                    val editing = editTarget
                    // Crisp tick on an actual new outgoing message (not edits).
                    // Gated by the Settings vibration switch via the helper.
                    if (editing == null && (text.isNotEmpty() || media.isNotEmpty())) {
                        haptics.tick()
                    }
                    // Send blip ("toc") — only for an actual new outgoing
                    // message (text and/or media), never for edits. Toggleable
                    // via the messageSounds pref.
                    if (editing == null && (text.isNotEmpty() || media.isNotEmpty()) &&
                        appearance.messageSounds
                    ) {
                        com.secondream.novagram.util.SoundFx.playSend(context)
                    }
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
                                        // Stamp editDate immediately so the
                                        // "modificato" tag renders in the same
                                        // frame as the new content. TDLib will
                                        // echo the authoritative editDate via
                                        // UpdateMessageEdited a few hundred
                                        // ms later; the collector above will
                                        // overwrite this value with the
                                        // server's, which is fine because
                                        // (a) the tag is binary > 0, not
                                        // shown timestamp-sensitive, and
                                        // (b) any small clock skew between
                                        // device and TDLib doesn't affect
                                        // rendering. System.currentTimeMillis
                                        // / 1000 gives epoch seconds matching
                                        // TDLib's editDate scale.
                                        messages[idx].editDate =
                                            (System.currentTimeMillis() / 1000).toInt()
                                        run { val m = messages[idx]; messages.removeAt(idx); messages.add(idx, m) }
                                    }
                                    messageContentOverrides[captured.id] = newContent
                                    interactionRevisions[captured.id] =
                                        (interactionRevisions[captured.id] ?: 0) + 1
                                }
                                // For caption edits we DON'T do a local
                                // optimistic content swap — the media
                                // content types (MessagePhoto / Video /
                                // Document / Animation / Audio / VoiceNote)
                                // each have different constructor arities
                                // that drift between TDLib releases, and
                                // mis-cloning one of them would crash the
                                // bubble. Instead, the safety-net inside
                                // the UpdateMessageEdited handler (below)
                                // refreshes the message via TdClient.getMessage
                                // when the content update doesn't arrive
                                // in lockstep — covering the gap that was
                                // leaving the visible caption stale until
                                // the user re-opened the chat.
                                TdClient.fireAndForget {
                                    if (isTextMsg) {
                                        TdClient.editMessageText(chatId, captured.id, text)
                                    } else {
                                        TdClient.editMessageCaption(chatId, captured.id, text)
                                    }
                                }
                            }
                        }
                        media.isNotEmpty() -> {
                            // Sending media with optional caption — caption may
                            // be blank, that's fine. Always clear local state
                            // before launching so a double-tap doesn't double-send.
                            input = androidx.compose.ui.text.input.TextFieldValue("")
                            pendingMedia = emptyList()
                            replyTarget = null
                            val caption = text.ifBlank { null }
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    when {
                                        media.size == 1 -> {
                                            // Single item: keep using the
                                            // dedicated single-message wrappers
                                            // — they're cheaper (no album round-
                                            // trip) and render exactly the same
                                            // on the receiver side.
                                            val it = media.first()
                                            when (it.kind) {
                                                PendingMediaKind.Photo ->
                                                    TdClient.sendPhoto(chatId, it.file.absolutePath, caption, rid)
                                                PendingMediaKind.Video ->
                                                    TdClient.sendVideo(chatId, it.file.absolutePath, caption, rid)
                                                PendingMediaKind.Document ->
                                                    TdClient.sendDocument(chatId, it.file.absolutePath, caption, rid)
                                            }
                                        }
                                        else -> {
                                            // Album path. TDLib's SendMessageAlbum
                                            // accepts up to 10 items; we capped
                                            // the picker accordingly so we never
                                            // overflow. Photos and videos can
                                            // share an album; documents must be
                                            // their own album. We DON'T split
                                            // here — the user picked a mixed set
                                            // explicitly, and if it includes a
                                            // doc TDLib will return an error
                                            // which surfaces via the runCatching
                                            // wrapper. In practice the photo
                                            // picker only returns photo+video
                                            // and the doc picker only docs, so
                                            // a mixed bag isn't reachable
                                            // through the normal flow.
                                            val groupItems = media.map {
                                                com.secondream.novagram.td.TdClient.MediaGroupItem(
                                                    filePath = it.file.absolutePath,
                                                    kind = when (it.kind) {
                                                        PendingMediaKind.Photo ->
                                                            com.secondream.novagram.td.TdClient.MediaGroupItemKind.Photo
                                                        PendingMediaKind.Video ->
                                                            com.secondream.novagram.td.TdClient.MediaGroupItemKind.Video
                                                        PendingMediaKind.Document ->
                                                            com.secondream.novagram.td.TdClient.MediaGroupItemKind.Document
                                                    }
                                                )
                                            }
                                            TdClient.sendMediaGroup(chatId, groupItems, caption, rid)
                                        }
                                    }
                                }
                            }
                        }
                        text.isNotEmpty() -> {
                            input = androidx.compose.ui.text.input.TextFieldValue("")
                            replyTarget = null
                            scope.launch {
                                runCatching {
                                    TdClient.sendText(chatId, text, rid)
                                }.onFailure { err ->
                                    // Failures here are silent by default —
                                    // TDLib rejects sends for secret chats
                                    // whose handshake hasn't completed, for
                                    // groups where the user is restricted,
                                    // and for channels we don't admin.
                                    // Surfacing the error tells the user
                                    // what's going on instead of leaving
                                    // them tapping a button that does
                                    // nothing.
                                    android.widget.Toast.makeText(
                                        context,
                                        err.message ?: "Invio non riuscito",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
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
                            // Short haptic tick when the mic long-press registers,
                            // so the user knows it landed without glancing back.
                            // Routed through the gated helper so the Settings
                            // vibration switch governs it.
                            haptics.tick(30L)
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
                                if (appearance.messageSounds) {
                                    com.secondream.novagram.util.SoundFx.playSend(context)
                                }
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
                recordingLocked = recordingLocked,
                onMicLock = { recordingLocked = true },
                onSendVoice = {
                    recording = false
                    recordingLocked = false
                    val res = recorder.stop()
                    if (res != null) {
                        if (appearance.messageSounds) {
                            com.secondream.novagram.util.SoundFx.playSend(context)
                        }
                        val rid = replyTarget?.id
                        replyTarget = null
                        scope.launch {
                            runCatching {
                                TdClient.sendVoiceNote(chatId, res.file.absolutePath, res.durationSeconds, rid)
                            }
                        }
                    }
                },
                onCancelVoice = {
                    recording = false
                    recordingLocked = false
                    recorder.cancel()
                },
                hasPendingMedia = pendingMedia.isNotEmpty() || editTarget != null,
                onContentReceived = { uri ->
                    // Keyboard inserted a GIF or sticker. Mime-sniff via the
                    // ContentResolver, copy off the content:// URI into our
                    // cache (since the keyboard's permission may expire) and
                    // send: animation path for GIF / mp4 so it auto-plays in
                    // the chat, photo path for static images.
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val mime = (context.contentResolver.getType(uri) ?: "").lowercase()
                        val file = com.secondream.novagram.util.FileUtils.copyUriToCache(context, uri) ?: return@launch
                        if (appearance.messageSounds) {
                            com.secondream.novagram.util.SoundFx.playSend(context)
                        }
                        runCatching {
                            if (mime == "image/gif" || mime == "video/mp4") {
                                TdClient.sendAnimation(chatId, file.absolutePath)
                            } else {
                                TdClient.sendPhoto(chatId, file.absolutePath)
                            }
                        }
                    }
                },
                showCommandsButton = botCommands.isNotEmpty() && appearance.showBotCommandsButton,
                onCommandsClick = { showAllCommands = !showAllCommands },
                focusRequester = inputFocus
            )
            }
            }
            }
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
            },
            onCreatePoll = if (isGroupChat) {
                {
                    showAttach = false
                    showPollComposer = true
                }
            } else null
        )
    }

    if (showPollComposer) {
        PollComposerSheet(
            onDismiss = { showPollComposer = false },
            onSend = { question, options, anonymous, multiple ->
                val rid = replyTarget?.id
                showPollComposer = false
                replyTarget = null
                if (appearance.messageSounds) {
                    com.secondream.novagram.util.SoundFx.playSend(context)
                }
                scope.launch {
                    runCatching {
                        TdClient.sendPoll(chatId, question, options, anonymous, multiple, rid)
                    }
                }
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
                if (appearance.messageSounds) {
                    com.secondream.novagram.util.SoundFx.playSend(context)
                }
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
                TdClient.fireAndForget {
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
            },
            onOpenMediaViewer = {
                // Open the hoisted viewer window stacked over the sheet; the
                // sheet stays underneath and is revealed again on close.
                viewerOpen = true
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
                // Fire the jump FIRST while we're still on the ChatScreen
                // composition scope — jumpToMessage launches in that
                // scope's coroutine context, so any subsequent dismiss
                // animation tearing down the sheet doesn't cancel the
                // pending history-paginate + scroll work. The dismiss
                // then runs as a separate post-action, after which the
                // user sees the chat scrolled to the target with the
                // flash highlight playing. Previously the order was
                // reversed and the sheet's dismissal could race the
                // launched coroutine, leaving the user back in the
                // chat at the original position with no jump.
                jumpToMessage(targetId)
                pinnedSheetOpen = false
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
        com.secondream.novagram.ui.components.AiAssistantModal(
            mode = com.secondream.novagram.ui.components.AiContext.MESSAGE,
            contextLabel = chatTitle,
            chatId = chatId,
            focusText = text,
            focusSender = senderName,
            focusMessageId = target.id,
            onReplyDraft = { aiReply ->
                input = androidx.compose.ui.text.input.TextFieldValue(
                    aiReply,
                    androidx.compose.ui.text.TextRange(aiReply.length)
                )
                replyTarget = target
            },
            onOpenTme = { url ->
                aiTarget = null
                openTelegramLink(android.net.Uri.parse(url))
            },
            onJumpMessage = { mid ->
                aiTarget = null
                jumpToMessage(mid)
            },
            onDismiss = { aiTarget = null }
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
        // Extract the relevant file id for THIS message. TDLib does NOT
        // mutate the TdApi.File instances embedded in MessageContent in
        // place when a download completes — it emits UpdateFile with a
        // FRESH instance. The instance held by msg.content can therefore
        // be stuck at local.path="" / isDownloadingCompleted=false even
        // though the file is fully on disk. That's why the previous
        // implementation surfaced Save only for photos/videos most of
        // the time and ALMOST NEVER for documents — documents are
        // typically downloaded on demand AFTER the message arrives, so
        // their content reference is the stale pre-download one.
        //
        // Fix: subscribe to the live file state via produceState. On
        // sheet open we fetch the latest via TdClient.getFile(fileId);
        // for as long as the sheet stays composed we also listen to
        // TdClient.fileUpdates so the Save tile appears the moment a
        // download completes WHILE the long-press menu is visible
        // (rare but happens — user long-presses a still-downloading
        // file, holds the sheet open, download finishes).
        val mediaFileId: Int? = when (val c = msg.content) {
            is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.photo.size }?.photo?.id
            is TdApi.MessageVideo -> c.video.video.id
            is TdApi.MessageAnimation -> c.animation.animation.id
            is TdApi.MessageDocument -> c.document.document.id
            is TdApi.MessageAudio -> c.audio.audio.id
            else -> null
        }
        val liveFile by produceState<TdApi.File?>(
            initialValue = null,
            key1 = mediaFileId,
            key2 = msg.id
        ) {
            val fid = mediaFileId ?: return@produceState
            // Prime with the freshest known state so saveSpec is
            // correct on first frame after long-press.
            value = runCatching { TdClient.getFile(fid) }.getOrNull()
            TdClient.fileUpdates.collect { f ->
                if (f.id == fid) value = f
            }
        }
        val saveSpec: SaveSpec? = run {
            // A file is "available to save" if its local path is set
            // AND the file actually exists on disk with non-zero size.
            // We deliberately do NOT rely solely on
            // local.isDownloadingCompleted: TDLib's flag and the
            // filesystem can disagree across sessions (file removed by
            // OS cache cleanup; previously-downloaded file that TDLib
            // forgot during a restart). Filesystem-truth wins.
            fun TdApi.File.availableLocally(): Boolean {
                val p = local?.path
                if (p.isNullOrBlank()) return false
                return runCatching {
                    val f = java.io.File(p)
                    if (!f.exists()) return@runCatching false
                    // Must be the COMPLETE file, not a partial download —
                    // otherwise "Salva" copied the half-fetched bytes (e.g.
                    // 100 MB of a 1 GB file). When TDLib knows the expected
                    // size, require the on-disk length to cover it; only when
                    // the size is genuinely unknown do we defer to TDLib's
                    // completed flag.
                    val expected = if (size > 0L) size else expectedSize
                    if (expected > 0L) f.length() >= expected
                    else (local?.isDownloadingCompleted == true && f.length() > 0L)
                }.getOrDefault(false)
            }
            // Prefer the live file (refreshed via getFile + fileUpdates)
            // when its id matches the one we care about. Otherwise fall
            // back to whatever's embedded in msg.content — covers the
            // first-frame case (liveFile is still null) and the "no
            // download was ever needed" case (own outgoing media).
            fun TdApi.File?.orFromContent(content: TdApi.File): TdApi.File =
                this ?: content
            when (val c = msg.content) {
                is TdApi.MessagePhoto -> {
                    val biggest = c.photo.sizes.maxByOrNull { it.photo.size } ?: return@run null
                    val live = liveFile.orFromContent(biggest.photo)
                    val p = live.local?.path
                    if (!p.isNullOrBlank() && live.availableLocally())
                        SaveSpec(p, "photo_${msg.id}.jpg", "image/jpeg",
                            com.secondream.novagram.util.FileUtils.SaveCategory.Media)
                    else null
                }
                is TdApi.MessageVideo -> {
                    val live = liveFile.orFromContent(c.video.video)
                    val p = live.local?.path
                    if (!p.isNullOrBlank() && live.availableLocally())
                        SaveSpec(p, c.video.fileName.ifBlank { "video_${msg.id}.mp4" },
                            c.video.mimeType.ifBlank { "video/mp4" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.Media)
                    else null
                }
                is TdApi.MessageAnimation -> {
                    val live = liveFile.orFromContent(c.animation.animation)
                    val p = live.local?.path
                    if (!p.isNullOrBlank() && live.availableLocally())
                        SaveSpec(p, c.animation.fileName.ifBlank { "anim_${msg.id}.mp4" },
                            c.animation.mimeType.ifBlank { "video/mp4" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.Media)
                    else null
                }
                is TdApi.MessageDocument -> {
                    val live = liveFile.orFromContent(c.document.document)
                    val p = live.local?.path
                    if (!p.isNullOrBlank() && live.availableLocally())
                        SaveSpec(p, c.document.fileName.ifBlank { "file_${msg.id}" },
                            c.document.mimeType.ifBlank { "application/octet-stream" },
                            com.secondream.novagram.util.FileUtils.SaveCategory.File)
                    else null
                }
                is TdApi.MessageAudio -> {
                    val live = liveFile.orFromContent(c.audio.audio)
                    val p = live.local?.path
                    if (!p.isNullOrBlank() && live.availableLocally())
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
            // Message links exist only for supergroups/channels (ChatTypeSupergroup
            // covers both — a channel is a supergroup with isChannel=true). TDLib
            // builds the correct public/private t.me link; we just copy it.
            onCopyLink = if (TdClient.getCachedChat(chatId)?.type is TdApi.ChatTypeSupergroup) {
                {
                    scope.launch {
                        val link = TdClient.getMessageLink(chatId, msg.id)
                        if (!link.isNullOrBlank()) {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(link))
                            com.secondream.novagram.ui.components.NovaSnackbar.show(
                                R.string.snack_link_copied,
                                com.secondream.novagram.ui.icons.PhosphorIcons.At
                            )
                        }
                    }
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
            onAi = if (!appearance.anthropicApiKey.isNullOrBlank() && appearance.aiMessageActionsEnabled) {
                {
                    aiTarget = msg
                    deleteTarget = null
                }
            } else null,
            onTogglePin = if (canPinHere) {
                {
                    val wasPinned = msg.id == pinnedMessageId
                    when {
                        wasPinned -> {
                            // Unpin: no prompt, just do it.
                            TdClient.fireAndForget {
                                runCatching { TdClient.unpinChatMessage(chatId, msg.id) }
                                    .onSuccess {
                                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                                            R.string.snack_message_unpinned,
                                            com.secondream.novagram.ui.icons.PhosphorIcons.PushPin
                                        )
                                    }
                            }
                            pinnedMessageId = 0L
                            pinned = null
                        }
                        isGroupChat -> {
                            // Group: ask whether to notify all members first.
                            pinNotifyTarget = msg
                        }
                        else -> {
                            // Private chat: pin straight away (notifies the peer).
                            TdClient.fireAndForget {
                                runCatching { TdClient.pinChatMessage(chatId, msg.id) }
                                    .onSuccess {
                                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                                            R.string.snack_message_pinned,
                                            com.secondream.novagram.ui.icons.PhosphorIcons.PushPin
                                        )
                                    }
                            }
                            pinnedMessageId = msg.id
                            pinned = msg
                        }
                    }
                    deleteTarget = null
                }
            } else null,
            isPinned = msg.id == pinnedMessageId,
            onReact = { emoji ->
                val chosenSame = msg.interactionInfo?.reactions?.reactions?.any {
                    it.isChosen && (it.type as? TdApi.ReactionTypeEmoji)?.emoji == emoji
                } == true
                // Reaction haptic: a satisfying double-tap when adding, a light
                // tick when removing. Gated by the Settings vibration switch.
                if (chosenSame) haptics.light() else haptics.confirm()
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
                // Durable: runs on TdClient's app scope, so leaving the chat
                // right after tapping no longer cancels the network call (the
                // reaction used to vanish on re-entry for exactly that reason).
                TdClient.toggleEmojiReactionDurably(chatId, msg.id, emoji, add = !chosenSame)
                deleteTarget = null
            },
            onDeleteForMe = {
                TdClient.fireAndForget {
                    TdClient.deleteMessages(chatId, longArrayOf(msg.id), revoke = false)
                }
                deleteTarget = null
            },
            onDeleteForEveryone = {
                TdClient.fireAndForget {
                    TdClient.deleteMessages(chatId, longArrayOf(msg.id), revoke = true)
                }
                deleteTarget = null
            },
            onMuteAuthor = { mute ->
                if (senderUserId != null) {
                    val label = context.getString(
                        if (mute) R.string.action_in_progress_muting
                        else R.string.action_in_progress_unmuting
                    )
                    scope.launch {
                        pendingActionLabel = label
                        try {
                            runCatching {
                                if (mute) TdClient.muteGroupUser(chatId, senderUserId)
                                else TdClient.unmuteGroupUser(chatId, senderUserId)
                            }
                        } finally {
                            pendingActionLabel = null
                        }
                    }
                }
                deleteTarget = null
            },
            onKickAuthor = { kick ->
                if (senderUserId != null) {
                    if (kick) {
                        // Ban → open the pre-ban confirm (keep/delete) first;
                        // the dialog performs the actual ban on confirm.
                        deleteTarget = null
                        banDeleteAllUid = senderUserId
                    } else {
                        // Unban is immediate, no confirmation needed.
                        val label = context.getString(R.string.action_in_progress_unbanning)
                        scope.launch {
                            pendingActionLabel = label
                            try {
                                runCatching { TdClient.unbanGroupUser(chatId, senderUserId) }
                            } finally {
                                pendingActionLabel = null
                            }
                        }
                        deleteTarget = null
                    }
                } else {
                    deleteTarget = null
                }
            }
        )
    }

    // Group pin confirmation with the "notify all members" toggle.
    pinNotifyTarget?.let { msg ->
        PinNotifyDialog(
            onPin = { notify ->
                scope.launch {
                    runCatching {
                        TdClient.pinChatMessage(
                            chatId, msg.id, disableNotification = !notify
                        )
                    }.onSuccess {
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.snack_message_pinned,
                            com.secondream.novagram.ui.icons.PhosphorIcons.PushPin
                        )
                    }
                }
                pinnedMessageId = msg.id
                pinned = msg
                pinNotifyTarget = null
            },
            onDismiss = { pinNotifyTarget = null }
        )
    }

    // Pre-ban confirmation (keep / delete-all) for banning from a message's
    // admin menu. The dialog performs the ban itself on confirm.
    banDeleteAllUid?.let { uid ->
        BanConfirmDialog(
            chatId = chatId,
            userId = uid,
            memberName = null,
            scope = scope,
            onDone = { },
            onDismiss = { banDeleteAllUid = null }
        )
    }

    // TTL chooser dialog. Five presets matching Telegram's stock UI:
    // off, 1 minute, 1 hour, 1 day, 1 week. Tap dispatches
    // SetChatMessageAutoDeleteTime and dismisses; works for any chat
    // type (the call is a no-op on chats where TDLib doesn't allow it,
    // wrapped in runCatching).
    // Telegram-link resolution overlay. While we're hitting TDLib to
    // look up a username, invite hash, or chat id behind a t.me URL,
    // we draw a translucent black scrim over the chat with a spinner
    // in the middle. Eats touches so the user can't accidentally fire
    // more taps that queue up additional resolutions. The first resolve
    // for any given username is the slow one (TDLib has to fetch the
    // chat record from the network); subsequent taps on the same link
    // are near-instant because TDLib caches the lookup.
    if (linkResolving || pendingActionLabel != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
                val caption = pendingActionLabel
                if (caption != null) {
                    Text(
                        text = caption,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    // Floating banner showing the bot's response to a callback button
    // tap (e.g., "Sei stato sbloccato"). Auto-dismisses after ~2.4s
    // (normal) or ~4.5s (alert). Sits above the input bar with a
    // soft surface background and the accent on the icon.
    inlineButtonResult?.let { result ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 96.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (result.isAlert) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (result.isAlert)
                        com.secondream.novagram.ui.icons.PhosphorIcons.X
                    else com.secondream.novagram.ui.icons.PhosphorIcons.Check,
                    contentDescription = null,
                    tint = if (result.isAlert) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    result.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (ttlDialogOpen) {
        val ttlOptions = listOf(
            0 to stringResource(R.string.ttl_off),
            60 to stringResource(R.string.ttl_1m),
            3600 to stringResource(R.string.ttl_1h),
            86400 to stringResource(R.string.ttl_1d),
            604800 to stringResource(R.string.ttl_1w)
        )
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = stringResource(R.string.chat_set_ttl),
            onDismiss = { ttlDialogOpen = false },
            tiles = ttlOptions.map { (seconds, label) ->
                com.secondream.novagram.ui.components.ActionTile(
                    label = label,
                    icon = if (seconds == 0)
                        com.secondream.novagram.ui.icons.PhosphorIcons.X
                    else com.secondream.novagram.ui.icons.PhosphorIcons.Bell,
                    onClick = {
                        ttlDialogOpen = false
                        scope.launch {
                            runCatching {
                                TdClient.setMessageAutoDeleteTime(chatId, seconds)
                            }
                        }
                    }
                )
            },
            tilesPerRow = 3
        )
    }

    if (needMicPermission) {
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = stringResource(R.string.mic_permission_title),
            description = stringResource(R.string.mic_permission_body),
            onDismiss = { needMicPermission = false },
            tiles = listOf(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.action_ok),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Check,
                    onClick = { needMicPermission = false }
                )
            ),
            tilesPerRow = 1
        )
    }

    if (infoOpen) {
        ChatInfoDialog(
            chatId = chatId,
            initialPage = infoInitialPage,
            onPageChanged = { infoInitialPage = it },
            onDismiss = { infoOpen = false },
            onJumpToMessage = { mid ->
                infoOpen = false
                jumpToMessage(mid)
            },
            onOpenMediaViewer = {
                // Open the hoisted viewer window stacked OVER this dialog — the
                // dialog stays composed underneath, so closing the viewer just
                // reveals it again. No nav round-trip, no reopen race.
                viewerOpen = true
            }
        )
    }

    // In-app photo/video viewer for media opened from the info dialog and the
    // profile sheet. Hosted as its OWN full-screen Dialog window so it stacks
    // on top of those surfaces (themselves Dialog / ModalBottomSheet windows)
    // rather than replacing the chat via the nav route. The originating
    // surface stays composed underneath, so dismissing this window reveals it
    // again instantly and reliably — no popBackStack, no lifecycle-timed
    // reopen, none of the "torna in chat" flakiness. Chat-bubble media keeps
    // the slide-up nav route (MEDIA_VIEWER) which works as is.
    if (viewerOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewerOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false
            )
        ) {
            com.secondream.novagram.ui.screens.MediaViewerScreen(
                filePath = com.secondream.novagram.ui.screens.MediaViewerHolder.currentPath ?: "",
                onClose = { viewerOpen = false }
            )
        }
    }

    if (deleteOpen) {
        val isPrivate = cachedChatLive?.type is TdApi.ChatTypePrivate
        // Private chats get TWO destructive tiles (delete locally vs
        // delete for both sides), non-private gets ONE (no "delete for
        // everyone" semantic in groups/channels). Plus a non-destructive
        // Cancel tile so the user always has an explicit escape — the
        // bottom-sheet swipe-down also dismisses, but a labeled Cancel
        // reads clearer.
        val deleteTiles = buildList {
            add(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.action_delete_chat),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                    destructive = true,
                    onClick = {
                        deleteOpen = false
                        TdClient.fireAndForget {
                            TdClient.deleteChatFully(TdClient.getCachedChat(chatId), revoke = false)
                        }
                        onBack()
                    }
                )
            )
            if (isPrivate) {
                add(
                    com.secondream.novagram.ui.components.ActionTile(
                        label = stringResource(R.string.delete_chat_for_everyone),
                        icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                        destructive = true,
                        onClick = {
                            deleteOpen = false
                            TdClient.fireAndForget {
                                TdClient.deleteChatFully(TdClient.getCachedChat(chatId), revoke = true)
                            }
                            onBack()
                        }
                    )
                )
            }
            add(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.delete_chat_cancel),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    onClick = { deleteOpen = false }
                )
            )
        }
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = stringResource(R.string.delete_chat_confirm_title),
            description = stringResource(R.string.delete_chat_confirm_body),
            onDismiss = { deleteOpen = false },
            tiles = deleteTiles,
            tilesPerRow = if (isPrivate) 3 else 2
        )
    }
    if (leaveOpen) {
        val cachedNow = TdClient.getCachedChat(chatId)
        val isChan = cachedNow?.type is TdApi.ChatTypeSupergroup &&
            (cachedNow.type as TdApi.ChatTypeSupergroup).isChannel
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = stringResource(R.string.leave_group_confirm, chatTitle),
            onDismiss = { leaveOpen = false },
            tiles = listOf(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(
                        if (isChan) R.string.action_leave_channel
                        else R.string.action_leave_group
                    ),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                    destructive = true,
                    onClick = {
                        leaveOpen = false
                        TdClient.fireAndForget { TdClient.leaveChat(chatId) }
                        onBack()
                    }
                ),
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.delete_chat_cancel),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    onClick = { leaveOpen = false }
                )
            )
        )
    }
    } // close back-swipe Box

    if (aiModalOpen) {
        com.secondream.novagram.ui.components.AiAssistantModal(
            mode = com.secondream.novagram.ui.components.AiContext.CHAT,
            contextLabel = chatTitle,
            chatId = chatId,
            onReplyDraft = { aiReply ->
                input = androidx.compose.ui.text.input.TextFieldValue(
                    aiReply,
                    androidx.compose.ui.text.TextRange(aiReply.length)
                )
            },
            onOpenTme = { url ->
                aiModalOpen = false
                openTelegramLink(android.net.Uri.parse(url))
            },
            onJumpMessage = { mid ->
                aiModalOpen = false
                jumpToMessage(mid)
            },
            onDismiss = { aiModalOpen = false }
        )
    }
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
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
internal fun ChatInfoDialog(
    chatId: Long,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    onDismiss: () -> Unit,
    onJumpToMessage: (Long) -> Unit,
    onOpenMediaViewer: () -> Unit = {}
) {
    val chat = remember(chatId) { TdClient.getCachedChat(chatId) }
    val title = chat?.title ?: stringResource(R.string.chat_default_title)
    val infoScope = androidx.compose.runtime.rememberCoroutineScope()

    var subtitle by remember(chatId) { mutableStateOf<String?>(null) }
    var description by remember(chatId) { mutableStateOf<String?>(null) }
    var phone by remember(chatId) { mutableStateOf<String?>(null) }
    var username by remember(chatId) { mutableStateOf<String?>(null) }

    // Group/admin context — drives whether the "Membri" tab appears.
    val chatType = chat?.type
    val supergroupId = (chatType as? TdApi.ChatTypeSupergroup)?.supergroupId
    val basicGroupId = (chatType as? TdApi.ChatTypeBasicGroup)?.basicGroupId
    val isChannel = (chatType as? TdApi.ChatTypeSupergroup)?.isChannel == true
    val isGroup = (supergroupId != null && !isChannel) || basicGroupId != null
    var myUserId by remember(chatId) { mutableStateOf(0L) }
    var isSelfAdmin by remember(chatId) { mutableStateOf(false) }
    var selfIsCreator by remember(chatId) { mutableStateOf(false) }
    var selfCanChangeInfo by remember(chatId) { mutableStateOf(false) }
    // Self-hosted full-screen photo/media viewer. The avatar/shared-media taps
    // used to delegate to the caller's onOpenMediaViewer, which the chat screen
    // wired up but the chat-list home did NOT — so from home the photo tap did
    // nothing. Hosting the viewer inside the dialog makes it work from every
    // entry point with no caller changes.
    var photoViewerOpen by remember(chatId) { mutableStateOf(false) }
    var selfCanRestrict by remember(chatId) { mutableStateOf(false) }

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
            }
            else -> {}
        }
    }

    // Resolve self id + admin status (gates the Membri tab).
    LaunchedEffect(chatId) {
        val me = runCatching { TdClient.getMe().id }.getOrNull() ?: return@LaunchedEffect
        myUserId = me
        val status = TdClient.getChatMemberStatus(chatId, me)
        isSelfAdmin = status is TdApi.ChatMemberStatusCreator ||
            status is TdApi.ChatMemberStatusAdministrator
        selfIsCreator = status is TdApi.ChatMemberStatusCreator
        selfCanChangeInfo = when (status) {
            is TdApi.ChatMemberStatusCreator -> true
            is TdApi.ChatMemberStatusAdministrator -> status.rights.canChangeInfo
            else -> false
        }
        selfCanRestrict = when (status) {
            is TdApi.ChatMemberStatusCreator -> true
            is TdApi.ChatMemberStatusAdministrator -> status.rights.canRestrictMembers
            else -> false
        }
    }

    val showMembers = isGroup && isSelfAdmin
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    // Tab labels were hardcoded Italian — resolve them from resources so they
    // localise. stringResource isn't callable inside remember{}, so resolve
    // up here and key the remember on one of them (they all change together
    // on a locale switch).
    val sInfo = stringResource(R.string.info_tab_info)
    val sPhoto = stringResource(R.string.info_tab_photos)
    val sVideo = stringResource(R.string.info_tab_videos)
    val sFile = stringResource(R.string.info_tab_files)
    val sLink = stringResource(R.string.info_tab_links)
    val sVoice = stringResource(R.string.info_tab_voice)
    val sMusic = stringResource(R.string.info_tab_music)
    val sMembers = stringResource(R.string.info_tab_members)
    val tabs = remember(showMembers, sInfo) {
        buildList {
            add(ChatInfoTab(sInfo, phos.Info))
            add(ChatInfoTab(sPhoto, phos.Image))
            add(ChatInfoTab(sVideo, phos.Play))
            add(ChatInfoTab(sFile, phos.FileText))
            add(ChatInfoTab(sLink, phos.Paperclip))
            add(ChatInfoTab(sVoice, phos.Microphone))
            add(ChatInfoTab(sMusic, phos.FileAudio))
            if (showMembers) add(ChatInfoTab(sMembers, phos.UsersThree))
        }
    }
    val membersIndex = if (showMembers) tabs.lastIndex else -1

    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = initialPage.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
        pageCount = { tabs.size }
    )
    val scope = rememberCoroutineScope()
    // Tab pill row scroll state, kept in sync with the pager so swiping the
    // content to a later tab (Voce / Musica / Membri) animates the pill row
    // along and the active pill never stays stranded off-screen — Eugenio's
    // "quando facciamo swipe fino all'ultima, quella dopo non si vede, deve
    // rientrare con animazione". animateScrollToItem brings it smoothly in.
    val tabsListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Search query for the media tabs (Foto/Video/File/Link/Voce/Musica),
    // shown in a search bar below the pills. Cleared whenever the tab changes
    // so each category starts fresh.
    var mediaQuery by remember(chatId) { mutableStateOf("") }
    LaunchedEffect(pagerState.currentPage) {
        mediaQuery = ""
        onPageChanged(pagerState.currentPage)
        runCatching { tabsListState.animateScrollToItem(pagerState.currentPage) }
    }
    var selectedMediaMessage by remember(chatId) { mutableStateOf<TdApi.Message?>(null) }
    var selectedMember by remember(chatId) { mutableStateOf<TdApi.ChatMember?>(null) }
    // User just banned from the member sheet, awaiting the "delete all their
    // messages?" prompt.
    var memberBanDeleteUid by remember(chatId) { mutableStateOf<Long?>(null) }
    // Bumped after a member action so the Membri list reloads in place (the
    // action already lands server-side; this gives immediate visual feedback).
    var membersRefresh by remember(chatId) { mutableStateOf(0) }
    // Group-photo picker for admins/owners: tapping the header avatar pulls an
    // image, copies it off the content:// URI into cache, and pushes it via
    // setChatPhoto. The editor itself now lives inline in the Info tab.
    val infoCtx = LocalContext.current
    var groupCropUri by remember(chatId) { mutableStateOf<android.net.Uri?>(null) }
    val groupPhotoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) groupCropUri = uri
    }
    groupCropUri?.let { uri ->
        com.secondream.novagram.ui.components.ImageCropDialog(
            imageUri = uri,
            onDismiss = { groupCropUri = null },
            onCropped = { path ->
                groupCropUri = null
                infoScope.launch {
                    runCatching { TdClient.setChatPhoto(chatId, path) }
                    com.secondream.novagram.ui.components.NovaSnackbar.show(
                        R.string.admin_group_photo_updated,
                        com.secondream.novagram.ui.icons.PhosphorIcons.Check
                    )
                }
            }
        )
    }
    val canEditGroup = (isGroup || isChannel) &&
        (selfIsCreator || (isSelfAdmin && selfCanChangeInfo))
    // Member-permission tile only makes sense in groups, and only for someone
    // who can actually restrict members (owner, or admin with that right).
    val editorShowPerms = isGroup && (selfIsCreator || selfCanRestrict)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // On tablets / landscape the full-bleed info sheet looked stretched
        // (Eugenio). The Dialog centres its content, so constraining the
        // Surface to a comfortable reading width turns it into a centred modal
        // card with the scrim around it; phones keep the full-screen sheet.
        val infoWide =
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
        androidx.compose.material3.Surface(
            modifier = if (infoWide)
                Modifier
                    .width(560.dp)
                    .fillMaxHeight(0.94f)
            else Modifier.fillMaxSize(),
            shape = if (infoWide)
                RoundedCornerShape(20.dp)
            else androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
          androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                androidx.compose.material3.TopAppBar(
                    title = {
                        Text(
                            title,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        androidx.compose.material3.IconButton(onClick = onDismiss) {
                            Icon(phos.CaretLeft, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {}
                )
                // Compact identity header.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The header avatar springs up into place when the sheet
                    // opens (a satisfying "lands in the centre" beat) and, on
                    // tap, opens the full-resolution profile photo full-screen
                    // via the in-app media viewer.
                    var avatarIn by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { avatarIn = true }
                    val avatarScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (avatarIn) 1f else 0.55f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 0.6f,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        ),
                        label = "info-avatar-in"
                    )
                    com.secondream.novagram.ui.components.Avatar(
                        file = chat?.photo?.small,
                        fallbackText = title,
                        bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(chatId),
                        size = 84.dp,
                        modifier = Modifier
                            .graphicsLayer { scaleX = avatarScale; scaleY = avatarScale }
                            .clip(CircleShape)
                            .clickable {
                                if (canEditGroup) {
                                    groupPhotoPicker.launch("image/*")
                                } else {
                                    val big = chat?.photo?.big ?: return@clickable
                                    infoScope.launch {
                                        val path = ensureDownloaded(big)
                                        if (path != null) {
                                            com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = false
                                            com.secondream.novagram.ui.screens.MediaViewerHolder.currentPath = path
                                            photoViewerOpen = true
                                        }
                                    }
                                }
                            }
                    )
                    if (canEditGroup) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.profile_change_photo),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { groupPhotoPicker.launch("image/*") }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    subtitle?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                // Icon tab row (home-pill style), synced to the pager.
                androidx.compose.foundation.lazy.LazyRow(
                    state = tabsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)
                ) {
                    items(tabs.size) { idx ->
                        val selected = pagerState.currentPage == idx
                        // Smoothly cross-fade the pill colours on selection
                        // (and during a pager swipe, as currentPage flips) so
                        // tab switching feels fluid instead of snapping.
                        val pillBg by androidx.compose.animation.animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            animationSpec = androidx.compose.animation.core.tween(220),
                            label = "pillBg"
                        )
                        val pillFg by androidx.compose.animation.animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                            animationSpec = androidx.compose.animation.core.tween(220),
                            label = "pillFg"
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(pillBg)
                                .clickable { scope.launch { pagerState.animateScrollToPage(idx) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                tabs[idx].icon,
                                contentDescription = null,
                                tint = pillFg,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tabs[idx].label,
                                style = MaterialTheme.typography.labelMedium,
                                color = pillFg,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    thickness = 0.5.dp
                )
                // Search bar for the media tabs — lets the user find a specific
                // photo / video / file / link by caption or name. Hidden on the
                // Info and Membri tabs where it doesn't apply.
                val onMediaTab = pagerState.currentPage != 0 &&
                    pagerState.currentPage != membersIndex
                androidx.compose.animation.AnimatedVisibility(visible = onMediaTab) {
                    androidx.compose.material3.OutlinedTextField(
                        value = mediaQuery,
                        onValueChange = { mediaQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        placeholder = { Text(stringResource(R.string.info_media_search_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        leadingIcon = {
                            Icon(phos.MagnifyingGlass, contentDescription = null)
                        },
                        trailingIcon = if (mediaQuery.isNotEmpty()) {
                            {
                                androidx.compose.material3.IconButton(onClick = { mediaQuery = "" }) {
                                    Icon(phos.X, contentDescription = null)
                                }
                            }
                        } else null
                    )
                }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    when (page) {
                        0 -> if (canEditGroup) {
                            GroupInfoEditor(
                                chatId = chatId,
                                currentTitle = title,
                                currentBio = description ?: "",
                                showPermissions = editorShowPerms,
                                onSaved = onDismiss
                            )
                        } else {
                            ChatInfoDetailPage(description, username, phone)
                        }
                        membersIndex -> ChatMembersTab(
                            chatId = chatId,
                            supergroupId = supergroupId,
                            basicGroupId = basicGroupId,
                            refreshKey = membersRefresh,
                            onMemberTap = { selectedMember = it }
                        )
                        else -> {
                            val (filter, isGrid) = mediaFilterForPage(page)
                            com.secondream.novagram.ui.components.MediaTabContent(
                                chatId = chatId,
                                filter = filter,
                                isGrid = isGrid,
                                query = mediaQuery,
                                onItemTap = { selectedMediaMessage = it }
                            )
                        }
                    }
                }
            }
            // Transfer/download badge as a TOP OVERLAY (not in the column
            // flow) so it never pushes the tabs/pager — that vertical shift
            // while swiping during an active download was the micro-jump.
            // Still on top of the dialog content and self-hiding when idle.
            com.secondream.novagram.transfer.TransferPanel(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .fillMaxWidth()
            )
          }
        }

        // Media item actions (open / jump-to-chat). Same behavior as before.
        selectedMediaMessage?.let { msg ->
            val ctx = LocalContext.current
            ChatMediaItemActions(
                message = msg,
                onDismiss = { selectedMediaMessage = null },
                onJumpToMessage = {
                    selectedMediaMessage = null
                    onJumpToMessage(msg.id)
                },
                onOpenInViewer = { path ->
                    selectedMediaMessage = null
                    // Set the path, then open the hoisted viewer window stacked
                    // over this dialog. We DON'T dismiss the dialog any more —
                    // it stays underneath, so closing the viewer returns here,
                    // not to the bare chat. isVideo is set by the action sheet
                    // before this fires.
                    com.secondream.novagram.ui.screens.MediaViewerHolder.currentPath = path
                    photoViewerOpen = true
                },
                onOpenViaIntent = { path, mime ->
                    selectedMediaMessage = null
                    // Route through FileUtils.openDocument so APK detection,
                    // the NEW_TASK flag and the no-handler toast all live in
                    // one place (was a bespoke intent here that skipped the
                    // apk-mime fix and showed the chooser).
                    com.secondream.novagram.util.FileUtils.openDocument(ctx, path, mime)
                }
            )
        }

        // Member actions (ban / mute / promote), shown when a member row is tapped.
        selectedMember?.let { member ->
            MemberActionSheet(
                chatId = chatId,
                member = member,
                actionScope = infoScope,
                selfIsOwner = selfIsCreator,
                onDismiss = { selectedMember = null },
                onChanged = { membersRefresh++ },
                onBanned = { uid -> memberBanDeleteUid = uid }
            )
        }
        // Pre-ban confirmation from the member sheet: ban only fires once the
        // user picks keep / delete. onDone bumps the refresh so the member and
        // banned lists update in real time.
        memberBanDeleteUid?.let { uid ->
            BanConfirmDialog(
                chatId = chatId,
                userId = uid,
                memberName = null,
                scope = infoScope,
                onDone = { membersRefresh++ },
                onDismiss = { memberBanDeleteUid = null }
            )
        }
        // Group editing now lives inline in the Info tab (GroupInfoEditor).
    }
    if (photoViewerOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { photoViewerOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false
            )
        ) {
            com.secondream.novagram.ui.screens.MediaViewerScreen(
                filePath = com.secondream.novagram.ui.screens.MediaViewerHolder.currentPath ?: "",
                onClose = { photoViewerOpen = false }
            )
        }
    }
}

/**
 * Public/Private switcher for an existing group. Private = no username; Public
 * = a t.me/<username> handle (a basic group is upgraded to a supergroup first,
 * which changes the chat id — so on any successful apply we close the sheet via
 * [onAfterChange] and let the user reopen the now-updated chat). The username
 * field validates live against checkChatUsername.
 */
@Composable
private fun GroupTypeEditor(
    chatId: Long,
    onAfterChange: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var currentlyPublic by remember { mutableStateOf(false) }
    var currentUsername by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var statusRes by remember { mutableStateOf(0) }
    var applying by remember { mutableStateOf(false) }

    LaunchedEffect(chatId) {
        val cur = runCatching { TdClient.groupPublicUsername(chatId) }.getOrNull()
        if (!cur.isNullOrBlank()) {
            currentlyPublic = true; currentUsername = cur
            isPublic = true; username = cur; available = true
        }
        loaded = true
    }
    LaunchedEffect(username, isPublic) {
        if (!isPublic) { available = null; statusRes = 0; checking = false; return@LaunchedEffect }
        val u = username.trim()
        if (u == currentUsername && u.isNotEmpty()) { available = true; statusRes = 0; checking = false; return@LaunchedEffect }
        if (u.isEmpty()) { available = null; statusRes = 0; checking = false; return@LaunchedEffect }
        if (u.length < 5) { available = false; statusRes = R.string.group_username_short; checking = false; return@LaunchedEffect }
        checking = true; statusRes = 0
        kotlinx.coroutines.delay(450)
        val res = runCatching { TdClient.checkChatUsername(chatId, u) }.getOrNull()
        checking = false
        when (res) {
            is TdApi.CheckChatUsernameResultOk -> { available = true; statusRes = R.string.group_username_ok }
            is TdApi.CheckChatUsernameResultUsernameOccupied -> { available = false; statusRes = R.string.group_username_taken }
            is TdApi.CheckChatUsernameResultUsernameInvalid -> { available = false; statusRes = R.string.group_username_invalid }
            null -> { available = null; statusRes = 0 }
            else -> { available = false; statusRes = R.string.group_username_unavailable }
        }
    }

    val targetChanged = (isPublic != currentlyPublic) ||
        (isPublic && username.trim() != currentUsername)
    val canApply = loaded && !applying && targetChanged &&
        (!isPublic || available == true)

    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.group_type_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(
                selected = !isPublic,
                onClick = { isPublic = false },
                label = { Text(stringResource(R.string.group_type_private)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
            androidx.compose.material3.FilterChip(
                selected = isPublic,
                onClick = { isPublic = true },
                label = { Text(stringResource(R.string.group_type_public)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
        androidx.compose.animation.AnimatedVisibility(visible = isPublic) {
            Column(Modifier.padding(top = 10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = username,
                    onValueChange = { v -> username = v.filter { it.isLetterOrDigit() || it == '_' } },
                    label = { Text(stringResource(R.string.group_username_label)) },
                    prefix = { Text("t.me/") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    isError = available == false,
                    trailingIcon = {
                        when {
                            checking -> androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                            )
                            available == true -> Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            else -> {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (statusRes != 0) {
                    Text(
                        stringResource(statusRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Button(
            onClick = {
                applying = true
                scope.launch {
                    val ok = if (isPublic) {
                        TdClient.makeGroupPublic(chatId, username.trim())
                    } else {
                        TdClient.makeGroupPrivate(chatId)
                    }
                    applying = false
                    if (ok) {
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.group_type_saved,
                            com.secondream.novagram.ui.icons.PhosphorIcons.Check
                        )
                        onAfterChange()
                    }
                }
            },
            enabled = canApply,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.group_type_apply))
        }
    }
}

/**
 * Thin "unread messages" divider shown in the chat list directly above the
 * first message that was unread when the chat was opened — Telegram's classic
 * separator. Anchored once on open (see unreadSeparatorId); it deliberately
 * does NOT move or vanish as messages get read, only when leaving and
 * re-entering the chat with nothing unread.
 */
@Composable
private fun UnreadMessagesSeparator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
        Text(
            text = stringResource(R.string.unread_messages_separator),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
    }
}

/**
 * Inline group editor rendered directly inside the chat-info "Info" tab for
 * admins/owners (everyone else sees the read-only bio). Mirrors the Profile
 * screen's grouped field cards. The group PHOTO is edited by tapping the info
 * header avatar, so there is intentionally no duplicate photo here. "Salva"
 * persists name/description and calls [onSaved]; the call site wires that to
 * the info view's dismiss, so saving simply takes the user back.
 */
@Composable
private fun GroupInfoEditor(
    chatId: Long,
    currentTitle: String,
    currentBio: String,
    showPermissions: Boolean = true,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var title by remember(chatId) { mutableStateOf(currentTitle) }
    var bio by remember(chatId) { mutableStateOf(currentBio) }
    var inviteLink by remember(chatId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var permsOpen by remember { mutableStateOf(false) }
    var isProtected by remember(chatId) {
        mutableStateOf(TdClient.getCachedChat(chatId)?.hasProtectedContent == true)
    }
    LaunchedEffect(chatId) {
        inviteLink = runCatching { TdClient.getOrCreatePrimaryInviteLink(chatId) }.getOrNull()
    }
    val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        focusedLabelColor = MaterialTheme.colorScheme.primary
    )
    // Inside a full-screen Dialog the window doesn't dispatch system-bar
    // insets, so navigationBarsPadding() resolved to 0 and the "Salva" button
    // sat under the gesture bar (Eugenio: "Salva ancora tagliato in basso").
    // Read the REAL navigation-bar height off the Activity's decor view and
    // pad the scroll content by it, so the last element always clears the
    // nav/gesture area regardless of the dialog's broken inset dispatch.
    val editorDensity = androidx.compose.ui.platform.LocalDensity.current
    val editorNavBottom = remember {
        val act = ctx as? android.app.Activity
        val insets = act?.window?.decorView?.let {
            androidx.core.view.ViewCompat.getRootWindowInsets(it)
        }
        val px = insets?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.navigationBars()
        )?.bottom ?: 0
        with(editorDensity) { px.toDp() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = editorNavBottom + 24.dp
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp)
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.admin_group_name)) },
                leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.UsersThree, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = bio,
                onValueChange = { bio = it },
                label = { Text(stringResource(R.string.admin_group_bio)) },
                leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Info, null) },
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
        if (showPermissions) {
            Spacer(Modifier.height(14.dp))
            com.secondream.novagram.ui.components.ActionTileButton(
                tile = com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.perm_group_title),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
                    onClick = { permsOpen = true }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
        // Advanced privacy: "Gruppo protetto" — when on, Telegram blocks
        // forwarding, local saving/copying and (best-effort) screenshots.
        // Visible to anyone who can edit the group (owner / admins with
        // change-info), matching the editor's own gate.
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.group_protected_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.group_protected_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = isProtected,
                onCheckedChange = { want ->
                    val previous = isProtected
                    isProtected = want // optimistic
                    scope.launch {
                        val ok = TdClient.toggleChatHasProtectedContent(chatId, want)
                        if (!ok) isProtected = previous // revert on failure
                    }
                }
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp)
        ) {
            GroupTypeEditor(chatId = chatId, onAfterChange = onSaved)
        }
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.admin_invite_link),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val link = inviteLink
            if (link.isNullOrBlank()) {
                Text(
                    stringResource(R.string.admin_invite_link_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    link,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    com.secondream.novagram.ui.components.ActionTileButton(
                        tile = com.secondream.novagram.ui.components.ActionTile(
                            label = stringResource(R.string.admin_copy),
                            icon = com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(link))
                                com.secondream.novagram.ui.components.NovaSnackbar.show(
                                    R.string.admin_link_copied,
                                    com.secondream.novagram.ui.icons.PhosphorIcons.Copy
                                )
                            }
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    com.secondream.novagram.ui.components.ActionTileButton(
                        tile = com.secondream.novagram.ui.components.ActionTile(
                            label = stringResource(R.string.admin_share),
                            icon = com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight,
                            onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, link)
                                }
                                runCatching {
                                    ctx.startActivity(android.content.Intent.createChooser(send, null))
                                }
                            }
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        androidx.compose.material3.Button(
            enabled = !saving && title.isNotBlank(),
            onClick = {
                saving = true
                scope.launch {
                    if (title.trim() != currentTitle.trim() && title.isNotBlank()) {
                        runCatching { TdClient.setChatTitle(chatId, title.trim()) }
                    }
                    if (bio.trim() != currentBio.trim()) {
                        runCatching { TdClient.setChatDescription(chatId, bio.trim()) }
                    }
                    com.secondream.novagram.ui.components.NovaSnackbar.show(
                        R.string.admin_group_updated,
                        com.secondream.novagram.ui.icons.PhosphorIcons.Check
                    )
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.admin_save))
        }
        Spacer(Modifier.height(40.dp))
    }
    if (permsOpen) {
        val curPerms = TdClient.getCachedChat(chatId)?.permissions
            ?: buildGroupPermissions(true, true, true, true, true)
        GroupPermissionsDialog(
            title = stringResource(R.string.perm_group_title),
            initial = curPerms,
            onSave = { p ->
                scope.launch {
                    runCatching { TdClient.setChatPermissions(chatId, p) }.onSuccess {
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.perm_saved,
                            com.secondream.novagram.ui.icons.PhosphorIcons.Check
                        )
                    }
                }
            },
            onDismiss = { permsOpen = false }
        )
    }
}

private data class ChatInfoTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/** Maps a pager page index (1..6) to its media filter + grid/list layout. */
private fun mediaFilterForPage(page: Int): Pair<TdApi.SearchMessagesFilter, Boolean> = when (page) {
    1 -> TdApi.SearchMessagesFilterPhoto() to true
    2 -> TdApi.SearchMessagesFilterVideo() to true
    3 -> TdApi.SearchMessagesFilterDocument() to false
    4 -> TdApi.SearchMessagesFilterUrl() to false
    5 -> TdApi.SearchMessagesFilterVoiceNote() to false
    else -> TdApi.SearchMessagesFilterAudio() to false
}

/** Info tab: scrollable detail rows (bio / username / phone) or a placeholder. */
@Composable
private fun ChatInfoDetailPage(
    description: String?,
    username: String?,
    phone: String?
) {
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        description?.let {
            ProfileDetailRow(
                icon = phos.Info,
                label = stringResource(R.string.chat_info_bio_label),
                value = it,
                linkify = true
            )
        }
        username?.let {
            ProfileDetailRow(
                icon = phos.At,
                label = stringResource(R.string.chat_info_username_label),
                value = "@$it",
                copyValue = "@$it"
            )
        }
        phone?.let {
            ProfileDetailRow(
                icon = phos.Phone,
                label = stringResource(R.string.chat_info_phone_label),
                value = it,
                copyValue = it
            )
        }
        if (description == null && username == null && phone == null) {
            Text(
                stringResource(R.string.chat_info_no_details),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Members tab (admins only): search field + member list. */
@Composable
private fun ChatMembersTab(
    chatId: Long,
    supergroupId: Long?,
    basicGroupId: Long?,
    refreshKey: Int,
    onMemberTap: (TdApi.ChatMember) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showBanned by remember(chatId) { mutableStateOf(false) }
    var members by remember(chatId) { mutableStateOf<List<TdApi.ChatMember>>(emptyList()) }
    var loading by remember(chatId) { mutableStateOf(true) }
    // Track the last refreshKey so we can tell an ACTION-triggered reload
    // (restrict/mute/unmute → membersRefresh++) apart from a query/tab change.
    var prevRefreshKey by remember(chatId) { mutableStateOf(refreshKey) }
    // Pagination state for the (supergroup) roster. Basic groups return their
    // whole member list from getBasicGroupFullInfo so they never page.
    val membersListState = rememberLazyListState()
    var loadingMoreMembers by remember(chatId) { mutableStateOf(false) }
    var membersEndReached by remember(chatId) { mutableStateOf(false) }
    LaunchedEffect(chatId, query, supergroupId, basicGroupId, refreshKey, showBanned) {
        val actionTriggered = refreshKey != prevRefreshKey
        prevRefreshKey = refreshKey
        suspend fun loadMembers(): List<TdApi.ChatMember> = when {
            supergroupId != null -> {
                val filter = when {
                    showBanned -> TdApi.SupergroupMembersFilterBanned(query)
                    query.isBlank() -> TdApi.SupergroupMembersFilterRecent()
                    else -> TdApi.SupergroupMembersFilterSearch(query)
                }
                runCatching {
                    TdClient.getSupergroupMembersFiltered(supergroupId, filter, 200)
                }.getOrNull()?.members?.toList().orEmpty()
            }
            basicGroupId != null -> {
                val all = runCatching {
                    TdClient.getBasicGroupFullInfo(basicGroupId)
                }.getOrNull()?.members?.toList().orEmpty()
                if (query.isBlank()) all
                else all.filter { m ->
                    val uid = (m.memberId as? TdApi.MessageSenderUser)?.userId
                    val u = uid?.let { TdClient.getCachedUser(it) }
                    val hay = listOfNotNull(
                        u?.firstName, u?.lastName, u?.usernames?.editableUsername
                    ).joinToString(" ").lowercase()
                    hay.contains(query.lowercase())
                }
            }
            else -> emptyList()
        }
        if (actionTriggered) {
            // After setChatMemberStatus (restrict / mute / unmute) TDLib's
            // supergroup member cache lags a beat, so an immediate refetch
            // returns the OLD status and the "Limitato" label fails to appear
            // or disappear in real time. Don't blank the list to a spinner on a
            // toggle (jarring) — keep the current rows visible, refetch after a
            // short delay, then confirm once more in case the sync was slow.
            kotlinx.coroutines.delay(300)
            members = loadMembers()
            kotlinx.coroutines.delay(900)
            members = loadMembers()
        } else {
            loading = true
            members = loadMembers()
            loading = false
        }
        // A full first page means there may be more — let the scroll-end loader
        // pull the next offset. A short page (or any basic group) means the
        // whole roster is already loaded.
        membersEndReached = if (supergroupId != null) members.size < 200 else true
    }

    // Forward pagination for large supergroup/channel rosters. The initial
    // load only pulls the first 200; without this the "Membri" list showed a
    // truncated roster that dead-ended mid-scroll. When the last visible row
    // nears the end we fetch the next page at offset = members.size and append
    // (dedup-guarded by sender id), until a page comes back empty.
    LaunchedEffect(membersListState, supergroupId, query, showBanned) {
        val sg = supergroupId ?: return@LaunchedEffect
        snapshotFlow { membersListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastIdx ->
                if (lastIdx >= members.size - 8 && members.isNotEmpty() &&
                    !loading && !loadingMoreMembers && !membersEndReached
                ) {
                    loadingMoreMembers = true
                    val filter = when {
                        showBanned -> TdApi.SupergroupMembersFilterBanned(query)
                        query.isBlank() -> TdApi.SupergroupMembersFilterRecent()
                        else -> TdApi.SupergroupMembersFilterSearch(query)
                    }
                    val page = runCatching {
                        TdClient.getSupergroupMembersFiltered(sg, filter, 200, members.size)
                    }.getOrNull()?.members?.toList().orEmpty()
                    val keyOf = { m: TdApi.ChatMember ->
                        when (val s = m.memberId) {
                            is TdApi.MessageSenderUser -> s.userId
                            is TdApi.MessageSenderChat -> s.chatId
                            else -> 0L
                        }
                    }
                    val existing = members.mapTo(HashSet<Long>(), keyOf)
                    val toAdd = page.filter { keyOf(it) !in existing }
                    if (toAdd.isNotEmpty()) members = members + toAdd
                    if (page.isEmpty() || toAdd.isEmpty()) membersEndReached = true
                    loadingMoreMembers = false
                }
            }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (supergroupId != null) {
            // Membri | Bannati segmented toggle. Banned list is supergroup-only
            // (basic groups can't ban). Tapping a banned row opens the same
            // member sheet, which already shows the "Sban" action for banned.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(stringResource(R.string.members_tab_members), false, !showBanned),
                    Triple(stringResource(R.string.members_tab_banned), true, showBanned)
                ).forEach { (label, banned, selected) ->
                    androidx.compose.material3.Surface(
                        onClick = { showBanned = banned; query = "" },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.member_search_hint)) },
            leadingIcon = {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass,
                    contentDescription = null
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp)
        )
        when {
            loading && members.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                }
            }
            members.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (showBanned) R.string.members_none_banned
                            else R.string.members_none_found
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(state = membersListState, modifier = Modifier.fillMaxSize()) {
                    items(members) { m ->
                        ChatMemberRow(member = m, onClick = { onMemberTap(m) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMemberRow(member: TdApi.ChatMember, onClick: () -> Unit) {
    val uid = (member.memberId as? TdApi.MessageSenderUser)?.userId
    var user by remember(uid) {
        mutableStateOf<TdApi.User?>(uid?.let { TdClient.getCachedUser(it) })
    }
    LaunchedEffect(uid) {
        if (user == null && uid != null) {
            user = runCatching { TdClient.getUser(uid) }.getOrNull()
        }
    }
    val name = user?.let { "${it.firstName} ${it.lastName}".trim() }
        ?.takeIf { it.isNotBlank() } ?: stringResource(R.string.user_fallback)
    val statusLabel = when (member.status) {
        is TdApi.ChatMemberStatusCreator -> stringResource(R.string.member_role_owner)
        is TdApi.ChatMemberStatusAdministrator -> stringResource(R.string.member_role_admin)
        is TdApi.ChatMemberStatusRestricted -> stringResource(R.string.member_role_restricted)
        is TdApi.ChatMemberStatusBanned -> stringResource(R.string.member_role_banned)
        else -> null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.secondream.novagram.ui.components.Avatar(
            file = user?.profilePhoto?.small,
            fallbackText = name,
            size = 40.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val uname = user?.usernames?.editableUsername
            if (uname != null) {
                Text(
                    "@$uname",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (statusLabel != null) {
            Text(
                statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Map the 5 user-facing permission toggles to a full TdApi.ChatPermissions. */
internal fun buildGroupPermissions(
    sendMessages: Boolean,
    sendMedia: Boolean,
    addUsers: Boolean,
    pinMessages: Boolean,
    changeInfo: Boolean
): TdApi.ChatPermissions = TdApi.ChatPermissions(
    sendMessages,   // canSendBasicMessages
    sendMedia,      // canSendAudios
    sendMedia,      // canSendDocuments
    sendMedia,      // canSendPhotos
    sendMedia,      // canSendVideos
    sendMedia,      // canSendVideoNotes
    sendMedia,      // canSendVoiceNotes
    sendMessages,   // canSendPolls
    sendMedia,      // canSendOtherMessages
    sendMessages,   // canAddLinkPreviews
    sendMessages,   // canReactToMessages
    sendMessages,   // canEditTag
    changeInfo,     // canChangeInfo
    addUsers,       // canInviteUsers
    pinMessages,    // canPinMessages
    false           // canCreateTopics
)

/**
 * Map the 8 group-relevant admin toggles to the full 17-field
 * ChatAdministratorRights. Channel-only powers (post/edit messages, stories,
 * direct messages, tags) and topic management stay false; canManageChat is
 * always on so the admin is listed and can reach basic management.
 */
private fun buildAdminRights(
    changeInfo: Boolean,
    deleteMessages: Boolean,
    banUsers: Boolean,
    inviteUsers: Boolean,
    pinMessages: Boolean,
    addAdmins: Boolean,
    manageVideoChats: Boolean,
    anonymous: Boolean
): TdApi.ChatAdministratorRights = TdApi.ChatAdministratorRights(
    true,             // canManageChat
    changeInfo,       // canChangeInfo
    false,            // canPostMessages (channels)
    false,            // canEditMessages (channels)
    deleteMessages,   // canDeleteMessages
    inviteUsers,      // canInviteUsers
    banUsers,         // canRestrictMembers
    pinMessages,      // canPinMessages
    false,            // canManageTopics
    addAdmins,        // canPromoteMembers
    manageVideoChats, // canManageVideoChats
    false,            // canPostStories
    false,            // canEditStories
    false,            // canDeleteStories
    false,            // canManageDirectMessages
    false,            // canManageTags
    anonymous         // isAnonymous
)

@Composable
private fun PermissionRow(
    labelRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(labelRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Pre-ban confirmation (Telegram-style): tapping "ban" opens THIS first, the
 * actual ban only fires once the user chooses. Three outcomes:
 *  • Annulla — nothing happens.
 *  • Mantieni i messaggi — ban only.
 *  • Elimina tutto — ban, then wipe the user's messages, showing a progress
 *    bar with the deleted-message count, then a snackbar.
 * onDone fires after the ban lands so the caller can refresh member lists.
 */
@Composable
private fun BanConfirmDialog(
    chatId: Long,
    userId: Long,
    memberName: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    // confirm → deleting → done
    var phase by remember { mutableStateOf("confirm") }
    var count by remember { mutableStateOf(0) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (phase == "confirm") onDismiss() }
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                if (phase == "confirm") {
                    Text(
                        if (memberName.isNullOrBlank())
                            stringResource(R.string.ban_confirm_title)
                        else stringResource(R.string.ban_confirm_title_named, memberName),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ban_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End
                    ) {
                        TextButton(onClick = {
                            phase = "deleting"
                            scope.launch {
                                runCatching { TdClient.kickGroupUser(chatId, userId) }
                                count = TdClient.countChatMessagesBySender(chatId, userId)
                                runCatching {
                                    TdClient.deleteChatMessagesBySender(chatId, userId)
                                }
                                phase = "done"
                                onDone()
                                com.secondream.novagram.ui.components.NovaSnackbar.show(
                                    R.string.ban_delete_all_done,
                                    com.secondream.novagram.ui.icons.PhosphorIcons.Trash
                                )
                                kotlinx.coroutines.delay(1100)
                                onDismiss()
                            }
                        }) {
                            Text(
                                stringResource(R.string.ban_confirm_delete),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = {
                            // Keep messages → ban only.
                            scope.launch {
                                runCatching { TdClient.kickGroupUser(chatId, userId) }
                                    .onSuccess {
                                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                                            R.string.snack_member_banned,
                                            com.secondream.novagram.ui.icons.PhosphorIcons.UserMinus
                                        )
                                    }
                                onDone()
                            }
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.ban_confirm_keep), maxLines = 1)
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel), maxLines = 1)
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.ban_deleting_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(16.dp))
                    if (phase == "done") {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.ban_deleted_count, count),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.ban_deleting_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pin confirmation for groups, mirroring Telegram: a single "Fissa" action
 * with a "notify all members" toggle on top (ON by default). Pinning silent
 * (toggle off) sets disableNotification=true so members aren't pinged.
 */
@Composable
private fun PinNotifyDialog(
    onPin: (notify: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var notify by remember { mutableStateOf(true) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    stringResource(R.string.pin_dialog_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { notify = !notify }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.pin_notify_all),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = notify,
                        onCheckedChange = { notify = it }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { onPin(notify) }) {
                        Text(stringResource(R.string.pin_dialog_confirm))
                    }
                }
            }
        }
    }
}

/**
 * Shared permissions editor for BOTH the group default permissions (global,
 * setChatPermissions) and a single member's restrictions (per-user,
 * restrictMember). The 5 toggles map to the full ChatPermissions.
 */
@Composable
internal fun GroupPermissionsDialog(
    title: String,
    initial: TdApi.ChatPermissions,
    onSave: (TdApi.ChatPermissions) -> Unit,
    onDismiss: () -> Unit,
    avatarFile: TdApi.File? = null,
    avatarName: String? = null
) {
    var sendMessages by remember { mutableStateOf(initial.canSendBasicMessages) }
    var sendMedia by remember { mutableStateOf(initial.canSendPhotos) }
    var addUsers by remember { mutableStateOf(initial.canInviteUsers) }
    var pinMessages by remember { mutableStateOf(initial.canPinMessages) }
    var changeInfo by remember { mutableStateOf(initial.canChangeInfo) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                if (avatarName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.secondream.novagram.ui.components.Avatar(
                            file = avatarFile,
                            fallbackText = avatarName,
                            size = 42.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                avatarName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                ) {
                    PermissionRow(R.string.perm_send_messages, sendMessages) { sendMessages = it }
                    PermissionRow(R.string.perm_send_media, sendMedia) { sendMedia = it }
                    PermissionRow(R.string.perm_add_users, addUsers) { addUsers = it }
                    PermissionRow(R.string.perm_pin_messages, pinMessages) { pinMessages = it }
                    PermissionRow(R.string.perm_change_info, changeInfo) { changeInfo = it }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.delete_chat_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(onClick = {
                        onSave(
                            buildGroupPermissions(
                                sendMessages, sendMedia, addUsers, pinMessages, changeInfo
                            )
                        )
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.admin_save))
                    }
                }
            }
        }
    }
}

/**
 * Admin rights picker — shown when promoting a member or re-editing an
 * existing admin. 8 group-relevant toggles map to ChatAdministratorRights via
 * buildAdminRights. Mirrors GroupPermissionsDialog's layout for consistency.
 */
@Composable
private fun AdminRightsDialog(
    title: String,
    initial: TdApi.ChatAdministratorRights,
    onSave: (TdApi.ChatAdministratorRights) -> Unit,
    onDismiss: () -> Unit,
    avatarFile: TdApi.File? = null,
    avatarName: String? = null
) {
    var changeInfo by remember { mutableStateOf(initial.canChangeInfo) }
    var deleteMessages by remember { mutableStateOf(initial.canDeleteMessages) }
    var banUsers by remember { mutableStateOf(initial.canRestrictMembers) }
    var inviteUsers by remember { mutableStateOf(initial.canInviteUsers) }
    var pinMessages by remember { mutableStateOf(initial.canPinMessages) }
    var addAdmins by remember { mutableStateOf(initial.canPromoteMembers) }
    var manageVideoChats by remember { mutableStateOf(initial.canManageVideoChats) }
    var anonymous by remember { mutableStateOf(initial.isAnonymous) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (avatarName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.secondream.novagram.ui.components.Avatar(
                            file = avatarFile,
                            fallbackText = avatarName,
                            size = 42.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                avatarName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                } else {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 2.dp)
                ) {
                    PermissionRow(R.string.admin_right_change_info, changeInfo) { changeInfo = it }
                    PermissionRow(R.string.admin_right_delete_messages, deleteMessages) { deleteMessages = it }
                    PermissionRow(R.string.admin_right_ban_users, banUsers) { banUsers = it }
                    PermissionRow(R.string.admin_right_invite_users, inviteUsers) { inviteUsers = it }
                    PermissionRow(R.string.admin_right_pin_messages, pinMessages) { pinMessages = it }
                    PermissionRow(R.string.admin_right_add_admins, addAdmins) { addAdmins = it }
                    PermissionRow(R.string.admin_right_video_chats, manageVideoChats) { manageVideoChats = it }
                    PermissionRow(R.string.admin_right_anonymous, anonymous) { anonymous = it }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                ) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.delete_chat_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Button(onClick = {
                        onSave(
                            buildAdminRights(
                                changeInfo, deleteMessages, banUsers, inviteUsers,
                                pinMessages, addAdmins, manageVideoChats, anonymous
                            )
                        )
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.admin_save))
                    }
                }
            }
        }
    }
}

/** Ban / mute / promote / permissions action sheet for a tapped group member. */
@Composable
private fun MemberActionSheet(
    chatId: Long,
    member: TdApi.ChatMember,
    actionScope: kotlinx.coroutines.CoroutineScope,
    selfIsOwner: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit = {},
    onBanned: (userId: Long) -> Unit = {}
) {
    // Actions run on a scope owned by the caller (the info view), NOT a
    // sheet-local rememberCoroutineScope — otherwise dismissing the sheet
    // cancels the coroutine and the post-action refresh (onChanged) never
    // fires, leaving the member list stale until you reopen the view.
    val scope = actionScope
    val uid = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return
    val status = member.status
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    var memberUser by remember(uid) { mutableStateOf<TdApi.User?>(null) }
    var chatPerms by remember(chatId) { mutableStateOf<TdApi.ChatPermissions?>(null) }
    LaunchedEffect(uid) { memberUser = runCatching { TdClient.getUser(uid) }.getOrNull() }
    LaunchedEffect(chatId) {
        chatPerms = runCatching { TdClient.getChat(chatId).permissions }.getOrNull()
    }
    val memberName = memberUser?.let {
        val n = "${it.firstName} ${it.lastName}".trim()
        if (n.isNotBlank()) n
        else it.usernames?.activeUsernames?.firstOrNull()?.let { u -> "@$u" }
    }
    var showPerms by remember { mutableStateOf(false) }
    if (showPerms) {
        val initial = (status as? TdApi.ChatMemberStatusRestricted)?.permissions
            ?: chatPerms
            ?: buildGroupPermissions(true, true, true, true, true)
        GroupPermissionsDialog(
            title = stringResource(R.string.perm_member_title),
            initial = initial,
            avatarFile = memberUser?.profilePhoto?.small,
            avatarName = memberName,
            onSave = { p ->
                scope.launch {
                    runCatching { TdClient.restrictMember(chatId, uid, p) }.onSuccess {
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.perm_saved,
                            com.secondream.novagram.ui.icons.PhosphorIcons.Check
                        )
                    }
                    onChanged()
                }
            },
            onDismiss = onDismiss
        )
        return
    }
    var showAdminRights by remember { mutableStateOf(false) }
    if (showAdminRights) {
        val initialRights = (status as? TdApi.ChatMemberStatusAdministrator)?.rights
            ?: buildAdminRights(true, true, true, true, true, false, true, false)
        AdminRightsDialog(
            title = stringResource(R.string.admin_rights_title),
            initial = initialRights,
            avatarFile = memberUser?.profilePhoto?.small,
            avatarName = memberName,
            onSave = { r ->
                scope.launch {
                    runCatching { TdClient.setAdminRights(chatId, uid, r) }.onSuccess {
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.snack_admin_granted,
                            com.secondream.novagram.ui.icons.PhosphorIcons.Check
                        )
                    }
                    onChanged()
                }
            },
            onDismiss = onDismiss
        )
        return
    }
    val isBanned = status is TdApi.ChatMemberStatusBanned
    // "Muted" (→ shows Riattiva) ONLY when the restriction actually silences
    // the member, i.e. they can't send basic/text messages. A PARTIAL
    // restriction set via "Permessi utente" (e.g. media off but text still
    // allowed) leaves canSendBasicMessages = true, so it must NOT read as
    // muted — that was Eugenio's bug: removing media showed "Limitato" (correct)
    // but also flipped the toggle to "Riattiva" as if muted. The row's
    // "Limitato" label still derives from Restricted status, which is right;
    // only the mute/unmute toggle is gated on real silence.
    val isMuted = (status as? TdApi.ChatMemberStatusRestricted)
        ?.permissions?.canSendBasicMessages == false
    val isAdmin = status is TdApi.ChatMemberStatusAdministrator
    val isCreator = status is TdApi.ChatMemberStatusCreator
    val tiles = buildList {
        if (!isCreator && !isBanned && !isAdmin) {
            add(com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(R.string.perm_member_title),
                icon = phos.Lock,
                onClick = { showPerms = true }
            ))
        }
        if (isBanned) {
            add(com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(R.string.member_unban),
                icon = phos.User,
                onClick = {
                    onDismiss()
                    scope.launch {
                        runCatching { TdClient.unbanGroupUser(chatId, uid) }.onSuccess {
                            com.secondream.novagram.ui.components.NovaSnackbar.show(
                                R.string.snack_member_unbanned, phos.Check
                            )
                        }
                        onChanged()
                    }
                }
            ))
        } else if (!isCreator && !isAdmin) {
            // Admins (and the creator) can't be banned directly — Telegram
            // requires demoting them first. The owner-only demote tile below
            // handles that; after demotion the member reopens as bannable.
            add(com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(R.string.member_ban),
                icon = phos.UserMinus,
                destructive = true,
                onClick = {
                    onDismiss()
                    onBanned(uid)
                }
            ))
        }
        if (isMuted) {
            add(com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(R.string.member_unmute),
                icon = phos.Bell,
                onClick = {
                    onDismiss()
                    scope.launch {
                        runCatching { TdClient.unmuteGroupUser(chatId, uid) }.onSuccess {
                            com.secondream.novagram.ui.components.NovaSnackbar.show(
                                R.string.snack_member_unmuted, phos.Bell
                            )
                        }
                        onChanged()
                    }
                }
            ))
        } else if (!isCreator && !isBanned) {
            add(com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(R.string.member_mute),
                icon = phos.SpeakerSlash,
                onClick = {
                    onDismiss()
                    scope.launch {
                        runCatching { TdClient.muteGroupUser(chatId, uid) }.onSuccess {
                            com.secondream.novagram.ui.components.NovaSnackbar.show(
                                R.string.snack_member_muted, phos.BellSlash
                            )
                        }
                        onChanged()
                    }
                }
            ))
        }
        if (!isCreator) {
            if (isAdmin) {
                // Only the group owner can manage existing admins (demote /
                // edit their rights). A regular admin viewing another admin
                // gets no admin-management tiles.
                if (selfIsOwner) {
                    add(com.secondream.novagram.ui.components.ActionTile(
                        label = stringResource(R.string.member_remove_admin),
                        icon = phos.UserMinus,
                        onClick = {
                            onDismiss()
                            scope.launch {
                                runCatching { TdClient.demoteFromAdmin(chatId, uid) }.onSuccess {
                                    com.secondream.novagram.ui.components.NovaSnackbar.show(
                                        R.string.snack_admin_revoked, phos.UserMinus
                                    )
                                }
                                onChanged()
                            }
                        }
                    ))
                    add(com.secondream.novagram.ui.components.ActionTile(
                        label = stringResource(R.string.admin_rights_title),
                        icon = phos.Lock,
                        onClick = { showAdminRights = true }
                    ))
                }
            } else if (!isBanned) {
                add(com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.member_make_admin),
                    icon = phos.Sparkle,
                    onClick = { showAdminRights = true }
                ))
            }
        }
        add(com.secondream.novagram.ui.components.ActionTile(
            label = stringResource(R.string.action_cancel),
            icon = phos.X,
            onClick = { onDismiss() }
        ))
    }
    com.secondream.novagram.ui.components.ActionBottomSheet(
        title = stringResource(R.string.member_manage_title),
        onDismiss = onDismiss,
        tiles = tiles,
        tilesPerRow = if (tiles.size >= 4) 2 else tiles.size.coerceAtLeast(1)
    )
}

/**
 * Per-item action sheet for the media gallery. Two tiles in the common
 * case (Apri / Visualizza in chat); for non-downloadable items the
 * Open tile self-disables until the underlying file finishes its
 * background fetch. Document/audio items open via an ACTION_VIEW
 * Intent with the file's mime; photos / videos use the in-app
 * MediaViewer for a coherent zoom/pinch experience.
 */
@Composable
private fun ChatMediaItemActions(
    message: TdApi.Message,
    onDismiss: () -> Unit,
    onJumpToMessage: () -> Unit,
    onOpenInViewer: (String) -> Unit,
    onOpenViaIntent: (path: String, mime: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    // When the chat restricts saving (Telegram "Restrict saving content" /
    // our "Gruppo protetto"), the Salva tile must not appear at all.
    val isProtected = TdClient.getCachedChat(message.chatId)?.hasProtectedContent == true
    // Build the tiles list dynamically so we can label them per content
    // kind (Apri foto vs Apri video vs Apri file) — matches how
    // Telegram differentiates the verbs across content types.
    val (openLabel, openIcon, openAction) = remember(message.id) {
        when (val c = message.content) {
            is TdApi.MessagePhoto -> {
                val biggest = c.photo.sizes.lastOrNull()?.photo
                Triple<String, _, () -> Unit>(
                    ctx.getString(R.string.chat_info_media_open_photo),
                    phos.Image,
                    {
                        scope.launch {
                            val f = biggest ?: return@launch
                            val ready = ensureDownloaded(f)
                            if (ready != null) {
                                com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = false
                                onOpenInViewer(ready)
                            }
                        }
                    }
                )
            }
            is TdApi.MessageVideo -> Triple<String, _, () -> Unit>(
                ctx.getString(R.string.chat_info_media_open_video),
                phos.Play,
                {
                    scope.launch {
                        val ready = ensureDownloaded(c.video.video)
                        if (ready != null) {
                            com.secondream.novagram.ui.screens.MediaViewerHolder.isVideo = true
                            onOpenInViewer(ready)
                        }
                    }
                }
            )
            is TdApi.MessageDocument -> Triple<String, _, () -> Unit>(
                ctx.getString(R.string.chat_info_media_open_file),
                phos.FileText,
                {
                    scope.launch {
                        val ready = ensureDownloaded(c.document.document)
                        if (ready != null) onOpenViaIntent(ready, c.document.mimeType.ifBlank { "*/*" })
                    }
                }
            )
            is TdApi.MessageAudio -> Triple<String, _, () -> Unit>(
                ctx.getString(R.string.chat_info_media_open_file),
                phos.FileAudio,
                {
                    scope.launch {
                        val ready = ensureDownloaded(c.audio.audio)
                        if (ready != null) onOpenViaIntent(ready, c.audio.mimeType.ifBlank { "audio/*" })
                    }
                }
            )
            is TdApi.MessageVoiceNote -> Triple<String, _, () -> Unit>(
                ctx.getString(R.string.chat_info_media_open_file),
                phos.Microphone,
                {
                    scope.launch {
                        val ready = ensureDownloaded(c.voiceNote.voice)
                        if (ready != null) onOpenViaIntent(ready, c.voiceNote.mimeType.ifBlank { "audio/ogg" })
                    }
                }
            )
            is TdApi.MessageText -> {
                // Url tab — extract the first URL and open via Intent.
                val first = c.text.entities.firstOrNull {
                    it.type is TdApi.TextEntityTypeUrl || it.type is TdApi.TextEntityTypeTextUrl
                }
                val url = when (val t = first?.type) {
                    is TdApi.TextEntityTypeTextUrl -> t.url
                    is TdApi.TextEntityTypeUrl ->
                        c.text.text.substring(first.offset, first.offset + first.length)
                    else -> c.text.text
                }
                Triple<String, _, () -> Unit>(
                    ctx.getString(R.string.chat_info_media_open_link),
                    phos.Paperclip,
                    {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                            )
                        }
                    }
                )
            }
            else -> Triple<String, _, () -> Unit>(
                ctx.getString(R.string.chat_info_media_open_file),
                phos.FileText,
                { /* unsupported */ }
            )
        }
    }
    // Per-kind "Salva" action: download (the top badge shows during the
    // fetch) then copy into Downloads/Nova. Null for links/text where there's
    // nothing to save.
    val saveAction: (() -> Unit)? = remember(message.id) {
        when (val c = message.content) {
            is TdApi.MessagePhoto -> {
                val f = c.photo.sizes.lastOrNull()?.photo
                val a: () -> Unit = {
                    saveMediaToDevice(scope, ctx, f, "photo_${message.id}.jpg", "image/jpeg",
                        com.secondream.novagram.util.FileUtils.SaveCategory.Media, onDismiss)
                }
                a
            }
            is TdApi.MessageVideo -> {
                val v = c.video
                val a: () -> Unit = {
                    saveMediaToDevice(scope, ctx, v.video, v.fileName.ifBlank { "video_${message.id}.mp4" },
                        v.mimeType.ifBlank { "video/mp4" },
                        com.secondream.novagram.util.FileUtils.SaveCategory.Media, onDismiss)
                }
                a
            }
            is TdApi.MessageDocument -> {
                val d = c.document
                val a: () -> Unit = {
                    saveMediaToDevice(scope, ctx, d.document, d.fileName.ifBlank { "file_${message.id}" },
                        d.mimeType.ifBlank { "application/octet-stream" },
                        com.secondream.novagram.util.FileUtils.SaveCategory.File, onDismiss)
                }
                a
            }
            is TdApi.MessageAudio -> {
                val au = c.audio
                val a: () -> Unit = {
                    saveMediaToDevice(scope, ctx, au.audio, au.fileName.ifBlank { "audio_${message.id}.mp3" },
                        au.mimeType.ifBlank { "audio/mpeg" },
                        com.secondream.novagram.util.FileUtils.SaveCategory.File, onDismiss)
                }
                a
            }
            is TdApi.MessageVoiceNote -> {
                val vn = c.voiceNote
                val a: () -> Unit = {
                    saveMediaToDevice(scope, ctx, vn.voice, "voice_${message.id}.ogg",
                        vn.mimeType.ifBlank { "audio/ogg" },
                        com.secondream.novagram.util.FileUtils.SaveCategory.File, onDismiss)
                }
                a
            }
            else -> null
        }
    }
    com.secondream.novagram.ui.components.ActionBottomSheet(
        title = ctx.getString(R.string.chat_info_media_action_title),
        onDismiss = onDismiss,
        tiles = listOfNotNull(
            com.secondream.novagram.ui.components.ActionTile(
                label = openLabel,
                icon = openIcon,
                onClick = openAction
            ),
            (if (isProtected) null else saveAction)?.let {
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.action_save),
                    icon = phos.DownloadSimple,
                    onClick = it
                )
            },
            com.secondream.novagram.ui.components.ActionTile(
                label = ctx.getString(R.string.chat_info_media_view_in_chat),
                icon = phos.ChatCircle,
                onClick = onJumpToMessage
            )
        ),
        tilesPerRow = 3
    )
}

/**
 * Ensure [file] is downloaded to local disk and return the local path,
 * or null if the download fails. Wrapped here so the per-content-kind
 * branches above stay terse.
 */
private suspend fun ensureDownloaded(file: TdApi.File?): String? {
    if (file == null) return null
    val existing = file.local?.path?.takeIf {
        it.isNotEmpty() && java.io.File(it).exists()
    }
    if (existing != null) return existing
    // Kick the download off immediately (non-blocking) so the top transfer
    // badge appears the instant the user taps "Apri"/"Salva", then await the
    // finished file below for the path.
    TdClient.startDownload(file.id)
    val downloaded = runCatching { TdClient.downloadFile(file.id) }.getOrNull()
    return downloaded?.local?.path?.takeIf {
        it.isNotEmpty() && java.io.File(it).exists()
    }
}

/**
 * Download [file] if needed (the transfer badge shows during the fetch) and
 * copy it into the public Downloads/Nova folder so it survives outside the
 * app, then toast the outcome. Backs the "Salva" tile in the media sheet.
 */
private fun saveMediaToDevice(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    file: TdApi.File?,
    displayName: String,
    mimeType: String,
    category: com.secondream.novagram.util.FileUtils.SaveCategory,
    onDone: () -> Unit
) {
    if (file == null) { onDone(); return }
    scope.launch {
        val path = ensureDownloaded(file)
        val ok = path != null && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.secondream.novagram.util.FileUtils.saveToDownloads(
                context, path, displayName, mimeType, category
            )
        }
        android.widget.Toast.makeText(
            context,
            if (ok) context.getString(R.string.media_saved_ok) else context.getString(R.string.media_save_failed),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        onDone()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    /**
     * When true, scan [value] for URLs and render them as accent-colored
     * clickable spans that open via Intent on tap. Bio fields turn this
     * on so a user pasting a personal link in their bio is reachable
     * with one tap. Username/phone leave it off — they're already
     * single-token values and adding URL detection on them just risks
     * false positives (e.g. a username that contains a dot).
     */
    linkify: Boolean = false,
    /**
     * Tap-to-copy behavior. When set, tapping the row body copies
     * [copyValue] (or [value] if null) to the system clipboard and
     * shows a small confirmation toast. The whole row becomes a
     * Copy affordance — most users discover it by tapping; a small
     * Copy icon on the right edge tells the rest of them. URL spans
     * inside the value still override the row tap so links are
     * always reachable.
     */
    copyValue: String? = value
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val accent = MaterialTheme.colorScheme.primary

    // Build the annotated value: if linkify is on, every URL detected
    // by android.util.Patterns.WEB_URL gets a clickable span. We
    // remember the result keyed on the raw value so recompositions
    // don't re-parse needlessly.
    val annotated = remember(value, linkify, accent) {
        if (!linkify) androidx.compose.ui.text.AnnotatedString(value)
        else {
            val builder = androidx.compose.ui.text.AnnotatedString.Builder()
            val pat = android.util.Patterns.WEB_URL
            val m = pat.matcher(value)
            var lastEnd = 0
            while (m.find()) {
                val start = m.start()
                val end = m.end()
                if (start > lastEnd) builder.append(value.substring(lastEnd, start))
                val raw = value.substring(start, end)
                // Annotate so the onClick handler below can resolve
                // which URL was tapped by character offset.
                builder.pushStringAnnotation(tag = "URL", annotation = raw)
                builder.pushStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = accent,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )
                )
                builder.append(raw)
                builder.pop(); builder.pop()
                lastEnd = end
            }
            if (lastEnd < value.length) builder.append(value.substring(lastEnd))
            builder.toAnnotatedString()
        }
    }

    fun copyToClipboard() {
        val v = copyValue ?: value
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(v))
        android.widget.Toast.makeText(
            ctx,
            ctx.getString(R.string.chat_info_copied),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                // Tap copies; long-press also copies (same affordance —
                // some users tap rows, others long-press). URLs inside
                // the annotated value handle their own onClick via
                // ClickableText below, which suppresses propagation.
                onClick = { copyToClipboard() },
                onLongClick = { copyToClipboard() }
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (linkify) {
                androidx.compose.foundation.text.ClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    onClick = { offset ->
                        val tapped = annotated.getStringAnnotations(
                            tag = "URL", start = offset, end = offset
                        ).firstOrNull()
                        if (tapped != null) {
                            // Normalize URLs missing a scheme so the
                            // Intent resolves to a browser (otherwise
                            // ACTION_VIEW on "example.com" matches
                            // nothing).
                            val raw = tapped.item
                            val url = if (raw.startsWith("http://") || raw.startsWith("https://"))
                                raw else "https://$raw"
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url)
                                    )
                                )
                            }
                        } else {
                            // No URL at the tap offset — treat as a
                            // regular row tap and copy the whole value.
                            copyToClipboard()
                        }
                    }
                )
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Right-edge Copy icon — silent affordance hint so the row's
        // copy-on-tap behaviour is discoverable without forcing the
        // user to try long-pressing first.
        Spacer(Modifier.width(8.dp))
        Icon(
            com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun labelMembers(count: Int, channel: Boolean = false): String =
    if (channel) "iscritti" else "membri"

/**
 * Replaces the message composer while a secret chat hasn't finished its
 * handshake (Pending) or has been closed by either side (Closed). Shows a
 * single centered line with a lock glyph so it reads as a deliberate state,
 * not a broken input. The body of the chat carries the fuller explanation
 * (see the AnimatedVisibility empty state over the message list).
 */
@Composable
private fun SecretChatLockedBar(closed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(
                if (closed) R.string.secret_closed_composer
                else R.string.secret_waiting_composer
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun InputBar(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    autoCapitalize: Boolean = true,
    placeholderText: String?,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: (sendIt: Boolean) -> Unit,
    recording: Boolean,
    // Voice-note LOCK: when the user slides the mic up, recording locks and
    // they can release their finger; the bar then shows trash + send instead
    // of the mic so they can record long notes hands-free.
    recordingLocked: Boolean = false,
    onMicLock: () -> Unit = {},
    onSendVoice: () -> Unit = {},
    onCancelVoice: () -> Unit = {},
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
    // Shows a "/" command-list button at the head of the bar (only when the
    // chat actually exposes bot commands) so the user can browse + tap them
    // without having to know the "type /" trick. Tapping toggles the full
    // command list above the bar.
    showCommandsButton: Boolean = false,
    onCommandsClick: () -> Unit = {},
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
                    if (recordingLocked) stringResource(R.string.recording_locked_hint)
                    else stringResource(R.string.recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = iconTint,
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
            } else {
                if (showCommandsButton) {
                    IconButton(
                        onClick = onCommandsClick,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.List,
                            contentDescription = "Comandi bot",
                            tint = iconTint
                        )
                    }
                }
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
                        keyboardOptions = KeyboardOptions(
                            capitalization = if (autoCapitalize)
                                KeyboardCapitalization.Sentences
                            else KeyboardCapitalization.None
                        ),
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
            // Both are GATED on realtime connectivity: when ConnectivityState
            // reports offline, the send button greys out and stops accepting
            // taps, and the mic button's press handlers no-op. Flips back
            // active the moment a usable network is restored — no polling,
            // we just collectAsState on the realtime ConnectivityManager-
            // backed flow.
            val online by com.secondream.novagram.connectivity
                .ConnectivityState.isOnline.collectAsState()
            if (recordingLocked) {
                // Locked voice recording: trash discards, send fires it off.
                // The red pulsing recording indicator + timer already shows in
                // the leading slot (recording stays true while locked).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onCancelVoice,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = onSendVoice,
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
                }
            } else {
            val showSend = !recording && (value.text.isNotBlank() || hasPendingMedia)
            androidx.compose.animation.AnimatedContent(
                targetState = showSend,
                transitionSpec = {
                    // The send button springs in with a little bounce while the
                    // mic fades out, and vice-versa when the text clears. Keyed
                    // strictly on `showSend` — which stays false through the
                    // ENTIRE mic press (recording flips the first condition
                    // false regardless of text) — so the mic button is never
                    // disposed mid-gesture, preserving the press→record→release
                    // chain the single-instance note below warns about.
                    (androidx.compose.animation.scaleIn(
                        initialScale = 0.5f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                        )
                    ) + androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(120)
                    )) togetherWith (androidx.compose.animation.scaleOut(
                        targetScale = 0.5f,
                        animationSpec = androidx.compose.animation.core.tween(140)
                    ) + androidx.compose.animation.fadeOut(
                        androidx.compose.animation.core.tween(110)
                    ))
                },
                label = "send-mic"
            ) { sending ->
                if (sending) {
                    // Press-scale feedback: the send button dips when pressed
                    // and springs back, giving the tap a tactile feel. Mirrors
                    // the AttachTile press animation; it's a single graphicsLayer
                    // scale (no layout pass) so it stays smooth on low-end
                    // phones.
                    val sendInteraction = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    }
                    val sendPressed by sendInteraction.collectIsPressedAsState()
                    val sendScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (sendPressed) 0.82f else 1f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessHigh
                        ),
                        label = "send-press"
                    )
                    IconButton(
                        onClick = { if (online) onSend() },
                        enabled = online,
                        interactionSource = sendInteraction,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                                .clip(CircleShape)
                                .background(
                                    if (online) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                ),
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
                    MicButton(
                        recording = recording,
                        enabled = online,
                        onDown = { if (online) onMicDown() },
                        onUp = { released -> if (online) onMicUp(released) },
                        onLock = onMicLock
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun MicButton(
    recording: Boolean,
    enabled: Boolean = true,
    onDown: () -> Unit,
    onUp: (Boolean) -> Unit,
    onLock: () -> Unit = {}
) {
    val baseColor = when {
        recording -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    val lockPx = 120.dp.toPx()
                    val cancelPx = 120.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onDown()
                        var locked = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // Finger lifted. If the user never slid up to
                                // lock, this is the normal hold-to-send release.
                                if (!locked) onUp(true)
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            // Slide LEFT past the threshold → CANCEL: discard the
                            // note (onUp(false) → recorder.cancel()). This is the
                            // slide-to-cancel the slide-up-to-lock change dropped
                            // (Eugenio). Only while NOT locked — a locked note is
                            // cancelled with the trash button instead.
                            if (!locked && dx <= -cancelPx) {
                                onUp(false)
                                break
                            }
                            // Slide UP past the threshold → LOCK so the user can
                            // let go and keep recording (long notes), then send
                            // with the explicit send button.
                            if (!locked && dy <= -lockPx) {
                                locked = true
                                onLock()
                            }
                        }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inner circle sized to MATCH the send button (40dp visible disc inside
        // a 48dp touch target), so mic and send look identical — the mic was a
        // full 48dp disc before, which read as bigger.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(baseColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.Microphone,
                null,
                tint = if (recording) MaterialTheme.colorScheme.onError
                       else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
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
    results: List<TdApi.Message>,
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
    onPickSticker: () -> Unit,
    onCreatePoll: (() -> Unit)? = null
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
                if (onCreatePoll != null) {
                    AttachTile(
                        label = stringResource(R.string.attach_poll),
                        icon = com.secondream.novagram.ui.icons.PhosphorIcons.List,
                        onClick = onCreatePoll,
                        modifier = Modifier.weight(1f)
                    )
                }
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

/**
 * Bottom sheet to compose and send a poll. Same surface/styling language as
 * AttachSheet. A question field, 2–10 option fields (add / remove), and two
 * switches (multiple answers, anonymous — on by default, the Telegram norm for
 * group polls). "Crea" is enabled only once the question and at least two
 * options are non-blank. Sends the trimmed, non-blank options upward.
 */
@Composable
private fun PollComposerSheet(
    onDismiss: () -> Unit,
    onSend: (question: String, options: List<String>, anonymous: Boolean, multiple: Boolean) -> Unit
) {
    val state = rememberModalBottomSheetState()
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var anonymous by remember { mutableStateOf(true) }
    var multiple by remember { mutableStateOf(false) }

    val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }
    val canSend = question.isNotBlank() && cleanOptions.size >= 2
    // Gate "Crea" on realtime connectivity — same rule as the chat send
    // button. Creating a poll is a network send; offline it would silently
    // fail, so we grey the button out until a usable network is back.
    val online by com.secondream.novagram.connectivity
        .ConnectivityState.isOnline.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.poll_title),
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))

            androidx.compose.material3.OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.poll_question_hint)) },
                shape = RoundedCornerShape(14.dp),
                maxLines = 3
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.poll_options_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))

            options.forEachIndexed { idx, value ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = value,
                        onValueChange = { options[idx] = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(stringResource(R.string.poll_option_hint, idx + 1))
                        },
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true
                    )
                    if (options.size > 2) {
                        IconButton(onClick = { options.removeAt(idx) }) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.X,
                                contentDescription = stringResource(R.string.poll_remove_option),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (options.size < 10) {
                androidx.compose.material3.TextButton(
                    onClick = { options.add("") }
                ) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.Plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.poll_add_option),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            PollToggleRow(
                label = stringResource(R.string.poll_multiple_answers),
                checked = multiple,
                onToggle = { multiple = it }
            )
            PollToggleRow(
                label = stringResource(R.string.poll_anonymous_toggle),
                checked = anonymous,
                onToggle = { anonymous = it }
            )

            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = { if (online) onSend(question.trim(), cleanOptions, anonymous, multiple) },
                enabled = canSend && online,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.poll_create))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PollToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggle(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onToggle
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MessageActionsSheet(
    message: TdApi.Message,
    isAdmin: Boolean,
    senderUserId: Long?,
    myUserId: Long?,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)?,
    /** Copy the public t.me link to this message. null hides the tile (only
     *  supergroups/channels have message links). */
    onCopyLink: (() -> Unit)?,
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
    onMuteAuthor: (mute: Boolean) -> Unit,
    onKickAuthor: (kick: Boolean) -> Unit
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
    // Server-side content-protection flags. When the chat (or this
    // specific message) prohibits forwarding / saving / copying we
    // hide the corresponding action tiles instead of surfacing
    // buttons that fail silently. The chat-level
    // [TdApi.Chat.hasProtectedContent] is the umbrella switch
    // ("Restrict saving content" in Telegram official); these
    // per-message booleans honour that PLUS any per-message
    // overrides (e.g. ephemeral / self-destruct media).
    var canForwardFromServer by remember(message.id) { mutableStateOf<Boolean?>(null) }
    var canSaveFromServer by remember(message.id) { mutableStateOf<Boolean?>(null) }
    var canCopyFromServer by remember(message.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(message.id) {
        runCatching {
            TdClient.getMessageProperties(message.chatId, message.id)
        }.onSuccess { props ->
            canRevokeFromServer = props.canBeDeletedForAllUsers
            canEditFromServer = props.canBeEdited
            canForwardFromServer = props.canBeForwarded
            canSaveFromServer = props.canBeSaved
            canCopyFromServer = props.canBeCopied
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
    // We grab the sender's *current* ChatMember status on sheet open so
    // the three admin tiles (mute, ban, owner-detection) all key off
    // the same fresh value. Since the sheet leaves composition between
    // long-press sessions, this LaunchedEffect re-runs every time the
    // sheet reopens — which gives us the "ri-tieni premuto e vedi
    // Smuta" toggle without any extra invalidation plumbing.
    var senderStatus by remember(message.id) {
        mutableStateOf<TdApi.ChatMemberStatus?>(null)
    }
    LaunchedEffect(message.id, senderUserId) {
        val uid = senderUserId
        if (uid != null && cachedChat?.type !is TdApi.ChatTypePrivate) {
            senderStatus = TdClient.getChatMemberStatus(message.chatId, uid)
        }
    }
    val senderIsOwner = senderStatus is TdApi.ChatMemberStatusCreator
    // Admin status — Telegram (and TDLib) does NOT allow an admin to
    // moderate (mute/ban) another admin: the call would return error
    // "USER_ADMIN_INVALID" or similar. Excluding the tiles upfront so
    // the user never sees a button that silently fails. Only the
    // creator/owner can demote-then-act on another admin, which is a
    // multi-step flow we don't surface in the long-press sheet.
    val senderIsAdmin = senderStatus is TdApi.ChatMemberStatusAdministrator
    // "Muted" means TDLib has them as Restricted with sending of basic
    // messages forbidden. We don't quibble about other Restricted
    // permission combos — anyone who can't post text is effectively
    // muted as far as our UI is concerned, so the toggle reads
    // correctly.
    val senderIsMuted = (senderStatus as? TdApi.ChatMemberStatusRestricted)
        ?.permissions?.canSendBasicMessages == false
    val senderIsBanned = senderStatus is TdApi.ChatMemberStatusBanned
    val showAdminActions = isAdmin &&
        cachedChat?.type !is TdApi.ChatTypePrivate &&
        senderUserId != null &&
        senderUserId != myUserId &&
        !senderIsOwner &&
        !senderIsAdmin

    val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(20.dp).navigationBarsPadding()) {
            // "Seen by" row — who has VIEWED my message in the group
            // (distinct from who reacted, which is rendered below). Only
            // shown for our own outgoing messages: TDLib returns an empty
            // viewer set for incoming messages, channels, large groups, or
            // messages outside the read-receipt window, so in all those
            // cases this block simply renders nothing. Sits ABOVE the
            // reactions so "who saw it" reads before "who reacted",
            // matching the user's request.
            if (message.isOutgoing) {
                val seenBy = remember(message.id) {
                    androidx.compose.runtime.mutableStateListOf<Long>()
                }
                // For 1-to-1 chats TDLib returns no viewer list, so we read
                // the chat's outbox-read marker instead: the message is "seen"
                // once lastReadOutboxMessageId catches up to it. null = group
                // (handled by seenBy) or not yet resolved.
                val isPrivateChat = cachedChat?.type is TdApi.ChatTypePrivate
                var privateRead by remember(message.id) { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(message.id) {
                    val ids = TdClient.getMessageViewers(message.chatId, message.id)
                    // Warm the user cache so the avatars resolve to real
                    // photos/initials instead of "?" placeholders.
                    ids.forEach { uid ->
                        if (TdClient.getCachedUser(uid) == null) {
                            runCatching { TdClient.getUser(uid) }
                        }
                    }
                    seenBy.clear()
                    seenBy.addAll(ids)
                    if (isPrivateChat) {
                        val c = TdClient.getCachedChat(message.chatId)
                        privateRead = (c?.lastReadOutboxMessageId ?: 0L) >= message.id
                    }
                }
                if (seenBy.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("👁", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        val shown = seenBy.take(6)
                        Row {
                            shown.forEachIndexed { i, uid ->
                                val user = remember(uid) { TdClient.getCachedUser(uid) }
                                val fallback = user?.let {
                                    listOfNotNull(
                                        it.firstName.takeIf { n -> n.isNotBlank() },
                                        it.lastName.takeIf { n -> n.isNotBlank() }
                                    ).joinToString(" ").ifBlank { "?" }
                                } ?: "?"
                                Box(
                                    modifier = Modifier
                                        .offset(x = (if (i == 0) 0 else -6 * i).dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(1.dp)
                                ) {
                                    com.secondream.novagram.ui.components.Avatar(
                                        file = user?.profilePhoto?.small,
                                        fallbackText = fallback,
                                        size = 22.dp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            seenBy.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else if (isPrivateChat && privateRead != null) {
                    // 1-to-1 read receipt: "Visualizzato" once the peer has
                    // read it, "Inviato" while it's still unread.
                    val read = privateRead == true
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (read) "👁" else "✓", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (read) R.string.receipt_seen else R.string.receipt_sent),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (read) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            // If this message already has reactions, list them at the
            // top so the user can see at a glance what others picked
            // and (with a tap) join or leave the same emoji. Same chip
            // styling as the inline ReactionStrip on the bubble — and
            // tapping fires onReact(emoji), which the parent routes
            // through addEmojiReaction / removeEmojiReaction based on
            // whether you've already reacted. Keeping the visual
            // language identical means a user moving between the
            // bubble strip and this sheet doesn't have to relearn
            // anything.
            val existingReactions = message.interactionInfo?.reactions?.reactions
                ?.mapNotNull { r ->
                    val emoji = (r.type as? TdApi.ReactionTypeEmoji)?.emoji
                    if (emoji != null) Triple(emoji, r.totalCount, r.isChosen) else null
                }.orEmpty()
            if (existingReactions.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    for ((emoji, count, chosen) in existingReactions) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onReact(emoji) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                            if (count > 1) {
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (chosen) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Viewers block: for each reaction emoji that has senders
                // attached (i.e. we know WHO reacted), show a one-line
                // row with the emoji + horizontal avatar stack of those
                // users. This replaces the old ReactionViewersSheet which
                // opened on long-press of a chip — viewers are now always
                // visible right here in the action sheet, no second tap.
                //
                // We fetch viewers on-demand via TDLib's GetMessageAddedReactions
                // because the embedded `recentSenderIds` only carries the
                // last 3 reactors. Coroutine scope is tied to the sheet's
                // composition: if the user dismisses the sheet before
                // GetMessageAddedReactions completes, the coroutine is
                // cancelled and we don't leak.
                val reactionViewers = remember(message.id) {
                    androidx.compose.runtime.mutableStateMapOf<String, List<TdApi.MessageSender>>()
                }
                LaunchedEffect(message.id) {
                    val reactionsObj = message.interactionInfo?.reactions ?: return@LaunchedEffect
                    for (r in reactionsObj.reactions) {
                        val emoji = (r.type as? TdApi.ReactionTypeEmoji)?.emoji ?: continue
                        val senders = TdClient.getMessageAddedReactions(
                            message.chatId, message.id, r.type, limit = 12
                        )
                        if (senders.isNotEmpty()) reactionViewers[emoji] = senders
                    }
                }
                if (reactionViewers.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        for ((emoji, senders) in reactionViewers) {
                            if (senders.isEmpty()) continue
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                val shown = senders.take(6)
                                val remaining = senders.size - shown.size
                                // Overlapping avatar stack. -6dp horizontal
                                // offset per index slides successive avatars
                                // partially under the previous one, mirroring
                                // the way Telegram packs reactor lists. We
                                // fetch the user on the fly via getCachedUser
                                // (synchronous, no IO).
                                Row {
                                    shown.forEachIndexed { i, s ->
                                        val userId = (s as? TdApi.MessageSenderUser)?.userId
                                        val user = remember(userId) {
                                            userId?.let { TdClient.getCachedUser(it) }
                                        }
                                        val fallback = user?.let {
                                            listOfNotNull(
                                                it.firstName.takeIf { n -> n.isNotBlank() },
                                                it.lastName.takeIf { n -> n.isNotBlank() }
                                            ).joinToString(" ").ifBlank { "?" }
                                        } ?: "?"
                                        Box(
                                            modifier = Modifier
                                                .offset(x = (if (i == 0) 0 else -6 * i).dp)
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(1.dp)
                                        ) {
                                            com.secondream.novagram.ui.components.Avatar(
                                                file = user?.profilePhoto?.small,
                                                fallbackText = fallback,
                                                size = 22.dp
                                            )
                                        }
                                    }
                                }
                                if (remaining > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "+$remaining",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

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
            val stickerScope = androidx.compose.runtime.rememberCoroutineScope()
            // Eugenio asked for a tile grid instead of the linear list of
            // rows — faster to scan, more visual, fewer taps to think
            // about. Each tile is an icon + label in a soft-coloured
            // rounded square. Destructive actions go bottom-right with
            // the error tint so they read as separate from neutral ops.
            val tiles = buildList<ActionTile> {
                val editAllowed = canEditFromServer ?: message.isOutgoing
                // A sticker carries its owning pack id; offer to install that
                // pack right from the long-press menu (idempotent).
                val stickerSetId = (message.content as? TdApi.MessageSticker)?.sticker?.setId ?: 0L
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
                if (stickerSetId != 0L) {
                    add(ActionTile(
                        stringResource(R.string.action_add_sticker_pack),
                        com.secondream.novagram.ui.icons.PhosphorIcons.Smiley,
                        {
                            onDismiss()
                            stickerScope.launch {
                                TdClient.installStickerSet(stickerSetId)
                                com.secondream.novagram.ui.components.NovaSnackbar.show(
                                    R.string.snack_sticker_pack_added,
                                    com.secondream.novagram.ui.icons.PhosphorIcons.Smiley
                                )
                            }
                        }
                    ))
                }
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
                // Protection-aware: hide Forward when TDLib says we
                // can't (chat hasProtectedContent OR message is
                // self-destruct / sensitive). Default to TRUE while
                // the MessageProperties round-trip is pending so we
                // don't flash the tile off-then-on for unrestricted
                // chats; for chats whose `hasProtectedContent` flag
                // is already cached we pre-fail the gate.
                val chatProtected = cachedChat?.hasProtectedContent == true
                val canForward = canForwardFromServer ?: !chatProtected
                if (canForward) {
                    add(ActionTile(stringResource(R.string.action_forward),
                        com.secondream.novagram.ui.icons.PhosphorIcons.Forward, onForward))
                }
                val canCopy = canCopyFromServer ?: !chatProtected
                if (onCopy != null && canCopy) {
                    add(ActionTile(stringResource(R.string.action_copy),
                        com.secondream.novagram.ui.icons.PhosphorIcons.Copy, onCopy))
                }
                if (onCopyLink != null) {
                    add(ActionTile(stringResource(R.string.action_copy_link),
                        com.secondream.novagram.ui.icons.PhosphorIcons.At, onCopyLink))
                }
                val canSave = canSaveFromServer ?: !chatProtected
                if (onSaveToDownloads != null && canSave) {
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
                    // Mute / unmute toggle based on the sender's
                    // *current* TDLib status (fetched on sheet open).
                    // Long-pressing a message you've just muted reopens
                    // the sheet with this tile showing "Smuta" — same
                    // tap removes the restriction. Real-time toggle,
                    // no leaving the chat required.
                    add(ActionTile(
                        stringResource(
                            if (senderIsMuted) R.string.action_unmute_author
                            else R.string.action_mute_author
                        ),
                        com.secondream.novagram.ui.icons.PhosphorIcons.SpeakerSlash,
                        { onMuteAuthor(!senderIsMuted) }
                    ))
                    // Same toggle pattern for ban: tile flips between
                    // "Espelli autore" and "Sblocca autore" against the
                    // current ChatMemberStatusBanned. Destructive tint
                    // only on the BAN action — unban is restorative.
                    add(ActionTile(
                        stringResource(
                            if (senderIsBanned) R.string.action_unkick_author
                            else R.string.action_kick_author
                        ),
                        com.secondream.novagram.ui.icons.PhosphorIcons.UserMinus,
                        { onKickAuthor(!senderIsBanned) },
                        destructive = !senderIsBanned
                    ))
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

/** @see com.secondream.novagram.ui.components.ActionTile */
private typealias ActionTile = com.secondream.novagram.ui.components.ActionTile

/** @see com.secondream.novagram.ui.components.ActionTileButton */
@Composable
private fun ActionTileButton(
    tile: ActionTile,
    modifier: Modifier = Modifier
) = com.secondream.novagram.ui.components.ActionTileButton(tile, modifier)

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
            is TdApi.MessageAnimatedEmoji -> c.emoji
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
    commands: List<com.secondream.novagram.td.BotCommandItem>,
    onPick: (com.secondream.novagram.td.BotCommandItem) -> Unit
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "/${cmd.command}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Which bot this command belongs to — so in a group
                        // with several bots it's clear who'll answer.
                        if (!cmd.botUsername.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "@${cmd.botUsername}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
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
 * Sits between the reply banner and the InputBar while media is pending
 * dispatch. Renders thumbnails in a horizontal scroll row — one tile per
 * pending item — each with its own small X button to remove individually.
 * A trailing "Annulla tutto" button clears the entire batch.
 *
 * For a single item we keep the legacy left-tile + filename + cancel
 * layout because the row of tiles would look lonely with one tile and
 * 90% empty space.
 */
@Composable
private fun PendingMediaPreview(
    media: List<PendingMediaItem>,
    onCancelAll: () -> Unit,
    onRemove: (Int) -> Unit
) {
    if (media.isEmpty()) return
    if (media.size == 1) {
        val item = media.first()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PendingMediaThumb(item, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (item.kind) {
                        PendingMediaKind.Photo -> stringResource(R.string.pending_media_photo)
                        PendingMediaKind.Video -> stringResource(R.string.pending_media_video)
                        PendingMediaKind.Document -> stringResource(R.string.pending_media_document)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancelAll) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        return
    }
    // Multi-item: horizontal row of thumbnails. Each tile has a small X
    // bubble in the top-right corner to remove just that item; the
    // trailing X (full-row) clears everything. Caption stays one for
    // the whole group (Telegram convention).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                pluralPendingMediaSummary(media),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onCancelAll) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(media.size, key = { idx -> media[idx].file.absolutePath }) { idx ->
                Box {
                    PendingMediaThumb(media[idx], size = 64.dp)
                    // Per-item remove badge. 20dp circle in the top-right
                    // corner with a small X — far enough above the tile
                    // that touch slop won't accidentally hit the play
                    // icon (videos) below.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onRemove(idx) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.X,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single-tile thumbnail render shared by both PendingMediaPreview
 * branches (single + multi). Pulled out so the multi-row stays
 * compact at the call site.
 */
@Composable
private fun PendingMediaThumb(item: PendingMediaItem, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (item.kind) {
            PendingMediaKind.Photo -> coil.compose.AsyncImage(
                model = item.file,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            PendingMediaKind.Video -> {
                coil.compose.AsyncImage(
                    model = item.file,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.Play,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(if (size >= 56.dp) 28.dp else 22.dp)
                )
            }
            PendingMediaKind.Document -> Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.FileText,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (size >= 56.dp) 28.dp else 22.dp)
            )
        }
    }
}

/**
 * Summary string for the multi-item pending header: "5 elementi",
 * "3 foto", "2 video", "4 foto e 1 video". Picks the simplest phrasing
 * that fits the actual mix.
 */
@Composable
private fun pluralPendingMediaSummary(media: List<PendingMediaItem>): String {
    val photos = media.count { it.kind == PendingMediaKind.Photo }
    val videos = media.count { it.kind == PendingMediaKind.Video }
    val docs = media.count { it.kind == PendingMediaKind.Document }
    val parts = mutableListOf<String>()
    if (photos > 0) parts.add(
        if (photos == 1) stringResource(R.string.pending_media_count_photo_one)
        else stringResource(R.string.pending_media_count_photo_many, photos)
    )
    if (videos > 0) parts.add(
        if (videos == 1) stringResource(R.string.pending_media_count_video_one)
        else stringResource(R.string.pending_media_count_video_many, videos)
    )
    if (docs > 0) parts.add(
        if (docs == 1) stringResource(R.string.pending_media_count_doc_one)
        else stringResource(R.string.pending_media_count_doc_many, docs)
    )
    return parts.joinToString(" + ")
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

/**
 * Result of an inline-callback round-trip surfaced to the user as a
 * transient banner. `isAlert` mirrors TDLib's CallbackQueryAnswer
 * flag — true bumps the display time and could be promoted to a modal
 * dialog if we add one later. Empty `text` is swallowed (some bots
 * answer the callback without a message, just an action).
 */
internal data class InlineButtonResult(val text: String, val isAlert: Boolean)

/**
 * Route an inline-keyboard button tap to the appropriate side-effect.
 *
 *   Callback → round-trip through the bot, show its returned text as
 *     a banner. Shows a spinner on the button until the answer lands.
 *   Url / LoginUrl → forward to openTelegramLink (which falls back to
 *     a system Intent for non-t.me URLs).
 *   SwitchInline → not handled yet (inline mode is a complex feature).
 *   Buy / Game / User / Pay → telegram-specific surfaces we don't
 *     support; surface a "non supportato" banner so the user knows
 *     why the tap didn't do anything.
 *
 * The signature is deliberately verbose so the call site reads as
 * a transactional intent: who's calling, what payload, where to
 * report results. Wrapping the side-effect dispatch in a top-level
 * function (instead of a method on ChatScreen) keeps the dispatch
 * logic out of the giant ChatScreen body and lets us unit-test it
 * if we ever add tests.
 */
internal fun handleInlineKeyboardButton(
    chatId: Long,
    message: TdApi.Message,
    button: TdApi.InlineKeyboardButton,
    buttonKey: String,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    setPendingKey: (String?) -> Unit,
    showCallbackResult: (String, Boolean) -> Unit,
    openLink: (android.net.Uri) -> Unit
) {
    when (val t = button.type) {
        is TdApi.InlineKeyboardButtonTypeCallback -> {
            setPendingKey(buttonKey)
            scope.launch {
                try {
                    val answer = runCatching {
                        TdClient.sendCallbackQuery(chatId, message.id, t.data)
                    }.getOrNull()
                    if (answer != null) {
                        val txt = answer.text.orEmpty()
                        if (answer.url.isNotEmpty()) {
                            // Some bots respond with a follow-up URL —
                            // open it (could be a payment, sign-in, etc.).
                            runCatching {
                                openLink(android.net.Uri.parse(answer.url))
                            }
                        }
                        if (txt.isNotBlank()) {
                            showCallbackResult(txt, answer.showAlert)
                        }
                    }
                } finally {
                    setPendingKey(null)
                }
            }
        }
        is TdApi.InlineKeyboardButtonTypeUrl -> {
            runCatching { openLink(android.net.Uri.parse(t.url)) }
        }
        is TdApi.InlineKeyboardButtonTypeLoginUrl -> {
            // Login-URL is meant to round-trip through getLoginUrl +
            // user confirmation; for simplicity we open the URL
            // directly (matches what older Telegram clients did before
            // the consent dialog was added). The bot's server sees a
            // missing tg_auth and prompts the user via a regular page.
            runCatching { openLink(android.net.Uri.parse(t.url)) }
        }
        else -> {
            // Buy / Game / User / SwitchInline / Pay / CallbackGame /
            // CallbackWithPassword — surface a soft banner so the user
            // knows their tap was received but isn't actionable in
            // Novagram yet.
            showCallbackResult(
                context.getString(com.secondream.novagram.R.string.inline_button_unsupported),
                false
            )
        }
    }
}
