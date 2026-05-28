package com.secondream.cheipgram.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

/**
 * Persistent transfer-status panel rendered at the top of the navigation
 * graph (Scaffold's content slot in MainActivity), so it sits above every
 * screen and survives navigation between chats / settings / tabs. When
 * no transfers are in flight the panel is fully gone — no empty card.
 *
 * Layout:
 *   - Collapsed (default): a single thin row showing "N trasferimenti"
 *     + a chevron, tappable to expand.
 *   - Expanded: scrollable list (up to ~40% of screen height) with one
 *     row per active transfer: arrow icon (up/down) + name + linear
 *     progress + "X% · A MB / B MB".
 *
 * Animations: slide-in/out from bottom + fade. Spring-driven progress
 * updates come for free since [LinearProgressIndicator]'s value
 * change is animated by Material 3.
 */
@Composable
fun TransferPanel(modifier: Modifier = Modifier) {
    val transfers by TransferTracker.transfers.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    // Collapse automatically whenever there are no transfers, so the pill
    // doesn't reopen empty next time.
    LaunchedEffect(transfers.isEmpty()) { if (transfers.isEmpty()) expanded = false }
    AnimatedVisibility(
        visible = transfers.isNotEmpty(),
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(top = 10.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val down = transfers.count { !it.isUpload }
            val up = transfers.count { it.isUpload }
            // COMPACT PILL — small, centered, not a full-width banner. Shows
            // a down/up arrow + the count of active transfers. Tap to open
            // the mini modal with the per-file progress list.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (up > 0 && down == 0) Icons.Outlined.ArrowUpward
                    else Icons.Outlined.ArrowDownward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (up > 0 && down > 0) "${down + up}" else "${down + up}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
            // MINI MODAL — appears under the pill when expanded.
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it })
            ) {
                Column(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(transfers, key = { it.fileId }) { t ->
                            TransferRow(t)
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildLabel(down: Int, up: Int): String {
    return when {
        down > 0 && up > 0 -> "${down + up} trasferimenti (↓$down · ↑$up)"
        down > 0 -> "$down ${if (down == 1) "download" else "download"} in corso"
        up > 0 -> "$up ${if (up == 1) "upload" else "upload"} in corso"
        else -> "Trasferimenti"
    }
}

@Composable
private fun TransferRow(t: TransferTracker.Transfer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (t.isUpload) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = t.fileName ?: "File #${t.fileId}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { t.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${(t.progress * 100).toInt()}% · ${formatMb(t.transferredBytes)} / ${formatMb(t.totalBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatMb(bytes: Long): String {
    val mb = 1024.0 * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.1f GB".format(bytes / gb)
        bytes >= mb -> "%.1f MB".format(bytes / mb)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
