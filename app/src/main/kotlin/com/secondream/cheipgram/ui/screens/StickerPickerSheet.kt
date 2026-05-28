@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import coil.compose.AsyncImage
import com.secondream.cheipgram.R
import com.secondream.cheipgram.td.TdClient
import org.drinkless.tdlib.TdApi

/**
 * Bottom sheet to browse and send stickers. v0.5.0 covers the two lists
 * that matter most for daily use:
 *   - Preferiti: stickers the user has favourited in any Telegram client
 *   - Recenti:  the most recently sent ones
 *
 * Full sticker-set browsing (search, packs, premium emoji) is intentionally
 * out of scope here; once we add it we'll expand the tab row instead of
 * rebuilding the picker.
 *
 * Tap on a sticker fires onPick(sticker); the host screen is responsible
 * for dismissing this sheet and calling TdClient.sendSticker. Thumbnails
 * are used in the grid (PNG) — full TGS/WebM playback only runs on actual
 * incoming messages to keep this picker cheap to render.
 */
@Composable
fun StickerPickerSheet(
    onDismiss: () -> Unit,
    onPick: (TdApi.Sticker) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        var selectedTab by remember { mutableStateOf(0) }
        var favorites by remember { mutableStateOf<List<TdApi.Sticker>?>(null) }
        var recents by remember { mutableStateOf<List<TdApi.Sticker>?>(null) }
        // "Tutti": the entire installed sticker library flattened. We load
        // the set headers first, then expand each set's stickers in parallel
        // so the grid populates progressively rather than after one giant
        // round trip.
        var allInstalled by remember { mutableStateOf<List<TdApi.Sticker>?>(null) }

        // Search state: query is what the TextField shows; debounced is what
        // we actually pass to TdClient. The 300ms debounce keeps us from
        // hammering TDLib while the user is still typing.
        var query by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<TdApi.Sticker>?>(null) }
        var searchInFlight by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            favorites = runCatching { TdClient.getFavoriteStickers().stickers.toList() }
                .getOrDefault(emptyList())
            recents = runCatching { TdClient.getRecentStickers().stickers.toList() }
                .getOrDefault(emptyList())
        }
        // Lazy-load the installed library when the user first taps "Tutti".
        // Avoids paying the round-trip cost up-front for users who never
        // browse beyond favorites/recents.
        LaunchedEffect(selectedTab) {
            if (selectedTab != 2 || allInstalled != null) return@LaunchedEffect
            val sets = runCatching { TdClient.getInstalledStickerSets() }
                .getOrNull()
                ?.sets
                ?.toList()
                .orEmpty()
            val flat = mutableListOf<TdApi.Sticker>()
            for (info in sets) {
                val set = runCatching { TdClient.getStickerSet(info.id) }.getOrNull()
                if (set != null) flat.addAll(set.stickers)
                // Update progressively so the grid fills in as sets load,
                // instead of staying empty until everything resolves.
                allInstalled = flat.toList()
            }
            if (allInstalled == null) allInstalled = emptyList()
        }
        LaunchedEffect(query) {
            val q = query.trim()
            if (q.isEmpty()) {
                searchResults = null
                searchInFlight = false
                return@LaunchedEffect
            }
            // Debounce. If the user keeps typing, this LaunchedEffect is
            // restarted and the delay is cancelled before the request fires.
            kotlinx.coroutines.delay(300)
            searchInFlight = true
            searchResults = runCatching { TdClient.searchStickers(q).stickers.toList() }
                .getOrDefault(emptyList())
            searchInFlight = false
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResourceSafe(R.string.sticker_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(10.dp))

            // Search field. We use the OutlinedTextField for a recognisable
            // pill shape that matches the rest of CheipGram, with a search
            // icon on the leading edge and a clear button on the trailing.
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResourceSafe(R.string.sticker_search_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                leadingIcon = {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Outlined.Search,
                        contentDescription = null
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        androidx.compose.material3.IconButton(onClick = { query = "" }) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.Close,
                                contentDescription = null
                            )
                        }
                    }
                } else null
            )
            Spacer(Modifier.height(10.dp))

            val isSearching = query.isNotBlank()
            if (!isSearching) {
                // Pill tabs matching the rest of the app (chat list + new chat).
                StickerTabs(
                    titles = listOf(
                        stringResourceSafe(R.string.sticker_tab_favorites),
                        stringResourceSafe(R.string.sticker_tab_recents),
                        stringResourceSafe(R.string.sticker_tab_all)
                    ),
                    selected = selectedTab,
                    onSelect = { selectedTab = it }
                )
                Spacer(Modifier.height(12.dp))
            }

            val current: List<TdApi.Sticker>? = when {
                isSearching -> searchResults
                selectedTab == 0 -> favorites
                selectedTab == 1 -> recents
                else -> allInstalled
            }
            val isLoading = (isSearching && (searchInFlight || searchResults == null)) ||
                (!isSearching && current == null)

            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                current == null || current.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResourceSafe(R.string.sticker_picker_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 480.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(current, key = { it.sticker.id }) { st ->
                        StickerCell(sticker = st, onClick = { onPick(st) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Single sticker tile in the picker grid.
 *
 * The previous version rendered the full sticker for every tile —
 * Lottie for TGS, ExoPlayer for WebM — and that meant a 200-sticker
 * "Tutti" grid spun up 200 decoders. On real devices the scroll
 * stuttered and individual cells took multiple seconds to show
 * because each one was waiting on its own decode pipeline.
 *
 * Now every format renders the THUMBNAIL (sticker.thumbnail) as a
 * plain image — TDLib produces a static WebP/PNG thumbnail for both
 * TGS and WebM stickers, so the picker stays cheap and consistent.
 * The full animation only runs when an incoming chat message uses
 * the sticker, which is where users actually want to see it move.
 *
 * We also subscribe to fileUpdates so the thumbnail fades in as it
 * downloads, instead of showing the emoji fallback "forever".
 */
@Composable
private fun StickerCell(sticker: TdApi.Sticker, onClick: () -> Unit) {
    val thumbFile: TdApi.File? = sticker.thumbnail?.file ?: sticker.sticker
    var fileState by remember(thumbFile?.id) {
        mutableStateOf(thumbFile)
    }
    LaunchedEffect(thumbFile?.id) {
        val f = thumbFile ?: return@LaunchedEffect
        // Snapshot the live state on (re-)entry. Without this a tile
        // that scrolled offscreen then back can stay stuck on the
        // emoji fallback even though the thumb finished downloading
        // in the gap — same bug pattern we fixed for chat photos.
        runCatching { TdClient.getFile(f.id) }.onSuccess { latest ->
            fileState = latest
        }
        val current = fileState ?: return@LaunchedEffect
        if (!current.local.isDownloadingCompleted && !current.local.isDownloadingActive) {
            runCatching { TdClient.downloadFile(current.id) }
        }
        TdClient.fileUpdates.collect { updated ->
            if (updated.id == f.id) fileState = updated
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val path = fileState?.local?.path
        if (!path.isNullOrBlank() && fileState?.local?.isDownloadingCompleted == true) {
            AsyncImage(
                model = path,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // While we wait for the thumbnail, show the sticker's emoji
            // hint (every Telegram sticker has one). Much friendlier
            // than an empty square and gives the user something to
            // pick by even before files finish syncing.
            Text(
                sticker.emoji.ifBlank { "🖼" },
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
private fun StickerTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onMuted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        titles.forEachIndexed { i, title ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected == i) primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected == i) onPrimary else onMuted,
                    fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun stringResourceSafe(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
