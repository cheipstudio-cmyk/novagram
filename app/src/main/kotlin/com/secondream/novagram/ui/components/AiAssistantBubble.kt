package com.secondream.novagram.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.secondream.novagram.ai.AiClient
import com.secondream.novagram.ai.AiMemory
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the modal is opened from — sets the starter tiles and the context. */
enum class AiContext { HOME, CHAT, MESSAGE }

/**
 * Novagram AI — a full-screen, chat-shaped assistant that looks like a normal
 * Novagram conversation. It is multi-turn and keeps context; for HOME and CHAT
 * the exchange is persisted locally (AiMemory) so it survives reopen. The
 * answer is rendered cleanly (no raw markdown asterisks), and any t.me links or
 * @mentions come out amber and tappable — a message link also surfaces a "Vai
 * al messaggio" chip that jumps there via [onOpenTme]. Opens by sliding up and
 * can be flicked down to close.
 */
@Composable
fun AiAssistantModal(
    mode: AiContext,
    contextLabel: String,
    chatId: Long = 0L,
    focusText: String? = null,
    focusSender: String? = null,
    onReplyDraft: ((String) -> Unit)? = null,
    onOpenTme: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val persistKey = when (mode) {
        AiContext.HOME -> "home"
        AiContext.CHAT -> "chat:$chatId"
        AiContext.MESSAGE -> null
    }

    val convo = remember { mutableStateListOf<Pair<String, String>>() }
    var streaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf<String?>(null) }
    var tier by remember { mutableStateOf(com.secondream.novagram.ai.AiPrefs.getTier(ctx)) }
    var hasUnread by remember { mutableStateOf(true) }
    LaunchedEffect(mode) {
        if (mode == AiContext.HOME) {
            hasUnread = runCatching { TdClient.recentUnreadDigest().isNotEmpty() }.getOrDefault(true)
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val codeColor = MaterialTheme.colorScheme.surfaceVariant

    // Restore any saved conversation for this surface.
    LaunchedEffect(persistKey) {
        if (persistKey != null && convo.isEmpty()) {
            val saved = AiMemory.load(ctx, persistKey)
            if (saved.isNotEmpty()) {
                convo.clear()
                convo.addAll(saved)
            }
        }
    }

    suspend fun buildSystem(): String {
        val tag = AppSettings.appearance.first().languageTag
        val loc = if (tag.isBlank() || tag.equals("system", ignoreCase = true))
            java.util.Locale.getDefault()
        else
            java.util.Locale.forLanguageTag(tag)
        val langName = loc.getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }
        val base = "You are Novagram AI, embedded inside a Telegram client. ALWAYS reply in " + langName +
            ". Reply ONLY in " + langName + ", even if the user's message, the action buttons, or the " +
            "chat context are written in another language. Write in PLAIN TEXT: no Markdown, no asterisks, " +
            "no headings, no code fences. Be concise and direct, no filler preamble. When you refer to a " +
            "specific message, include its t.me link so it becomes tappable."
        return when (mode) {
            AiContext.HOME -> {
                val unread = TdClient.recentUnreadDigest()
                val digest = if (unread.isNotEmpty()) unread else TdClient.recentChatsDigest()
                val label = if (unread.isNotEmpty()) "unread messages" else "recent messages"
                val block = digest.joinToString("\n\n") { "## " + it.title + "\n" + it.lines.joinToString("\n") }
                base + "\n\nThe user's " + label + " across chats:\n<chats>\n" +
                    (block.ifBlank { "(none)" }) + "\n</chats>"
            }
            AiContext.CHAT -> {
                val lines = TdClient.chatRecentLines(chatId, 80)
                base + "\n\nYou are inside the chat \"" + contextLabel +
                    "\". Recent messages (oldest to newest):\n<chat>\n" +
                    (lines.joinToString("\n").ifBlank { "(empty)" }) + "\n</chat>"
            }
            AiContext.MESSAGE -> {
                val lines = TdClient.chatRecentLines(chatId, 20)
                base + "\n\nThe user long-pressed this message" +
                    (focusSender?.let { " from " + it } ?: "") + ":\n\"" + (focusText ?: "") +
                    "\"\n\nSurrounding chat context:\n<chat>\n" +
                    (lines.joinToString("\n").ifBlank { "(empty)" }) + "\n</chat>"
            }
        }
    }

    fun persist() {
        if (persistKey != null) {
            val snapshot = convo.toList()
            scope.launch { AiMemory.save(ctx, persistKey, snapshot) }
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
                AiClient.streamConversation(convo.toList(), systemPrompt, tier.model) { delta ->
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
                persist()
            }.onFailure {
                error = it.message ?: "Errore"
                streaming = false
                if (convo.isNotEmpty() && convo.last().first == "user") convo.removeAt(convo.lastIndex)
            }
        }
    }

    LaunchedEffect(convo.size, convo.lastOrNull()?.second?.length, streaming) {
        if (convo.isNotEmpty()) runCatching { listState.animateScrollToItem(convo.size) }
    }

    val tiles = when (mode) {
        AiContext.HOME -> if (hasUnread) listOf(
            AiTile("Riassumi le chat non lette", "Cosa ti sei perso", AiGlyph.Chats) {
                send("Riassumi le mie chat non lette, raggruppando per chat.")
            },
            AiTile("Cosa richiede risposta", "Le cose urgenti", AiGlyph.Reply) {
                send("Tra i messaggi non letti, dimmi cosa richiede una mia risposta o azione.")
            },
            AiTile("Cerca tra i messaggi", "Trova qualcosa", AiGlyph.Search) {
                send("Aiutami a cercare qualcosa tra i miei messaggi recenti.")
            }
        ) else listOf(
            AiTile("Riassumi una chat recente", "Aggiornami", AiGlyph.Chats) {
                send("Riassumi cosa è successo di recente nelle mie chat principali.")
            },
            AiTile("Cerca tra i messaggi", "Trova qualcosa", AiGlyph.Search) {
                send("Aiutami a cercare qualcosa tra i miei messaggi recenti.")
            }
        )
        AiContext.CHAT -> listOf(
            AiTile("Riassumi questa chat", "Riepilogo recente", AiGlyph.Chats) {
                send("Riassumi i messaggi recenti di questa chat.")
            },
            AiTile("Punti chiave", "I momenti importanti", AiGlyph.Info) {
                send("Elenca i punti chiave e le decisioni prese in questa chat di recente.")
            },
            AiTile("Traduci", "Gli ultimi messaggi", AiGlyph.Translate) {
                send("Traduci nella mia lingua gli ultimi messaggi di questa chat, tenendo i nomi.")
            },
            AiTile("Bozza risposta", "Proponi cosa scrivere", AiGlyph.Reply) {
                send("Proponi una risposta adatta all'ultimo messaggio di questa chat.")
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
            },
            AiTile("Riassumi sopra", "Il thread fin qui", AiGlyph.Chats) {
                send("Riassumi il thread di messaggi attorno a questo.")
            }
        )
    }

    Dialog(
        onDismissRequest = { onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val hPx = with(density) { maxHeight.toPx() }
            var visible by remember { mutableStateOf(false) }
            var everShown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true; everShown = true }
            LaunchedEffect(visible) { if (!visible && everShown) { delay(180); onDismiss() } }
            val scale by animateFloatAsState(if (visible) 1f else 0.92f, tween(180), label = "scale")
            val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "alpha")
            val offsetY = remember { Animatable(0f) }
            val close = { visible = false; Unit }
            val dragState = rememberDraggableState { delta ->
                scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            translationY = offsetY.value
                        },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 6.dp
                ) {
                    Column(Modifier.fillMaxSize()) {
                    // Drag handle + header (this region is the drag-to-dismiss zone).
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = dragState,
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    if (offsetY.value > hPx * 0.18f || velocity > 1800f) {
                                        offsetY.animateTo(hPx, tween(220))
                                        onDismiss()
                                    } else {
                                        offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                    }
                                }
                            )
                    ) {
                        Box(
                            Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(width = 38.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
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
                                    fontSize = 19.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    contextLabel,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (convo.isNotEmpty()) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            convo.clear()
                                            error = null
                                            systemPrompt = null
                                            if (persistKey != null) scope.launch { AiMemory.clear(ctx, persistKey) }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text("Azzera", fontSize = 13.sp, color = accent)
                                }
                            }
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { close() },
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
                    }

                    // Body: starter tiles when empty, else the conversation.
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (convo.isEmpty() && error == null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    if (mode == AiContext.MESSAGE)
                                        "Scegli cosa fare con il messaggio, o scrivi una richiesta."
                                    else
                                        "Posso aiutarti e continuare la conversazione tenendo il contesto. Scegli un'azione o scrivi qui sotto.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(14.dp))
                                tiles.forEach { tile ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(15.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.12f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) { tile.onClick() }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(13.dp)
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(38.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(accent.copy(alpha = 0.16f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    tile.glyph.icon(),
                                                    contentDescription = null,
                                                    tint = accent,
                                                    modifier = Modifier.size(21.dp)
                                                )
                                            }
                                            Spacer(Modifier.size(13.dp))
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
                                    Spacer(Modifier.height(9.dp))
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        tiles.forEach { tile ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(20.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.12f)),
                                                modifier = Modifier.clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) { tile.onClick() }
                                            ) {
                                                Text(
                                                    tile.label,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                itemsIndexed(convo) { index, msg ->
                                    val role = msg.first
                                    val body = msg.second
                                    val isUser = role == "user"
                                    val jumpLink = remember(body) {
                                        if (isUser) null
                                        else Regex("(?:https?://)?t\\.me/(?:c/[0-9]+/[0-9]+|[A-Za-z0-9_]+/[0-9]+)")
                                            .find(body)?.value
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().animateItem(),
                                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                    ) {
                                        Surface(
                                            color = if (isUser) accent.copy(alpha = 0.2f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(
                                                topStart = 18.dp,
                                                topEnd = 18.dp,
                                                bottomStart = if (isUser) 18.dp else 5.dp,
                                                bottomEnd = if (isUser) 5.dp else 18.dp
                                            ),
                                            modifier = Modifier
                                                .widthIn(max = 300.dp)
                                                .pointerInput(body) {
                                                    detectTapGestures(onLongPress = {
                                                        clipboard.setText(AnnotatedString(body))
                                                    })
                                                }
                                        ) {
                                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                                if (isUser) {
                                                    Text(
                                                        body,
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                } else {
                                                    Text(
                                                        buildAiText(body, accent, codeColor, onOpenTme),
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (jumpLink != null && onOpenTme != null) {
                                                        Spacer(Modifier.height(9.dp))
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(accent.copy(alpha = 0.16f))
                                                                .clickable(
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null
                                                                ) {
                                                                    val u = if (jumpLink.startsWith("http")) jumpLink else "https://$jumpLink"
                                                                    onOpenTme(u)
                                                                }
                                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                                        ) {
                                                            Icon(
                                                                com.secondream.novagram.ui.icons.PhosphorIcons.Reply,
                                                                contentDescription = null,
                                                                tint = accent,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(Modifier.size(6.dp))
                                                            Text("Vai al messaggio", fontSize = 12.sp, color = accent)
                                                        }
                                                    }
                                                    if (!streaming && index == convo.lastIndex && body.isNotBlank()) {
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
                                                                            close()
                                                                        }
                                                                        .padding(vertical = 4.dp, horizontal = 4.dp)
                                                                ) {
                                                                    Text("Usa come risposta", fontSize = 12.sp, color = accent)
                                                                }
                                                                Spacer(Modifier.size(14.dp))
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
                                            modifier = Modifier.fillMaxWidth().animateItem(),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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

                            val err = error
                            if (err != null) {
                                Text(
                                    err,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // Input bar — rides above the keyboard / nav bar.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                                if (input.isEmpty()) {
                                    Text(
                                        "Scrivi a Novagram AI...",
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
                        }
                        Spacer(Modifier.size(8.dp))
                        Box(
                            Modifier
                                .size(44.dp)
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
                                modifier = Modifier.size(20.dp)
                            )
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

/** Inline bold/code so the bubble never shows literal ** or backticks. */
private fun AnnotatedString.Builder.appendInline(seg: String, codeColor: Color) {
    val rx = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`")
    var last = 0
    for (m in rx.findAll(seg)) {
        if (m.range.first > last) append(seg.substring(last, m.range.first))
        val g = m.groupValues
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[2]) }
            m.groups[3] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor)) { append(g[3]) }
        }
        last = m.range.last + 1
    }
    if (last < seg.length) append(seg.substring(last))
}

/**
 * Render an assistant reply: strip stray markdown, then turn URLs, t.me links
 * and @mentions into amber tappable spans. t.me links / mentions route through
 * [onOpenTme] (internal jump / open chat); other URLs open normally.
 */
private fun buildAiText(
    text: String,
    accent: Color,
    codeColor: Color,
    onOpenTme: ((String) -> Unit)?
): AnnotatedString {
    val cleaned = text.lines().joinToString("\n") { raw ->
        var l = raw
        l = l.replace(Regex("^\\s*#{1,6}\\s+"), "")
        l = l.replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
        l
    }
    val linkRx = Regex("(https?://[^\\s]+)|(t\\.me/[^\\s]+)|(@[A-Za-z0-9_]{4,})")
    val styles = TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.Medium))
    return buildAnnotatedString {
        var last = 0
        for (m in linkRx.findAll(cleaned)) {
            if (m.range.first > last) appendInline(cleaned.substring(last, m.range.first), codeColor)
            val tok = m.value
            val isTme = tok.startsWith("t.me/") || tok.startsWith("@") || tok.contains("//t.me/")
            val url = when {
                tok.startsWith("@") -> "https://t.me/" + tok.drop(1)
                tok.startsWith("t.me/") -> "https://" + tok
                else -> tok
            }
            if (isTme && onOpenTme != null) {
                withLink(LinkAnnotation.Clickable("tme", styles, linkInteractionListener = { _ -> onOpenTme(url) })) {
                    append(tok)
                }
            } else {
                withLink(LinkAnnotation.Url(url, styles)) { append(tok) }
            }
            last = m.range.last + 1
        }
        if (last < cleaned.length) appendInline(cleaned.substring(last), codeColor)
    }
}

private enum class AiGlyph { Chats, Translate, Reply, Info, Search }

private fun AiGlyph.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    AiGlyph.Chats -> com.secondream.novagram.ui.icons.PhosphorIcons.Chats
    AiGlyph.Translate -> com.secondream.novagram.ui.icons.PhosphorIcons.Translate
    AiGlyph.Reply -> com.secondream.novagram.ui.icons.PhosphorIcons.Reply
    AiGlyph.Info -> com.secondream.novagram.ui.icons.PhosphorIcons.Info
    AiGlyph.Search -> com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass
}

private data class AiTile(
    val label: String,
    val sub: String,
    val glyph: AiGlyph,
    val onClick: () -> Unit
)
