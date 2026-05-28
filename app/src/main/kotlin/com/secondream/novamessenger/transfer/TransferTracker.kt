package com.secondream.novamessenger.transfer

import com.secondream.novamessenger.td.TdClient
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
        val fileName: String?
    ) {
        val progress: Float
            get() = if (totalBytes > 0)
                (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else 0f
    }

    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

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
            when {
                downloading -> {
                    active[file.id] = Transfer(
                        fileId = file.id,
                        isUpload = false,
                        totalBytes = total,
                        transferredBytes = downloaded,
                        fileName = null
                    )
                }
                uploading -> {
                    active[file.id] = Transfer(
                        fileId = file.id,
                        isUpload = true,
                        totalBytes = total,
                        transferredBytes = uploaded,
                        fileName = null
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
