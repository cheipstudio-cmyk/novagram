package com.secondream.novagram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.novagram.td.TdClient
import org.drinkless.tdlib.TdApi

/**
 * Tabbed media gallery surfaced at the bottom of ChatInfoDialog. Six
 * filters mirror Telegram official's "Chat info → Media": Foto / Video
 * / File / Link / Voce / Musica. Each tab lazily fetches the matching
 * messages from TDLib on first selection (then caches in remember so
 * tab switches don't re-fetch).
 *
 * Tapping an item invokes [onItemTap] with the message so the caller
 * (ChatInfoDialog) can present a small action sheet — "Apri file" for
 * non-image content, or "Visualizza in chat" which dismisses the
 * dialog and calls jumpToMessage. The gallery itself doesn't decide
 * what to do; that's the caller's job, which keeps this widget reusable
 * (e.g. if we later add a "shared with me" aggregate view).
 *
 * Photos / videos render as a 3-column square grid; documents / links /
 * voice / music render as a vertical list of rows. The 6 filters chunk
 * naturally into grid-vs-list at compose time.
 */
@Composable
fun ChatMediaGallery(
    chatId: Long,
    onItemTap: (TdApi.Message) -> Unit
) {
    val tabs = remember {
        listOf(
            MediaTab("Foto", TdApi.SearchMessagesFilterPhoto(), MediaLayout.Grid),
            MediaTab("Video", TdApi.SearchMessagesFilterVideo(), MediaLayout.Grid),
            MediaTab("File", TdApi.SearchMessagesFilterDocument(), MediaLayout.List),
            MediaTab("Link", TdApi.SearchMessagesFilterUrl(), MediaLayout.List),
            MediaTab("Voce", TdApi.SearchMessagesFilterVoiceNote(), MediaLayout.List),
            MediaTab("Musica", TdApi.SearchMessagesFilterAudio(), MediaLayout.List)
        )
    }
    var selectedIdx by remember { mutableStateOf(0) }
    // Per-tab cache: filter-class-name → list. Keyed on the class so
    // we re-use across recompositions but invalidate naturally when
    // the chatId-keyed remember{} discards everything.
    val cache = remember(chatId) { mutableMapOf<String, List<TdApi.Message>>() }
    var loading by remember(chatId, selectedIdx) { mutableStateOf(false) }
    var messages by remember(chatId, selectedIdx) {
        mutableStateOf(cache[tabs[selectedIdx].filter::class.java.name] ?: emptyList())
    }
    LaunchedEffect(chatId, selectedIdx) {
        val tab = tabs[selectedIdx]
        val key = tab.filter::class.java.name
        if (cache.containsKey(key)) {
            messages = cache[key]!!
            return@LaunchedEffect
        }
        loading = true
        val res = runCatching {
            TdClient.searchChatMessages(chatId, tab.filter)
        }.getOrDefault(emptyList())
        cache[key] = res
        messages = res
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Tab bar — pill-style, horizontally scrollable. Each tab tile
        // is a small rounded chip; the selected one fills with accent.
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 4.dp, vertical = 8.dp
            )
        ) {
            items(tabs.size) { idx ->
                val tab = tabs[idx]
                val selected = idx == selectedIdx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        )
                        .clickable { selectedIdx = idx }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Body — grid for visual filters, list for text/file filters.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 480.dp)
        ) {
            when {
                loading && messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                    }
                }
                messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(com.secondream.novagram.R.string.gallery_empty_category),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                tabs[selectedIdx].layout == MediaLayout.Grid -> {
                    MediaGrid(messages = messages, onItemTap = onItemTap)
                }
                else -> {
                    MediaList(
                        messages = messages,
                        filter = tabs[selectedIdx].filter,
                        onItemTap = onItemTap
                    )
                }
            }
        }
    }
}

private enum class MediaLayout { Grid, List }

/**
 * One media tab's content, used as a HorizontalPager page in the full-screen
 * chat-info view. Fetches the matching messages once via searchChatMessages,
 * then renders a 3-col grid (photos/videos) or a vertical list. Fills its
 * container so swiping between pages never re-measures the parent — the fix
 * for the old bottom sheet that snapped up/down on every tab switch.
 */
@Composable
internal fun MediaTabContent(
    chatId: Long,
    filter: TdApi.SearchMessagesFilter,
    isGrid: Boolean,
    query: String = "",
    onItemTap: (TdApi.Message) -> Unit
) {
    val key = filter::class.java.name
    var loading by remember(chatId, key) { mutableStateOf(true) }
    var messages by remember(chatId, key) { mutableStateOf<List<TdApi.Message>>(emptyList()) }
    LaunchedEffect(chatId, key, query) {
        loading = true
        // Debounce typing so we don't hit TDLib on every keystroke; an empty
        // query loads the whole category exactly as before.
        if (query.isNotEmpty()) kotlinx.coroutines.delay(300)
        messages = runCatching { TdClient.searchChatMessages(chatId, filter, query.trim()) }
            .getOrDefault(emptyList())
        loading = false
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            loading && messages.isEmpty() -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            messages.isEmpty() -> {
                Text(
                    androidx.compose.ui.res.stringResource(com.secondream.novagram.R.string.gallery_empty_category),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            isGrid -> MediaGrid(messages = messages, onItemTap = onItemTap)
            else -> MediaList(messages = messages, filter = filter, onItemTap = onItemTap)
        }
    }
}

private data class MediaTab(
    val label: String,
    val filter: TdApi.SearchMessagesFilter,
    val layout: MediaLayout
)

@Composable
private fun MediaGrid(
    messages: List<TdApi.Message>,
    onItemTap: (TdApi.Message) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(messages, key = { it.id }) { msg ->
            MediaGridCell(msg = msg, onClick = { onItemTap(msg) })
        }
    }
}

