package com.secondream.novagram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.novagram.R
import com.secondream.novagram.td.ChatSummary
import com.secondream.novagram.td.TdClient
import org.drinkless.tdlib.TdApi

/**
 * Bottom-sheet forward flow shown when the user taps "Forward" on a message.
 *
 * Two steps inside a single ModalBottomSheet so the user keeps the same
 * dismiss target the whole way through:
 *
 *  1. Chat picker — lists every chat in the user's chat list, sorted by
 *     recency, with a search field on top. Tapping a chat moves to step 2.
 *  2. Forward composer — shows the destination chat, a quoted preview of
 *     the message being forwarded (same visual language as ReplyPreview),
 *     and a free-form caption field. The Send button fires onForward with
 *     (destChatId, caption-or-null) and dismisses the sheet.
 *
 * The caller is responsible for actually doing the forward call: typically
 * `TdClient.forwardMessages(...)` followed by `TdClient.sendText(destChatId,
 * caption)` when the caption is non-blank. We don't perform either here so
 * forward semantics stay in the screen layer where retries/error UI live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardChatPickerSheet(
    sourceMessage: TdApi.Message,
    onDismiss: () -> Unit,
    onForward: (destChatId: Long, caption: String?) -> Unit
) {
    val allChats by TdClient.chats.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(allChats, query) {
        val q = query.trim()
        if (q.isBlank()) allChats
        else allChats.filter { it.title.contains(q, ignoreCase = true) }
    }
    // Two-step state: null while picking, set once a destination is chosen.
    // Going back to step 1 just nulls it out — the chat list is still live
    // underneath so no reload needed.
    var selectedChat by remember { mutableStateOf<ChatSummary?>(null) }

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
            val dest = selectedChat
            if (dest == null) {
                // ── Step 1: pick destination chat ──────────────────────
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
                        ForwardChatRow(summary = c, onClick = { selectedChat = c })
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                // ── Step 2: preview + optional caption + send ──────────
                ForwardComposer(
                    destination = dest,
                    sourceMessage = sourceMessage,
                    onBack = { selectedChat = null },
                    onSend = { caption ->
                        onForward(dest.id, caption?.takeIf { it.isNotBlank() })
                    }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Single-step chat picker for "share into a chat" flows that don't forward a
 * message (e.g. sharing a saved theme). Same look as the forward picker's
 * step 1 — search field + recency-sorted chat list reusing [ForwardChatRow] —
 * but tapping a chat fires [onPick] with its id straight away (no composer /
 * caption step). The caller does the actual send + navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareChatPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    onPick: (chatId: Long) -> Unit
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
                title,
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

/**
 * Step 2 of the forward flow: top bar with back + destination chat name,
 * a quoted preview of the source message, a free-form caption field, and a
 * send button. Matches the visual language of ReplyPreview in ChatScreen
 * (3dp accent stripe + sender label + 1-line preview) so the user
 * immediately recognises "this is the message being forwarded".
 */
@Composable
private fun ForwardComposer(
    destination: ChatSummary,
    sourceMessage: TdApi.Message,
    onBack: () -> Unit,
    onSend: (String?) -> Unit
) {
    val preview = remember(sourceMessage.id) { previewTextFor(sourceMessage) }
    val senderName = remember(sourceMessage.senderId, sourceMessage.id) {
        when (val s = sourceMessage.senderId) {
            is TdApi.MessageSenderUser -> {
                val u = TdClient.getCachedUser(s.userId)
                "${u?.firstName.orEmpty()} ${u?.lastName.orEmpty()}".trim().ifBlank { "Utente" }
            }
            is TdApi.MessageSenderChat ->
                TdClient.getCachedChat(s.chatId)?.title ?: "Chat"
            else -> ""
        }
    }
    var caption by remember { mutableStateOf("") }

    // Header: back arrow + destination chat avatar + name.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft,
                contentDescription = null
            )
        }
        Spacer(Modifier.width(4.dp))
        Avatar(
            file = destination.chat.photo?.small,
            fallbackText = destination.title,
            bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(destination.id),
            size = 36.dp
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.forward_compose_target_prefix),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                destination.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    // Quoted preview of the source message — same accent stripe as
    // ReplyPreview in ChatScreen so the affordance reads consistently.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.forward_compose_from_prefix, senderName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    // Optional caption. Sent as a separate text message right after the
    // forward — TDLib's ForwardMessages doesn't support attaching a caption
    // to an existing message, so the screen-layer handler dispatches two
    // calls in sequence when caption.isNotBlank().
    OutlinedTextField(
        value = caption,
        onValueChange = { caption = it },
        placeholder = { Text(stringResource(R.string.forward_compose_caption_hint)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 4
    )
    Spacer(Modifier.height(12.dp))

    // Send button. Always enabled — even an empty caption is valid (means
    // "forward without note"). The send label switches between "Inoltra" and
    // "Inoltra con didascalia" so the user knows which they're committing to.
    val hasCaption = caption.trim().isNotBlank()
    Button(
        onClick = { onSend(if (hasCaption) caption else null) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            if (hasCaption) stringResource(R.string.forward_compose_send_with_caption)
            else stringResource(R.string.forward_compose_send)
        )
    }
}

/**
 * Build a short, human-friendly preview string for a message — mirrors the
 * mapping used by ReplyPreview / NotificationHelper so users see the same
 * shorthand everywhere.
 */
private fun previewTextFor(msg: TdApi.Message): String = when (val c = msg.content) {
    is TdApi.MessageText -> c.text.text.take(120)
    is TdApi.MessagePhoto -> "📷 Foto" +
        (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    is TdApi.MessageVideo -> "🎬 Video" +
        (c.caption.text.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    is TdApi.MessageVoiceNote -> "🎤 Nota vocale"
    is TdApi.MessageDocument -> "📎 ${c.document.fileName}"
    is TdApi.MessageAnimation -> "GIF"
    is TdApi.MessageSticker -> c.sticker.emoji.ifBlank { "Sticker" } + " Sticker"
    is TdApi.MessageAudio -> "🎵 " + c.audio.title.ifBlank { c.audio.fileName.ifBlank { "Audio" } }
    is TdApi.MessageLocation -> "📍 Posizione"
    is TdApi.MessageContact -> "👤 Contatto"
    else -> "Messaggio"
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
            bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(summary.id),
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
