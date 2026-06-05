package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the modal is opened from — sets the starter tiles and the context. */
enum class AiContext { HOME, CHAT, MESSAGE }

/**
 * Novagram AI — a near-full-screen conversational modal. It keeps the whole
 * exchange (multi-turn), so follow-ups stay in context. The relevant material
 * (unread digest / this chat's recent messages / the long-pressed message) is
 * folded into the system prompt once, then the tiles and the free-form box are
 * just user turns. Answers stream in; a typing indicator shows while waiting.
 * Opens with a scale+fade and animates back out on dismiss.
 */
@Composable
fun AiAssistantModal(
    mode: AiContext,
    contextLabel: String,
    chatId: Long = 0L,
    focusText: String? = null,
    focusSender: String? = null,
    onReplyDraft: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val convo = remember { mutableStateListOf<Pair<String, String>>() }
    var streaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf<String?>(null) }

    var visible by remember { mutableStateOf(false) }
    var everShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true; everShown = true }
    LaunchedEffect(visible) { if (!visible && everShown) { delay(180); onDismiss() } }
    val dismiss = { visible = false }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val langName = java.util.Locale.getDefault()
        .getDisplayLanguage(java.util.Locale.getDefault())
        .ifBlank { "English" }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    suspend fun buildSystem(): String {
        val base = "You are Novagram AI, embedded inside a Telegram client. Reply in " +
            langName + ", concise and direct, no filler preamble. Use Markdown sparingly."
        return when (mode) {
            AiContext.HOME -> {
                val digest = TdClient.recentUnreadDigest()
                val block = digest.joinToString("\n\n") { "## " + it.title + "\n" + it.lines.joinToString("\n") }
                base + "\n\nThe user's unread messages across chats:\n<unread>\n" +
                    (block.ifBlank { "(none)" }) + "\n</unread>"
            }
            AiContext.CHAT -> {
                val lines = TdClient.chatRecentLines(chatId, 40)
                base + "\n\nYou are inside the chat \"" + contextLabel + "\". Recent messages:\n<chat>\n" +
                    (lines.joinToString("\n").ifBlank { "(empty)" }) + "\n</chat>"
            }
            AiContext.MESSAGE -> {
                val lines = TdClient.chatRecentLines(chatId, 16)
                base + "\n\nThe user long-pressed this message" +
                    (focusSender?.let { " from " + it } ?: "") + ":\n\"" + (focusText ?: "") +
                    "\"\n\nSurrounding chat context:\n<chat>\n" +
                    (lines.joinToString("\n").ifBlank { "(empty)" }) + "\n</chat>"
            }
        }
    }

    fun send(userText: String) {
        if (streaming) return
        convo.add("user" to userText)
        error = null
        streaming = true
        scope.launch {
            runCatching {
                val key = AppSettings.appearance.first().anthropicApiKey
                if (key.isNullOrBlank()) {
                    error = "Manca la chiave API (Impostazioni)"
                    streaming = false
                    if (convo.isNotEmpty() && convo.last().first == "user") convo.removeAt(convo.lastIndex)
                    return@launch
                }
                if (systemPrompt == null) systemPrompt = buildSystem()
                var started = false
                AiClient.streamConversation(convo.toList(), systemPrompt) { delta ->
                    if (!started) {
                        convo.add("assistant" to delta)
                        started = true
                    } else {
                        val i = convo.lastIndex
                        convo[i] = "assistant" to (convo[i].second + delta)
                    }
                }
                if (!started) convo.add("assistant" to "(nessuna risposta)")
                streaming = false
            }.onFailure {
                error = it.message ?: "Errore"
                streaming = false
                if (convo.isNotEmpty() && convo.last().first == "user") convo.removeAt(convo.lastIndex)
            }
        }
    }

    // Auto-scroll to the newest as it streams.
    LaunchedEffect(convo.size, convo.lastOrNull()?.second?.length, streaming) {
        if (convo.isNotEmpty()) runCatching { listState.animateScrollToItem(convo.size) }
    }

    val tiles = when (mode) {
        AiContext.HOME -> listOf(
            AiTile("Riassumi le chat non lette", "Cosa ti sei perso", AiGlyph.Chats) {
                send("Riassumi le mie chat non lette, raggruppando per chat.")
            }
        )
        AiContext.CHAT -> listOf(
            AiTile("Riassumi i non letti", "Riepilogo di questa chat", AiGlyph.Chats) {
                send("Riassumi i messaggi recenti di questa chat.")
            },
            AiTile("Traduci", "Gli ultimi messaggi", AiGlyph.Translate) {
                send("Traduci nella mia lingua gli ultimi messaggi di questa chat, tenendo i nomi.")
            }
        )
        AiContext.MESSAGE -> listOf(
            AiTile("Rispondi", "Proponi una risposta", AiGlyph.Reply) {
                send("Proponi una risposta breve e naturale a questo messaggio.")
            },
            AiTile("Traduci", "Questo messaggio", AiGlyph.Translate) {
                send("Traduci questo messaggio nella mia lingua.")
            },
            AiTile("Spiega", "Cosa significa", AiGlyph.Info) {
                send("Spiega in breve cosa significa questo messaggio.")
            }
        )
    }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(spring(stiffness = Spring.StiffnessMediumLow), initialScale = 0.9f) +
                    fadeIn(tween(160)),
                exit = scaleOut(tween(170), targetScale = 0.92f) + fadeOut(tween(150))
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
                            .imePadding()
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
                                    ) { dismiss() },
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

                        Spacer(Modifier.height(16.dp))

                        // Body: starter tiles when empty, else the conversation.
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            if (convo.isEmpty() && error == null) {
                                Column {
                                    Text(
                                        if (mode == AiContext.MESSAGE)
                                            "Scegli cosa fare con il messaggio, o scrivi una richiesta."
                                        else
                                            "Tocca un'azione o scrivi una richiesta. Posso continuare a rispondere mantenendo il contesto.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    tiles.forEach { tile ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
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
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    itemsIndexed(convo) { index, msg ->
                                        val role = msg.first
                                        val body = msg.second
                                        val isUser = role == "user"
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                        ) {
                                            Surface(
                                                color = if (isUser) accent.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.widthIn(max = 280.dp)
                                            ) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Text(
                                                        body,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (!isUser && !streaming && index == convo.lastIndex && body.isNotBlank()) {
                                                        Spacer(Modifier.height(8.dp))
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Box(
                                                                Modifier
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .clickable(
                                                                        interactionSource = remember { MutableInteractionSource() },
                                                                        indication = null
                                                                    ) { clipboard.setText(AnnotatedString(body)) }
                                                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                                                            ) {
                                                                Text("Copia", fontSize = 12.sp, color = accent)
                                                            }
                                                            if (mode == AiContext.MESSAGE && onReplyDraft != null) {
                                                                Spacer(Modifier.size(14.dp))
                                                                Box(
                                                                    Modifier
                                                                        .clip(RoundedCornerShape(8.dp))
                                                                        .clickable(
                                                                            interactionSource = remember { MutableInteractionSource() },
                                                                            indication = null
                                                                        ) {
                                                                            onReplyDraft(body)
                                                                            dismiss()
                                                                        }
                                                                        .padding(vertical = 4.dp, horizontal = 4.dp)
                                                                ) {
                                                                    Text("Usa come risposta", fontSize = 12.sp, color = accent)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (streaming && (convo.isEmpty() || convo.last().first == "user")) {
                                        item {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.Start
                                            ) {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                    shape = RoundedCornerShape(16.dp)
                                                ) {
                                                    Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                                        AiTypingDots(accent)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            val err = error
                            if (err != null) {
                                Text(
                                    err,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.BottomStart)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Input
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
                                            if (q.isNotEmpty()) send(q)
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
    }
}

@Composable
private fun AiTypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$i"
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = a))
            )
        }
    }
}

private enum class AiGlyph { Chats, Translate, Reply, Info }

private fun AiGlyph.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    AiGlyph.Chats -> com.secondream.novagram.ui.icons.PhosphorIcons.Chats
    AiGlyph.Translate -> com.secondream.novagram.ui.icons.PhosphorIcons.Translate
    AiGlyph.Reply -> com.secondream.novagram.ui.icons.PhosphorIcons.Reply
    AiGlyph.Info -> com.secondream.novagram.ui.icons.PhosphorIcons.Info
}

private data class AiTile(
    val label: String,
    val sub: String,
    val glyph: AiGlyph,
    val onClick: () -> Unit
)
