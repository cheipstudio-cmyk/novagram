package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.secondream.novagram.ai.AiClient
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the modal is opened from — sets the starter tiles and the context. */
enum class AiContext { HOME, CHAT, MESSAGE }

/**
 * In-session conversation memory, keyed per context ("home" / "chat:<id>").
 * Keeps the thread alive across modal opens within the app run. Not persisted
 * to disk yet, so it resets on process death.
 */
object AiChatMemory {
    private val store = HashMap<String, MutableList<Pair<String, String>>>()
    fun get(key: String): List<Pair<String, String>> = store[key]?.toList() ?: emptyList()
    fun set(key: String, convo: List<Pair<String, String>>) {
        store[key] = convo.toMutableList()
    }
    fun clear(key: String) { store.remove(key) }
}

private data class MdRun(val text: String, val bold: Boolean, val italic: Boolean)

private val LINK_RE = Regex("(https?://\\S+|t\\.me/\\S+|@[A-Za-z0-9_]{3,})")

private fun tokenizeMd(s: String, baseBold: Boolean): List<MdRun> {
    val out = ArrayList<MdRun>()
    var bold = baseBold
    var italic = false
    val cur = StringBuilder()
    fun push() {
        if (cur.isNotEmpty()) {
            out.add(MdRun(cur.toString(), bold, italic))
            cur.setLength(0)
        }
    }
    var i = 0
    while (i < s.length) {
        if (i + 1 < s.length && s[i] == '*' && s[i + 1] == '*') {
            push(); bold = !bold; i += 2; continue
        }
        when (s[i]) {
            '*' -> { push(); italic = !italic; i++ }
            '`' -> { i++ }
            else -> { cur.append(s[i]); i++ }
        }
    }
    push()
    return out
}

private fun AnnotatedString.Builder.appendStyled(text: String, bold: Boolean, italic: Boolean, color: Color?) {
    pushStyle(
        SpanStyle(
            fontWeight = if (bold) FontWeight.SemiBold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            color = color ?: Color.Unspecified
        )
    )
    append(text)
    pop()
}

private fun AnnotatedString.Builder.appendWithLinks(run: MdRun, accent: Color) {
    val s = run.text
    var last = 0
    for (m in LINK_RE.findAll(s)) {
        if (m.range.first > last) appendStyled(s.substring(last, m.range.first), run.bold, run.italic, null)
        appendStyled(m.value, run.bold, run.italic, accent)
        last = m.range.last + 1
    }
    if (last < s.length) appendStyled(s.substring(last), run.bold, run.italic, null)
}

/** Renders the model's markdown into styled text: bold/italic applied, markers
 *  stripped (no raw asterisks), links and @mentions tinted with [accent]. */
private fun buildAiAnnotated(raw: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val lines = raw.split("\n")
    lines.forEachIndexed { li, lineRaw ->
        var line = lineRaw
        var header = false
        when {
            line.startsWith("### ") -> { line = line.removePrefix("### "); header = true }
            line.startsWith("## ") -> { line = line.removePrefix("## "); header = true }
            line.startsWith("# ") -> { line = line.removePrefix("# "); header = true }
        }
        if (line.startsWith("- ") || line.startsWith("* ")) {
            append("\u2022  ")
            line = line.substring(2)
        }
        tokenizeMd(line, header).forEach { run -> appendWithLinks(run, accent) }
        if (li < lines.lastIndex) append("\n")
    }
}

