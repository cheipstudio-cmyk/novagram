package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.screens.avatarBackgroundFor
import org.drinkless.tdlib.TdApi

/**
 * Renders a compact "sta scrivendo…" badge with up to 3 overlapping
 * sender avatars on the left and a label that adapts to count:
 *   1 sender  → "Alice sta scrivendo…"
 *   2 senders → "Alice, Bob stanno scrivendo…"
 *   3+        → "Alice e altri 2 stanno scrivendo…"
 *
 * For private chats (single peer) the avatar is omitted — the chat
 * header already shows it large at the top, repeating it above the
 * input feels redundant. We render just the animated dots + text in
 * that case.
 *
 * The composable subscribes to TdClient.chatActions and pulls user
 * names/photos from the user cache. Hidden entirely when no peer is
 * acting; animates in with a slide+fade so the input bar visually
 * shifts up by ~24dp when the indicator appears.
 */
@Composable
fun TypingIndicator(
    chatId: Long,
    modifier: Modifier = Modifier,
    showAvatars: Boolean = true
) {
    val actionsMap by TdClient.chatActions.collectAsState()
    val perChat = actionsMap[chatId].orEmpty()
    // Filter to only TYPING actions for now — the other ChatAction
    // subtypes (recording voice, uploading photo, etc.) deserve their
    // own copy and we'd rather show nothing than mislabel them as
    // "sta scrivendo" when the peer is e.g. uploading a video.
    val typingSenders = remember(perChat) {
        perChat.filterValues { it is TdApi.ChatActionTyping }.keys.toList()
    }
    // Resolve sender → User for the names + photos. Pulled from the
    // cache only (no network round-trip) since the typing badge
    // updates frequently and a per-tick getUser would hammer TDLib;
    // any user whose entry isn't cached yet renders with their id as
    // fallback letter, which is rare and brief.
    val resolved = remember(typingSenders) {
        typingSenders.mapNotNull { sid ->
            if (sid > 0L) {
                TdClient.getCachedUser(sid)?.let { sid to it }
            } else null
        }
    }

    AnimatedVisibility(
        visible = typingSenders.isNotEmpty(),
        enter = fadeIn(tween(180)) + expandVertically(tween(180)),
        exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showAvatars && resolved.isNotEmpty()) {
                // Stack up to 3 avatars with a small horizontal offset
                // so they look like overlapping circles. The Box width
                // is computed so the trailing label sits at a stable
                // distance from the rightmost avatar regardless of
                // sender count.
                val avatarSize = 18.dp
                val overlap = 6.dp
                val visible = resolved.take(3)
                val totalW = avatarSize + (avatarSize - overlap) * (visible.size - 1).coerceAtLeast(0)
                Box(modifier = Modifier.width(totalW).height(avatarSize)) {
                    visible.forEachIndexed { idx, (sid, user) ->
                        Box(
                            modifier = Modifier
                                .offset(x = (avatarSize - overlap) * idx)
                                .size(avatarSize)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            Avatar(
                                file = user.profilePhoto?.small,
                                fallbackText = user.firstName.ifBlank { user.lastName.ifBlank { "?" } },
                                bgColor = avatarBackgroundFor(sid),
                                size = avatarSize
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            // Label. Italic-ish through MaterialTheme.typography.bodySmall
            // weight + accent colour to feel "live" instead of permanent
            // chrome.
            val label = buildLabel(resolved.map { it.second }, typingSenders.size)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(4.dp))
            AnimatedDots(color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Three small dots that bounce in sequence — the universal "live
 * activity" indicator. We render them with `infiniteTransition` and
 * stagger the keyframes so each dot peaks at a different phase.
 */
@Composable
private fun AnimatedDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(3) { idx ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(idx * 180)
                ),
                label = "dot-$idx"
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

private fun buildLabel(users: List<TdApi.User>, totalCount: Int): String {
    fun displayName(u: TdApi.User): String {
        val first = u.firstName.trim()
        val last = u.lastName.trim()
        return when {
            first.isNotEmpty() -> first
            last.isNotEmpty() -> last
            u.usernames?.editableUsername?.isNotEmpty() == true -> "@${u.usernames!!.editableUsername}"
            else -> "Qualcuno"
        }
    }
    val names = users.map(::displayName)
    return when {
        totalCount <= 0 -> ""
        totalCount == 1 -> "${names.firstOrNull() ?: "Qualcuno"} sta scrivendo"
        totalCount == 2 && names.size >= 2 ->
            "${names[0]}, ${names[1]} stanno scrivendo"
        names.isEmpty() -> "$totalCount persone stanno scrivendo"
        else -> {
            val shown = names.first()
            val others = totalCount - 1
            if (others == 1) "$shown e altro/a stanno scrivendo"
            else "$shown e altri $others stanno scrivendo"
        }
    }
}
