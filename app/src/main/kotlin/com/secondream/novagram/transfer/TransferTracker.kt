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

    /**
     * Cancel/clear a transfer: asks TDLib to stop the download and drops the
     * row from the panel immediately. A stalled download stops emitting file
     * updates, so [onFile] would never remove it on its own — this is the
     * manual clear for a stuck badge.
     */
    fun cancel(fileId: Int) {
        scope.launch { runCatching { TdClient.cancelDownloadFile(fileId) } }
        synchronized(mutex) {
            active.remove(fileId)
            _transfers.value = active.values.sortedBy { it.fileId }
        }
        progressStamp.remove(fileId)
        rearmCount.remove(fileId)
        locations.remove(fileId)
    }

    private val active = mutableMapOf<Int, Transfer>()
    private val mutex = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** fileId → (lastTransferredBytes, lastChangeMs). Drives stall detection. */
    private val progressStamp = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Long>>()

    /**
     * fileId → how many times we've re-armed a *parked* download (TDLib set
     * isDownloadingActive=false while the file was still incomplete). Bounded
     * so a genuinely dead file (deleted server-side, expired reference) can't
     * loop forever — after the cap we drop the row. Cleared the moment real
     * progress arrives, so a transfer that recovers and later stalls again
     * gets a fresh budget.
     */
    private val rearmCount = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private const val MAX_REARM = 4

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
        // Stuck-download watchdog. TDLib sometimes parks an active download
        // (its isDownloadingActive stays true but no bytes move) and doesn't
        // resume on its own when connectivity comes back. Every few seconds
        // we re-issue DownloadFile for any download whose byte count hasn't
        // advanced for a while — TDLib resumes from the already-fetched prefix
        // (offset 0 / limit 0), so this nudges it forward without restarting.
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(4_000)
                val now = System.currentTimeMillis()
                val stalled = synchronized(mutex) {
                    active.values
                        .filter { !it.isUpload && it.totalBytes > 0 && it.transferredBytes < it.totalBytes }
                        .map { it.fileId }
                }.filter { fid ->
                    val s = progressStamp[fid]
                    s != null && now - s.second > 6_000
                }
                for (fid in stalled) {
                    runCatching { TdClient.startDownload(fid) }
                    // Push the stamp forward so we don't hammer the same file
                    // every tick; if the nudge works, onFile will refresh it
                    // with real progress.
                    progressStamp[fid] = (progressStamp[fid]?.first ?: 0L) to now
                }
            }
        }
    }

    private fun onFile(file: TdApi.File) {
        val downloading = file.local.isDownloadingActive
        val uploading = file.remote.isUploadingActive
        val downloaded = file.local.downloadedSize
        val uploaded = file.remote.uploadedSize
        val total = file.size.coerceAtLeast(0L)
        // Stall detection: remember the last byte count + when it last moved.
        // TDLib stops emitting updates for a parked transfer, so a stamp that
        // hasn't advanced is exactly how the watchdog spots a stuck download.
        val now = System.currentTimeMillis()
        if (downloading || uploading) {
            val bytes = if (downloading) downloaded else uploaded
            val prev = progressStamp[file.id]
            if (prev == null || bytes != prev.first) {
                progressStamp[file.id] = bytes to now
                // Bytes advanced (or first sighting) → this transfer is healthy;
                // forget any parked-download retry budget we'd accrued.
                rearmCount.remove(file.id)
            }
        } else {
            progressStamp.remove(file.id)
        }
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
                    // No longer in flight. Three sub-cases:
                    //  • genuinely finished or cancelled → drop the row.
                    //  • TDLib PARKED an unfinished download (isDownloadingActive
                    //    flipped to false without completing — the silent
                    //    "stuck at 26% on a tiny file, good connection" bug):
                    //    re-arm the download and KEEP the row so the watchdog
                    //    keeps an eye on it. Bounded by MAX_REARM so a dead
                    //    file reference can't loop forever.
                    val prevRow = active[file.id]
                    val wasDownload = prevRow != null && !prevRow.isUpload
                    val incomplete = total > 0 &&
                        file.local.downloadedSize < total &&
                        !file.local.isDownloadingCompleted
                    val tries = rearmCount[file.id] ?: 0
                    if (wasDownload && incomplete && tries < MAX_REARM) {
                        rearmCount[file.id] = tries + 1
                        scope.launch { runCatching { TdClient.startDownload(file.id) } }
                        active[file.id] = prevRow!!.copy(
                            transferredBytes = file.local.downloadedSize,
                            totalBytes = total
                        )
                        progressStamp[file.id] = file.local.downloadedSize to now
                    } else {
                        active.remove(file.id)
                        rearmCount.remove(file.id)
                    }
                }
            }
            _transfers.value = active.values.sortedBy { it.fileId }
        }
    }
}
