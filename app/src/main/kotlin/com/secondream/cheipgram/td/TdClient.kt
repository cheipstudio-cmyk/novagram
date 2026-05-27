package com.secondream.cheipgram.td

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
import com.secondream.cheipgram.BuildConfig
import com.secondream.cheipgram.settings.AppSettings
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
            is TdApi.UpdateUser -> {
                userCache[obj.user.id] = obj.user
            }
            is TdApi.UpdateMessageContent -> {
                scope.launch {
                    _messageContentUpdates.emit(MessageContentUpdate(obj.chatId, obj.messageId, obj.newContent))
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
                scope.launch { _chatUpdates.emit(obj.message.chatId) }
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
                val pos = chat.positions.firstOrNull { it.list is TdApi.ChatListMain }
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
                    revision = rev
                )
            }
            .sortedByDescending { it.order }
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
        is TdApi.ChatTypeSecret -> ChatKind.Private
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
            useSecretChats = false
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
    }

    suspend fun openChat(chatId: Long) { send(TdApi.OpenChat(chatId)) }
    suspend fun closeChat(chatId: Long) { send(TdApi.CloseChat(chatId)) }
    suspend fun viewMessages(chatId: Long, ids: LongArray) {
        send(TdApi.ViewMessages(chatId, ids, TdApi.MessageSourceChatHistory(), true))
    }

    suspend fun getChat(chatId: Long): TdApi.Chat = send(TdApi.GetChat(chatId))

    suspend fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int): TdApi.Messages =
        send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))

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

enum class ChatKind { Private, Group, Channel }

data class ChatSummary(
    val id: Long,
    val title: String,
    val order: Long,
    val unread: Int,
    val lastMessagePreview: String,
    val lastMessageTimestamp: Long,
    val kind: ChatKind,
    val chat: TdApi.Chat,
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
