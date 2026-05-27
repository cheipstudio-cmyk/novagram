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
import com.secondream.cheipgram.ui.components.AnimatedTgsSticker
import com.secondream.cheipgram.ui.components.WebmVideoSticker
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

        LaunchedEffect(Unit) {
            favorites = runCatching { TdClient.getFavoriteStickers().stickers.toList() }
                .getOrDefault(emptyList())
            recents = runCatching { TdClient.getRecentStickers().stickers.toList() }
                .getOrDefault(emptyList())
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
            Spacer(Modifier.height(12.dp))

            // Pill tabs matching the rest of the app (chat list + new chat).
            StickerTabs(
                titles = listOf(
                    stringResourceSafe(R.string.sticker_tab_favorites),
                    stringResourceSafe(R.string.sticker_tab_recents)
                ),
                selected = selectedTab,
                onSelect = { selectedTab = it }
            )

            Spacer(Modifier.height(12.dp))

            val current: List<TdApi.Sticker>? = if (selectedTab == 0) favorites else recents
            when {
                current == null -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                current.isEmpty() -> Box(
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
 * Single sticker tile in the picker grid. Three render paths:
 *   - WebP (static): downloaded thumbnail or the sticker file itself
 *   - TGS (Lottie): we keep the Lottie playing because the tile is small
 *     and the cost is low; previewing only the thumbnail strips animation
 *     which makes the picker feel dead.
 *   - WebM (video): ExoPlayer instance, same reasoning. We rely on
 *     DisposableEffect inside WebmVideoSticker to release decoders as the
 *     user scrolls past.
 */
@Composable
private fun StickerCell(sticker: TdApi.Sticker, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        when (sticker.format) {
            is TdApi.StickerFormatWebp -> {
                val thumb = sticker.thumbnail?.file?.local?.path
                if (!thumb.isNullOrBlank()) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(sticker.emoji.ifBlank { "🖼" }, style = MaterialTheme.typography.titleLarge)
                }
            }
            is TdApi.StickerFormatTgs -> AnimatedTgsSticker(
                file = sticker.sticker,
                fallbackEmoji = sticker.emoji.ifBlank { "🖼" }
            )
            is TdApi.StickerFormatWebm -> WebmVideoSticker(
                file = sticker.sticker,
                fallbackEmoji = sticker.emoji.ifBlank { "🖼" }
            )
            else -> Text(sticker.emoji.ifBlank { "🖼" }, style = MaterialTheme.typography.titleLarge)
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
