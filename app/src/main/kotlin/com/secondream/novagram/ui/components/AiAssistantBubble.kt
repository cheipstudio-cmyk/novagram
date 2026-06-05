package com.secondream.novagram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.secondream.novagram.ai.AiClient
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the modal is opened from — decides the tiles and the context it reads. */
enum class AiContext { HOME, CHAT }

/**
 * Novagram AI — a near-full-screen modal opened from the home FAB or the
 * in-chat title-bar button. Context tiles (HOME = recap unread across chats;
 * CHAT = recap / translate THIS chat) plus a free-form box; the answer streams
 * straight into the panel. No pulsing orb — a quiet line while it reads, then
 * the text types in.
 */
@Composable
fun AiAssistantModal(
    mode: AiContext,
    contextLabel: String,
    chatId: Long = 0L,
    onDismiss: () -> Unit
) {
    var streaming by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val langName = java.util.Locale.getDefault()
        .getDisplayLanguage(java.util.Locale.getDefault())
        .ifBlank { "English" }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    fun runAction(builder: suspend () -> Pair<String?, String>?) {
        started = true
        error = null
        resultText = ""
        streaming = true
        scope.launch {
            runCatching {
                val key = AppSettings.appearance.first().anthropicApiKey
                if (key.isNullOrBlank()) {
                    error = "Manca la chiave API (Impostazioni)"
                    streaming = false
                    return@launch
                }
                val prompt = builder()
                if (prompt == null) {
                    error = "Niente da elaborare qui"
                    streaming = false
                    return@launch
                }
                AiClient.stream(prompt.second, prompt.first) { delta -> resultText += delta }
                streaming = false
            }.onFailure {
                error = it.message ?: "Errore"
                streaming = false
            }
        }
    }

    fun summarizeHome() = runAction {
        val digest = TdClient.recentUnreadDigest()
        if (digest.isEmpty()) null else {
            val block = digest.joinToString("\n\n") { "## " + it.title + "\n" + it.lines.joinToString("\n") }
            val sys = "You are an assistant inside a messaging app recapping the user's unread " +
                "messages across several chats. Reply in " + langName + ". Group by chat with a " +
                "short bold chat name, then one or two concise bullets of what's new and anything " +
                "that needs a reply. No preamble, no closing remark."
            sys to "<unread>\n" + block + "\n</unread>\n\nRecap my unread messages."
        }
    }

    fun summarizeChat() = runAction {
        val lines = TdClient.chatRecentLines(chatId, 40)
        if (lines.isEmpty()) null else {
            val sys = "You summarise a chat conversation. Reply in " + langName +
                ", at most 6 short lines, no preamble like \"Here is the summary\"."
            sys to "<chat>\n" + lines.joinToString("\n") + "\n</chat>\n\nSummarise what was said."
        }
    }

    fun translateChat() = runAction {
        val lines = TdClient.chatRecentLines(chatId, 25)
        if (lines.isEmpty()) null else {
            val sys = "You are a translator. Translate the recent messages into " + langName +
                ", keeping each \"Name:\" prefix. Return only the translation, no commentary."
            sys to lines.joinToString("\n")
        }
    }

    fun freeform(q: String) = runAction {
        val contextText = if (mode == AiContext.CHAT)
            TdClient.chatRecentLines(chatId, 30).joinToString("\n")
        else
            TdClient.recentUnreadDigest().joinToString("\n\n") { "## " + it.title + "\n" + it.lines.joinToString("\n") }
        val sys = "You are Novagram AI, embedded in a messaging app. Reply in " + langName +
            ", concise and direct, no preamble. Use the context below only if relevant."
        sys to "<context>\n" + contextText + "\n</context>\n\n" + q
    }

    val tiles = if (mode == AiContext.HOME) listOf(
        AiTile("Riassumi le chat non lette", "Cosa ti sei perso", AiGlyph.Chats) { summarizeHome() }
    ) else listOf(
        AiTile("Riassumi i non letti", "Riepilogo di questa chat", AiGlyph.Chats) { summarizeChat() },
        AiTile("Traduci", "Gli ultimi messaggi", AiGlyph.Translate) { translateChat() }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                            contentDescription = null,
                            tint = onAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Novagram AI",
                            fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            contextLabel,
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.X,
                            contentDescription = "Chiudi",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Context tiles (quick actions)
                tiles.forEach { tile ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = !streaming
                            ) { tile.onClick() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(accent.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    tile.glyph.icon(),
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(23.dp)
                                )
                            }
                            Spacer(Modifier.size(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tile.label,
                                    fontSize = 15.sp,
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

                Spacer(Modifier.height(6.dp))

                // Result / conversation area (fills the rest)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp)
                ) {
                    val err = error
                    when {
                        err != null -> Text(
                            err,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        !started -> Text(
                            "Tocca un'azione qui sopra o scrivi una richiesta in basso.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        streaming && resultText.isEmpty() -> Text(
                            "Sto leggendo i messaggi...",
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                resultText + if (streaming) " \u258b" else "",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!streaming && resultText.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { clipboard.setText(AnnotatedString(resultText)) }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
                                        contentDescription = "Copia",
                                        tint = accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    Text("Copia", fontSize = 12.sp, color = accent)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Free-form input
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (input.isEmpty()) {
                                Text(
                                    "Chiedi o dai un comando...",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                ),
                                cursorBrush = SolidColor(accent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = input.isNotBlank() && !streaming
                                ) {
                                    val q = input.trim()
                                    input = ""
                                    if (q.isNotEmpty()) freeform(q)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.ArrowUp,
                                contentDescription = "Invia",
                                tint = onAccent,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
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
    val label: String,
    val sub: String,
    val glyph: AiGlyph,
    val onClick: () -> Unit
)
