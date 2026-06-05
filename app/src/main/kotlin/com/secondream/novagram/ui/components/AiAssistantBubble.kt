package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Novagram AI surface — SLICE 1: pure motion + layout shell.
 *
 * A floating button that lives at the bottom-right of whatever screen mounts
 * it. Tapping it expands a bubble that GROWS OUT OF THE BUTTON (the panel's
 * transform origin is pinned to the FAB corner) with a spring, the action
 * tiles cascade in one after another, and a scrim fades behind to catch an
 * outside tap. Tapping the FAB again (now an ✕) collapses it back into itself.
 *
 * The tiles do NOT do anything yet — this build exists only to judge and tune
 * the FEEL of the open/close on a real device. Streaming, the per-chat AI
 * session, and the agentic commands (with confirmation cards) are later slices
 * wired through [onCommand].
 *
 * Mount it as the LAST child of a full-screen Box so it overlays everything:
 *     Box(Modifier.fillMaxSize()) { ...screen...; AiAssistantBubble(chatTitle) }
 *
 * @param contextLabel what the assistant is currently scoped to (e.g. the chat
 *        title) — shown under the title so it reads as "I work on THIS chat".
 * @param onCommand invoked with a stable id when a tile is tapped (wired later).
 */
@Composable
fun AiAssistantBubble(
    contextLabel: String,
    modifier: Modifier = Modifier,
    onCommand: (String) -> Unit = {}
) {
    var open by remember { mutableStateOf(false) }

    // Read-only commands first (safe, no confirmation). Act-commands (PDF,
    // group, reply-for-me) come later behind a preview/confirm card.
    val tiles = remember {
        listOf(
            AiTile("summarize_unread", "Riassumi i non letti"),
            AiTile("find_message", "Trova un messaggio"),
            AiTile("translate", "Traduci la chat")
        )
    }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Box(modifier = modifier.fillMaxSize()) {

        // Scrim — fades in behind the bubble, swallows the outside tap. No
        // ripple (it's a dismiss surface, not a button).
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

        // The bubble. Anchored bottom-end and grown FROM the FAB corner:
        // transformOrigin (1,1) = bottom-right, so the scale-up emanates out of
        // the button instead of from the panel's own centre. Spring (not tween)
        // so it has a little life on the way out.
        AnimatedVisibility(
            visible = open,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 86.dp),
            enter = scaleIn(
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                initialScale = 0.55f,
                transformOrigin = TransformOrigin(0f, 1f)
            ) + fadeIn(tween(120)),
            exit = scaleOut(
                animationSpec = tween(170),
                targetScale = 0.6f,
                transformOrigin = TransformOrigin(0f, 1f)
            ) + fadeOut(tween(140))
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(min = 240.dp, max = 300.dp)
            ) {
                Column(Modifier.padding(16.dp)) {

                    // Header — sparkle mark + serif italic title (the editorial
                    // accent), with the current scope underneath.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✦", fontSize = 16.sp, color = onAccent)
                        }
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(
                                "Novagram AI",
                                fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                contextLabel,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Tiles — cascade in one by one. Each tile's alpha + vertical
                    // offset is driven by its own animation, staggered by index,
                    // and only "armed" once the panel is open so the cascade
                    // restarts on every open.
                    tiles.forEachIndexed { i, tile ->
                        val progress by animateFloatAsState(
                            targetValue = if (open) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = 240,
                                delayMillis = 60 + i * 55
                            ),
                            label = "tile-$i"
                        )
                        val dy = with(LocalDensity.current) { ((1f - progress) * 14.dp.toPx()) }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = progress; translationY = dy }
                                .clickable {
                                    onCommand(tile.id)
                                    open = false
                                }
                        ) {
                            Text(
                                tile.label,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Conversation entry (visual only for now).
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 14.dp, end = 7.dp, top = 7.dp, bottom = 7.dp)
                        ) {
                            Text(
                                "Chiedi o dai un comando…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("↑", fontSize = 15.sp, color = onAccent)
                            }
                        }
                    }
                }
            }
        }

        // The FAB itself. A subtle press-scale, and its glyph crossfades /
        // rotates between the sparkle (closed) and an ✕ (open) so the same
        // button reads as "open AI" and "close" without moving.
        val fabScale by animateFloatAsState(
            targetValue = if (open) 0.92f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "fab-scale"
        )
        val iconSpin by animateFloatAsState(
            targetValue = if (open) 90f else 0f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
            label = "fab-spin"
        )
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 18.dp)
                .graphicsLayer { scaleX = fabScale; scaleY = fabScale }
                .size(52.dp)
                .clip(CircleShape)
                .background(accent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { open = !open },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (open) "✕" else "✦",
                fontSize = if (open) 20.sp else 22.sp,
                color = onAccent,
                modifier = Modifier.graphicsLayer { rotationZ = iconSpin }
            )
        }
    }
}

private data class AiTile(val id: String, val label: String)
