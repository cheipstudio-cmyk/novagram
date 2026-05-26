package com.secondream.cheipgram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.secondream.cheipgram.td.TdClient
import org.drinkless.tdlib.TdApi

/**
 * Reusable circular avatar:
 *   - when `file` is null or not yet downloaded, draws a colored circle with
 *     the first letter of `fallbackText` (or "?" if empty)
 *   - when `file` is downloaded, renders the image cropped to a circle
 *   - reacts to TdClient.fileUpdates so the picture appears in real time once
 *     the download completes, without forcing the caller to re-fetch anything
 */
@Composable
fun Avatar(
    file: TdApi.File?,
    fallbackText: String,
    bgColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    var current by remember(file?.id) { mutableStateOf(file) }
    LaunchedEffect(file?.id) {
        val f = file ?: return@LaunchedEffect
        // Always pull the latest file state. TDLib's downloadFile is idempotent:
        // if the file is already complete it returns the cached File instantly,
        // if it's downloading we just wait for it. This fixes the case where
        // another Avatar started the download before we subscribed to
        // fileUpdates and we missed the completion event.
        val initial = runCatching { TdClient.downloadFile(f.id) }.getOrNull()
        if (initial != null) current = initial
        TdClient.fileUpdates.collect { updated ->
            if (updated.id == f.id) current = updated
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        val path = current?.local?.path
        if (!path.isNullOrBlank() && current?.local?.isDownloadingCompleted == true) {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            val letter = fallbackText.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                letter,
                style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontStyle = FontStyle.Italic
            )
        }
    }
}