/**
 * Novagram AI — a near-full-screen conversational sheet that rises from the
 * bottom. Multi-turn: the thread persists per context across opens. The input
 * docks above the keyboard; swipe the header down to dismiss. Answers are
 * rendered (bold/links), not raw markdown. Starter tiles depend on where it
 * was opened from (home recap / this chat / a long-pressed message).
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
    val memKey = when (mode) {
        AiContext.HOME -> "home"
        AiContext.CHAT -> "chat:$chatId"
        AiContext.MESSAGE -> null
    }
    val convo = remember {
        mutableStateListOf<Pair<String, String>>().also { list ->
            if (memKey != null) list.addAll(AiChatMemory.get(memKey))
        }
    }
    var streaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf<String?>(null) }

    var visible by remember { mutableStateOf(false) }
    var everShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true; everShown = true }
    LaunchedEffect(visible) { if (!visible && everShown) { delay(200); onDismiss() } }
    val dismiss = { visible = false }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val dragY = remember { Animatable(0f) }
    val langName = java.util.Locale.getDefault()
        .getDisplayLanguage(java.util.Locale.getDefault())
        .ifBlank { "English" }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    suspend fun buildSystem(): String {
        val base = "You are Novagram AI, embedded inside a Telegram client. Reply in " +
            langName + ", concise and direct, no filler preamble. You may use **bold** and " +
            "- bullets, but keep formatting light."
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

    // Persist + autoscroll as the thread changes.
    LaunchedEffect(convo.size, convo.lastOrNull()?.second?.length, streaming) {
        if (memKey != null) AiChatMemory.set(memKey, convo.toList())
        if (convo.isNotEmpty()) runCatching { listState.animateScrollToItem(convo.size) }
    }

    val tiles = when (mode) {
        AiContext.HOME -> listOf(
            AiTile("Riassumi le chat non lette", "Cosa ti sei perso", AiGlyph.Chats) {
                send("Riassumi le mie chat non lette, raggruppando per chat.")
            },
            AiTile("Cosa è urgente", "Le cose da non perdere", AiGlyph.Bell) {
                send("Cosa tra i messaggi non letti sembra urgente o richiede una risposta?")
            },
            AiTile("Chi devo richiamare", "Conversazioni in sospeso", AiGlyph.Phone) {
                send("Chi mi ha scritto e aspetta una risposta da me?")
            }
        )
        AiContext.CHAT -> listOf(
            AiTile("Riassumi", "Riepilogo di questa chat", AiGlyph.Chats) {
                send("Riassumi i messaggi recenti di questa chat.")
            },
            AiTile("Punti chiave", "Le cose importanti", AiGlyph.List) {
                send("Elenca i punti chiave emersi in questa chat.")
            },
            AiTile("Cosa rispondere", "Proponi una risposta", AiGlyph.Reply) {
                send("In base alla conversazione, cosa potrei rispondere ora? Proponi una bozza.")
            },
            AiTile("Traduci", "Gli ultimi messaggi", AiGlyph.Translate) {
                send("Traduci nella mia lingua gli ultimi messaggi di questa chat, tenendo i nomi.")
            }
        )
        AiContext.MESSAGE -> listOf(
            AiTile("Rispondi", "Proponi una risposta", AiGlyph.Reply) {
                send("Proponi una risposta breve e naturale a questo messaggio.")
            },
            AiTile("Tono formale", "Risposta più formale", AiGlyph.Pencil) {
                send("Proponi una risposta a questo messaggio con un tono formale e professionale.")
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
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                    initialOffsetY = { it }
                ) + fadeIn(tween(140)),
                exit = slideOutVertically(tween(200), targetOffsetY = { it }) + fadeOut(tween(150))
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.94f)
                        .offset { IntOffset(0, dragY.value.roundToInt()) },
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .imePadding()
                    ) {
                        // Drag handle + header (vertical drag-down dismisses).
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (dragY.value > 170f) dismiss()
                                            else scope.launch { dragY.animateTo(0f, spring()) }
                                        },
                                        onVerticalDrag = { _, dy ->
                                            scope.launch { dragY.snapTo((dragY.value + dy).coerceAtLeast(0f)) }
                                        }
                                    )
                                }
                                .padding(horizontal = 18.dp)
                        ) {
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .width(40.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                            )
                            Spacer(Modifier.height(14.dp))
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
                                if (convo.isNotEmpty()) {
                                    Box(
                                        Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                convo.clear()
                                                if (memKey != null) AiChatMemory.clear(memKey)
                                                systemPrompt = null
                                                error = null
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                                            contentDescription = "Nuova conversazione",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(Modifier.size(8.dp))
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
                            Spacer(Modifier.height(14.dp))
                        }

                        // Body
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 18.dp)
                        ) {
                            if (convo.isEmpty() && error == null) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    item {
                                        Text(
                                            if (mode == AiContext.MESSAGE)
                                                "Scegli cosa fare con il messaggio, o scrivi una richiesta."
                                            else
                                                "Tocca un'azione o scrivi. Continuo a rispondere mantenendo il contesto.",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(bottom = 4.dp)
                                        )
                                    }
                                    itemsIndexed(tiles) { _, tile ->
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
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    itemsIndexed(convo) { index, msg ->
                                        val isUser = msg.first == "user"
                                        val body = msg.second
                                        val isLastAssistant = !isUser && index == convo.lastIndex
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                        ) {
                                            Surface(
                                                color = if (isUser) accent.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                shape = RoundedCornerShape(18.dp),
                                                modifier = Modifier.widthIn(max = 300.dp)
                                            ) {
                                                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                                    if (isUser) {
                                                        Text(
                                                            body,
                                                            fontSize = 14.sp,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    } else {
                                                        val shown = if (isLastAssistant && streaming) body + " \u258b" else body
                                                        Text(
                                                            buildAiAnnotated(shown, accent),
                                                            fontSize = 14.sp,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                    if (isLastAssistant && !streaming && body.isNotBlank()) {
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
                                                    shape = RoundedCornerShape(18.dp)
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

                        // Input (docks above the keyboard via imePadding)
                        Box(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 14.dp)) {
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

private enum class AiGlyph { Chats, Translate, Reply, Info, Bell, Phone, List, Pencil }

private fun AiGlyph.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    AiGlyph.Chats -> com.secondream.novagram.ui.icons.PhosphorIcons.Chats
    AiGlyph.Translate -> com.secondream.novagram.ui.icons.PhosphorIcons.Translate
    AiGlyph.Reply -> com.secondream.novagram.ui.icons.PhosphorIcons.Reply
    AiGlyph.Info -> com.secondream.novagram.ui.icons.PhosphorIcons.Info
    AiGlyph.Bell -> com.secondream.novagram.ui.icons.PhosphorIcons.Bell
    AiGlyph.Phone -> com.secondream.novagram.ui.icons.PhosphorIcons.Phone
    AiGlyph.List -> com.secondream.novagram.ui.icons.PhosphorIcons.List
    AiGlyph.Pencil -> com.secondream.novagram.ui.icons.PhosphorIcons.PencilSimple
}

private data class AiTile(
    val label: String,
    val sub: String,
    val glyph: AiGlyph,
    val onClick: () -> Unit
)
