package com.secondream.cheipgram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.cheipgram.R
import com.secondream.cheipgram.td.TdClient
import org.drinkless.tdlib.TdApi

/**
 * "Who reacted" sheet. Shows the list of users who added each emoji to
 * a message, grouped by emoji at the top with chip filters. Tapping a
 * chip filters the list to just that emoji.
 *
 * Loaded lazily via getMessageAddedReactions(chatId, messageId) the
 * first time the sheet is shown. While loading we show a small spinner
 * instead of an empty list, so the sheet doesn't look broken on slow
 * connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionViewersSheet(
    chatId: Long,
    messageId: Long,
    onDismiss: () -> Unit
) {
    var allReactions by remember(messageId) { mutableStateOf<List<TdApi.AddedReaction>?>(null) }
    var selectedEmoji by remember(messageId) { mutableStateOf<String?>(null) }

    LaunchedEffect(messageId) {
        allReactions = runCatching {
            TdClient.getMessageAddedReactions(chatId, messageId).reactions.toList()
        }.getOrDefault(emptyList())
    }

    val emojiCounts = remember(allReactions) {
        allReactions.orEmpty()
            .mapNotNull { (it.type as? TdApi.ReactionTypeEmoji)?.emoji }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
    }

    val filtered = remember(allReactions, selectedEmoji) {
        val list = allReactions.orEmpty()
        if (selectedEmoji == null) list
        else list.filter { (it.type as? TdApi.ReactionTypeEmoji)?.emoji == selectedEmoji }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .navigationBarsPadding()
        ) {
            Text(
                stringResource(R.string.reactions_who_title),
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(12.dp))

            // Emoji filter chips. The "All" chip clears the filter and is
            // always shown first; it stays selected when no specific emoji
            // is pinned.
            if (emojiCounts.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChipRow(
                        label = "${(allReactions?.size ?: 0)}",
                        selected = selectedEmoji == null,
                        onClick = { selectedEmoji = null }
                    )
                    for ((emoji, count) in emojiCounts) {
                        FilterChipRow(
                            label = "$emoji $count",
                            selected = selectedEmoji == emoji,
                            onClick = { selectedEmoji = emoji }
                        )
                    }
                }
            }

            when {
                allReactions == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                filtered.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.home_no_unread),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                        items(filtered, key = { it.date }) { reaction ->
                            ReactionViewerRow(reaction = reaction)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FilterChipRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReactionViewerRow(reaction: TdApi.AddedReaction) {
    val senderId = reaction.senderId
    var displayName by remember { mutableStateOf<String?>(null) }
    var photoFile by remember { mutableStateOf<TdApi.File?>(null) }
    var bgKey by remember { mutableStateOf(0L) }
    LaunchedEffect(senderId) {
        when (senderId) {
            is TdApi.MessageSenderUser -> {
                val user = TdClient.getCachedUser(senderId.userId)
                    ?: runCatching { TdClient.getUser(senderId.userId) }.getOrNull()
                displayName = listOfNotNull(user?.firstName, user?.lastName)
                    .joinToString(" ").ifBlank { "User" }
                photoFile = user?.profilePhoto?.small
                bgKey = senderId.userId
            }
            is TdApi.MessageSenderChat -> {
                val chat = TdClient.getCachedChat(senderId.chatId)
                displayName = chat?.title ?: "Chat"
                photoFile = chat?.photo?.small
                bgKey = senderId.chatId
            }
            else -> {}
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            file = photoFile,
            fallbackText = displayName ?: "?",
            bgColor = com.secondream.cheipgram.ui.screens.avatarBackgroundFor(bgKey),
            size = 36.dp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            displayName ?: "…",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val emoji = (reaction.type as? TdApi.ReactionTypeEmoji)?.emoji
        if (emoji != null) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        }
    }
}
