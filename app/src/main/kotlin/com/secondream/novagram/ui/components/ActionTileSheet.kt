package com.secondream.novagram.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One row in an ActionBottomSheet. icon + label + onClick. destructive
 * flips the tile to errorContainer tint (red text + red icon) for clear
 * visual signaling of irreversible actions (delete, leave).
 */
data class ActionTile(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false
)

/**
 * Square-ish tile: icon top, label below, soft tinted background, press
 * spring-scales to 92%. Same pattern as MessageActionsSheet's grid —
 * exported here so confirmation/picker bottom sheets across the app
 * have a single source of truth for "what an action button looks like".
 */
@Composable
fun ActionTileButton(
    tile: ActionTile,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "tile-press"
    )
    val cs = MaterialTheme.colorScheme
    val bg = if (tile.destructive) cs.errorContainer.copy(alpha = 0.4f)
             else cs.surfaceVariant
    val iconTint = if (tile.destructive) cs.error else cs.primary
    val labelColor = if (tile.destructive) cs.error else cs.onSurface
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = tile.onClick
            )
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            tile.icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tile.label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 2,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Drop-in replacement for AlertDialog when the choice is between a
 * small set of named actions. Rendered as a ModalBottomSheet (rounded
 * top, partial-screen overlay) with title + optional description text
 * + N tiles arranged in rows of `tilesPerRow` (default 2).
 *
 * Use cases across the app:
 *  - Confirm/destructive prompts (delete chat → 1 destructive tile)
 *  - Two-way picker (chat type Normal vs Secret → 2 tiles)
 *  - Multi-option selector (TTL: Off / 1d / 1w / 1m → 2x2 tiles)
 *  - Warning with single CTA (mic permission needed → 1 tile)
 *
 * Tiles get equal-weight Row layout so they expand to fill the row
 * regardless of count. The bottom-sheet shape and surface come from
 * the M3 defaults — same look as MessageActionsSheet.
 *
 * NOTE on dismissal: the caller is responsible for setting their own
 * open-state to false. The sheet's onDismissRequest invokes onDismiss
 * (swipe-down or scrim tap); tile onClick handlers must ALSO clear
 * the open-state if they want the sheet to close after the action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    tiles: List<ActionTile>,
    description: String? = null,
    tilesPerRow: Int = 2
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            // Chunk into rows. Pad the last row with empty Spacers
            // (weight=1f each) so the remaining tiles don't stretch
            // across the whole width — keeps tile sizes consistent
            // when the count isn't a multiple of tilesPerRow.
            tiles.chunked(tilesPerRow).forEachIndexed { rowIdx, rowTiles ->
                if (rowIdx > 0) Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowTiles.forEach { t ->
                        ActionTileButton(
                            tile = t,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(tilesPerRow - rowTiles.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
