package com.secondream.cheipgram.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.secondream.cheipgram.td.TdClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.drinkless.tdlib.TdApi

/**
 * Renders a Telegram voice note with a working play / pause control,
 * a TDLib-provided waveform (or a flat placeholder if none), and a
 * "current / total" duration label.
 *
 * Lifecycle:
 *   - On first composition we kick off a download for the voice file
 *     via TdClient.downloadFile so it lands on disk; we observe
 *     UpdateFile to track progress and pick up the local path when
 *     ready.
 *   - The ExoPlayer instance is created lazily on first play and
 *     released in onDispose; one player per bubble means seeking and
 *     playback state stay independent across messages.
 */
@Composable
fun VoiceNotePlayer(
    voiceNote: TdApi.VoiceNote,
    accent: Color,
    onBackground: Color
) {
    val ctx = LocalContext.current
    // Local copy of the file object so updates from UpdateFile patch it
    // in place rather than forcing the whole bubble to fetch the message
    // again.
    var file by remember(voiceNote.voice.id) { mutableStateOf(voiceNote.voice) }
    var ready by remember(voiceNote.voice.id) { mutableStateOf(file.local.isDownloadingCompleted) }

    LaunchedEffect(voiceNote.voice.id) {
        // Snapshot the live state on (re-)entry. TDLib mutates File objects
        // in place but Compose doesn't observe field-level changes, so
        // without an explicit re-read a voice note that downloaded while
        // the bubble was scrolled off-screen would come back with the
        // stale (empty) path and the Play button stuck disabled.
        runCatching { TdClient.getFile(voiceNote.voice.id) }.onSuccess { latest ->
            file = latest
            if (latest.local.isDownloadingCompleted) ready = true
        }
        if (!file.local.isDownloadingCompleted) {
            runCatching { TdClient.downloadFile(file.id) }
        }
        TdClient.fileUpdates.collect { upd ->
            if (upd.id == voiceNote.voice.id) {
                file = upd
                if (upd.local.isDownloadingCompleted) ready = true
            }
        }
    }

    var player: ExoPlayer? by remember { mutableStateOf(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    val durationMs = (voiceNote.duration * 1000L).coerceAtLeast(1L)

    // Release the player when the composable leaves the tree, otherwise
    // ExoPlayer hangs on to audio focus + the media file handle.
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    // While playing, poll position so the progress bar advances smoothly
    // (Player's getCurrentPosition isn't observable on its own).
    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            player?.let {
                positionMs = it.currentPosition.coerceAtLeast(0)
                if (it.playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    positionMs = 0L
                    it.seekTo(0L)
                    it.pause()
                }
            }
            delay(60L)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(enabled = ready) {
                    val path = file.local.path
                    if (path.isNullOrBlank()) return@clickable
                    val p = player ?: ExoPlayer.Builder(ctx).build().also {
                        it.setMediaItem(MediaItem.fromUri(path))
                        it.prepare()
                        player = it
                    }
                    if (isPlaying) {
                        p.pause()
                        isPlaying = false
                    } else {
                        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
                        p.play()
                        isPlaying = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            VoiceWaveform(
                waveform = voiceNote.waveform,
                progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                playedColor = accent,
                unplayedColor = onBackground.copy(alpha = 0.35f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatVoiceTime(if (isPlaying || positionMs > 0) positionMs else voiceNote.duration * 1000L),
                style = MaterialTheme.typography.labelSmall,
                color = onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun VoiceWaveform(
    waveform: ByteArray?,
    progress: Float,
    playedColor: Color,
    unplayedColor: Color
) {
    // Telegram stores the waveform packed as 5-bit samples (0..31) inside
    // a byte array. Unpack into normalized 0..1 floats. Fallback: 30
    // flat bars when the waveform is missing (rare; some clients omit it).
    val bars: List<Float> = remember(waveform) {
        if (waveform == null || waveform.isEmpty()) {
            List(30) { 0.4f }
        } else unpackWaveform(waveform)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val n = bars.size
        if (n == 0) return@Canvas
        val totalSpacing = size.width * 0.35f
        val barW = (size.width - totalSpacing) / n
        val gap = totalSpacing / (n - 1).coerceAtLeast(1)
        val midY = size.height / 2f
        val cutoffX = size.width * progress
        for (i in 0 until n) {
            val x = i * (barW + gap)
            val h = (bars[i] * size.height * 0.9f).coerceAtLeast(2f)
            val color = if (x + barW / 2f <= cutoffX) playedColor else unplayedColor
            drawRoundRect(
                color = color,
                topLeft = Offset(x, midY - h / 2f),
                size = Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

/**
 * Unpack 5-bit-per-sample waveform packed by Telegram. Each byte holds
 * 8/5 ≈ 1.6 samples; iterate bit by bit. We downsample to ~30 bars to
 * keep the visual readable on small bubbles regardless of duration.
 */
private fun unpackWaveform(packed: ByteArray): List<Float> {
    val raw = buildList {
        val totalBits = packed.size * 8
        var bit = 0
        while (bit + 5 <= totalBits) {
            val byteIdx = bit / 8
            val bitInByte = bit % 8
            val first = (packed[byteIdx].toInt() and 0xFF) ushr bitInByte
            val needHi = (bitInByte + 5) > 8
            val sample = if (needHi && byteIdx + 1 < packed.size) {
                val hi = packed[byteIdx + 1].toInt() and 0xFF
                ((first or (hi shl (8 - bitInByte))) and 0x1F)
            } else first and 0x1F
            add(sample / 31f)
            bit += 5
        }
    }
    if (raw.isEmpty()) return List(30) { 0.4f }
    // Downsample/upsample to 30 bars.
    val target = 30
    return List(target) { i ->
        val srcIdx = (i.toFloat() / target * raw.size).toInt().coerceIn(0, raw.size - 1)
        raw[srcIdx]
    }
}

private fun formatVoiceTime(ms: Long): String {
    val total = ms / 1000L
    val m = total / 60L
    val s = total % 60L
    return "%d:%02d".format(m, s)
}