@Composable
private fun MediaGridCell(msg: TdApi.Message, onClick: () -> Unit) {
    val content = msg.content
    val (thumbFile, isVideo) = when (content) {
        is TdApi.MessagePhoto -> {
            // Smallest size for thumbnails — TDLib's sizes are sorted
            // by approximate diagonal, [0] is usually the lowest-res
            // preview that's already downloaded.
            content.photo.sizes.firstOrNull()?.photo to false
        }
        is TdApi.MessageVideo -> {
            content.video.thumbnail?.file to true
        }
        else -> null to false
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        var localPath by remember(thumbFile?.id) {
            mutableStateOf(
                thumbFile?.local?.path?.takeIf {
                    it.isNotEmpty() && java.io.File(it).exists()
                }
            )
        }
        // Await the (priority-32, synchronous) download and update state so the
        // cell recomposes into the image the moment the thumb lands. The old
        // code fired a low-priority download but never re-read the path, so the
        // grid sat on the placeholder until the user scrolled the chat to warm
        // the cache — exactly the "tabs don't load unless I scroll first" bug.
        LaunchedEffect(thumbFile?.id) {
            val f = thumbFile ?: return@LaunchedEffect
            if (localPath == null && f.id != 0) {
                val downloaded = runCatching {
                    com.secondream.novagram.td.TdClient.downloadFile(f.id)
                }.getOrNull()
                val p = downloaded?.local?.path
                if (!p.isNullOrEmpty() && java.io.File(p).exists()) localPath = p
            }
        }
        if (localPath != null) {
            val bmp = remember(localPath) {
                runCatching { android.graphics.BitmapFactory.decodeFile(localPath) }.getOrNull()
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            }
        } else {
            // No local thumb yet — paint a flat tint with an icon to
            // hint at the media kind. TDLib will mark the file local
            // on next chat-open / scroll, so re-visiting picks it up.
            Icon(
                if (isVideo) com.secondream.novagram.ui.icons.PhosphorIcons.Play
                else com.secondream.novagram.ui.icons.PhosphorIcons.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(28.dp).align(Alignment.Center)
            )
            // NOTE: the thumb download is kicked off by the single
            // priority-1 LaunchedEffect at the top of this Box (it fires
            // whenever localPath == null). We deliberately do NOT start a
            // second download here: a duplicate DownloadFile for the same
            // fileId makes TDLib bump the request to the higher priority,
            // which would defeat the low-priority intent and waste a
            // round-trip on every grid cell while the user scrolls.
        }
        // Video overlay — play glyph in the corner so users can tell
        // photos from videos at a glance in the grid.
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.Play,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    val secs = (content as? TdApi.MessageVideo)?.video?.duration ?: 0
                    Text(
                        text = "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaList(
    messages: List<TdApi.Message>,
    filter: TdApi.SearchMessagesFilter,
    onItemTap: (TdApi.Message) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(messages, key = { it.id }) { msg ->
            MediaListRow(msg = msg, filter = filter, onClick = { onItemTap(msg) })
        }
    }
}

@Composable
private fun MediaListRow(
    msg: TdApi.Message,
    filter: TdApi.SearchMessagesFilter,
    onClick: () -> Unit
) {
    val (icon, title, subtitle) = describe(msg, filter)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun describe(
    msg: TdApi.Message,
    filter: TdApi.SearchMessagesFilter
): Triple<androidx.compose.ui.graphics.vector.ImageVector, String, String> {
    val phos = com.secondream.novagram.ui.icons.PhosphorIcons
    return when (val c = msg.content) {
        is TdApi.MessageDocument -> Triple(
            phos.FileText,
            c.document.fileName.ifBlank { "File" },
            humanSize(c.document.document.size) +
                (c.caption?.text?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        )
        is TdApi.MessageVoiceNote -> Triple(
            phos.Microphone,
            "Nota vocale",
            "${c.voiceNote.duration}s"
        )
        is TdApi.MessageAudio -> Triple(
            phos.FileAudio,
            c.audio.title.ifBlank { c.audio.fileName.ifBlank { "Audio" } },
            c.audio.performer.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty() +
                "${c.audio.duration}s"
        )
        is TdApi.MessageText -> {
            // For the Url filter, the message is a text that contains
            // a link — render the first link entity (or the text body
            // if no entity is marked) and put the rest of the body in
            // the subtitle line.
            val entities = c.text.entities
            val firstLink = entities.firstOrNull {
                it.type is TdApi.TextEntityTypeUrl || it.type is TdApi.TextEntityTypeTextUrl
            }
            val url = when (val type = firstLink?.type) {
                is TdApi.TextEntityTypeTextUrl -> type.url
                is TdApi.TextEntityTypeUrl -> {
                    c.text.text.substring(firstLink.offset, firstLink.offset + firstLink.length)
                }
                else -> c.text.text
            }
            Triple(phos.Paperclip, url, "")
        }
        else -> Triple(phos.FileText, "Elemento", "")
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "kB", "MB", "GB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024.0 && u < units.size - 1) { v /= 1024.0; u++ }
    return if (u == 0) "$bytes ${units[u]}"
    else String.format(java.util.Locale.US, "%.1f %s", v, units[u])
}
