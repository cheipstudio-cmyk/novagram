@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.secondream.cheipgram.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.secondream.cheipgram.R
import androidx.compose.ui.res.stringResource
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.theme.Ink
import org.drinkless.tdlib.TdApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: TdApi.Message,
    onLongPress: (TdApi.Message) -> Unit = {}
) {
    val mine = message.isOutgoing
    val align = if (mine) Alignment.End else Alignment.Start
    val bubbleColor = if (mine) Ink.BubbleMine else Ink.BubbleTheirs
    val shape = if (mine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                .combinedClickable(
                    onClick = { /* future: open media */ },
                    onLongClick = { onLongPress(message) }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = align
        ) {
            MessageContent(message)
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatHHmm(message.date),
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Faint
            )
        }
    }
}

@Composable
private fun MessageContent(message: TdApi.Message) {
    when (val c = message.content) {
        is TdApi.MessageText -> {
            Text(
                c.text.text,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.Cream
            )
        }
        is TdApi.MessagePhoto -> {
            val photo = c.photo.sizes.lastOrNull()?.photo
            DownloadingImage(
                initialFile = photo,
                placeholderIcon = { Icon(Icons.Outlined.Image, null, tint = Ink.Muted) },
                placeholderLabel = stringResource(R.string.media_photo)
            )
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyMedium, color = Ink.Cream)
            }
        }
        is TdApi.MessageVideo -> {
            // Show the thumbnail file (cheap, usually auto-downloaded by TDLib).
            // Tap-to-play would belong in a dedicated player screen, Round 2+.
            val thumb = c.video.thumbnail?.file
            Box(contentAlignment = Alignment.Center) {
                DownloadingImage(
                    initialFile = thumb,
                    placeholderIcon = { Icon(Icons.Outlined.PlayArrow, null, tint = Ink.Cream) },
                    placeholderLabel = stringResource(R.string.media_video)
                )
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Ink.Bg.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        null,
                        tint = Ink.Cream,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyMedium, color = Ink.Cream)
            }
        }
        is TdApi.MessageAnimation -> {
            // GIF: show thumbnail, autoplay belongs to Round 2.
            val thumb = c.animation.thumbnail?.file
            DownloadingImage(
                initialFile = thumb,
                placeholderIcon = { Icon(Icons.Outlined.Image, null, tint = Ink.Muted) },
                placeholderLabel = stringResource(R.string.media_gif)
            )
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyMedium, color = Ink.Cream)
            }
        }
        is TdApi.MessageDocument -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        c.document.fileName.ifBlank { stringResource(R.string.media_document) },
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink.Cream,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatBytes(c.document.document.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Muted
                    )
                }
            }
            if (c.caption.text.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(c.caption.text, style = MaterialTheme.typography.bodyMedium, color = Ink.Cream)
            }
        }
        is TdApi.MessageVoiceNote -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.GraphicEq, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.media_voice_note), style = MaterialTheme.typography.titleSmall, color = Ink.Cream)
                    Text("${c.voiceNote.duration}s", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                }
            }
        }
        is TdApi.MessageAudio -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AudioFile, null, tint = Ink.Amber, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        c.audio.title.ifBlank { c.audio.fileName }.ifBlank { stringResource(R.string.media_audio) },
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink.Cream
                    )
                    Text("${c.audio.duration}s", style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                }
            }
        }
        is TdApi.MessageSticker -> Text(stringResource(R.string.media_sticker), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        else -> Text(stringResource(R.string.media_unsupported), style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
    }
}

/**
 * Renders a TDLib file (photo / video thumb / animation thumb) and progresses
 * through "request download" → "downloading" → "show image" without forcing
 * the parent to re-fetch the chat history. Reacts to UpdateFile events in real
 * time via TdClient.fileUpdates.
 */
@Composable
private fun DownloadingImage(
    initialFile: TdApi.File?,
    placeholderIcon: @Composable () -> Unit,
    placeholderLabel: String
) {
    if (initialFile == null) {
        ImagePlaceholder(placeholderIcon, placeholderLabel)
        return
    }
    var file by remember(initialFile.id) { mutableStateOf(initialFile) }

    LaunchedEffect(initialFile.id) {
        val f = initialFile
        if (!f.local.isDownloadingCompleted && !f.local.isDownloadingActive) {
            runCatching { TdClient.downloadFile(f.id) }
        }
        TdClient.fileUpdates.collect { updated ->
            if (updated.id == initialFile.id) file = updated
        }
    }

    val path = file.local?.path
    if (!path.isNullOrBlank() && file.local.isDownloadingCompleted) {
        AsyncImage(
            model = path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(260.dp)
                .heightIn(min = 120.dp, max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        ImagePlaceholder(placeholderIcon, placeholderLabel)
    }
}

@Composable
private fun ImagePlaceholder(
    icon: @Composable () -> Unit,
    label: String
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .heightIn(min = 140.dp, max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.SurfaceHi),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon()
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        }
    }
}

private fun formatHHmm(epochSec: Int): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSec.toLong() * 1000L))

private fun formatBytes(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digit = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(3)
    val value = size / Math.pow(1024.0, digit.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digit])
}
