package com.secondream.turbogram.td

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
import com.secondream.turbogram.BuildConfig
import com.secondream.turbogram.settings.AppSettings
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
                chatCache[obj.chatId]?.lastMessage = obj.lastMessage
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
            is TdApi.UpdateUser -> {
                userCache[obj.user.id] = obj.user
            }
            is TdApi.UpdateMessageContent -> {
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateDeleteMessages -> {
                scope.launch { _chatUpdates.emit(obj.chatId) }
            }
            is TdApi.UpdateMessageSendSucceeded -> {
                scope.launch { _chatUpdates.emit(obj.message.chatId) }
            }
            is TdApi.UpdateFile -> {
                scope.launch { _chatUpdates.emit(0L) }
            }
            else -> { /* noop */ }
        }
    }

    private fun onError(error: Throwable) {
        Log.e(TAG, "TDLib error", error)
    }

    private fun refreshChats() {
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
                    chat = chat
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

    suspend fun sendText(chatId: Long, text: String) {
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, true)
        send(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun sendPhoto(chatId: Long, filePath: String, caption: String? = null) {
        val content = TdApi.InputMessagePhoto(
            TdApi.InputFileLocal(filePath),
            null, null, intArrayOf(), 0, 0,
            caption?.let { TdApi.FormattedText(it, emptyArray()) },
            false, null, false
        )
        send(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun sendDocument(chatId: Long, filePath: String, caption: String? = null) {
        val content = TdApi.InputMessageDocument(
            TdApi.InputFileLocal(filePath),
            null, false,
            caption?.let { TdApi.FormattedText(it, emptyArray()) }
        )
        send(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun sendVoiceNote(chatId: Long, filePath: String, durationSeconds: Int) {
        val content = TdApi.InputMessageVoiceNote(
            TdApi.InputFileLocal(filePath),
            durationSeconds, ByteArray(0), null, null
        )
        send(TdApi.SendMessage(chatId, null, null, null, null, content))
    }

    suspend fun downloadFile(fileId: Int): TdApi.File {
        return send(TdApi.DownloadFile(fileId, 32, 0, 0, true))
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
    val chat: TdApi.Chat
)
