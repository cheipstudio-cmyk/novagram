package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Novagram AI surface — SLICE 1b: the FAB MORPHS into the bubble.
 *
 * Not a button that pops a separate panel: it is ONE element that transforms.
 * Collapsed it's a 56dp amber circle with the sparkle. Tapped, the SAME
 * surface grows up-and-right from its bottom-left corner — its size animates
 * (animateContentSize), its colour bleeds from amber to the dark panel
 * surface, its corner relaxes from a circle to a soft rounded rect — and the
 * panel content is revealed by the growing bounds (the surface clips to its
 * shape, so it reads as a container expanding, not a card appearing). A scrim
 * fades behind; tapping it (or the chevron) collapses the surface back into
 * the circle.
 *
 * Tiles are still inert here — this build is to judge the MORPH and the look.
 * The actions, streaming and per-chat session are the next slices, wired
 * through [onCommand].
 *
 * Mount as the LAST child of a full-screen Box so it overlays everything.
 */
@Composable
fun AiAssistantBubble(
    contextLabel: String,
    modifier: Modifier = Modifier,
    onCommand: (String) -> Unit = {}
) {
    var open by remember { mutableStateOf(false) }

    val tiles = remember {
        listOf(
            AiTile("summarize_unread", "Riassumi i non letti", "Cosa mi sono perso", AiGlyph.Chats),
            AiTile("find_message", "Trova un messaggio", "Cerca per significato", AiGlyph.Search),
            AiTile("translate", "Traduci", "Gli ultimi messaggi", AiGlyph.Translate)
        )
    }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val surface = MaterialTheme.colorScheme.surface

    Box(modifier = modifier.fillMaxSize()) {

        // Scrim behind the surface; catches the outside tap.
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { open = false }
            )
        }

        // The morphing element. Bottom-left pinned; everything else animates.
        val containerColor by animateColorAsState(
            targetValue = if (open) surface else accent,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "ai-bg"
        )
        val corner by animateDpAsState(
            targetValue = if (open) 22.dp else 28.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "ai-corner"
        )
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(corner),
            shadowElevation = 10.dp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 18.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.78f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .clickable(
                    enabled = !open,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { open = true }
        ) {
            if (open) {
                AiPanel(
                    contextLabel = contextLabel,
                    tiles = tiles,
                    accent = accent,
                    onAccent = onAccent,
                    onCollapse = { open = false },
                    onCommand = {
                        onCommand(it)
                        open = false
                    }
                )
            } else {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                        contentDescription = "Novagram AI",
                        tint = onAccent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AiPanel(
    contextLabel: String,
    tiles: List<AiTile>,
    accent: Color,
    onAccent: Color,
    onCollapse: () -> Unit,
    onCommand: (String) -> Unit
) {
    // Whole panel fades up slightly as the container grows, so the content
    // settles in rather than snapping on.
    Column(
        modifier = Modifier
            .width(330.dp)
            .padding(16.dp)
    ) {
        // Header: sparkle mark + serif italic title + scope + collapse chevron.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                    contentDescription = null,
                    tint = onAccent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Novagram AI",
                    fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    contextLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onCollapse() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.CaretDown,
                    contentDescription = "Chiudi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Action tiles — Novagram style: amber-tinted icon chip + title + sub.
        tiles.forEach { tile ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onCommand(tile.id) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            tile.glyph.icon(),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            tile.label,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            tile.sub,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Conversation entry (visual only for now).
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp)
            ) {
                Text(
                    "Chiedi o dai un comando...",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.ArrowUp,
                        contentDescription = "Invia",
                        tint = onAccent,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

private enum class AiGlyph { Chats, Search, Translate }

private fun AiGlyph.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    AiGlyph.Chats -> com.secondream.novagram.ui.icons.PhosphorIcons.Chats
    AiGlyph.Search -> com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass
    AiGlyph.Translate -> com.secondream.novagram.ui.icons.PhosphorIcons.Translate
}

private data class AiTile(
    val id: String,
    val label: String,
    val sub: String,
    val glyph: AiGlyph
)
