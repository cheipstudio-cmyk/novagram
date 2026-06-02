package com.secondream.novagram.td

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.secondream.novagram.BuildConfig
import com.secondream.novagram.settings.AppSettings
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TdClient {
    private const val TAG = "TdClient"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: Client? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    /**
     * The signed-in user's id, cached synchronously so UI that needs to
     * recognise the "self" chat (Saved Messages) can do so on the first
     * composition without awaiting GetMe. Populated from the my_id
     * UpdateOption (fires early in the session) and re-affirmed by getMe().
     * 0 until known.
     */
    @Volatile
    var cachedMyUserId: Long = 0L
        private set

    private val _newMessages = MutableSharedFlow<TdApi.Message>(extraBufferCapacity = 32)
    val newMessages = _newMessages.asSharedFlow()

    private val _chatUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val chatUpdates = _chatUpdates.asSharedFlow()

    /**
     * Active "X is doing Y" actions per chat, keyed by sender id. Mirrors
     * TDLib's UpdateChatAction stream: peer starts typing → entry added,
     * peer sends/stops → entry removed (or auto-expires after ~6s if we
     * miss the cancel event, which happens when the peer closes their
     * client mid-typing). ChatScreen subscribes to this to render the
     * typing badge above the input bar.
     *
     * Inner map is sender-id → action so we can show multiple users
     * typing simultaneously in a group ("Alice, Bob stanno scrivendo")
     * with their avatars. We strip ChatActionCancel before storing —
     * its presence in the stream is a "remove me from the map" signal.
     */
    private val _chatActions = MutableStateFlow<Map<Long, Map<Long, TdApi.ChatAction>>>(emptyMap())
    val chatActions: StateFlow<Map<Long, Map<Long, TdApi.ChatAction>>> = _chatActions.asStateFlow()

    // Set of user ids currently online (UserStatusOnline). Fed by
    // UpdateUserStatus and seeded from UpdateUser, so the chat-list rows can
    // show the green online dot reactively, without each row polling.
    private val _onlineUsers = MutableStateFlow<Set<Long>>(emptySet())
    val onlineUsers: StateFlow<Set<Long>> = _onlineUsers.asStateFlow()

    /**
     * Per (chatId, senderId) auto-expiry deadline in epoch-millis. TDLib
     * re-sends UpdateChatAction every ~5s while the peer is still
     * typing; if we don't see another update within ~6s the entry is
     * considered stale and we drop it. Without this safety net a peer
     * who quits typing without sending (closed app, lost connection)
     * would leave their "is typing" entry stuck in the map forever
     * since the cancel event never arrived.
     */
    private val chatActionDeadlines = mutableMapOf<Pair<Long, Long>, Long>()

    /**
     * Last known SERVER-SIDE mention count per chat. We need this
     * separately from chatCache[chatId].unreadMentionCount because the
     * latter is the OPTIMISTIC client value (mutated by
     * decrementChatMentionCount when the user taps the chip). When
     * TDLib echoes UpdateChatUnreadMentionCount we compare against
     * lastKnownServerMentionCount to tell apart three cases:
     *  - echo == previous server value → server didn't decrement
     *    (our viewMessages was a no-op for server). Keep our
     *    optimistic client decrements.
     *  - echo  < previous server value → server caught up. Use the
     *    echo value directly.
     *  - echo  > previous server value → new mention(s) arrived. Add
     *    the positive delta to the client count.
     *
     * Without this tracking the chip flashes briefly to N-1 (our local
     * decrement) and reverts to N (the echo over-writes) — exactly
     * Eugenio's "il counter non decrementa" complaint.
     */
    private val lastKnownServerMentionCount = mutableMapOf<Long, Int>()
    private val lastKnownServerReactionCount = mutableMapOf<Long, Int>()

    /**
     * Emits whenever TDLib notifies the deletion of one or more messages
     * (permanent deletes only, transient updates ignored). Consumers should
     * remove the matching ids from their in-memory message lists.
     */
    private val _deletedMessages = MutableSharedFlow<DeleteEvent>(extraBufferCapacity = 32)
    val deletedMessages = _deletedMessages.asSharedFlow()

    /**
     * Emits every UpdateFile so message bubbles can react to download
     * progress without us re-fetching whole chat histories.
     */
    private val _fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 64)
    val fileUpdates = _fileUpdates.asSharedFlow()

    /**
     * Emits whenever the content of a specific message is edited or replaced
     * (e.g. a photo finished uploading and now has a server file id).
     */
    private val _messageContentUpdates = MutableSharedFlow<MessageContentUpdate>(extraBufferCapacity = 32)
    val messageContentUpdates = _messageContentUpdates.asSharedFlow()

    /**
     * Emits the updated [TdApi.Poll] whenever TDLib reports its tallies
     * changed (someone voted, the poll was closed, …). UpdatePoll is keyed
     * by poll.id only — it carries no chat/message — so the chat screen maps
     * it back onto the bubble that holds a MessagePoll with the same poll id.
     */
    private val _pollUpdates = MutableSharedFlow<TdApi.Poll>(extraBufferCapacity = 32)
    val pollUpdates = _pollUpdates.asSharedFlow()

    /**
     * Emits the updated [TdApi.SecretChat] whenever its handshake state
     * changes. A freshly created secret chat starts in
     * SecretChatStatePending until the peer's device comes online and
     * completes the key exchange; the chat screen watches this to show a
     * "waiting for the other person" notice and to keep the composer
     * disabled until the state flips to Ready (TDLib rejects sends before
     * then anyway).
     */
    private val _secretChatUpdates = MutableSharedFlow<TdApi.SecretChat>(extraBufferCapacity = 16)
    val secretChatUpdates = _secretChatUpdates.asSharedFlow()

    /**
     * Emits when TDLib reports a message's editDate / reply markup changed.
     * Distinct from messageContentUpdates because TDLib emits these on
     * separate updates: UpdateMessageContent for the body, UpdateMessageEdited
     * for the metadata. Both need to be applied for the bubble to fully
     * reflect an edit (content swap + "modificato" tag rendering, which
     * keys off editDate > 0).
     */
    private val _messageEdited = MutableSharedFlow<MessageEditedUpdate>(extraBufferCapacity = 32)
    val messageEdited = _messageEdited.asSharedFlow()

    /**
     * Emitted when TDLib confirms (or rejects) a message we sent. Carries
     * the original local-only id we put into the list at send time plus
     * the now-authoritative message. ChatScreen uses both to swap the
     * placeholder bubble (sendingState=Pending, "⏱" tick) for the real
     * one with sendingState=null and a proper id — without this the tick
     * stays on the local message forever, only flipping to a checkmark
     * after the user backs out and reopens the chat (which forces a full
     * history reload).
     */
    data class MessageSendUpdate(
        val oldMessageId: Long,
        val newMessage: TdApi.Message,
        val failed: Boolean
    )
    private val _messageSendUpdates = MutableSharedFlow<MessageSendUpdate>(extraBufferCapacity = 16)
    val messageSendUpdates = _messageSendUpdates.asSharedFlow()

    /**
     * Emitted when a message's interaction info (reactions, view count,
     * forward count) changes. ChatScreen listens to this to swap in the
     * fresh `interactionInfo` on the matching cached message so reactions
     * appear without re-fetching the history.
     */
    data class InteractionInfoUpdate(val chatId: Long, val messageId: Long, val info: TdApi.MessageInteractionInfo?)
    private val _interactionInfoUpdates = MutableSharedFlow<InteractionInfoUpdate>(extraBufferCapacity = 32)
    val interactionInfoUpdates = _interactionInfoUpdates.asSharedFlow()

    private val chatCache = mutableMapOf<Long, TdApi.Chat>()
    private val userCache = mutableMapOf<Long, TdApi.User>()
    private val supergroupCache = mutableMapOf<Long, TdApi.Supergroup>()

    // Per-scope mute / notification settings. Telegram bundles every chat
    // into one of three scopes; muting "all groups" or "all channels"
    // stores the value here rather than on each chat. Updated by the
    // UpdateScopeNotificationSettings handler. Used by [isChatMuted] so
    // NotificationHelper respects scope-level mutes for chats whose
    // per-chat settings say useDefaultMuteFor=true.
    @Volatile private var scopePrivate: TdApi.ScopeNotificationSettings? = null
    @Volatile private var scopeGroup: TdApi.ScopeNotificationSettings? = null
    @Volatile private var scopeChannel: TdApi.ScopeNotificationSettings? = null

    /**
     * Resolve the effective mute state for [chat] taking BOTH per-chat
     * and scope settings into account. Returns true if any notification
     * for this chat should be suppressed by mute (callers still need to
     * check personal-ping bypasses on top of this).
     *
     * Semantics per TDLib: ChatNotificationSettings.useDefaultMuteFor
     * means "fall back to my scope" — when true, the chat's own muteFor
     * is meaningless and we must read from the scope. When false, the
     * per-chat muteFor is authoritative.
     */
    fun isChatMuted(chat: TdApi.Chat?): Boolean {
        // SAFER DEFAULT for notifications: when we lack info (chat
        // not yet in cache OR notificationSettings missing), prefer
        // to SUPPRESS the notification rather than let it leak
        // through. Eugenio's complaint "ho ricevuto notifiche da
        // chat private silenziate" was rooted in the OPPOSITE
        // defaults here: a chat that hadn't been fully synced from
        // TDLib (or whose per-chat NotificationSettings field landed
        // null during the brief cache-fill window) was treated as
        // "not muted", and any incoming message slipped a heads-up
        // through even though the chat was muted server-side. The
        // worst-case downside of the safer default is a
        // momentarily-missed legitimate notification right at app
        // startup — much lower cost than leaking notifications from
        // chats the user explicitly silenced.
        if (chat == null) return true
        val per = chat.notificationSettings ?: return true
        if (!per.useDefaultMuteFor) return per.muteFor > 0
        // Fall back to the right scope for this chat type.
        val scope = when (chat.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> scopePrivate
            is TdApi.ChatTypeBasicGroup -> scopeGroup
            is TdApi.ChatTypeSupergroup -> {
                val sg = chat.type as TdApi.ChatTypeSupergroup
                if (sg.isChannel) scopeChannel else scopeGroup
            }
            else -> null
        }
        // Same SAFER DEFAULT for the scope: when scope is null (not
        // yet received from TDLib — the brief window between
        // auth-ready and the first UpdateScopeNotificationSettings
        // push) we prefer to SKIP the notification.
        if (scope == null) return true
        return scope.muteFor > 0
    }

    /**
     * Variant of [isChatMuted] used by the chat-list tab badges. The
     * difference is the scope-null default: for NOTIFICATIONS we err
     * on the side of muting (better to miss one push than to leak a
     * notification the user thought they'd silenced), but for BADGES
     * we err the other way — assume NOT muted on uncertainty so the
     * badge stays visible during the brief startup window before
     * UpdateScopeNotificationSettings arrives. A momentarily-visible
     * badge is cosmetic; a momentarily-hidden one looks broken.
     */
    fun isChatMutedForBadge(chat: TdApi.Chat?): Boolean {
        if (chat == null) return false
        val per = chat.notificationSettings ?: return false
        if (!per.useDefaultMuteFor) return per.muteFor > 0
        val scope = when (chat.type) {
            is TdApi.ChatTypePrivate, is TdApi.ChatTypeSecret -> scopePrivate
            is TdApi.ChatTypeBasicGroup -> scopeGroup
            is TdApi.ChatTypeSupergroup -> {
                val sg = chat.type as TdApi.ChatTypeSupergroup
                if (sg.isChannel) scopeChannel else scopeGroup
            }
            else -> null
        }
        return (scope?.muteFor ?: 0) > 0
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        Client.setLogMessageHandler(0) { verbosity, message ->
            if (verbosity == 0) Log.e(TAG, "TDLib fatal: $message")
        }
        try {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
        } catch (e: Throwable) {
            Log.w(TAG, "Could not set log verbosity: ${e.message}")
        }

        // Chat-action expiry sweeper. Walks the deadline map roughly
        // every second and drops sender entries whose last
        // UpdateChatAction landed more than 6.5s ago. This is the
        // safety net for peers who quit typing without us seeing a
        // ChatActionCancel (closed app, crash, lost connectivity). The
        // sweeper runs forever — it's a few map lookups per tick and
        // a no-op when nothing is expiring.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = System.currentTimeMillis()
                val expired = chatActionDeadlines
                    .filterValues { it < now }
                    .keys
                    .toList()
                if (expired.isNotEmpty()) {
                    expired.forEach { chatActionDeadlines.remove(it) }
                    val current = _chatActions.value.toMutableMap()
                    var changed = false
                    expired.forEach { (chatId, senderId) ->
                        val perChat = current[chatId]?.toMutableMap() ?: return@forEach
                        if (perChat.remove(senderId) != null) {
                            changed = true
                            if (perChat.isEmpty()) current.remove(chatId)
                            else current[chatId] = perChat
                        }
                    }
                    if (changed) _chatActions.value = current
                }
            }
        }

        scope.launch {
            val cfg = AppSettings.apiConfig.first()
            when {
                cfg.apiId != 0 && cfg.apiHash.isNotBlank() -> {
                    // Already configured (user opened the app before)
                    startClient()
                }
                BuildConfig.TG_API_ID != 0 && BuildConfig.TG_API_HASH.isNotBlank() -> {
                    // Baked-in credentials from CI: copy to DataStore and proceed.
                    // From now on the app behaves as if the user had entered them.
                    AppSettings.setApiConfig(BuildConfig.TG_API_ID, BuildConfig.TG_API_HASH)
                    startClient()
                }
                else -> {
                    // No credentials anywhere: ask the user (debug/dev fallback).
                    _authState.value = AuthState.NeedApiConfig
                }
            }
        }
    }

    fun startClient() {
        if (client != null) return
        client = Client.create(::onUpdate, ::onError, ::onError)
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> handleAuthState(obj.authorizationState)
            is TdApi.UpdateOption -> {
                // TDLib emits my_id very early in the session (well before
                // GetMe resolves). Caching it lets the chat list pin Saved
                // Messages to the top on the FIRST frame instead of starting
                // it un-pinned and visibly jumping it up once GetMe returns
                // — the "saltello al rientro" Eugenio noticed.
                if (obj.name == "my_id") {
                    (obj.value as? TdApi.OptionValueInteger)?.let {
                        cachedMyUserId = it.value
                    }
                }
            }
            is TdApi.UpdateNewMessage -> {
                scope.launch { _newMessages.emit(obj.message) }
            }
            is TdApi.UpdateNewChat -> {
                chatCache[obj.chat.id] = obj.chat
                // Initialize last-known-server tracking. The chat
                // ships with its current server-side mention/reaction
                // count; we mirror that into our tracker so subsequent
                // UpdateChatUnreadMentionCount echoes can be compared
                // against this baseline.
                lastKnownServerMentionCount[obj.chat.id] = obj.chat.unreadMentionCount
                lastKnownServerReactionCount[obj.chat.id] = obj.chat.unreadReactionCount
                // Eagerly warm the avatar thumb so a later notification can
                // render the photo from disk INSTANTLY instead of blocking on
                // a synchronous download at notify time — the root cause of
                // the "avatar never shows in notifications" bug. Fire-and-
                // forget, a few KB, skipped when already downloaded.
                obj.chat.photo?.small?.let { f ->
                    if (f.local?.isDownloadingCompleted != true && f.id != 0) {
                        scope.launch { runCatching { downloadFile(f.id) } }
                    }
                }
                refreshChats()
            }
            is TdApi.UpdateChatLastMessage -> {
                chatCache[obj.chatId]?.let { chat ->
                    chat.lastMessage = obj.lastMessage
                    // The positions array carried by this update supersedes the
                    // chat's previous positions whenever TDLib decides the new
                    // message changes the ordering. Without this assignment the
                    // chat list never re-orders to put the chat with the freshest
                    // message on top.
                    if (obj.positions.isNotEmpty()) chat.positions = obj.positions
                }
                refreshChats()
            }
            is TdApi.UpdateChatPosition -> {
                chatCache[obj.chatId]?.let { chat ->
                    val positions = chat.positions.toMutableList()
                    positions.removeAll { it.list.constructor == obj.position.list.constructor }
                    if (obj.position.order != 0L) positions.add(obj.position)
                    chat.positions = positions.toTypedArray()
                }
                // TDLib confirmed the position change. If the chat was in
                // our optimistic "deleted" set AND the echoed position is
                // order=0 on Main, the delete went through — we can
                // safely clear the hidden flag now (the chat now has no
                // Main position anyway, so refreshChats will exclude it
                // naturally going forward, and any future restore by
                // another device will re-add it cleanly).
                if (obj.position.list is TdApi.ChatListMain && obj.position.order == 0L) {
                    hiddenChats.remove(obj.chatId)
                }
                refreshChats()
            }
            is TdApi.UpdateChatTitle -> {
                chatCache[obj.chatId]?.title = obj.title
                refreshChats()
            }
            is TdApi.UpdateChatPermissions -> {
                // Keep cached default permissions fresh so the group "Permessi"
                // dialog shows the real values right after a save (previously it
                // only updated on app restart).
                chatCache[obj.chatId]?.permissions = obj.permissions
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateChatReadInbox -> {
                chatCache[obj.chatId]?.unreadCount = obj.unreadCount
                refreshChats()
                // If the unread count just hit zero, drop any active
                // notification for the chat. Covers two cases at once:
                // (a) we ourselves just opened the chat → openChat
                // fires this update once TDLib has marked-read, and
                // (b) another device the user owns read the messages →
                // the notification on THIS device is now stale and
                // should go too. The chat-screen entrypoint also
                // calls dismissForChat directly for instant clear on
                // local open; this handler is the catch-all for
                // cross-device and openChat-pending paths.
                if (obj.unreadCount == 0) {
                    com.secondream.novagram.notifications.NotificationHelper
                        .dismissForChat(obj.chatId)
                }
            }
            is TdApi.UpdateChatReadOutbox -> {
                chatCache[obj.chatId]?.lastReadOutboxMessageId = obj.lastReadOutboxMessageId
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateChatIsMarkedAsUnread -> {
                // The "Mark as unread" flag is server-side and independent
                // of the unreadCount — a chat with 0 unread messages can
                // still carry isMarkedAsUnread=true after the user
                // explicitly marked it on another device. Without this
                // handler the chat row would render no badge even though
                // the server says "unread" — exactly the gap audit #3
                // called out.
                chatCache[obj.chatId]?.isMarkedAsUnread = obj.isMarkedAsUnread
                refreshChats()
            }
            is TdApi.UpdateChatUnreadMentionCount -> {
                // Reconcile server echo with our optimistic client
                // decrements. See lastKnownServerMentionCount kdoc for
                // the three-case logic.
                val previousServer = lastKnownServerMentionCount[obj.chatId] ?: 0
                val newServer = obj.unreadMentionCount
                val currentClient = chatCache[obj.chatId]?.unreadMentionCount ?: 0
                val finalValue = if (newServer > previousServer) {
                    // New mention(s) arrived server-side. Add the
                    // delta to whatever the client currently has, so
                    // the new mention is reflected even if our
                    // optimistic decrements made currentClient lower
                    // than previousServer.
                    currentClient + (newServer - previousServer)
                } else {
                    // Server caught up (or no change). The echo's
                    // value is the new truth.
                    newServer
                }
                chatCache[obj.chatId]?.unreadMentionCount = finalValue.coerceAtLeast(0)
                lastKnownServerMentionCount[obj.chatId] = newServer
                refreshChats()
                // Also notify per-chat observers (the in-chat mention
                // chip in ChatScreen listens on chatUpdates to know
                // when to hide itself after readAllChatMentions). Without
                // this emit, the cache updates but the chip never
                // re-renders and stays stuck.
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateChatUnreadReactionCount -> {
                val previousServer = lastKnownServerReactionCount[obj.chatId] ?: 0
                val newServer = obj.unreadReactionCount
                val currentClient = chatCache[obj.chatId]?.unreadReactionCount ?: 0
                val finalValue = if (newServer > previousServer) {
                    currentClient + (newServer - previousServer)
                } else {
                    newServer
                }
                chatCache[obj.chatId]?.unreadReactionCount = finalValue.coerceAtLeast(0)
                lastKnownServerReactionCount[obj.chatId] = newServer
                refreshChats()
                // Same per-chat observer notify as mention count above.
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateChatNotificationSettings -> {
                // After setChatMuted(...) TDLib echoes the new settings back
                // via this update. Without this handler the cached chat keeps
                // showing the old muteFor and "Silenzia" in the action sheet
                // never flips to "Riattiva" even though the mute actually took.
                chatCache[obj.chatId]?.notificationSettings = obj.notificationSettings
                refreshChats()
                // Wake per-chat observers (in-chat title-bar bell icon,
                // mute toggle label in the action sheet) — they listen
                // on chatUpdates rather than on the chat-list flow.
                // Without this emit, muting from another device or via
                // the action sheet doesn't flip the in-chat bell icon
                // until the user leaves and re-enters the chat.
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateChatAction -> {
                // Peer is typing / recording voice / picking sticker / etc.
                // We only surface a "sta scrivendo" badge for ChatActionTyping
                // — the other action variants (UploadingDocument, etc.) are
                // ignored for now because stock Telegram on Android shows the
                // same generic indicator regardless. The interesting bit is
                // multiplexing per-sender so groups show "X, Y stanno
                // scrivendo" with both avatars, not a single anonymous chip.
                val senderId = when (val s = obj.senderId) {
                    is TdApi.MessageSenderUser -> s.userId
                    is TdApi.MessageSenderChat -> -s.chatId  // namespace away from user ids
                    else -> 0L
                }
                if (senderId != 0L) {
                    val current = _chatActions.value.toMutableMap()
                    val perChat = current[obj.chatId]?.toMutableMap() ?: mutableMapOf()
                    val action = obj.action
                    if (action is TdApi.ChatActionCancel) {
                        perChat.remove(senderId)
                        chatActionDeadlines.remove(obj.chatId to senderId)
                    } else {
                        perChat[senderId] = action
                        // Re-arm the auto-expiry deadline. TDLib resends
                        // every ~5s while typing continues, so 6.5s gives
                        // us a comfortable margin without leaving stale
                        // entries lingering visibly.
                        chatActionDeadlines[obj.chatId to senderId] =
                            System.currentTimeMillis() + 6500
                    }
                    if (perChat.isEmpty()) current.remove(obj.chatId)
                    else current[obj.chatId] = perChat
                    _chatActions.value = current
                }
            }
            is TdApi.UpdateChatDraftMessage -> {
                // Drafts sync across devices: editing on Desktop pushes one of
                // these to us. Mirror into the cache so the next chat-list
                // rebuild shows the right preview and so ChatScreen reads
                // the latest draft when it opens.
                chatCache[obj.chatId]?.draftMessage = obj.draftMessage
                refreshChats()
            }
            is TdApi.UpdateScopeNotificationSettings -> {
                // Telegram exposes THREE mute scopes: Private chats, basic
                // groups + supergroups, and channels. A user who mutes "all
                // groups" from settings sets muteFor on the Group scope,
                // not on every group's per-chat settings — each affected
                // chat's ChatNotificationSettings keeps muteFor=0 with
                // useDefaultMuteFor=true, and the scope holds the actual
                // value. Without caching these, NotificationHelper reads
                // chat.notificationSettings.muteFor (=0) and fires heads-up
                // for chats the user muted at scope level — exactly the
                // bug Eugenio hit.
                when (obj.scope) {
                    is TdApi.NotificationSettingsScopePrivateChats ->
                        scopePrivate = obj.notificationSettings
                    is TdApi.NotificationSettingsScopeGroupChats ->
                        scopeGroup = obj.notificationSettings
                    is TdApi.NotificationSettingsScopeChannelChats ->
                        scopeChannel = obj.notificationSettings
                }
            }
            is TdApi.UpdateUser -> {
                userCache[obj.user.id] = obj.user
                _onlineUsers.value =
                    if (obj.user.status is TdApi.UserStatusOnline) _onlineUsers.value + obj.user.id
                    else _onlineUsers.value - obj.user.id
                // Same avatar pre-warm as chats: lets group-message
                // notifications render the SENDER's photo from disk instead
                // of blocking on a download at notify time.
                obj.user.profilePhoto?.small?.let { f ->
                    if (f.local?.isDownloadingCompleted != true && f.id != 0) {
                        scope.launch { runCatching { downloadFile(f.id) } }
                    }
                }
            }
            is TdApi.UpdateUserStatus -> {
                // Keep the cached user's status fresh AND maintain the online
                // id set the chat list observes for its green dot.
                userCache[obj.userId]?.status = obj.status
                _onlineUsers.value =
                    if (obj.status is TdApi.UserStatusOnline) _onlineUsers.value + obj.userId
                    else _onlineUsers.value - obj.userId
            }
            is TdApi.UpdateSupergroup -> {
                // Cache the supergroup record so we can synchronously read
                // its isForum flag from ChatScreen (drives the topic-list
                // panel) and similar properties without hitting TDLib
                // every time the chat opens.
                supergroupCache[obj.supergroup.id] = obj.supergroup
            }
            is TdApi.UpdateMessageContent -> {
                // If the edited message is the chat's LAST message, update the
                // cached lastMessage content and refresh the list so the chat
                // row's preview reflects the edit in real time — TDLib does
                // NOT fire UpdateChatLastMessage for an in-place content edit,
                // so without this the list preview stayed stale until reorder.
                chatCache[obj.chatId]?.lastMessage?.let { lm ->
                    if (lm.id == obj.messageId) {
                        lm.content = obj.newContent
                        refreshChats()
                    }
                }
                scope.launch {
                    _messageContentUpdates.emit(MessageContentUpdate(obj.chatId, obj.messageId, obj.newContent))
                    _chatUpdates.emit(obj.chatId)
                }
            }
            is TdApi.UpdateMessageEdited -> {
                // TDLib fires this AFTER an edit completes, carrying the
                // new editDate (and updated replyMarkup if any). Note this
                // is a DIFFERENT update from UpdateMessageContent — content
                // changes ride on Content, but the "this message was
                // edited" metadata only lands here. Without this handler
                // message.editDate stays at 0 forever, which is why the
                // "modificato" tag was never appearing under bubbles even
                // when the content visibly updated.
                //
                // We re-emit on the SAME messageContentUpdates flow so
                // ChatScreen's existing collector picks it up. The
                // collector ignores the `newContent` of MessageContentUpdate
                // when it's null; we pass null here to signal "metadata
                // only — re-fetch the message for editDate and bump the
                // revision". Cleaner than a separate flow + collector pair
                // because the downstream behaviour is identical: mutate the
                // cached Message, bump revision, recompose bubble.
                scope.launch {
                    _messageEdited.emit(MessageEditedUpdate(obj.chatId, obj.messageId, obj.editDate, obj.replyMarkup))
                    _chatUpdates.emit(obj.chatId)
                }
            }
            is TdApi.UpdateMessageInteractionInfo -> {
                // Reactions/views/forwards changed. Propagate so the chat
                // screen can rebuild the affected MessageBubble.
                scope.launch {
                    _interactionInfoUpdates.emit(
                        InteractionInfoUpdate(obj.chatId, obj.messageId, obj.interactionInfo)
                    )
                }
            }
            is TdApi.UpdatePoll -> {
                // A poll's votes/state changed. Forward the fresh poll; the
                // chat screen finds the message whose MessagePoll has this
                // poll.id and swaps in the new tallies (live vote bars).
                scope.launch { _pollUpdates.emit(obj.poll) }
            }
            is TdApi.UpdateSecretChat -> {
                // The secret chat's handshake state (or key/layer) changed.
                // Forward it so an open ChatScreen can lift the "waiting for
                // the peer" notice the moment the key exchange completes.
                scope.launch { _secretChatUpdates.emit(obj.secretChat) }
            }
            is TdApi.UpdateDeleteMessages -> {
                // isPermanent=false fires when messages slide out of cache,
                // not on user-initiated delete. Only forward the real ones.
                if (obj.isPermanent) {
                    scope.launch {
                        _deletedMessages.emit(DeleteEvent(obj.chatId, obj.messageIds))
                        _chatUpdates.emit(obj.chatId)
                    }
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                scope.launch {
                    // ChatScreen needs the (oldId, newMessage) pair to swap
                    // its optimistic placeholder bubble for the confirmed
                    // one. Without this swap the ⏱ tick stays forever.
                    _messageSendUpdates.emit(
                        MessageSendUpdate(
                            oldMessageId = obj.oldMessageId,
                            newMessage = obj.message,
                            failed = false
                        )
                    )
                    _chatUpdates.emit(obj.message.chatId)
                }
            }
            is TdApi.UpdateMessageSendFailed -> {
                scope.launch {
                    _messageSendUpdates.emit(
                        MessageSendUpdate(
                            oldMessageId = obj.oldMessageId,
                            newMessage = obj.message,
                            failed = true
                        )
                    )
                    _chatUpdates.emit(obj.message.chatId)
                }
            }
            is TdApi.UpdateFile -> {
                scope.launch { _fileUpdates.emit(obj.file) }
            }
            else -> { /* noop */ }
        }
    }

    private fun onError(error: Throwable) {
        Log.e(TAG, "TDLib error", error)
    }

    private var refreshRevision = 0L

    private fun refreshChats() {
        val rev = ++refreshRevision
        val list = chatCache.values
            .mapNotNull { chat ->
                // Suppress chats the user has just deleted (optimistic
                // hide). The entry is cleared by the UpdateChatPosition
                // handler when TDLib confirms the chat's Main-list
                // position dropped to 0, or by deleteChatHistory itself
                // if the TDLib call throws.
                if (chat.id in hiddenChats) return@mapNotNull null
                val mainPos = chat.positions.firstOrNull { it.list is TdApi.ChatListMain }
                val archivePos = chat.positions.firstOrNull { it.list is TdApi.ChatListArchive }
                // Prefer the Main list position; fall back to Archive so
                // archived chats still surface (in the Archiviati tab).
                val pos = mainPos?.takeIf { it.order != 0L } ?: archivePos
                if (pos == null || pos.order == 0L) null
                else ChatSummary(
                    id = chat.id,
                    title = chat.title.ifBlank { resolveTitle(chat) },
                    order = pos.order,
                    unread = chat.unreadCount,
                    lastMessagePreview = buildPreview(chat.lastMessage),
                    lastMessageTimestamp = chat.lastMessage?.date?.toLong()?.times(1000) ?: 0L,
                    kind = resolveKind(chat),
                    chat = chat,
                    isArchived = (mainPos == null || mainPos.order == 0L) && archivePos != null,
                    // ChatPosition.isPinned is the authoritative source.
                    // Pinned chats from the user pin them to top of list
                    // via TdApi.ToggleChatIsPinned; TDLib reflects the
                    // state back through UpdateChatPosition.
                    isPinned = pos.isPinned,
                    isMarkedAsUnread = chat.isMarkedAsUnread,
                    unreadMentionCount = chat.unreadMentionCount,
                    unreadReactionCount = chat.unreadReactionCount,
                    isMuted = isChatMutedForBadge(chat),
                    revision = rev
                )
            }
            // Sort: pinned first (preserving their relative order), then
            // un-pinned by their numeric `order` field (descending = most
            // recent activity at top). Pinned chats also have `order`
            // values from TDLib that are HIGHER than non-pinned ones, so
            // sortedByDescending alone WOULD put them first — but we
            // explicitly sort by isPinned first as belt-and-suspenders
            // against TDLib's order-numbering changing across releases.
            .sortedWith(
                compareByDescending<ChatSummary> { it.isPinned }
                    .thenByDescending { it.order }
            )
        _chats.value = list
    }

    private fun resolveTitle(chat: TdApi.Chat): String {
        return when (val type = chat.type) {
            is TdApi.ChatTypePrivate -> {
                val u = userCache[type.userId]
                if (u != null) "${u.firstName} ${u.lastName}".trim().ifBlank { "Utente ${type.userId}" }
                else "Utente ${type.userId}"
            }
            is TdApi.ChatTypeBasicGroup -> "Gruppo ${type.basicGroupId}"
            is TdApi.ChatTypeSupergroup -> if (type.isChannel) "Canale ${type.supergroupId}" else "Gruppo ${type.supergroupId}"
            is TdApi.ChatTypeSecret -> "Chat segreta"
            else -> "Chat ${chat.id}"
        }
    }

    private fun resolveKind(chat: TdApi.Chat): ChatKind = when (val type = chat.type) {
        is TdApi.ChatTypePrivate -> ChatKind.Private
        is TdApi.ChatTypeBasicGroup -> ChatKind.Group
        is TdApi.ChatTypeSupergroup -> if (type.isChannel) ChatKind.Channel else ChatKind.Group
        is TdApi.ChatTypeSecret -> ChatKind.Secret
        else -> ChatKind.Private
    }

    /** Resolve the message author name (for groups/channels in notifications and preview). */
    fun resolveSenderName(message: TdApi.Message): String {
        return when (val s = message.senderId) {
            is TdApi.MessageSenderUser -> userCache[s.userId]
                ?.let { "${it.firstName} ${it.lastName}".trim() }
                ?.ifBlank { null }
                ?: "Utente"
            is TdApi.MessageSenderChat -> chatCache[s.chatId]?.title ?: "Chat"
            else -> ""
        }
    }

    /**
     * Human-readable description of a "service"/system message — someone
     * joining or leaving, a pinned message, a renamed group, a video chat,
     * a screenshot, and so on. These carry no MessageText, so without this
     * they surface as a bare "Nuovo messaggio" in notifications and
     * "Messaggio" in the chat list. Returns null for ordinary content
     * messages (the caller then uses its own media/text preview). Names are
     * resolved from the user/chat caches.
     */
    fun serviceMessageText(msg: TdApi.Message): String? {
        val actor = resolveSenderName(msg).ifBlank { "Qualcuno" }
        val senderUid = (msg.senderId as? TdApi.MessageSenderUser)?.userId
        fun nameOf(uid: Long): String =
            userCache[uid]?.let { "${it.firstName} ${it.lastName}".trim() }?.ifBlank { null }
                ?: "utente"
        fun namesOf(ids: LongArray): String =
            if (ids.isEmpty()) "qualcuno" else ids.joinToString(", ") { nameOf(it) }
        return when (val c = msg.content) {
            is TdApi.MessageChatJoinByLink -> "$actor si è unito al gruppo"
            is TdApi.MessageChatAddMembers -> {
                val ids = c.memberUserIds
                if (ids.size == 1 && senderUid != null && ids[0] == senderUid)
                    "$actor si è unito al gruppo"
                else "$actor ha aggiunto ${namesOf(ids)}"
            }
            is TdApi.MessageChatDeleteMember ->
                if (senderUid != null && c.userId == senderUid) "$actor ha lasciato il gruppo"
                else "$actor ha rimosso ${nameOf(c.userId)}"
            is TdApi.MessagePinMessage -> "$actor ha fissato un messaggio"
            is TdApi.MessageChatChangeTitle -> "$actor ha cambiato il nome in «${c.title}»"
            is TdApi.MessageChatChangePhoto -> "$actor ha cambiato la foto del gruppo"
            is TdApi.MessageChatDeletePhoto -> "$actor ha rimosso la foto del gruppo"
            is TdApi.MessageBasicGroupChatCreate -> "$actor ha creato il gruppo «${c.title}»"
            is TdApi.MessageSupergroupChatCreate -> "$actor ha creato «${c.title}»"
            is TdApi.MessageChatUpgradeTo -> "Il gruppo è diventato un supergruppo"
            is TdApi.MessageChatUpgradeFrom -> "Il gruppo è diventato un supergruppo"
            is TdApi.MessageContactRegistered -> "$actor si è iscritto a Telegram"
            is TdApi.MessageScreenshotTaken -> "$actor ha fatto uno screenshot"
            is TdApi.MessageChatSetMessageAutoDeleteTime ->
                if (c.messageAutoDeleteTime == 0) "$actor ha disattivato l'autodistruzione dei messaggi"
                else "$actor ha impostato l'autodistruzione dei messaggi"
            is TdApi.MessageChatSetTheme -> "$actor ha cambiato il tema della chat"
            is TdApi.MessageChatBoost -> "Il gruppo ha ricevuto un boost"
            is TdApi.MessageVideoChatStarted -> "📞 Videochiamata iniziata"
            is TdApi.MessageVideoChatEnded -> "📞 Videochiamata terminata"
            is TdApi.MessageVideoChatScheduled -> "📞 Videochiamata programmata"
            is TdApi.MessageInviteVideoChatParticipants ->
                "$actor ha invitato dei partecipanti alla videochiamata"
            is TdApi.MessageForumTopicCreated -> "Topic creato: «${c.name}»"
            is TdApi.MessageForumTopicEdited -> "Topic modificato"
            is TdApi.MessageForumTopicIsClosedToggled -> if (c.isClosed) "Topic chiuso" else "Topic riaperto"
            is TdApi.MessageForumTopicIsHiddenToggled -> if (c.isHidden) "Topic nascosto" else "Topic mostrato"
            is TdApi.MessageCustomServiceAction -> c.text
            is TdApi.MessageGameScore -> "$actor ha totalizzato ${c.score} punti"
            is TdApi.MessagePaymentSuccessful -> "Pagamento effettuato"
            is TdApi.MessageGiftedPremium -> "Regalo Telegram Premium"
            is TdApi.MessageGiftedStars -> "Regalo di stelle"
            is TdApi.MessageGiveawayCreated -> "Giveaway avviato"
            is TdApi.MessageGiveaway -> "Giveaway"
            is TdApi.MessagePremiumGiftCode -> "Codice regalo Premium"
            is TdApi.MessageProximityAlertTriggered -> "Avviso di prossimità"
            is TdApi.MessageExpiredPhoto -> "📷 Foto scaduta"
            is TdApi.MessageExpiredVideo -> "🎬 Video scaduto"
            is TdApi.MessageUnsupported -> "Messaggio non supportato"
            else -> null
        }
    }

    /**
     * Compose a short single-line preview of a message's content. Used
     * by the chat-list row (last message) and by ChatScreen's pinned-
     * message banner. Returns empty string for null.
     */
    fun buildPreview(msg: TdApi.Message?): String {
        if (msg == null) return ""
        serviceMessageText(msg)?.let { return it }
        return when (val c = msg.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> "📷 " + (c.caption.text.ifBlank { "Foto" })
            is TdApi.MessageVoiceNote -> "🎙 Nota vocale"
            is TdApi.MessageAudio -> "🎵 " + (c.audio.title.ifBlank { "Audio" })
            is TdApi.MessageDocument -> "📎 " + c.document.fileName
            is TdApi.MessageVideo -> "🎬 " + (c.caption.text.ifBlank { "Video" })
            is TdApi.MessageVideoNote -> "📹 Video messaggio"
            is TdApi.MessageSticker -> c.sticker.emoji.ifBlank { "Sticker" } + " Sticker"
            is TdApi.MessageAnimation -> "GIF" + (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            is TdApi.MessageLocation -> "📍 Posizione"
            is TdApi.MessageVenue -> "📍 " + c.venue.title.ifBlank { "Luogo" }
            is TdApi.MessageContact -> "👤 " + "${c.contact.firstName} ${c.contact.lastName}".trim().ifBlank { "Contatto" }
            is TdApi.MessagePoll -> "📊 " + c.poll.question.text.ifBlank { "Sondaggio" }
            is TdApi.MessageGame -> "🎮 " + c.game.title.ifBlank { "Gioco" }
            is TdApi.MessageDice -> c.emoji.ifBlank { "🎲" }
            is TdApi.MessageStory -> "📖 Storia"
            is TdApi.MessageInvoice -> "🧾 Fattura"
            is TdApi.MessageCall -> "📞 Chiamata"
            else -> "Messaggio"
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _authState.value = AuthState.WaitParameters
                scope.launch { sendTdlibParameters() }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = AuthState.WaitPhoneNumber
            }
            is TdApi.AuthorizationStateWaitCode -> {
                // codeInfo.type tells us HOW the code was delivered. Telegram's
                // server sends it inside the Telegram app when another active
                // session exists; with NO active session (e.g. the user has
                // Telegram nowhere) it falls back to SMS / a call. Surface that
                // so the login screen can say where to look.
                _authState.value = AuthState.WaitCode(
                    codeHint = state.codeInfo.phoneNumber,
                    viaTelegram = state.codeInfo.type is TdApi.AuthenticationCodeTypeTelegramMessage
                )
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitPassword(hint = state.passwordHint)
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                // Brand-new number with no Telegram account — TDLib needs a
                // name to create one. The login screen shows the name step.
                _authState.value = AuthState.WaitRegistration
            }
            is TdApi.AuthorizationStateReady -> {
                _authState.value = AuthState.Ready
                scope.launch { loadChats(100) }
                // Eagerly populate scope notification settings cache.
                // TDLib also pushes these via UpdateScopeNotificationSettings
                // but the timing isn't deterministic — without prefetch
                // there's a window between auth-ready and the first push
                // where a new message in a scope-muted chat would slip
                // through. Three concurrent get calls remove that race.
                scope.launch {
                    runCatching {
                        scopePrivate = send(
                            TdApi.GetScopeNotificationSettings(
                                TdApi.NotificationSettingsScopePrivateChats()
                            )
                        )
                    }
                }
                scope.launch {
                    runCatching {
                        scopeGroup = send(
                            TdApi.GetScopeNotificationSettings(
                                TdApi.NotificationSettingsScopeGroupChats()
                            )
                        )
                    }
                }
                scope.launch {
                    runCatching {
                        scopeChannel = send(
                            TdApi.GetScopeNotificationSettings(
                                TdApi.NotificationSettingsScopeChannelChats()
                            )
                        )
                    }
                }
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authState.value = AuthState.LoggingOut
                chatCache.clear()
                _chats.value = emptyList()
            }
            is TdApi.AuthorizationStateClosed -> {
                _authState.value = AuthState.Closed
                client = null
            }
            else -> { /* other transient states */ }
        }
    }

    private suspend fun sendTdlibParameters() {
        val cfg = AppSettings.apiConfig.first()
        val params = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = File(appContext.filesDir, "tdlib").absolutePath
            filesDirectory = File(appContext.filesDir, "tdlib_files").absolutePath
            databaseEncryptionKey = ByteArray(0)
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            // Secret chats MUST be enabled here for CreateNewSecretChat
            // and SecretChat-typed messaging to work end-to-end. With
            // this flag false (the previous setting) TDLib silently
            // accepts the create call but leaves the session in a
            // half-initialised state — the chat appears in the list
            // but tapping it crashes, and deleting it leaves an
            // un-finalisable record in the local db that breaks every
            // subsequent cold start. Turning the flag on lets TDLib
            // own the encryption handshake and lifecycle correctly.
            useSecretChats = true
            apiId = cfg.apiId
            apiHash = cfg.apiHash
            systemLanguageCode = java.util.Locale.getDefault().language.ifBlank { "en" }
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = BuildConfig.VERSION_NAME
        }
        try { send(params) } catch (e: Throwable) { Log.e(TAG, "setTdlibParameters failed", e) }
    }

    @Volatile private var lastOfflineNoticeMs = 0L

    /**
     * Deliberate, user-initiated writes that warrant an offline notice when
     * attempted with no connection. Matched by [TdApi.Function] simple class
     * name. Intentionally excludes reads and automatic/high-frequency
     * requests (SendChatAction typing, SetChatDraftMessage, ViewMessages,
     * OpenChat/CloseChat, CreateChat-by-id) so the notice only fires on
     * things the user explicitly tapped.
     */
    private val OFFLINE_NOTICE_ACTIONS = setOf(
        "SendMessage", "SendMessageAlbum", "ForwardMessages",
        "EditMessageText", "EditMessageCaption", "DeleteMessages",
        "SetPollAnswer", "AddMessageReaction", "RemoveMessageReaction",
        "ToggleChatIsPinned", "SetChatNotificationSettings", "AddChatToList",
        "DeleteChatHistory", "LeaveChat", "JoinChat", "JoinChatByInviteLink",
        "CreateNewBasicGroupChat", "CreateNewSupergroupChat",
        "CreateNewSecretChat", "CreatePrivateChat", "AddChatMembers",
        "SetChatMemberStatus", "SetChatTitle", "SetChatDescription",
        "SetChatPhoto", "SetChatPermissions", "SetChatMessageAutoDeleteTime",
        "SetMessageSenderBlockList", "SetBio", "SetName", "SetUsername"
    )

    suspend inline fun <reified R : TdApi.Object> send(query: TdApi.Function<R>): R {
        return sendRaw(query) as R
    }

    suspend fun sendRaw(query: TdApi.Function<*>): TdApi.Object {
        maybeNotifyOffline(query)
        val c = client ?: throw IllegalStateException("TDLib client not started")
        return suspendCancellableCoroutine { cont ->
            c.send(query) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TdException(result.code, result.message))
                } else {
                    cont.resume(result)
                }
            }
        }
    }

    /**
     * Global "you're offline" safety net. Every deliberate user action goes
     * through [sendRaw]; when the device is offline AND the request is one of
     * the user-initiated writes below, we surface a single transient notice
     * (NovaSnackbar) so a tap never just fails in silence. Reads (getChat,
     * getChatHistory, downloadFile, …) and high-frequency/automatic requests
     * (typing, draft saves, view marks) are deliberately NOT in the set, so
     * they never trigger the notice. Throttled to one notice per ~1.5s so a
     * burst of taps (or an album of sends) shows it once. We still pass the
     * request to TDLib — it queues queueable writes and runs them on
     * reconnect; the notice is purely informational.
     */
    private fun maybeNotifyOffline(query: TdApi.Function<*>) {
        if (com.secondream.novagram.connectivity.ConnectivityState.isOnline.value) return
        if (query::class.java.simpleName !in OFFLINE_NOTICE_ACTIONS) return
        val now = System.currentTimeMillis()
        if (now - lastOfflineNoticeMs < 1500L) return
        lastOfflineNoticeMs = now
        com.secondream.novagram.ui.components.NovaSnackbar.show(
            com.secondream.novagram.R.string.offline_action_notice,
            com.secondream.novagram.ui.icons.PhosphorIcons.Info
        )
    }

    suspend fun configureApi(apiId: Int, apiHash: String) {
        AppSettings.setApiConfig(apiId, apiHash)
        if (client == null) startClient()
    }

    suspend fun setPhone(phone: String) {
        send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    suspend fun setCode(code: String) {
        send(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun setPassword(password: String) {
        send(TdApi.CheckAuthenticationPassword(password))
    }

    /** Create a new Telegram account for a number that has none. Only valid
     *  while TDLib is in AuthorizationStateWaitRegistration (after the code
     *  step for a brand-new user). */
    suspend fun registerUser(firstName: String, lastName: String) {
        send(TdApi.RegisterUser(firstName.trim(), lastName.trim(), false))
    }

    suspend fun logOut() {
        try { send(TdApi.LogOut()) } catch (e: Throwable) { Log.w(TAG, "logOut: ${e.message}") }
    }

    suspend fun loadChats(limit: Int) {
        // LoadChats pulls the NEXT `limit` chats into the in-memory list in
        // activity order — one call only loads one batch. TDLib answers with
        // error 404 ("Chats list is exhausted") once everything is loaded, so
        // a single call leaves chats further down the list unloaded. That's
        // why low-traffic channels were missing from the Canali tab. Drain
        // the list in batches until 404 (or a hard loop cap, so a giant
        // account can't spin forever). Each batch only loads lightweight chat
        // metadata, not messages, so this stays cheap.
        suspend fun drain(list: TdApi.ChatList, label: String) {
            repeat(40) {
                val r = runCatching { send(TdApi.LoadChats(list, limit)) }
                if (r.isFailure) {
                    // 404 = fully loaded (expected); anything else we also stop
                    // on, but log it so a real error is visible.
                    (r.exceptionOrNull() as? TdException)?.let {
                        if (it.code != 404) Log.w(TAG, "loadChats($label): ${it.message}")
                    }
                    return
                }
            }
        }
        drain(TdApi.ChatListMain(), "main")
        // Also pull the archive so the Archiviati tab (when enabled) has data.
        drain(TdApi.ChatListArchive(), "archive")
    }

    suspend fun openChat(chatId: Long) { send(TdApi.OpenChat(chatId)) }
    suspend fun closeChat(chatId: Long) { send(TdApi.CloseChat(chatId)) }
    suspend fun viewMessages(chatId: Long, ids: LongArray) {
        send(TdApi.ViewMessages(chatId, ids, TdApi.MessageSourceChatHistory(), true))
    }

    /**
     * Tell TDLib the user "opened" this message — i.e., looked at its
     * content directly, not just scrolled past it. Required to decrement
     * server-side unread_mention_count for mention messages: plain
     * viewMessages(forceRead=true) is enough for regular read receipts
     * but mentions need this stronger signal in current TDLib versions
     * or the server keeps the chat's mention badge non-zero, which then
     * gets echoed back through UpdateChatUnreadMentionCount and reverts
     * any optimistic client-side decrement.
     */
    suspend fun openMessageContent(chatId: Long, messageId: Long) {
        runCatching { send(TdApi.OpenMessageContent(chatId, messageId)) }
    }

    suspend fun getChat(chatId: Long): TdApi.Chat = send(TdApi.GetChat(chatId))

    /**
     * Register this device's FCM token with TDLib so the Telegram
     * backend pushes notifications to it. Called from
     * NovagramFcmService.onNewToken AND from app startup once Firebase
     * has resolved the cached token. Idempotent — re-registering the
     * same token is a no-op on the server. Wrapped in runCatching so
     * a failure during the auth-not-ready window (token arrives before
     * the user has finished login) silently drops the call; we'll
     * re-register on next token rotation or app start.
     */
    suspend fun registerDeviceForFcm(token: String) {
        runCatching {
            // DeviceTokenFirebaseCloudMessaging:
            //  - token: the FCM registration token
            //  - encrypt: true → Telegram encrypts the push payload
            //    end-to-end. The recommended default; means processing
            //    requires TDLib to decrypt server-side.
            send(
                TdApi.RegisterDevice(
                    TdApi.DeviceTokenFirebaseCloudMessaging(token, true),
                    longArrayOf()
                )
            )
        }
    }

    /**
     * Forward an FCM data payload (as JSON string) to TDLib's push
     * processor. TDLib decrypts, fetches the linked message, and emits
     * UpdateNewMessage — at which point our existing NotificationHelper
     * shows the local notification through the regular channel.
     */
    suspend fun processPushNotification(payloadJson: String) {
        runCatching { send(TdApi.ProcessPushNotification(payloadJson)) }
    }

    suspend fun getChatHistory(
        chatId: Long,
        fromMessageId: Long,
        offset: Int,
        limit: Int
    ): TdApi.Messages =
        send(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false))

    /**
     * Convenience overload: fetch [limit] messages strictly older than
     * [fromMessageId]. Equivalent to the long form with offset = 0.
     */
    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): TdApi.Messages =
        getChatHistory(chatId, fromMessageId, 0, limit)

    /**
     * Compact, plain-text digest of recent unread messages across the
     * busiest chats — the fuel for the home "Riepilogo" AI feature. Reads
     * everything from OUTSIDE any open chat: picks the most recent unread,
     * non-archived chats (newest activity first) and pulls their latest few
     * messages, dropping the user's own. Each line is "Name: preview" so the
     * model can attribute who said what. Returns at most [maxChats] groups.
     */
    suspend fun recentUnreadDigest(maxChats: Int = 6, perChat: Int = 8): List<ChatUnreadDigest> {
        val me = cachedMyUserId
        val candidates = _chats.value
            .filter { !it.isArchived && it.unread > 0 }
            .sortedByDescending { it.order }
            .take(maxChats)
        val out = mutableListOf<ChatUnreadDigest>()
        for (cs in candidates) {
            val msgs = runCatching {
                getChatHistory(cs.id, 0L, perChat).messages?.toList().orEmpty()
            }.getOrNull().orEmpty()
            val lines = msgs
                .asReversed() // TDLib returns newest-first; flip to chronological
                .filter { (it.senderId as? TdApi.MessageSenderUser)?.userId != me }
                .takeLast(cs.unread.coerceIn(1, perChat))
                .mapNotNull { m ->
                    val text = buildPreview(m).trim()
                    if (text.isBlank()) return@mapNotNull null
                    val who = resolveSenderName(m).trim()
                    if (who.isBlank()) text else "$who: $text"
                }
            if (lines.isNotEmpty()) out += ChatUnreadDigest(cs.title, lines)
        }
        return out
    }

    /**
     * Search messages in a chat filtered by content type. Drives the
     * media-gallery tabs in ChatInfoDialog: pass a TdApi filter (e.g.
     * SearchMessagesFilterPhoto, SearchMessagesFilterDocument,
     * SearchMessagesFilterUrl) and TDLib returns the matching subset
     * of the chat's history newest-first. fromMessageId=0 means "from
     * the newest"; non-zero paginates older.
     *
     * The senderId / topic-thread args are 0 — we want EVERYTHING in
     * the chat across all senders / topics. ChatInfoDialog renders
     * the full chat scope, not a topic-scoped one, even when opened
     * from inside a forum topic; that mirrors official Telegram's
     * "Chat info → Media" which also spans the whole chat.
     */
    suspend fun searchChatMessages(
        chatId: Long,
        filter: TdApi.SearchMessagesFilter,
        query: String = "",
        fromMessageId: Long = 0L,
        limit: Int = 100
    ): List<TdApi.Message> {
        val r = runCatching {
            // Newer TDLib added a `topic_id: MessageTopic?` slot as the 2nd
            // arg and dropped the trailing thread/topic Longs. We pass null
            // for it — we want the chat-wide media gallery, not topic-scoped.
            send(TdApi.SearchChatMessages(
                chatId, null, query, null, fromMessageId, 0, limit, filter
            )) as TdApi.FoundChatMessages
        }.getOrNull() ?: return emptyList()
        return r.messages.toList()
    }

    /**
     * Build the InputMessageReplyTo for the SendMessage call. Returns null
     * when there's no reply (TDLib treats null as "not a reply"). The 0/""
     * trailing args are the checklist/poll-option fields, irrelevant for
     * regular message replies.
     */
    private fun buildReplyTo(messageId: Long?): TdApi.InputMessageReplyTo? =
        messageId?.takeIf { it != 0L }?.let {
            TdApi.InputMessageReplyToMessage(it, null, 0, "")
        }

    suspend fun sendText(
        chatId: Long,
        text: String,
        replyToMessageId: Long? = null
    ) {
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    /**
     * Send a bot command, attaching an explicit bot_command text entity the
     * way the official clients do. Some bots only react to a properly-entitied
     * "/cmd@bot" in groups; a plain-text send (no entities) wasn't triggering
     * them. If TDLib rejects the manually-supplied entity we fall back to a
     * plain text send so we never end up sending nothing.
     */
    suspend fun sendBotCommand(
        chatId: Long,
        commandText: String,
        replyToMessageId: Long? = null
    ) {
        val entities = arrayOf<TdApi.TextEntity>(
            TdApi.TextEntity(0, commandText.length, TdApi.TextEntityTypeBotCommand())
        )
        val content = TdApi.InputMessageText(
            TdApi.FormattedText(commandText, entities), null, true
        )
        val ok = runCatching {
            send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
        }.isSuccess
        if (!ok) sendText(chatId, commandText, replyToMessageId)
    }

    /**
     * Send a regular (non-quiz) poll. Polls only make sense in groups /
     * channels, so the UI gates the entry point — this is never reached in a
     * 1-on-1. [options] must hold 2..10 non-blank entries. Built with
     * named-field assignment rather than the 15-arg positional constructor so
     * the seven booleans can't silently end up in the wrong slots.
     */
    suspend fun sendPoll(
        chatId: Long,
        question: String,
        options: List<String>,
        isAnonymous: Boolean,
        allowsMultipleAnswers: Boolean,
        replyToMessageId: Long? = null
    ) {
        val inputOptions: Array<TdApi.InputPollOption> = options.map { opt ->
            TdApi.InputPollOption().apply {
                text = TdApi.FormattedText(opt, emptyArray())
                media = null
            }
        }.toTypedArray()
        val content = TdApi.InputMessagePoll().apply {
            this.question = TdApi.FormattedText(question, emptyArray())
            this.options = inputOptions
            description = TdApi.FormattedText("", emptyArray())
            media = null
            this.isAnonymous = isAnonymous
            this.allowsMultipleAnswers = allowsMultipleAnswers
            allowsRevoting = true
            membersOnly = false
            countryCodes = emptyArray()
            shuffleOptions = false
            hideResultsUntilCloses = false
            type = TdApi.InputPollTypeRegular(false)
            openPeriod = 0
            closeDate = 0
            isClosed = false
        }
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    /**
     * Cast, change, or retract a poll vote. [optionIds] are the zero-based
     * indices of the chosen option(s): one element for a single-answer poll,
     * several for a multi-answer poll, or an empty array to retract. TDLib
     * echoes the new tallies through UpdatePoll, which the chat screen maps
     * back onto the bubble — so the bars update live without a reload.
     */
    suspend fun setPollAnswer(chatId: Long, messageId: Long, optionIds: IntArray) {
        runCatching { send(TdApi.SetPollAnswer(chatId, messageId, optionIds)) }
    }

    /**
     * Create a new basic group with the given members + title; returns the new
     * chat id (or null on failure). TDLib auto-upgrades basic groups to
     * supergroups as they grow, so this is the right primitive for the
     * "create group" contact multi-select flow.
     */
    suspend fun createGroup(userIds: List<Long>, title: String): Long? {
        val res = runCatching {
            send(TdApi.CreateNewBasicGroupChat(userIds.toLongArray(), title, 0))
        }.getOrNull()
        return (res as? TdApi.CreatedBasicGroupChat)?.chatId
    }

    /**
     * Announce a "X is doing Y" action to the chat. Telegram clients
     * call this every ~5 seconds while the user is composing a message
     * (and once with [TdApi.ChatActionCancel] when they stop) so peers
     * see a live "sta scrivendo…" indicator. Errors are swallowed —
     * action emissions are best-effort and a single missed call just
     * means the peer's typing bubble blinks out a beat early.
     */
    suspend fun sendChatAction(chatId: Long, action: TdApi.ChatAction) {
        runCatching {
            send(TdApi.SendChatAction(chatId, null, null, action))
        }
    }

    // ===== Editing =====

    /**
     * Replace the text content of a previously-sent text message. TDLib
     * decides whether the edit is allowed (time window, bot-vs-user rules,
     * channel admin permissions) and returns an error otherwise — we let
     * that propagate so the caller can surface "modifica non possibile".
     *
     * Only valid for messages whose content is [TdApi.MessageText]. For
     * media messages (photo/video/document with caption) use
     * [editMessageCaption] instead — captions and text bodies are stored
     * separately in TDLib.
     */
    suspend fun editMessageText(chatId: Long, messageId: Long, newText: String) {
        val content = TdApi.InputMessageText(
            TdApi.FormattedText(newText, emptyArray()),
            /* linkPreviewOptions = */ null,
            /* clearDraft = */ false
        )
        send(TdApi.EditMessageText(chatId, messageId, null, content))
    }

    /**
     * Replace only the caption of a media message (photo/video/document/
     * animation/audio with caption). Passing a blank string clears the
     * caption entirely. Keeps the original media intact — we don't
     * re-upload or even touch the file.
     */
    suspend fun editMessageCaption(chatId: Long, messageId: Long, newCaption: String) {
        val formatted = if (newCaption.isBlank()) null
        else TdApi.FormattedText(newCaption, emptyArray())
        send(TdApi.EditMessageCaption(
            chatId, messageId,
            /* replyMarkup = */ null,
            formatted,
            /* showAboveText = */ false
        ))
    }

    // ===== Drafts =====

    /**
     * Read the current draft for a chat. We pull it from our cache rather
     * than asking TDLib each time — UpdateChatDraftMessage events keep
     * chatCache in sync, and on chat open the cache is already populated
     * by GetChats during init.
     *
     * Returns the plain draft text or null if there is no draft. We don't
     * surface replyTo here yet; the input bar in ChatScreen has its own
     * reply preview state driven by user gestures.
     */
    fun getChatDraftText(chatId: Long): String? {
        val draft = chatCache[chatId]?.draftMessage ?: return null
        val content = draft.inputMessageText as? TdApi.InputMessageText ?: return null
        return content.text?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Persist (or clear) the draft for a chat. Passing a blank `text`
     * clears the draft entirely so the chat list stops showing the
     * "Bozza:" prefix. TDLib syncs drafts across devices, so what we
     * save here also shows up on Telegram Desktop/Web.
     *
     * `replyToMessageId` is optional and mirrors the reply target that
     * was active in the input bar when the user left the chat — TDLib
     * preserves it so the reply preview is still there when they come back.
     */
    suspend fun setChatDraft(
        chatId: Long,
        text: String,
        replyToMessageId: Long? = null
    ) {
        val draft = if (text.isBlank()) {
            null
        } else {
            // Field assignment over all-args constructor: TDLib occasionally
            // appends new fields (e.g. effectId) between versions and this
            // style stays compatible without having to track the schema.
            TdApi.DraftMessage().apply {
                replyTo = buildReplyTo(replyToMessageId)
                date = (System.currentTimeMillis() / 1000L).toInt()
                inputMessageText = TdApi.InputMessageText(
                    TdApi.FormattedText(text, emptyArray()),
                    null,
                    /* clearDraft = */ false
                )
            }
        }
        // Mirror the change into our cache immediately so chat-list rows
        // refresh without waiting for the round-trip UpdateChatDraftMessage.
        chatCache[chatId]?.draftMessage = draft
        runCatching {
            // Recent TDLib replaced the old `messageThreadId: Long` parameter
            // with `topicId: MessageTopic?`. Field-assignment style stays
            // compatible with both shapes (kotlin no-arg + setter call) and
            // we pass null because we only target the main thread of the
            // chat — topic-specific drafts are out of scope here.
            send(TdApi.SetChatDraftMessage().apply {
                this.chatId = chatId
                this.draftMessage = draft
            })
        }
    }

    suspend fun sendPhoto(
        chatId: Long,
        filePath: String,
        caption: String? = null,
        replyToMessageId: Long? = null
    ) {
        val content = TdApi.InputMessagePhoto(
            TdApi.InputFileLocal(filePath),
            null, null, intArrayOf(), 0, 0,
            caption?.let { TdApi.FormattedText(it, emptyArray()) },
            false, null, false
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    suspend fun sendDocument(
        chatId: Long,
        filePath: String,
        caption: String? = null,
        replyToMessageId: Long? = null
    ) {
        val content = TdApi.InputMessageDocument(
            TdApi.InputFileLocal(filePath),
            null, false,
            caption?.let { TdApi.FormattedText(it, emptyArray()) }
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    suspend fun sendVoiceNote(
        chatId: Long,
        filePath: String,
        durationSeconds: Int,
        replyToMessageId: Long? = null
    ) {
        val content = TdApi.InputMessageVoiceNote(
            TdApi.InputFileLocal(filePath),
            durationSeconds, ByteArray(0), null, null
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    /**
     * Send a local video with optional caption. duration/width/height left
     * at 0 — TDLib derives them from the file during upload. We claim
     * supportsStreaming=true so the recipient can start playback before the
     * download finishes (TDLib will adjust based on the actual container).
     */
    suspend fun sendVideo(
        chatId: Long,
        filePath: String,
        caption: String? = null,
        replyToMessageId: Long? = null
    ) {
        val content = TdApi.InputMessageVideo(
            TdApi.InputFileLocal(filePath),
            null, null, 0,           // thumbnail, cover, start_timestamp
            intArrayOf(),            // added_sticker_file_ids
            0, 0, 0,                 // duration, width, height
            true,                    // supports_streaming
            caption?.let { TdApi.FormattedText(it, emptyArray()) },
            false, null, false       // show_caption_above_media, self_destruct, has_spoiler
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    /**
     * Send a GIF / animation. TDLib's InputMessageAnimation accepts a local
     * file (an actual .gif or an mp4) and renders it as an auto-playing
     * animation on every client.
     */
    suspend fun sendAnimation(
        chatId: Long,
        filePath: String,
        caption: String? = null,
        replyToMessageId: Long? = null
    ) {
        val content = TdApi.InputMessageAnimation(
            TdApi.InputFileLocal(filePath),
            null,                    // thumbnail
            intArrayOf(),            // added_sticker_file_ids
            0, 0, 0,                 // duration, width, height
            caption?.let { TdApi.FormattedText(it, emptyArray()) },
            false, false             // show_caption_above_media, has_spoiler
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    /**
     * Send multiple photos and/or videos as a single Telegram media group
     * (a.k.a. "album"). The first item carries the optional caption — this
     * matches official Telegram behaviour where the caption appears under
     * the first thumb and applies to the entire group.
     *
     * Mixing rules (Telegram-side, enforced by the server):
     *   - Photos and videos can share an album
     *   - Documents must be their own album (no mixing with photo/video)
     *   - All-or-nothing per kind in mixed-doc scenarios
     * The caller is expected to honour these — passing a mix of docs +
     * photos will surface a TDLib error.
     *
     * Album size limit is 10 items per TDLib; we DON'T enforce it here
     * since the photo picker already caps at 10. If a caller passes more
     * TDLib will reject the request and we propagate the error.
     */
    suspend fun sendMediaGroup(
        chatId: Long,
        items: List<MediaGroupItem>,
        caption: String? = null,
        replyToMessageId: Long? = null
    ) {
        if (items.isEmpty()) return
        val contents: Array<TdApi.InputMessageContent> = Array(items.size) { idx ->
            val it = items[idx]
            // Caption only on the FIRST item — TDLib carries it as the
            // group's caption when rendered. Captions on subsequent items
            // would each get their own line under the group, which isn't
            // what stock Telegram does.
            val itemCaption = if (idx == 0) caption?.let {
                TdApi.FormattedText(it, emptyArray())
            } else null
            when (it.kind) {
                MediaGroupItemKind.Photo -> TdApi.InputMessagePhoto(
                    TdApi.InputFileLocal(it.filePath),
                    null, null, intArrayOf(), 0, 0,
                    itemCaption, false, null, false
                )
                MediaGroupItemKind.Video -> TdApi.InputMessageVideo(
                    TdApi.InputFileLocal(it.filePath),
                    null, null, 0,
                    intArrayOf(),
                    0, 0, 0,
                    true,
                    itemCaption,
                    false, null, false
                )
                MediaGroupItemKind.Document -> TdApi.InputMessageDocument(
                    TdApi.InputFileLocal(it.filePath),
                    null, false,
                    itemCaption
                )
            }
        }
        send(TdApi.SendMessageAlbum(
            chatId, null, buildReplyTo(replyToMessageId), null, contents
        ))
    }

    /** Kind discriminator used by [sendMediaGroup]. */
    enum class MediaGroupItemKind { Photo, Video, Document }

    /** Single item inside a [sendMediaGroup] call. */
    data class MediaGroupItem(val filePath: String, val kind: MediaGroupItemKind)

    /** Recently used stickers (most-recently-sent first). */
    suspend fun getRecentStickers(): TdApi.Stickers =
        send(TdApi.GetRecentStickers(false))

    /** Stickers the user has explicitly favourited via Telegram. */
    suspend fun getFavoriteStickers(): TdApi.Stickers =
        send(TdApi.GetFavoriteStickers())

    /**
     * Search the global sticker corpus by free-text query. Returns up to
     * `limit` Stickers, sorted by TDLib relevance. `emojis` lets the caller
     * narrow to a specific emoji ("😂"); we pass empty for an open search.
     */
    suspend fun searchStickers(query: String, limit: Int = 40): TdApi.Stickers =
        send(TdApi.SearchStickers(
            TdApi.StickerTypeRegular(),
            "",          // emojis filter
            query,
            emptyArray(), // input_language_codes
            0,           // offset
            limit
        ))

    /**
     * Sticker sets the user has installed (i.e. the "My Stickers" library
     * in Telegram). Used by the picker's "Tutti" tab.
     */
    suspend fun getInstalledStickerSets(): TdApi.StickerSets =
        send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular()))

    /** Fetch one sticker set's full sticker list given its id. */
    suspend fun getStickerSet(setId: Long): TdApi.StickerSet =
        send(TdApi.GetStickerSet(setId))

    /**
     * Send a sticker the user already has in their stickers list. We pass
     * the sticker's existing file id via InputFileId; width/height/emoji
     * come straight from the TdApi.Sticker we picked.
     */
    suspend fun sendSticker(chatId: Long, sticker: TdApi.Sticker, replyToMessageId: Long? = null) {
        val content = TdApi.InputMessageSticker(
            TdApi.InputFileId(sticker.sticker.id),
            null,                       // thumbnail
            sticker.width,
            sticker.height,
            sticker.emoji
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    /**
     * Fetch members of a supergroup (or non-channel megagroup). filter=null
     * returns the most recently active members which is what we want for the
     * @-mention picker. limit capped at 200 by TDLib.
     */
    suspend fun getSupergroupMembers(supergroupId: Long, limit: Int = 100): TdApi.ChatMembers =
        send(TdApi.GetSupergroupMembers(supergroupId, null, 0, limit.coerceIn(1, 200)))

    /**
     * Filtered variant used by the chat-info "Membri" tab and the admin-label
     * cache. Pass SupergroupMembersFilterAdministrators() to list owner+admins,
     * SupergroupMembersFilterSearch(query) for username/name search, or
     * SupergroupMembersFilterRecent() for the default roster. Distinct name from
     * the no-filter overload above to avoid a default-argument call ambiguity.
     */
    suspend fun getSupergroupMembersFiltered(
        supergroupId: Long,
        filter: TdApi.SupergroupMembersFilter?,
        limit: Int = 200
    ): TdApi.ChatMembers =
        send(TdApi.GetSupergroupMembers(supergroupId, filter, 0, limit.coerceIn(1, 200)))

    suspend fun downloadFile(fileId: Int): TdApi.File {
        return send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
    }

    /**
     * Kick off a download WITHOUT waiting for completion (synchronous=false).
     * Returns the moment TDLib accepts the request; progress then streams via
     * UpdateFile, which TransferTracker turns into the top download badge.
     * Used so tapping "Apri" on an un-downloaded item shows feedback (the
     * badge) instantly, while the caller separately awaits the finished path.
     */
    suspend fun startDownload(fileId: Int) {
        runCatching { send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) }
    }

    /** Stop an in-flight download (used by the panel's cancel button). */
    suspend fun cancelDownloadFile(fileId: Int) {
        runCatching { send(TdApi.CancelDownloadFile(fileId, false)) }
    }

    /**
     * Variant of [downloadFile] that lets the caller specify a priority
     * 1..32. Used by background warmers (e.g. chat-info media gallery
     * thumbs) that want to populate the cache without competing for
     * bandwidth with foreground downloads the user is actively waiting
     * on. Priority 1 = lowest; 32 = highest (the default for ad-hoc
     * downloads).
     */
    suspend fun downloadFile(fileId: Int, priority: Int): TdApi.File {
        return send(TdApi.DownloadFile(fileId, priority.coerceIn(1, 32), 0, 0, true))
    }

    /**
     * Quick lookup of a file's current state — no download triggered.
     * TDLib mutates the [TdApi.File] objects we hand out (the same Java
     * reference) but Compose doesn't observe field mutation, so any reader
     * that wants the latest local.path / isDownloadingCompleted has to ask
     * TDLib again. Used by DownloadingImage on (re-)compose and by the
     * MessageBubble tap handler before opening media / documents.
     */
    suspend fun getFile(fileId: Int): TdApi.File = send(TdApi.GetFile(fileId))

    // ----- Profile -----

    /** Returns the currently signed-in user. */
    suspend fun getMe(): TdApi.User = send(TdApi.GetMe()).also { cachedMyUserId = it.id }

    /** Returns a specific user by id. */
    suspend fun getUser(userId: Long): TdApi.User = send(TdApi.GetUser(userId))

    /**
     * Returns extended user info (bio, photo, etc) that the lightweight User
     * object doesn't carry. Pulled lazily when the Profile screen opens.
     */
    suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo =
        send(TdApi.GetUserFullInfo(userId))

    // ===== Reactions =====

    /**
     * Add an emoji reaction to a message. `isBig=false` posts the normal-size
     * floating animation; `updateRecentReactions=true` adds the emoji to
     * the user's recent reactions list so it surfaces first next time.
     */
    suspend fun addEmojiReaction(chatId: Long, messageId: Long, emoji: String) {
        send(TdApi.AddMessageReaction(
            chatId, messageId,
            TdApi.ReactionTypeEmoji(emoji),
            /* isBig = */ false,
            /* updateRecentReactions = */ true
        ))
    }

    /** Remove a previously-added emoji reaction. No-op if it wasn't there. */
    suspend fun removeEmojiReaction(chatId: Long, messageId: Long, emoji: String) {
        send(TdApi.RemoveMessageReaction(
            chatId, messageId, TdApi.ReactionTypeEmoji(emoji)
        ))
    }

    // ===== Group / channel admin actions =====

    /**
     * Returns the caller's status in a chat — used to decide whether to
     * show admin actions like "delete for everyone" or "kick" in the UI.
     * For private chats the result is meaningless; only call for groups
     * and supergroups.
     */
    suspend fun getMyChatMember(chatId: Long, myUserId: Long): TdApi.ChatMember =
        send(TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(myUserId)))

    /**
     * Fetch any user's membership in a chat. Used by the message actions
     * sheet to detect whether the sender is the group CREATOR — admins
     * must not see ban/mute options against the owner. Returns null on
     * any error (e.g. user left the group) so callers fail safe to
     * "not owner" rather than crashing.
     */
    suspend fun getChatMemberStatus(chatId: Long, userId: Long): TdApi.ChatMemberStatus? =
        runCatching {
            send(TdApi.GetChatMember(chatId, TdApi.MessageSenderUser(userId))).status
        }.getOrNull()

    /**
     * Pin a message in a chat. `disableNotification=true` keeps it quiet
     * (no "pinned a message" service ping), `onlyForSelf` pins it only on
     * the current account (allowed in private chats). TDLib enforces the
     * permission server-side; we surface failures via runCatching upstream.
     */
    suspend fun pinChatMessage(
        chatId: Long,
        messageId: Long,
        disableNotification: Boolean = false,
        onlyForSelf: Boolean = false
    ) {
        send(TdApi.PinChatMessage(chatId, messageId, disableNotification, onlyForSelf))
    }

    suspend fun unpinChatMessage(chatId: Long, messageId: Long) {
        send(TdApi.UnpinChatMessage(chatId, messageId))
    }

    /**
     * Pin / unpin a CHAT (not a message) to the top of the user's
     * chat list. Telegram caps the number of pinned chats per list
     * (~5 non-premium, 10 premium); TDLib returns an error in that
     * case which our caller can surface.
     *
     * The chatList argument selects which list to pin in (Main vs
     * Archive); we default to Main since the Archiviati tab in our
     * UI is its own surface and users rarely re-pin within archive.
     */
    suspend fun toggleChatIsPinned(chatId: Long, isPinned: Boolean) {
        send(TdApi.ToggleChatIsPinned(TdApi.ChatListMain(), chatId, isPinned))
    }

    /**
     * Configure TDLib's OWN auto-download presets across all network
     * types. This is separate from our per-bubble gating: TDLib will
     * eagerly pull photos/small files in the background according to its
     * presets regardless of what our UI does, so to truly honor the
     * "auto-download off" toggle we must disable it at the TDLib level
     * too. A no-arg AutoDownloadSettings is all-disabled; when enabled we
     * set generous caps so normal media still flows.
     */
    suspend fun applyAutoDownloadSetting(enabled: Boolean) {
        val s = TdApi.AutoDownloadSettings()
        s.isAutoDownloadEnabled = enabled
        if (enabled) {
            s.maxPhotoFileSize = 10 * 1024 * 1024
            s.maxVideoFileSize = 50L * 1024 * 1024
            s.maxOtherFileSize = 50L * 1024 * 1024
        }
        val types = listOf(
            TdApi.NetworkTypeWiFi(),
            TdApi.NetworkTypeMobile(),
            TdApi.NetworkTypeMobileRoaming(),
            TdApi.NetworkTypeOther()
        )
        for (t in types) {
            runCatching { send(TdApi.SetAutoDownloadSettings(s, t)) }
        }
    }

    /**
     * Whether the current user can pin messages in this chat. Private
     * chats always allow it (pin-for-self). Groups/channels require the
     * member to be creator or an admin whose rights include canPinMessages.
     */
    suspend fun canPinMessages(chatId: Long): Boolean {
        val chat = getCachedChat(chatId) ?: return false
        if (chat.type is TdApi.ChatTypePrivate || chat.type is TdApi.ChatTypeSecret) return true
        val me = runCatching { getMe().id }.getOrNull() ?: return false
        val status = getChatMemberStatus(chatId, me)
        return when (status) {
            is TdApi.ChatMemberStatusCreator -> true
            is TdApi.ChatMemberStatusAdministrator -> status.rights?.canPinMessages == true
            else -> false
        }
    }

    /**
     * Kick a user from a group: ban with bannedUntilDate=0 means permanent.
     * In groups (vs supergroups/channels) "ban" is the only way to remove
     * a member — there's no separate "kick" operation in TDLib.
     */
    suspend fun kickGroupUser(chatId: Long, userId: Long) {
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusBanned(0)
        ))
    }

    /**
     * Reverse of [kickGroupUser]. Restore the user to plain member status
     * (no admin rights, no restrictions). Called by the admin action sheet
     * when the user is already banned — same tap, opposite direction —
     * so a quick toggle works without leaving the sheet.
     */
    suspend fun unbanGroupUser(chatId: Long, userId: Long) {
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusMember(0)
        ))
    }

    /**
     * Mute a user in a group: restrict them from sending messages of any
     * kind, but leave them as a member so they can still read. Forever
     * (restrictedUntilDate=0). Reading-only permissions are derived from
     * "all false" on the chatPermissions object.
     */
    suspend fun muteGroupUser(chatId: Long, userId: Long) {
        val noWrite = TdApi.ChatPermissions(
            false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false
        )
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusRestricted(true, 0, noWrite)
        ))
    }

    /**
     * Reverse of [muteGroupUser]. Lift all restrictions and return the
     * user to plain member status. Used by the admin action sheet so the
     * mute tile toggles in place — once a user is muted, the next long
     * press shows "Smuta" and tapping it routes through here.
     */
    suspend fun unmuteGroupUser(chatId: Long, userId: Long) {
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusMember(0)
        ))
    }

    /**
     * Promote a member to administrator with a sensible default rights set
     * (manage chat, change info, delete messages, restrict/ban, pin, invite,
     * manage topics & video chats) but deliberately WITHOUT can_promote_members
     * — handing out the ability to mint more admins is a heavier decision than
     * a one-tap promote should make. The owner can still grant that from
     * Telegram's full admin editor if they want it.
     */
    suspend fun promoteToAdmin(chatId: Long, userId: Long) {
        val rights = TdApi.ChatAdministratorRights(
            true,  // canManageChat
            true,  // canChangeInfo
            false, // canPostMessages (channels only)
            false, // canEditMessages (channels only)
            true,  // canDeleteMessages
            true,  // canInviteUsers
            true,  // canRestrictMembers
            true,  // canPinMessages
            true,  // canManageTopics
            false, // canPromoteMembers
            true,  // canManageVideoChats
            false, // canPostStories
            false, // canEditStories
            false, // canDeleteStories
            false, // canManageDirectMessages
            false, // canManageTags
            false  // isAnonymous
        )
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusAdministrator(true, rights)
        ))
    }

    /** Set the group's DEFAULT permissions (apply to every regular member). */
    suspend fun setChatPermissions(chatId: Long, permissions: TdApi.ChatPermissions) {
        send(TdApi.SetChatPermissions(chatId, permissions))
    }

    /**
     * Restrict a single member to the given permissions (granular per-user,
     * the "Permessi utente" screen). isMember stays true so they remain in the
     * group; untilDate 0 means forever.
     *
     * IMPORTANT: if the new permissions give the member back everything the
     * group's default allows (i.e. they're no longer denied anything), set
     * plain Member status instead of a Restricted-with-full-permissions.
     * TDLib does NOT auto-collapse a Restricted status whose permissions match
     * the default back to Member, so without this the "Limitato" label stuck
     * forever after re-enabling a permission (Eugenio: re-adding media didn't
     * clear "Limitato"). Mirrors how [unmuteGroupUser] returns to Member.
     */
    suspend fun restrictMember(
        chatId: Long,
        userId: Long,
        permissions: TdApi.ChatPermissions
    ) {
        val def = getCachedChat(chatId)?.permissions
        val noLongerRestricted = def != null && permissionsCoverDefault(permissions, def)
        val status: TdApi.ChatMemberStatus = if (noLongerRestricted) {
            TdApi.ChatMemberStatusMember(0)
        } else {
            TdApi.ChatMemberStatusRestricted(true, 0, permissions)
        }
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId), status
        ))
    }

    /**
     * True when [p] is not more restrictive than the group default [def] on any
     * permission the per-member editor can control — i.e. the member isn't
     * denied anything regular members are allowed, so they count as
     * unrestricted. can_create_topics is excluded because the member-permission
     * dialog never grants it (it isn't a toggle there), so it must not by
     * itself keep a member flagged as restricted.
     */
    private fun permissionsCoverDefault(
        p: TdApi.ChatPermissions,
        def: TdApi.ChatPermissions
    ): Boolean {
        // For each field: a denial only counts if the default GRANTS it but the
        // member does NOT (default true && member false). Otherwise fine.
        fun ok(member: Boolean, default: Boolean) = !default || member
        return ok(p.canSendBasicMessages, def.canSendBasicMessages) &&
            ok(p.canSendAudios, def.canSendAudios) &&
            ok(p.canSendDocuments, def.canSendDocuments) &&
            ok(p.canSendPhotos, def.canSendPhotos) &&
            ok(p.canSendVideos, def.canSendVideos) &&
            ok(p.canSendVideoNotes, def.canSendVideoNotes) &&
            ok(p.canSendVoiceNotes, def.canSendVoiceNotes) &&
            ok(p.canSendPolls, def.canSendPolls) &&
            ok(p.canSendOtherMessages, def.canSendOtherMessages) &&
            ok(p.canAddLinkPreviews, def.canAddLinkPreviews) &&
            ok(p.canReactToMessages, def.canReactToMessages) &&
            ok(p.canEditTag, def.canEditTag) &&
            ok(p.canChangeInfo, def.canChangeInfo) &&
            ok(p.canInviteUsers, def.canInviteUsers) &&
            ok(p.canPinMessages, def.canPinMessages)
    }

    /**
     * Promote (or re-edit) an admin with EXPLICIT rights — drives the admin
     * rights picker. canBeEdited is always true so the creator can later
     * adjust or revoke. Replaces the fixed-rights [promoteToAdmin] when the
     * user chooses powers from the dialog.
     */
    suspend fun setAdminRights(
        chatId: Long,
        userId: Long,
        rights: TdApi.ChatAdministratorRights
    ) {
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusAdministrator(true, rights)
        ))
    }

    /** Add members to an existing chat (used when populating a fresh public supergroup). */
    suspend fun addChatMembers(chatId: Long, userIds: List<Long>) {
        if (userIds.isEmpty()) return
        runCatching { send(TdApi.AddChatMembers(chatId, userIds.toLongArray())) }
    }

    /** Set (non-blank) or clear (blank → private) a supergroup's public username. */
    suspend fun setSupergroupUsername(supergroupId: Long, username: String) {
        send(TdApi.SetSupergroupUsername(supergroupId, username))
    }

    /**
     * Upgrade a basic group to a supergroup. IRREVERSIBLE and the chat id
     * CHANGES — the returned id points at the new supergroup chat, the old
     * basic-group chat becomes a read-only "upgraded" stub. Needed before a
     * basic group can be made public (only supergroups carry usernames).
     */
    suspend fun upgradeToSupergroup(chatId: Long): Long? {
        val chat = runCatching {
            send(TdApi.UpgradeBasicGroupChatToSupergroupChat(chatId))
        }.getOrNull()
        return (chat as? TdApi.Chat)?.id
    }

    /**
     * Validate a desired public username. Pass chatId = 0 to check for a
     * brand-new chat (creation flow), or an existing chat id when editing.
     */
    suspend fun checkChatUsername(chatId: Long, username: String): TdApi.CheckChatUsernameResult? =
        runCatching { send(TdApi.CheckChatUsername(chatId, username)) }.getOrNull()
            as? TdApi.CheckChatUsernameResult

    /**
     * Create a PUBLIC group: a supergroup (createNewSupergroupChat takes no
     * members, unlike a basic group) that we then populate via addChatMembers
     * and make public via setSupergroupUsername. Returns the new chat id.
     */
    suspend fun createPublicGroup(
        title: String,
        username: String,
        userIds: List<Long>
    ): Long? {
        val chat = runCatching {
            send(TdApi.CreateNewSupergroupChat(title, false, false, "", null, 0, false))
        }.getOrNull() as? TdApi.Chat ?: return null
        val sgId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        addChatMembers(chat.id, userIds)
        if (sgId != null && username.isNotBlank()) {
            runCatching { send(TdApi.SetSupergroupUsername(sgId, username)) }
        }
        return chat.id
    }

    /**
     * Create a broadcast channel (a supergroup with isChannel = true). Optional
     * description is set at creation; a non-blank [username] makes it public
     * (t.me/<username>), otherwise it's private. Returns the new chat id.
     */
    suspend fun createChannel(
        title: String,
        description: String,
        username: String
    ): Long? {
        val chat = runCatching {
            send(TdApi.CreateNewSupergroupChat(title, false, true, description, null, 0, false))
        }.getOrNull() as? TdApi.Chat ?: return null
        val sgId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        if (sgId != null && username.isNotBlank()) {
            runCatching { send(TdApi.SetSupergroupUsername(sgId, username)) }
        }
        return chat.id
    }
    suspend fun getSupergroup(supergroupId: Long): TdApi.Supergroup? =
        runCatching { send(TdApi.GetSupergroup(supergroupId)) }.getOrNull() as? TdApi.Supergroup

    /**
     * Current public username of a group, or null if it's private (no username
     * or a basic group). Used by the edit sheet to seed the Public/Private UI.
     */
    suspend fun groupPublicUsername(chatId: Long): String? {
        val chat = runCatching { getChat(chatId) }.getOrNull() ?: return null
        val sgId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        val sg = getSupergroup(sgId) ?: return null
        return sg.usernames?.editableUsername?.takeIf { it.isNotBlank() }
            ?: sg.usernames?.activeUsernames?.firstOrNull()
    }

    /**
     * Make a group public by giving it [username]. A basic group is first
     * upgraded to a supergroup (irreversible, the chat id changes). Returns
     * true on success.
     */
    suspend fun makeGroupPublic(chatId: Long, username: String): Boolean {
        val chat = runCatching { getChat(chatId) }.getOrNull() ?: return false
        val sgId = when (val t = chat.type) {
            is TdApi.ChatTypeSupergroup -> t.supergroupId
            is TdApi.ChatTypeBasicGroup -> {
                val up = runCatching {
                    send(TdApi.UpgradeBasicGroupChatToSupergroupChat(chatId))
                }.getOrNull() as? TdApi.Chat ?: return false
                (up.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return false
            }
            else -> return false
        }
        return runCatching { send(TdApi.SetSupergroupUsername(sgId, username)); true }
            .getOrDefault(false)
    }

    /** Make a group private by clearing its username. Basic groups are already private. */
    suspend fun makeGroupPrivate(chatId: Long): Boolean {
        val sgId = (runCatching { getChat(chatId) }.getOrNull()?.type
            as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return true
        return runCatching { send(TdApi.SetSupergroupUsername(sgId, "")); true }
            .getOrDefault(false)
    }

    /** Reverse of [promoteToAdmin]: drop the user back to a plain member. */
    suspend fun demoteFromAdmin(chatId: Long, userId: Long) {
        send(TdApi.SetChatMemberStatus(
            chatId, TdApi.MessageSenderUser(userId),
            TdApi.ChatMemberStatusMember(0)
        ))
    }

    /**
     * Fetch the list of users who reacted to a message with a specific
     * reaction type. Used by MessageActionsSheet to render the avatar
     * stack next to each reaction chip. limit=12 fits one row of small
     * avatars on a phone with a "+N" overflow indicator for the rest.
     * Returns an empty list on any error so the caller can render a
     * graceful no-viewers state instead of crashing the sheet.
     */
    suspend fun getMessageAddedReactions(
        chatId: Long,
        messageId: Long,
        reactionType: TdApi.ReactionType,
        limit: Int = 12
    ): List<TdApi.MessageSender> = runCatching {
        send(TdApi.GetMessageAddedReactions(chatId, messageId, reactionType, "", limit))
            .reactions.map { it.senderId }
    }.getOrDefault(emptyList())

    // ===== Bot commands =====

    /**
     * Return the list of /commands a bot exposes in a chat. For private
     * chats the bot's commands come from its UserFullInfo.botInfo.commands.
     * For groups we ask TDLib with a chat-scoped query so we get the
     * commands the bot is actually offering in *that* group (which can
     * differ from its private-chat command set).
     */
    suspend fun getBotCommandsForChat(chatId: Long): List<BotCommandItem> {
        // Two sources, merged (deduped by command name, group-scope first):
        //
        // 1) GROUP-SCOPE: commands a bot registered specifically for THIS
        //    group live on the group's full info — SupergroupFullInfo.
        //    botCommands / BasicGroupFullInfo.botCommands. (GetCommands is a
        //    bot-only method and returns nothing for a normal user account,
        //    which is why a chat-scoped query was useless here.)
        //
        // 2) DEFAULT-SCOPE: but MOST bots only register their commands in the
        //    default scope, which does NOT appear in the group's full info —
        //    so the picker came up empty in groups (e.g. an admin bot's
        //    /mute). Official Telegram, when you type "/" in a group, also
        //    shows each bot member's default command set. We replicate that:
        //    enumerate the group's bot members and pull each one's
        //    UserFullInfo.botInfo.commands. Each command is tagged with its
        //    bot's @username so the caller can send "/cmd@bot" in groups.
        val chat = getCachedChat(chatId)
        val merged = LinkedHashMap<String, BotCommandItem>()

        suspend fun usernameOf(botId: Long): String? {
            val u = getCachedUser(botId) ?: runCatching { getUser(botId) }.getOrNull()
            return u?.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: u?.usernames?.editableUsername?.takeIf { it.isNotBlank() }
        }

        val groupScoped: Array<TdApi.BotCommands>? = when (val t = chat?.type) {
            is TdApi.ChatTypeSupergroup ->
                runCatching { getSupergroupFullInfo(t.supergroupId) }.getOrNull()?.botCommands
            is TdApi.ChatTypeBasicGroup ->
                runCatching { getBasicGroupFullInfo(t.basicGroupId) }.getOrNull()?.botCommands
            else -> null
        }
        groupScoped?.forEach { bc ->
            val uname = usernameOf(bc.botUserId)
            bc.commands.forEach { c ->
                merged.putIfAbsent(c.command, BotCommandItem(c.command, c.description, bc.botUserId, uname))
            }
        }

        val botIds = LinkedHashSet<Long>()
        when (val t = chat?.type) {
            is TdApi.ChatTypeSupergroup -> {
                val members = runCatching {
                    send(
                        TdApi.GetSupergroupMembers(
                            t.supergroupId,
                            TdApi.SupergroupMembersFilterBots(),
                            0, 50
                        )
                    )
                }.getOrNull()
                members?.members?.forEach { m ->
                    (m.memberId as? TdApi.MessageSenderUser)?.let { botIds.add(it.userId) }
                }
            }
            is TdApi.ChatTypeBasicGroup -> {
                val full = runCatching { getBasicGroupFullInfo(t.basicGroupId) }.getOrNull()
                full?.members?.forEach { m ->
                    val uid = (m.memberId as? TdApi.MessageSenderUser)?.userId ?: return@forEach
                    val u = getCachedUser(uid) ?: runCatching { getUser(uid) }.getOrNull()
                    if (u?.type is TdApi.UserTypeBot) botIds.add(uid)
                }
            }
            else -> {}
        }
        for (botId in botIds) {
            val cmds = runCatching { getUserFullInfo(botId) }.getOrNull()
                ?.botInfo?.commands ?: continue
            val uname = usernameOf(botId)
            cmds.forEach { c ->
                merged.putIfAbsent(c.command, BotCommandItem(c.command, c.description, botId, uname))
            }
        }
        return merged.values.toList()
    }

    /**
     * Tap a Callback inline-keyboard button on a bot message. TDLib
     * round-trips through the bot's webhook and returns a
     * CallbackQueryAnswer with optional text/url/alert flag — we surface
     * those to the caller so the UI can show a toast / open a URL /
     * pop a dialog as the bot intends.
     *
     * `payload` is the `data` field from the button (a small opaque byte
     * blob the bot uses to route the callback on its side). We pass it
     * wrapped in CallbackQueryPayloadData. The other payload kinds
     * (Game / PasswordCheck) are rare in user-facing flows; we handle
     * the basic Data path here and fail soft on the rest.
     */
    suspend fun sendCallbackQuery(
        chatId: Long,
        messageId: Long,
        data: ByteArray
    ): TdApi.CallbackQueryAnswer {
        return send(
            TdApi.GetCallbackQueryAnswer(
                chatId, messageId,
                TdApi.CallbackQueryPayloadData(data)
            )
        ) as TdApi.CallbackQueryAnswer
    }

    /** Full info about a basic group, including its description and members. */
    suspend fun getBasicGroupFullInfo(basicGroupId: Long): TdApi.BasicGroupFullInfo =
        send(TdApi.GetBasicGroupFullInfo(basicGroupId))

    /** Full info about a supergroup or channel: description, member count, link. */
    suspend fun getSupergroupFullInfo(supergroupId: Long): TdApi.SupergroupFullInfo =
        send(TdApi.GetSupergroupFullInfo(supergroupId))

    // ---- Admin: edit group profile ----

    /** Rename a group / channel (admin only; TDLib rejects otherwise). */
    suspend fun setChatTitle(chatId: Long, title: String) {
        send(TdApi.SetChatTitle(chatId, title))
    }

    /** Set a group / channel description (bio). Empty string clears it. */
    suspend fun setChatDescription(chatId: Long, description: String) {
        send(TdApi.SetChatDescription(chatId, description))
    }

    /** Replace the group / channel photo from a local image file path. */
    suspend fun setChatPhoto(chatId: Long, localPath: String) {
        send(
            TdApi.SetChatPhoto(
                chatId,
                TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(localPath))
            )
        )
    }

    /**
     * The group's shareable invite link. Reads the primary link already on
     * the full info when present; otherwise asks TDLib to mint one. Returns
     * null if neither works (e.g. not enough rights).
     */
    suspend fun getOrCreatePrimaryInviteLink(chatId: Long): String? {
        val chat = getCachedChat(chatId) ?: return null
        val existing = when (val t = chat.type) {
            is TdApi.ChatTypeSupergroup -> runCatching {
                getSupergroupFullInfo(t.supergroupId).inviteLink?.inviteLink
            }.getOrNull()
            is TdApi.ChatTypeBasicGroup -> runCatching {
                getBasicGroupFullInfo(t.basicGroupId).inviteLink?.inviteLink
            }.getOrNull()
            else -> null
        }
        if (!existing.isNullOrBlank()) return existing
        return runCatching {
            send(TdApi.CreateChatInviteLink(chatId, "", 0, 0, false)).inviteLink
        }.getOrNull()
    }

    // ---- Block list (global user blocking, distinct from group ban) ----

    /** Block a user globally (they can't message you / see your status). */
    suspend fun blockUser(userId: Long) {
        send(
            TdApi.SetMessageSenderBlockList(
                TdApi.MessageSenderUser(userId),
                TdApi.BlockListMain()
            )
        )
    }

    /** Remove a user from the block list. */
    suspend fun unblockUser(userId: Long) {
        send(TdApi.SetMessageSenderBlockList(TdApi.MessageSenderUser(userId), null))
    }

    /** The user ids currently on the main block list. */
    suspend fun getBlockedUserIds(limit: Int = 200): List<Long> = runCatching {
        send(TdApi.GetBlockedMessageSenders(TdApi.BlockListMain(), 0, limit))
            .senders
            .mapNotNull { (it as? TdApi.MessageSenderUser)?.userId }
    }.getOrDefault(emptyList())

    /** Whether [userId] is currently blocked (reads the user's full info). */
    suspend fun isUserBlocked(userId: Long): Boolean = runCatching {
        getUserFullInfo(userId).blockList is TdApi.BlockListMain
    }.getOrDefault(false)


    /**
     * Who has *viewed* this message in the group (distinct from who
     * reacted). Returns the viewer user ids in TDLib's order. TDLib only
     * yields a non-empty set for our own recently-sent messages, in groups
     * small enough to track read receipts, and within the view window;
     * outside those conditions it returns an empty set and the caller
     * simply renders no "seen by" row. Wrapped in runCatching because
     * TDLib errors (message too old, group too large, channel) come back
     * as exceptions and we treat them all as "no viewers".
     */
    suspend fun getMessageViewers(chatId: Long, messageId: Long): List<Long> {
        val res = runCatching {
            send(TdApi.GetMessageViewers(chatId, messageId))
        }.getOrNull() ?: return emptyList()
        return res.viewers.map { it.userId }.filter { it != 0L }
    }

    /** Update the signed-in user's first/last name (last name may be empty). */
    suspend fun setName(firstName: String, lastName: String) {
        send(TdApi.SetName(firstName, lastName))
    }

    /**
     * Update the signed-in user's bio (about). Pass an empty string to clear.
     * Telegram limits the bio length (currently 70 chars for free, 140 for premium).
     */
    suspend fun setBio(bio: String) {
        send(TdApi.SetBio(bio))
    }

    /**
     * Update the signed-in user's public @username. Pass an empty string to
     * remove. Telegram rejects taken usernames; runCatching at the call site.
     */
    suspend fun setUsername(username: String) {
        send(TdApi.SetUsername(username))
    }

    /**
     * Replace the signed-in user's profile photo from a local image file path.
     * isPublic=true makes it visible to non-contacts (default Telegram).
     */
    suspend fun setProfilePhoto(filePath: String, isPublic: Boolean = true) {
        send(TdApi.SetProfilePhoto(TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(filePath)), isPublic))
    }

    // ----- Contacts & creating chats -----

    /**
     * Returns the user ids of every Telegram contact synced for this account.
     * Resolve to TdApi.User via getUser() (or pull from the cache that TDLib
     * keeps internally and that we mirror via UpdateUser).
     */
    suspend fun getContacts(): LongArray {
        val r = send(TdApi.GetContacts()) as TdApi.Users
        return r.userIds
    }

    /** Search Telegram contacts by name or username, server-side. */
    suspend fun searchContacts(query: String, limit: Int = 50): LongArray {
        if (query.isBlank()) return LongArray(0)
        val r = send(TdApi.SearchContacts(query, limit)) as TdApi.Users
        return r.userIds
    }

    /**
     * Hand TDLib a batch of phone-book contacts so it can tell us which ones
     * already have a Telegram account. The returned LongArray pairs index-wise
     * with the input list: position i is the userId of contacts[i], or 0 if
     * that phone number is not on Telegram.
     */
    suspend fun importContacts(contacts: List<PhoneContact>): LongArray {
        if (contacts.isEmpty()) return LongArray(0)
        val arr = Array(contacts.size) { i ->
            val c = contacts[i]
            TdApi.ImportedContact(c.phoneNumber, c.firstName, c.lastName, TdApi.FormattedText("", emptyArray()))
        }
        val r = send(TdApi.ImportContacts(arr)) as TdApi.ImportedContacts
        return r.userIds
    }

    /**
     * Open (or create) a 1:1 chat with the given Telegram user id. With
     * force=true TDLib will create the chat eagerly even if it didn't exist
     * before, which is what we want for the "new chat" flow.
     */
    suspend fun createPrivateChat(userId: Long, force: Boolean = true): TdApi.Chat {
        return send(TdApi.CreatePrivateChat(userId, force))
    }

    /**
     * Delete messages. `revoke=true` removes the messages for everyone in the
     * chat when allowed (private chats, own messages in groups), otherwise it
     * only deletes the local copy.
     *
     * Emits the DeleteEvent OPTIMISTICALLY before the TDLib call so the UI
     * can drop the bubbles instantly — without this, weak-connection users
     * tap "Elimina per me", the sheet closes, and the message stays
     * visible for 1-3 seconds while the round-trip completes, which reads
     * as "il delete non funziona". If the TDLib call ultimately fails the
     * message will reappear on the next history fetch (rare); the
     * trade-off is right.
     */
    suspend fun deleteMessages(chatId: Long, messageIds: LongArray, revoke: Boolean) {
        _deletedMessages.emit(DeleteEvent(chatId, messageIds))
        send(TdApi.DeleteMessages(chatId, messageIds, revoke))
    }

    /**
     * Chats the user just asked us to delete. We don't actually mutate
     * chatCache (that would corrupt state if the delete fails) — instead
     * refreshChats() filters these ids out of the published list, so the
     * row vanishes instantly even on weak connections. We clear the
     * entry once TDLib echoes via UpdateChatPosition (order=0 on
     * Main list) confirming the delete landed, OR if the TDLib call
     * throws.
     */
    private val hiddenChats = mutableSetOf<Long>()

    /**
     * Delete the entire history of a chat. removeFromChatList=true also drops
     * the chat from the user's chat list (Telegram's "Delete chat" behaviour).
     * revoke=true erases the messages for everyone where permitted.
     *
     * Optimistically hides the chat from the chat list BEFORE the TDLib
     * round-trip completes — see [hiddenChats]. Restores it on failure
     * so the user can retry.
     */
    suspend fun deleteChatHistory(chatId: Long, removeFromChatList: Boolean, revoke: Boolean) {
        if (removeFromChatList) {
            hiddenChats.add(chatId)
            refreshChats()
        }
        try {
            send(TdApi.DeleteChatHistory(chatId, removeFromChatList, revoke))
        } catch (t: Throwable) {
            hiddenChats.remove(chatId)
            refreshChats()
            throw t
        }
    }

    /**
     * Leave a group, supergroup or channel. For private chats this is a
     * no-op on TDLib's side — those should use deleteChatHistory instead.
     */
    suspend fun leaveChat(chatId: Long) {
        send(TdApi.LeaveChat(chatId))
    }

    /**
     * Per-message properties (can be deleted for all, can be forwarded,
     * can be edited, etc). TDLib stopped exposing these flags on Message
     * directly — they live on this separate object now, fetched on demand.
     * Used by the message-actions sheet to decide which buttons to show.
     */
    suspend fun getMessageProperties(chatId: Long, messageId: Long): TdApi.MessageProperties =
        send(TdApi.GetMessageProperties(chatId, messageId))

    /**
     * Fetch a single message by chat + id. Used by the reply-quote bar
     * in MessageBubble when the inline preview carried by
     * [TdApi.MessageReplyToMessage] isn't enough (or isn't present in
     * older message records). Returns null if the message has been
     * deleted on the server or TDLib can't find it.
     */
    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? =
        runCatching { send(TdApi.GetMessage(chatId, messageId)) as? TdApi.Message }
            .getOrNull()

    /**
     * Forward messages from one chat to another. message_ids must be sorted
     * ascending. send_copy=false preserves the original "Forwarded from"
     * header; remove_caption=false keeps any captions intact.
     */
    suspend fun forwardMessages(toChatId: Long, fromChatId: Long, messageIds: LongArray) {
        send(TdApi.ForwardMessages(
            toChatId,
            null,           // topic_id
            fromChatId,
            messageIds,
            null,           // options
            false,          // send_copy
            false           // remove_caption
        ))
    }

    /**
     * List everyone who reacted to a message. Passing null reactionType
     * returns reactions of all kinds; we use this for the "who reacted"
     * sheet and group by emoji on the client side.
     */
    suspend fun getMessageAddedReactions(chatId: Long, messageId: Long): TdApi.AddedReactions =
        send(TdApi.GetMessageAddedReactions(chatId, messageId, null, "", 100))

    /**
     * Most-recently-pinned message in a chat. Returns null if nothing is
     * pinned or TDLib hasn't synced the chat yet. Used to drive the
     * "pinned" banner at the top of ChatScreen.
     */
    suspend fun getChatPinnedMessage(chatId: Long): TdApi.Message? =
        runCatching { send(TdApi.GetChatPinnedMessage(chatId)) }.getOrNull()

    /**
     * Move a chat into the Archive list (or back to Main). TDLib's
     * AddChatToList replaces the chat's list membership; the chat-position
     * update that follows refreshes our chats flow so the list re-sorts
     * and the chat moves between the main tabs and the Archiviati tab.
     */
    suspend fun archiveChat(chatId: Long, archived: Boolean) {
        val list: TdApi.ChatList =
            if (archived) TdApi.ChatListArchive() else TdApi.ChatListMain()
        runCatching { send(TdApi.AddChatToList(chatId, list)) }
    }

    /**
     * Full-text search through the messages of a chat. Returns the matching
     * messages newest-first. Powers the chat search bar (with up/down
     * arrows cycling through results).
     */
    suspend fun searchInChat(chatId: Long, query: String, limit: Int = 100): List<TdApi.Message> {
        if (query.isBlank()) return emptyList()
        val res = runCatching {
            send(TdApi.SearchChatMessages(
                chatId,
                null,         // topic_id
                query,        // query
                null,         // sender_id
                0L,           // from_message_id (0 = newest)
                0,            // offset
                limit,        // limit
                null          // filter (no filter = text search)
            ))
        }.getOrNull() ?: return emptyList()
        return res.messages.toList()
    }

    /**
     * All pinned messages in a chat (channels can have many). We cap at
     * 20 because the banner only cycles through visible ones; pulling
     * everything would slow first-render.
     */
    suspend fun searchPinnedMessages(chatId: Long): List<TdApi.Message> {
        val res = runCatching {
            send(TdApi.SearchChatMessages(
                chatId,
                null,                                  // topic_id
                "",                                    // query
                null,                                  // sender_id
                0L,                                    // from_message_id (0 = newest)
                0,                                     // offset
                20,                                    // limit
                TdApi.SearchMessagesFilterPinned()
            ))
        }.getOrNull() ?: return emptyList()
        return res.messages.toList()
    }

    /**
     * Find the FIRST unread @-mention or reply-to-me message in a chat.
     * Used by the in-chat "@N" chip that lets the user jump straight to
     * a pending mention without scrolling manually. Returns null when
     * there is no unread mention (the chip is hidden in that case).
     */
    suspend fun findFirstUnreadMention(chatId: Long): TdApi.Message? {
        val res = runCatching {
            send(TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                0L,
                0,
                1,
                TdApi.SearchMessagesFilterUnreadMention()
            ))
        }.getOrNull() ?: return null
        return res.messages.firstOrNull()
    }

    /**
     * Find the first unread reaction TO YOUR messages in a chat. Used
     * by the "♥N" chip alongside findFirstUnreadMention. TDLib returns
     * the message id; the caller jumps to it via the existing
     * jumpToMessage path and marks reactions read.
     */
    suspend fun findFirstUnreadReaction(chatId: Long): TdApi.Message? {
        val res = runCatching {
            send(TdApi.SearchChatMessages(
                chatId,
                null,
                "",
                null,
                0L,
                0,
                1,
                TdApi.SearchMessagesFilterUnreadReaction()
            ))
        }.getOrNull() ?: return null
        return res.messages.firstOrNull()
    }

    /** Mark all @-mentions in a chat as read. */
    suspend fun readAllChatMentions(chatId: Long) {
        runCatching { send(TdApi.ReadAllChatMentions(chatId)) }
    }

    /** Mark all reactions in a chat as read. */
    suspend fun readAllChatReactions(chatId: Long) {
        runCatching { send(TdApi.ReadAllChatReactions(chatId)) }
    }

    /** Total size (bytes) of TDLib's downloaded files — fast, no full scan. */
    suspend fun storageFilesSize(): Long {
        val s = runCatching { send(TdApi.GetStorageStatisticsFast()) }
            .getOrNull() as? TdApi.StorageStatisticsFast
        return s?.filesSize ?: 0L
    }

    /**
     * Delete every downloaded media/cache file TDLib is holding, WITHOUT
     * touching the account or session (no logout). All limits set to 0 and
     * immunity_delay 0 means "nothing is spared". return_deleted_file_statistics
     * = true makes the returned StorageStatistics describe the DELETED files, so
     * .size is the number of bytes actually freed.
     */
    suspend fun clearTdlibDownloads(): Long {
        val stats = runCatching {
            send(
                TdApi.OptimizeStorage(
                    0L,            // size: keep 0 bytes
                    0,             // ttl
                    0,             // count
                    0,             // immunity_delay: nothing immune
                    emptyArray(),  // file_types: all
                    longArrayOf(), // chat_ids: all
                    longArrayOf(), // exclude_chat_ids
                    true,          // return_deleted_file_statistics
                    0              // chat_limit
                )
            )
        }.getOrNull() as? TdApi.StorageStatistics
        return stats?.size ?: 0L
    }

    /**
     * Locally decrement the chat's unread mention count by 1 and notify
     * subscribers. Used by the @-mention chip click flow: tapping the
     * chip navigates to ONE specific mention message — the chip should
     * update immediately to reflect that one mention has been
     * "consumed", even though TDLib's server-side decrement (via
     * ViewMessages on the visited message) is async and can lag.
     *
     * Without this local patch, the chip stayed visible after a tap
     * because the StateFlow had not yet observed the count drop —
     * Eugenio's complaint that "il badge non si rimuove una volta
     * cliccato". The TDLib echo via UpdateChatUnreadMentionCount still
     * arrives later and tops up to whatever the server thinks; if we
     * over-decremented (very unlikely race), the next echo corrects.
     */
    fun decrementChatMentionCount(chatId: Long) {
        val c = chatCache[chatId] ?: return
        if (c.unreadMentionCount > 0) {
            c.unreadMentionCount = c.unreadMentionCount - 1
            scope.launch { _chatUpdates.emit(chatId) }
            refreshChats()
        }
    }

    /**
     * Optimistically ZERO the unread mention count so the in-chat "@" chip
     * vanishes the instant it's tapped. Tapping the chip calls
     * readAllChatMentions, which clears every mention in the chat
     * server-side; the UpdateChatUnreadMentionCount echo then confirms 0.
     * Without this the chip waited on that echo — which lags and is
     * occasionally never delivered — so it appeared stuck (Eugenio's "il
     * badge menzione cliccando non sparisce"). Unlike
     * [decrementChatMentionCount] this drops the count straight to 0
     * because "read all" consumes every pending mention at once.
     */
    fun clearChatMentionCountLocal(chatId: Long) {
        val c = chatCache[chatId] ?: return
        if (c.unreadMentionCount != 0) {
            c.unreadMentionCount = 0
            scope.launch { _chatUpdates.emit(chatId) }
            refreshChats()
        }
    }

    /** Reaction-side companion of [decrementChatMentionCount]. */
    fun decrementChatReactionCount(chatId: Long) {
        val c = chatCache[chatId] ?: return
        if (c.unreadReactionCount > 0) {
            c.unreadReactionCount = c.unreadReactionCount - 1
            scope.launch { _chatUpdates.emit(chatId) }
            refreshChats()
        }
    }

    /**
     * Reaction-side companion of [clearChatMentionCountLocal]. Optimistically
     * zeroes the unread reaction count so the in-chat "♥" chip vanishes the
     * instant the reacted message is read — whether by tapping the chip or by
     * scrolling it into view. readAllChatReactions clears them server-side, but
     * its UpdateChatUnreadReactionCount echo lags / is sometimes never
     * delivered, which left the badge stuck on scroll even after reading the
     * whole chat (exactly the mention bug). The later echo tops up to whatever
     * the server reports if we were wrong.
     */
    fun clearChatReactionCountLocal(chatId: Long) {
        val c = chatCache[chatId] ?: return
        if (c.unreadReactionCount != 0) {
            c.unreadReactionCount = 0
            scope.launch { _chatUpdates.emit(chatId) }
            refreshChats()
        }
    }

    /**
     * Resolve public chats by username/title query. Returns the cached Chat
     * objects so callers can read title, type, photo, etc. Used by the
     * home-page "Join Nova" card to detect membership.
     */
    suspend fun searchPublicChats(query: String): List<TdApi.Chat> {
        val ids = runCatching {
            send(TdApi.SearchPublicChats(query))
        }.getOrNull()?.chatIds ?: return emptyList()
        return ids.toList().mapNotNull { id ->
            chatCache[id] ?: runCatching { send(TdApi.GetChat(id)) }.getOrNull()
        }
    }

    /**
     * Resolve a single @username to a Chat, opening it on TDLib's side so
     * subsequent message reads work. Used by the t.me deep-link handler.
     */
    suspend fun searchPublicChat(username: String): TdApi.Chat =
        send(TdApi.SearchPublicChat(username))

    /**
     * Join a private chat / group by its invite link or invite hash. The
     * link must be the full https://t.me/+HASH or https://t.me/joinchat/HASH
     * form. Returns the resulting Chat so the caller can open it.
     */
    suspend fun joinChatByInviteLink(link: String): TdApi.Chat =
        send(TdApi.JoinChatByInviteLink(link))

    /**
     * Join a public chat by id (supergroup or channel surfaced via
     * SearchPublicChats). Idempotent — TDLib silently no-ops if you're
     * already a member, so callers don't need to pre-check.
     */
    suspend fun joinChat(chatId: Long) {
        runCatching { send(TdApi.JoinChat(chatId)) }
    }

    /**
     * Inspect an invite link without joining. Returns info including the
     * chatId when the current user is already a member (chatId != 0), so
     * callers can open the existing chat instead of triggering the
     * "already participant" error from JoinChatByInviteLink.
     */
    suspend fun checkChatInviteLink(link: String): TdApi.ChatInviteLinkInfo =
        send(TdApi.CheckChatInviteLink(link))

    /**
     * Create a new end-to-end-encrypted secret chat with `userId`. The
     * returned Chat lives separately from the user's normal private
     * chat with the same person — Telegram intentionally keeps them as
     * distinct rows. The other side gets a UI prompt to accept the
     * connection; until they do the chat is in a Pending state and we
     * can already render it locally.
     */
    suspend fun createNewSecretChat(userId: Long): TdApi.Chat {
        return send(TdApi.CreateNewSecretChat(userId))
    }

    /**
     * Fetch the current [TdApi.SecretChat] (handshake state, key hash,
     * protocol layer) for a secret chat id. Returns null if TDLib can't
     * resolve it yet. The chat screen calls this on open to decide whether
     * to show the "waiting for the other person" notice; live changes
     * thereafter arrive via [secretChatUpdates].
     */
    suspend fun getSecretChat(secretChatId: Int): TdApi.SecretChat? {
        return runCatching { send(TdApi.GetSecretChat(secretChatId)) }.getOrNull()
    }

    /**
     * Set the auto-delete timer on a chat. `ttlSeconds = 0` turns the
     * timer off; positive values mean every message in the chat will
     * disappear that many seconds after being read. Works for both
     * regular and secret chats (different TDLib enforcement, same
     * API). Standard Telegram presets: 60 (1min), 3600 (1h), 86400
     * (1d), 604800 (1w).
     */
    suspend fun setMessageAutoDeleteTime(chatId: Long, ttlSeconds: Int) {
        send(TdApi.SetChatMessageAutoDeleteTime(chatId, ttlSeconds))
    }

    /**
     * Update the "show online status" privacy rule on the Telegram
     * server. `visibleToEveryone = true` lets all users see our online
     * state ("AllowAll"); `false` hides it from everyone
     * ("RestrictAll"). We don't currently expose the contacts-only and
     * per-user lists — the setting in our UI is a single boolean toggle.
     */
    suspend fun setShowStatusVisibility(visibleToEveryone: Boolean) {
        val rule: TdApi.UserPrivacySettingRule = if (visibleToEveryone)
            TdApi.UserPrivacySettingRuleAllowAll()
        else TdApi.UserPrivacySettingRuleRestrictAll()
        val rules = TdApi.UserPrivacySettingRules(arrayOf(rule))
        send(TdApi.SetUserPrivacySettingRules(
            TdApi.UserPrivacySettingShowStatus(),
            rules
        ))
    }

    /**
     * Mute or unmute a chat. Mute is implemented as Int.MAX_VALUE seconds —
     * effectively "forever" — which matches Telegram's "Disable notifications".
     * Unmute clears `muteFor` back to zero. All other notification fields are
     * left to inherit the scope default (use_default_* = true).
     */
    suspend fun setChatMuted(chatId: Long, muted: Boolean) {
        val muteFor = if (muted) Int.MAX_VALUE else 0
        val s = TdApi.ChatNotificationSettings(
            false, muteFor,         // use_default_mute_for, mute_for
            true, 0L,               // use_default_sound, sound_id
            true, true,             // use_default_show_preview, show_preview
            true, false,            // use_default_mute_stories, mute_stories
            true, 0L,               // use_default_story_sound, story_sound_id
            true, true,             // use_default_show_story_poster, show_story_poster
            true, false,            // use_default_disable_pinned_message_notifications, disable_pinned_message_notifications
            true, false             // use_default_disable_mention_notifications, disable_mention_notifications
        )
        send(TdApi.SetChatNotificationSettings(chatId, s))
    }

    /**
     * Read whether the account shows the read-date marker (the second tick in
     * a private chat) to other people. Telegram applies this globally, not
     * per chat. Defaults to true if TDLib hasn't synced yet.
     */
    suspend fun getReadDatePrivacy(): Boolean {
        val r = runCatching {
            send(TdApi.GetReadDatePrivacySettings()) as TdApi.ReadDatePrivacySettings
        }.getOrNull()
        return r?.showReadDate ?: true
    }

    suspend fun setReadDatePrivacy(show: Boolean) {
        send(TdApi.SetReadDatePrivacySettings(TdApi.ReadDatePrivacySettings(show)))
    }

    /** Server-side chat search by title/username. Returns matching chat ids. */
    suspend fun searchChatsRemote(query: String, limit: Int = 50): LongArray {
        if (query.isBlank()) return LongArray(0)
        return runCatching {
            (send(TdApi.SearchChats(query, limit)) as TdApi.Chats).chatIds
        }.getOrDefault(LongArray(0))
    }

    fun getCachedChat(id: Long): TdApi.Chat? = chatCache[id]
    fun getCachedUser(id: Long): TdApi.User? = userCache[id]
    fun getCachedSupergroup(id: Long): TdApi.Supergroup? = supergroupCache[id]
}

