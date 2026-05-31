package com.secondream.novagram.transfer

import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * App-wide singleton that watches TDLib file updates and exposes the
 * currently-active download and upload set as a Compose-observable list.
 *
 * The persistent transfer panel (rendered at MainActivity scope above the
 * nav host) collects this state and shows each in-flight transfer as a
 * row with a progress bar + percentage + MB downloaded/total. Because the
 * tracker is a `object` and starts collecting in init, its state survives
 * navigation between screens — the panel stays visible across the chat
 * list, individual chats, settings, etc.
 *
 * Why not use ChatScreen's existing fileUpdates collector: that one is
 * scoped to a single chatId and dies on back. The panel needs a global,
 * always-on observation that doesn't restart per screen.
 */
object TransferTracker {

    /** One row of the transfer panel. */
    data class Transfer(
        val fileId: Int,
        val isUpload: Boolean,
        val totalBytes: Long,
        val transferredBytes: Long,
        val fileName: String?,
        /** Chat + message this file belongs to, when known (registered by the
         *  message bubble that rendered it). Lets the panel jump straight to
         *  the message that's downloading the file. Null = no jump target. */
        val chatId: Long? = null,
        val messageId: Long? = null
    ) {
        val progress: Float
            get() = if (totalBytes > 0)
                (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else 0f
    }

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    /**
     * fileId → (chatId, messageId) of the message that owns the file. Populated
     * by MessageBubble as it renders media (the bubble is the one place that
     * knows both the file and its message). Read from [onFile] to tag each
     * Transfer with a jump target. ConcurrentHashMap because registration
     * happens on the compose/main thread while [onFile] runs on Dispatchers.
     */
    private val locations = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Long>>()

    /** Record where a file lives so a panel tap can jump to it. */
    fun registerLocation(fileId: Int, chatId: Long, messageId: Long) {
        if (fileId != 0 && messageId != 0L) locations[fileId] = chatId to messageId
    }

    /**
     * A pending "open this chat at this message" request raised when the user
     * taps a transfer row. MainActivity observes it and routes through its
     * pendingChatId pipeline (same path as a deep link), then calls
     * [consumeJump]. Pair is (chatId, messageId).
     */
    private val _jumpRequest = MutableStateFlow<Pair<Long, Long>?>(null)
    val jumpRequest: StateFlow<Pair<Long, Long>?> = _jumpRequest.asStateFlow()

    fun requestJump(chatId: Long, messageId: Long) {
        _jumpRequest.value = chatId to messageId
    }

    fun consumeJump() {
        _jumpRequest.value = null
    }

    private val active = mutableMapOf<Int, Transfer>()
    private val mutex = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /**
     * Idempotent start. Called from MainActivity after TDLib has been
     * initialised so the very first fileUpdate we see is real. Subsequent
     * calls are no-ops.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            TdClient.fileUpdates.collect { file -> onFile(file) }
        }
    }

    private fun onFile(file: TdApi.File) {
        val downloading = file.local.isDownloadingActive
        val uploading = file.remote.isUploadingActive
        val downloaded = file.local.downloadedSize
        val uploaded = file.remote.uploadedSize
        val total = file.size.coerceAtLeast(0L)
        synchronized(mutex) {
            val loc = locations[file.id]
            when {
                downloading -> {
                    active[file.id] = Transfer(
                        fileId = file.id,
                        isUpload = false,
                        totalBytes = total,
                        transferredBytes = downloaded,
                        fileName = null,
                        chatId = loc?.first,
                        messageId = loc?.second
                    )
                }
                uploading -> {
                    active[file.id] = Transfer(
                        fileId = file.id,
                        isUpload = true,
                        totalBytes = total,
                        transferredBytes = uploaded,
                        fileName = null,
                        chatId = loc?.first,
                        messageId = loc?.second
                    )
                }
                else -> {
                    // Transfer completed, cancelled, or otherwise no longer
                    // in flight — remove from the visible panel. If it was
                    // never tracked this is a no-op.
                    active.remove(file.id)
                }
            }
            _transfers.value = active.values.sortedBy { it.fileId }
        }
    }
}
