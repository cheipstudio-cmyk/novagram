package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secondream.novagram.ai.AiClient
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the bubble is mounted — decides the tiles and the context it reads. */
enum class AiContext { HOME, CHAT }

/**
 * Novagram AI surface: a FAB that MORPHS into a working assistant bubble.
 *
 * Collapsed it's an amber circle; tapped, the SAME element grows (size, colour
 * and a percent corner so it never flashes a square) into the dark panel.
 * Inside: context tiles (HOME = recap unread across chats; CHAT = recap /
 * translate THIS chat) plus a free-form box. Tapping a tile or sending a prompt
 * streams Claude's answer straight into the panel (the orb shows while waiting,
 * then the text types in). The scrim or the chevron collapses it back.
 *
 * @param mode HOME (above the +, reads all unread) or CHAT (small, right, reads
 *        this chat — pass [chatId]).
 */
@Composable
fun AiAssistantBubble(
    contextLabel: String,
    mode: AiContext,
    chatId: Long = 0L,
    modifier: Modifier = Modifier,
    collapsedSize: Dp = 56.dp,
    endInset: Dp = 16.dp,
    bottomInset: Dp = 18.dp,
    navBarPadding: Boolean = false
) {
    var open by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var streaming by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var runningLabel by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val langName = java.util.Locale.getDefault()
        .getDisplayLanguage(java.util.Locale.getDefault())
        .ifBlank { "English" }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val surface = MaterialTheme.colorScheme.surface

    fun runAction(label: String, builder: suspend () -> Pair<String?, String>?) {
        showResult = true
        runningLabel = label
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

    fun summarizeHome() = runAction("Riepilogo non letti") {
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

    fun summarizeChat() = runAction("Riepilogo") {
        val lines = TdClient.chatRecentLines(chatId, 40)
        if (lines.isEmpty()) null else {
            val sys = "You summarise a chat conversation. Reply in " + langName +
                ", at most 5 short lines, no preamble like \"Here is the summary\"."
            sys to "<chat>\n" + lines.joinToString("\n") + "\n</chat>\n\nSummarise what was said."
        }
    }

    fun translateChat() = runAction("Traduzione") {
        val lines = TdClient.chatRecentLines(chatId, 25)
        if (lines.isEmpty()) null else {
            val sys = "You are a translator. Translate the recent messages into " + langName +
                ", keeping each \"Name:\" prefix. Return only the translation, no commentary."
            sys to lines.joinToString("\n")
        }
    }

    fun freeform(q: String) = runAction(q) {
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

    Box(modifier = modifier.fillMaxSize()) {

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

        val containerColor by animateColorAsState(
            targetValue = if (open) surface else accent,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "ai-bg"
        )
        val cornerPct by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (open) 8f else 50f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "ai-corner"
        )
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(percent = cornerPct.toInt()),
            shadowElevation = 10.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = endInset, bottom = bottomInset)
                .then(if (navBarPadding) Modifier.navigationBarsPadding() else Modifier)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
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
                Column(
                    modifier = Modifier
                        .width(330.dp)
                        .padding(16.dp)
                ) {
                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showResult) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { showResult = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft,
                                    contentDescription = "Indietro",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
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
                                if (showResult) runningLabel else "Novagram AI",
                                fontFamily = MaterialTheme.typography.titleMedium.fontFamily,
                                fontStyle = if (showResult) FontStyle.Normal else FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                contextLabel,
                                fontSize = 12.sp,
                                maxLines = 1,
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
                                ) { open = false },
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

                    if (!showResult) {
                        tiles.forEach { tile ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { tile.onClick() }
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
                    } else {
                        val err = error
                        if (err != null) {
                            Text(
                                err,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (streaming && resultText.isEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                com.secondream.novagram.ai.AiThinkingIndicator()
                            }
                        } else {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    resultText + if (streaming) " \u258b" else "",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (resultText.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { clipboard.setText(AnnotatedString(resultText)) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
                                            contentDescription = "Copia",
                                            tint = accent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copia", fontSize = 12.sp, color = accent)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // Free-form input (works in both contexts).
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp)
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (input.isEmpty()) {
                                    Text(
                                        "Chiedi o dai un comando...",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 13.sp
                                    ),
                                    cursorBrush = SolidColor(accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Box(
                                Modifier
                                    .size(32.dp)
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
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.size(collapsedSize), contentAlignment = Alignment.Center) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                        contentDescription = "Novagram AI",
                        tint = onAccent,
                        modifier = Modifier.size(collapsedSize * 0.46f)
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
    val label: String,
    val sub: String,
    val glyph: AiGlyph,
    val onClick: () -> Unit
)