class TdException(val code: Int, message: String) : RuntimeException(message)

enum class ChatKind { Private, Group, Channel, Secret }

/**
 * One bot /command, plus the identity of the bot that owns it. The owning
 * bot matters because in a group with more than one bot a bare "/command"
 * isn't routed anywhere (bots with privacy mode ignore it) — it has to be
 * sent as "/command@botusername". We also surface [botUsername] in the
 * picker so the user can tell which bot a command belongs to.
 */
data class BotCommandItem(
    val command: String,
    val description: String,
    val botUserId: Long,
    val botUsername: String?
)

/** One chat's worth of recent unread message lines, for the AI digest. */
data class ChatUnreadDigest(val title: String, val lines: List<String>)

data class ChatSummary(
    val id: Long,
    val title: String,
    val order: Long,
    val unread: Int,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val kind: ChatKind,
    val chat: TdApi.Chat,
    val isArchived: Boolean = false,
    /** True when the user has pinned this chat to the top of the list. */
    val isPinned: Boolean = false,
    /**
     * Server-side "Mark as unread" flag. When true the chat shows the
     * unread affordance even when the actual message-unread count is
     * zero — e.g. the user marked the chat unread from another device
     * after reading the last message. Without this the badge would
     * never render in that case.
     */
    val isMarkedAsUnread: Boolean = false,
    /** Count of unread @-mentions or replies-to-me in this chat. */
    val unreadMentionCount: Int = 0,
    /** Count of unread reactions to the user's own messages in this chat. */
    val unreadReactionCount: Int = 0,
    /**
     * Whether this chat is currently muted (per-chat or via scope
     * fallback). Used by the chat-list tab badges so the top-of-tab
     * unread totals exclude chats whose messages should not be
     * notifying — matches the user's mental model that "the badge
     * tells me about stuff I haven't read AND care about right now".
     * Counts for muted chats are still shown on the individual row;
     * only the aggregate at the tab level filters them out.
     */
    val isMuted: Boolean = false,
    val revision: Long = 0L
)

data class DeleteEvent(val chatId: Long, val messageIds: LongArray) {
    // equals/hashCode are content-based here only to silence the
    // Array-property lint; we don't actually compare DeleteEvents anywhere.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = chatId.hashCode() * 31 + messageIds.contentHashCode()
}

data class MessageContentUpdate(
    val chatId: Long,
    val messageId: Long,
    val newContent: TdApi.MessageContent
)

/**
 * Metadata side of an edit: editDate (epoch seconds when the message was
 * last edited; >0 enables the "modificato" tag in MessageBubble) plus
 * the latest inline keyboard markup if any. Body content travels on
 * MessageContentUpdate; both can fire for the same edit so the
 * downstream collector applies them independently.
 */
data class MessageEditedUpdate(
    val chatId: Long,
    val messageId: Long,
    val editDate: Int,
    val replyMarkup: TdApi.ReplyMarkup?
)

/**
 * Plain phone-book contact read from the device, before we know whether it's
 * on Telegram. importContacts() hands these to TDLib and tells us which ones
 * already have an account.
 */
data class PhoneContact(
    val phoneNumber: String,
    val firstName: String,
    val lastName: String,
    val deviceContactId: Long
)
