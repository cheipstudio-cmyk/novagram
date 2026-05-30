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

    private val _newMessages = MutableSharedFlow<TdApi.Message>(extraBufferCapacity = 32)
    val newMessages = _newMessages.asSharedFlow()

    private val _chatUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val chatUpdates = _chatUpdates.asSharedFlow()

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
        if (chat == null) return false
        val per = chat.notificationSettings ?: return false
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
        // SAFER DEFAULT: when scope is null (not yet received from
        // TDLib — happens during the brief window between auth-ready
        // and the first UpdateScopeNotificationSettings push) we
        // prefer to SKIP the notification rather than let it leak
        // through. Without this default, any message arriving in a
        // scope-muted chat during app startup would fire heads-up
        // even though the user had explicitly muted it (e.g. "Mute
        // all groups" in Telegram settings, then a group msg lands
        // before TDLib has pushed the group scope back to us).
        if (scope == null) return true
        return scope.muteFor > 0
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
            is TdApi.UpdateNewMessage -> {
                scope.launch { _newMessages.emit(obj.message) }
            }
            is TdApi.UpdateNewChat -> {
                chatCache[obj.chat.id] = obj.chat
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
                refreshChats()
            }
            is TdApi.UpdateChatTitle -> {
                chatCache[obj.chatId]?.title = obj.title
                refreshChats()
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
            is TdApi.UpdateChatNotificationSettings -> {
                // After setChatMuted(...) TDLib echoes the new settings back
                // via this update. Without this handler the cached chat keeps
                // showing the old muteFor and "Silenzia" in the action sheet
                // never flips to "Riattiva" even though the mute actually took.
                chatCache[obj.chatId]?.notificationSettings = obj.notificationSettings
                refreshChats()
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
            }
            is TdApi.UpdateMessageContent -> {
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

    private fun buildPreview(msg: TdApi.Message?): String {
        if (msg == null) return ""
        return when (val c = msg.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> "📷 " + (c.caption.text.ifBlank { "Foto" })
            is TdApi.MessageVoiceNote -> "🎙 Nota vocale"
            is TdApi.MessageAudio -> "🎵 " + (c.audio.title.ifBlank { "Audio" })
            is TdApi.MessageDocument -> "📎 " + c.document.fileName
            is TdApi.MessageVideo -> "🎬 Video"
            is TdApi.MessageSticker -> "Sticker"
            is TdApi.MessageAnimation -> "GIF"
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
                _authState.value = AuthState.WaitCode(codeHint = state.codeInfo.phoneNumber)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitPassword(hint = state.passwordHint)
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

    suspend inline fun <reified R : TdApi.Object> send(query: TdApi.Function<R>): R {
        return sendRaw(query) as R
    }

    suspend fun sendRaw(query: TdApi.Function<*>): TdApi.Object {
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

    suspend fun logOut() {
        try { send(TdApi.LogOut()) } catch (e: Throwable) { Log.w(TAG, "logOut: ${e.message}") }
    }

    suspend fun loadChats(limit: Int) {
        try { send(TdApi.LoadChats(TdApi.ChatListMain(), limit)) }
        catch (e: TdException) { Log.w(TAG, "loadChats: ${e.message}") }
        // Also pull the archive so the Archiviati tab (when enabled) has
        // data. Failures are non-fatal — archive is a secondary surface.
        try { send(TdApi.LoadChats(TdApi.ChatListArchive(), limit)) }
        catch (e: TdException) { Log.w(TAG, "loadChats(archive): ${e.message}") }
    }

    suspend fun openChat(chatId: Long) { send(TdApi.OpenChat(chatId)) }
    suspend fun closeChat(chatId: Long) { send(TdApi.CloseChat(chatId)) }
    suspend fun viewMessages(chatId: Long, ids: LongArray) {
        send(TdApi.ViewMessages(chatId, ids, TdApi.MessageSourceChatHistory(), true))
    }

    suspend fun getChat(chatId: Long): TdApi.Chat = send(TdApi.GetChat(chatId))

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
     * Build the InputMessageReplyTo for the SendMessage call. Returns null
     * when there's no reply (TDLib treats null as "not a reply"). The 0/""
     * trailing args are the checklist/poll-option fields, irrelevant for
     * regular message replies.
     */
    private fun buildReplyTo(messageId: Long?): TdApi.InputMessageReplyTo? =
        messageId?.takeIf { it != 0L }?.let {
            TdApi.InputMessageReplyToMessage(it, null, 0, "")
        }

    suspend fun sendText(chatId: Long, text: String, replyToMessageId: Long? = null) {
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
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

    suspend fun sendPhoto(chatId: Long, filePath: String, caption: String? = null, replyToMessageId: Long? = null) {
        val content = TdApi.InputMessagePhoto(
            TdApi.InputFileLocal(filePath),
            null, null, intArrayOf(), 0, 0,
            caption?.let { TdApi.FormattedText(it, emptyArray()) },
            false, null, false
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    suspend fun sendDocument(chatId: Long, filePath: String, caption: String? = null, replyToMessageId: Long? = null) {
        val content = TdApi.InputMessageDocument(
            TdApi.InputFileLocal(filePath),
            null, false,
            caption?.let { TdApi.FormattedText(it, emptyArray()) }
        )
        send(TdApi.SendMessage(chatId, null, buildReplyTo(replyToMessageId), null, null, content))
    }

    suspend fun sendVoiceNote(chatId: Long, filePath: String, durationSeconds: Int, replyToMessageId: Long? = null) {
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
    suspend fun sendVideo(chatId: Long, filePath: String, caption: String? = null, replyToMessageId: Long? = null) {
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
    suspend fun sendAnimation(chatId: Long, filePath: String, caption: String? = null, replyToMessageId: Long? = null) {
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

    suspend fun downloadFile(fileId: Int): TdApi.File {
        return send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
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
    suspend fun getMe(): TdApi.User = send(TdApi.GetMe())

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
    suspend fun getBotCommandsForChat(chatId: Long): List<TdApi.BotCommand> {
        // The "any bot" form: pass scope=BotCommandScopeChat and TDLib
        // returns the merged command list across every bot in the chat.
        val res = runCatching {
            send(TdApi.GetCommands(TdApi.BotCommandScopeChat(chatId), ""))
        }.getOrNull()
        return res?.commands?.toList() ?: emptyList()
    }

    /** Full info about a basic group, including its description and members. */
    suspend fun getBasicGroupFullInfo(basicGroupId: Long): TdApi.BasicGroupFullInfo =
        send(TdApi.GetBasicGroupFullInfo(basicGroupId))

    /** Full info about a supergroup or channel: description, member count, link. */
    suspend fun getSupergroupFullInfo(supergroupId: Long): TdApi.SupergroupFullInfo =
        send(TdApi.GetSupergroupFullInfo(supergroupId))

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
     */
    suspend fun deleteMessages(chatId: Long, messageIds: LongArray, revoke: Boolean) {
        send(TdApi.DeleteMessages(chatId, messageIds, revoke))
    }

    /**
     * Delete the entire history of a chat. removeFromChatList=true also drops
     * the chat from the user's chat list (Telegram's "Delete chat" behaviour).
     * revoke=true erases the messages for everyone where permitted.
     */
    suspend fun deleteChatHistory(chatId: Long, removeFromChatList: Boolean, revoke: Boolean) {
        send(TdApi.DeleteChatHistory(chatId, removeFromChatList, revoke))
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
}

class TdException(val code: Int, message: String) : RuntimeException(message)

enum class ChatKind { Private, Group, Channel, Secret }

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
