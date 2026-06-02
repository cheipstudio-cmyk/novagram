@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.screens

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.togetherWith
import coil.compose.AsyncImage
import com.secondream.novagram.R
import com.secondream.novagram.td.TdClient
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
        // "I miei pack": the list of installed sets (headers only). Tapping a
        // pack expands its full sticker grid in-place with a back arrow.
        var packs by remember { mutableStateOf<List<TdApi.StickerSetInfo>?>(null) }
        var expandedPack by remember { mutableStateOf<TdApi.StickerSetInfo?>(null) }
        var expandedPackStickers by remember { mutableStateOf<List<TdApi.Sticker>?>(null) }

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
        // Pack headers for the "I miei pack" tab (index 3).
        LaunchedEffect(selectedTab) {
            if (selectedTab != 3 || packs != null) return@LaunchedEffect
            packs = runCatching { TdClient.getInstalledStickerSets().sets.toList() }
                .getOrDefault(emptyList())
        }
        // Full sticker list for the pack the user expanded.
        LaunchedEffect(expandedPack) {
            val info = expandedPack ?: run { expandedPackStickers = null; return@LaunchedEffect }
            expandedPackStickers = null
            expandedPackStickers = runCatching { TdClient.getStickerSet(info.id).stickers.toList() }
                .getOrDefault(emptyList())
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
            // pill shape that matches the rest of Nova, with a search
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
                        com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass,
                        contentDescription = null
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        androidx.compose.material3.IconButton(onClick = { query = "" }) {
                            androidx.compose.material3.Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.X,
                                contentDescription = null
                            )
                        }
                    }
                } else null
            )
            Spacer(Modifier.height(10.dp))

            val isSearching = query.isNotBlank()
            if (!isSearching) {
                if (selectedTab == 3 && expandedPack != null) {
                    // Inside an expanded pack: back arrow + pack title replace
                    // the tab row, so the arrow returns to the 4 tabs.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.IconButton(onClick = { expandedPack = null }) {
                            androidx.compose.material3.Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft,
                                contentDescription = stringResourceSafe(R.string.action_back)
                            )
                        }
                        Text(
                            expandedPack?.title.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    // Pill tabs matching the rest of the app (chat list + new chat).
                    StickerTabs(
                        titles = listOf(
                            stringResourceSafe(R.string.sticker_tab_favorites),
                            stringResourceSafe(R.string.sticker_tab_recents),
                            stringResourceSafe(R.string.sticker_tab_all),
                            stringResourceSafe(R.string.sticker_tab_packs)
                        ),
                        selected = selectedTab,
                        onSelect = { selectedTab = it }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Which "view" is on screen: -1 = search results, else the tab
            // index. AnimatedContent crossfades + slides between them, and the
            // whole region is pinned to a FIXED height so switching tabs no
            // longer makes the sheet jump. The lists have wildly different
            // lengths (favorites might be 8, "Tutti" 300+) and the old
            // heightIn(min..max) let each one resize the sheet — exactly the
            // "il modal cambia altezza con stacchi brutti" Eugenio hit.
            val viewState = if (isSearching) -1 else selectedTab
            androidx.compose.animation.AnimatedContent(
                targetState = viewState,
                transitionSpec = {
                    val dx = if (targetState > initialState) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ) { w -> dx * w / 5 } + androidx.compose.animation.fadeIn(
                        animationSpec = androidx.compose.animation.core.tween(200)
                    )) togetherWith (androidx.compose.animation.slideOutHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(220)
                    ) { w -> -dx * w / 5 } + androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(140)
                    ))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp),
                label = "sticker-view"
            ) { state ->
                if (state == 3) {
                    // ── "I miei pack": pack list, or one expanded pack's grid ──
                    val info = expandedPack
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (info == null) {
                            val p = packs
                            when {
                                p == null -> CircularProgressIndicator()
                                p.isEmpty() -> Text(
                                    stringResourceSafe(R.string.sticker_picker_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                else -> LazyVerticalGrid(
                                    columns = GridCells.Fixed(1),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(p, key = { it.id }) { pack ->
                                        PackRow(pack = pack, onClick = { expandedPack = pack })
                                    }
                                }
                            }
                        } else {
                            val st = expandedPackStickers
                            when {
                                st == null -> CircularProgressIndicator()
                                st.isEmpty() -> Text(
                                    stringResourceSafe(R.string.sticker_picker_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                else -> LazyVerticalGrid(
                                    columns = GridCells.Fixed(4),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(st, key = { it.sticker.id }) { s ->
                                        StickerCell(sticker = s, onClick = { onPick(s) })
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val list: List<TdApi.Sticker>? = when (state) {
                        -1 -> searchResults
                        0 -> favorites
                        1 -> recents
                        else -> allInstalled
                    }
                    val loading = if (state == -1) (searchInFlight || searchResults == null)
                                  else list == null
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            loading -> CircularProgressIndicator()
                            list.isNullOrEmpty() -> Text(
                                stringResourceSafe(R.string.sticker_picker_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(list, key = { it.sticker.id }) { st ->
                                    StickerCell(sticker = st, onClick = { onPick(st) })
                                }
                            }
                        }
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
    val alreadyReady = thumbFile?.local?.isDownloadingCompleted == true &&
        !thumbFile.local.path.isNullOrBlank()
    var fileState by remember(thumbFile?.id) {
        mutableStateOf(thumbFile)
    }
    LaunchedEffect(thumbFile?.id) {
        val f = thumbFile ?: return@LaunchedEffect
        // Fast path: the thumb came back from TDLib already downloaded —
        // skip the GetFile round-trip AND the fileUpdates subscription
        // entirely. On the "Tutti" tab the grid can hold hundreds of
        // stickers; firing a TDLib call per cell as it scrolled into view
        // was a real source of the "liste laggose" stutter.
        if (alreadyReady) return@LaunchedEffect
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

    // Tactile press feedback: the tile springs down to 86% while held and
    // bounces back on release. Cheap (only visible cells are composed) and
    // makes the picker feel responsive instead of a flat grid of buttons.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.45f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "sticker-press"
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .scale(pressScale)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        val path = fileState?.local?.path
        if (!path.isNullOrBlank() && fileState?.local?.isDownloadingCompleted == true) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(
                    androidx.compose.ui.platform.LocalContext.current
                )
                    .data(path)
                    .crossfade(true)
                    .build(),
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

/**
 * One installed pack in the "I miei pack" tab. Tapping anywhere on the
 * row expands the pack into its full sticker grid (handled by the host).
 * The cover reuses StickerCell so its thumbnail download + fade-in is
 * identical to the grid; the rest of the row shows title and count.
 */
@Composable
private fun PackRow(pack: TdApi.StickerSetInfo, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "pack-row-press"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pressScale)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            val cover = pack.covers.firstOrNull()
            if (cover != null) {
                StickerCell(sticker = cover, onClick = onClick)
            } else {
                Text("\uD83D\uDDC2", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pack.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${pack.size} " + stringResourceSafe(R.string.sticker_pack_count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    // Sliding-pill tabs: instead of the selected tab's background hard-
    // cutting on/off, a single rounded pill springs across to sit under
    // the active tab, and the label colours crossfade in step. The fixed
    // height lets the pill fillMaxHeight cleanly inside BoxWithConstraints.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val count = titles.size.coerceAtLeast(1)
        val pillWidth = maxWidth / count
        val pillX by androidx.compose.animation.core.animateDpAsState(
            targetValue = pillWidth * selected.coerceIn(0, count - 1),
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.82f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            ),
            label = "sticker-tab-pill"
        )
        Box(
            modifier = Modifier
                .offset(x = pillX)
                .width(pillWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(primary)
        )
        Row(modifier = Modifier.fillMaxSize()) {
            titles.forEachIndexed { i, title ->
                val labelColor by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected == i) onPrimary else onMuted,
                    animationSpec = androidx.compose.animation.core.tween(220),
                    label = "sticker-tab-label"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor,
                        fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun stringResourceSafe(id: Int): String =
    androidx.compose.ui.res.stringResource(id)
