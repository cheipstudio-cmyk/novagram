package com.secondream.novagram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.novagram.td.TdClient
import org.drinkless.tdlib.TdApi

/**
 * Forum-topic list panel rendered inside ChatScreen when the active
 * chat's supergroup is flagged isForum=true and the user hasn't yet
 * picked a topic. Replaces the message list with a scrollable list of
 * topics — each row shows the topic's name, the last message preview,
 * unread badges, and an icon-color circle (Telegram bundles topics
 * with a custom emoji + a 6-color palette; we render the color, and
 * the emoji glyph if we can decode it from the cached file).
 *
 * On tap, [onPickTopic] receives the messageThreadId — ChatScreen
 * then switches to threaded mode and renders that topic's messages.
 *
 * The "General" topic (the default topic where messages flow when no
 * topic is selected by the poster) is rendered first and pinned at
 * the top of the list — matches Telegram behaviour.
 */
@Composable
fun TopicListPanel(
    chatId: Long,
    onPickTopic: (messageThreadId: Long, name: String) -> Unit
) {
    var topics by remember(chatId) { mutableStateOf<List<TdApi.ForumTopic>>(emptyList()) }
    var loading by remember(chatId) { mutableStateOf(true) }
    LaunchedEffect(chatId) {
        loading = true
        topics = runCatching { TdClient.getForumTopics(chatId) }
            .getOrDefault(emptyList())
        loading = false
    }

    if (loading && topics.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
        }
        return
    }
    if (topics.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Nessun topic disponibile",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
    ) {
        items(topics, key = { it.info.messageThreadId }) { topic ->
            TopicRow(topic = topic, onClick = {
                onPickTopic(topic.info.messageThreadId, topic.info.name)
            })
        }
    }
}

@Composable
private fun TopicRow(topic: TdApi.ForumTopic, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color-coded circle as the topic icon. Telegram exposes 7
        // canonical colors keyed on `iconColor` (an int we map to our
        // palette). Custom-emoji icons would require fetching the
        // sticker file; for now we just paint the color and overlay
        // the first letter of the topic name as a fallback initial.
        val palette = listOf(
            Color(0xFF6FB9F0), // blue
            Color(0xFFFFD67E), // gold
            Color(0xFFCB86DB), // purple
            Color(0xFF8EEE98), // green
            Color(0xFFFF93B2), // pink
            Color(0xFFFB6F5F)  // red
        )
        val color = palette[
            (topic.info.iconColor and 0x7fffffff) % palette.size
        ]
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            val initial = topic.info.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            Text(
                initial,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            // Unread badge — a small dot at the top-right corner when
            // the topic carries unread messages. Same pattern as the
            // chat list rows.
            if (topic.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (topic.unreadCount > 99) "99+" else topic.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                topic.info.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = topic.lastMessage?.let { msg ->
                when (val c = msg.content) {
                    is TdApi.MessageText -> c.text.text
                    is TdApi.MessagePhoto -> "📷 " + (c.caption?.text ?: "Foto")
                    is TdApi.MessageVideo -> "🎬 " + (c.caption?.text ?: "Video")
                    is TdApi.MessageDocument -> "📄 " + (c.caption?.text ?: "File")
                    is TdApi.MessageVoiceNote -> "🎙 Nota vocale"
                    is TdApi.MessageSticker -> c.sticker.emoji
                    else -> ""
                }
            }.orEmpty()
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (topic.isPinned) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "📌",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
