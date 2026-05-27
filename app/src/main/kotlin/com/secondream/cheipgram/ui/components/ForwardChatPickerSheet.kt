package com.secondream.cheipgram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.secondream.cheipgram.td.ChatSummary
import com.secondream.cheipgram.td.TdClient

/**
 * Bottom-sheet chat picker shown when the user taps "Forward" on a
 * message. Lists every chat in the user's chat list, sorted by recency
 * (same order as the home chat list), with a small search field on top.
 *
 * Tapping a chat fires onPick with the destination chatId and the sheet
 * closes. The caller is responsible for actually sending the forward
 * call via TdClient.forwardMessages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardChatPickerSheet(
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit
) {
    val allChats by TdClient.chats.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(allChats, query) {
        val q = query.trim()
        if (q.isBlank()) allChats
        else allChats.filter { it.title.contains(q, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text(
                stringResource(R.string.forward_picker_title),
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.forward_picker_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 0.dp, max = 480.dp)
            ) {
                items(filtered, key = { it.id }) { c ->
                    ForwardChatRow(summary = c, onClick = { onPick(c.id) })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ForwardChatRow(summary: ChatSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            file = summary.chat.photo?.small,
            fallbackText = summary.title,
            bgColor = com.secondream.cheipgram.ui.screens.avatarBackgroundFor(summary.id),
            size = 40.dp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            summary.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
