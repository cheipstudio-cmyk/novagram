package com.secondream.novagram.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import com.secondream.novagram.R
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.core.view.WindowCompat
import com.secondream.novagram.ai.AiClient
import com.secondream.novagram.ai.AiMemory
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.td.MsgRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
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
    focusMessageId: Long = 0L,
    onReplyDraft: ((String) -> Unit)? = null,
    onOpenTme: ((String) -> Unit)? = null,
    onJumpMessage: ((Long) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val persistKey = when (mode) {
        AiContext.HOME -> "home"
        AiContext.CHAT -> "chat:$chatId"
        // A single-message thread is its OWN session, keyed by that message, so
        // it never mixes with the chat-level AI opened from the top bar. Opening
        // the same message again resumes it; the top bar stays a separate thread.
        AiContext.MESSAGE -> if (focusMessageId != 0L) "msg:$chatId:$focusMessageId" else null
    }

    val convo = remember { mutableStateListOf<Pair<String, String>>() }
    var streaming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf<String?>(null) }
    var tier by remember { mutableStateOf(com.secondream.novagram.ai.AiPrefs.getTier(ctx)) }
    var hasUnread by remember { mutableStateOf(true) }
    // Referenceable messages for the current chat (CHAT mode only) — lets the
    // AI cite messages as [n] and lets us render a tappable card that jumps.
    val refs = remember { mutableStateListOf<MsgRef>() }
    LaunchedEffect(mode) {
        if (mode == AiContext.HOME) {
            hasUnread = runCatching { TdClient.recentUnreadDigest().isNotEmpty() }.getOrDefault(true)
        }
    }
    LaunchedEffect(mode, chatId) {
        if (mode != AiContext.HOME && chatId != 0L) {
            val loaded = runCatching { TdClient.chatRecentRefs(chatId, 60) }.getOrDefault(emptyList())
            refs.clear(); refs.addAll(loaded)
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
        val myName = runCatching { TdClient.getMe().firstName.trim() }.getOrNull().orEmpty()
        val whoAmI = if (myName.isNotBlank())
            " The person using Novagram, the one you are helping, is named " + myName + "."
        else ""
        val base = "You are Novagram AI, the built-in assistant of Novagram — a fast, independent Android " +
            "client for Telegram, built in Kotlin and Jetpack Compose (it is NOT the official Telegram app). " +
            "You help the user both with their chats and with using Novagram itself. Features Novagram has: " +
            "custom themes with imported chat backgrounds, message reactions and mentions, a per-chat media " +
            "gallery (photos, videos, files, links, voice, music), voice notes with adjustable playback speed, " +
            "message forwarding and replies, protected-content handling, bot slash-commands and inline " +
            "keyboards, and this AI assistant. It ships via Google Play and GitHub Releases. IMPORTANT: " +
            "Novagram does NOT have chat folders — never tell the user to create or use folders. Never " +
            "recommend a feature unless you are sure Novagram has it; if unsure whether a feature exists, say " +
            "you're not certain rather than inventing menus or steps. Never imply the user is on the official " +
            "Telegram app. ALWAYS reply in " + langName + ". Reply ONLY in " + langName +
            ", even if the user's message, the action buttons, or the chat context are written in another " +
            "language. Write in PLAIN TEXT: no Markdown, no asterisks, no headings, no code fences. Be concise " +
            "and practical, lead with the answer, no filler preamble. When it would help the user continue " +
            "without typing, you MAY end your reply with exactly one final line starting with 'SUGGEST::' " +
            "followed by two or three very short tap-reply options separated by ' | ' (for example: " +
            "SUGGEST:: Sì, procedi | No | Spiega meglio). Put nothing after that line, and omit it entirely " +
            "when no natural follow-up exists." +
            " When you propose a concrete message the user could send as-is in this chat (for example " +
            "because they asked you to draft, write, or translate a reply), put that exact message — and " +
            "nothing else — on one final line starting with 'REPLY::', with no quotation marks around it, " +
            "for example: REPLY:: Ciao, arrivo verso le 20. Include this line only when there is a single " +
            "ready-to-send message; never invent one otherwise. If you add both REPLY:: and SUGGEST::, " +
            "put REPLY:: first." + whoAmI +
            " In any chat context provided below, lines whose sender is \"You\" are the user's OWN " +
            "messages; every other sender is a different person. Use this to be personal and specific: " +
            "refer to what the user themselves said when it helps, and give concrete, actionable help — " +
            "for example propose an exact reply they could send, or point out what they still need to " +
            "answer or decide. Avoid generic advice that ignores their actual situation."
        val citeByNumber = " Each message in the context is numbered like [1], [2]. When a specific message " +
            "is central to your answer, you may cite it by writing its number in square brackets, for example " +
            "[3]. Cite AT MOST one or two truly key messages in the whole reply, never more, only real numbers " +
            "from the list, and only when it genuinely helps. Most replies need no citation at all."
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
                val numbered = if (refs.isNotEmpty())
                    refs.mapIndexed { i, r -> "[" + (i + 1) + "] " + r.sender + ": " + r.text }.joinToString("\n")
                else
                    TdClient.chatRecentLines(chatId, 80).joinToString("\n")
                base + citeByNumber + "\n\nYou are inside the chat \"" + contextLabel +
                    "\". Recent messages (oldest to newest):\n<chat>\n" +
                    (numbered.ifBlank { "(empty)" }) + "\n</chat>"
            }
            AiContext.MESSAGE -> {
                val numbered = if (refs.isNotEmpty())
                    refs.mapIndexed { i, r -> "[" + (i + 1) + "] " + r.sender + ": " + r.text }.joinToString("\n")
                else
                    TdClient.chatRecentLines(chatId, 20).joinToString("\n")
                base + citeByNumber + "\n\nThe user long-pressed this message" +
                    (focusSender?.let { " from " + it } ?: "") + ":\n\"" + (focusText ?: "") +
                    "\"\n\nSurrounding chat context:\n<chat>\n" +
                    (numbered.ifBlank { "(empty)" }) + "\n</chat>"
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
                    error = ctx.getString(R.string.ai_error_no_key)
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
                if (!started) convo.add("assistant" to ctx.getString(R.string.ai_no_response))
                streaming = false
                persist()
            }.onFailure {
                error = it.message ?: ctx.getString(R.string.ai_error_generic)
                streaming = false
                if (convo.isNotEmpty() && convo.last().first == "user") convo.removeAt(convo.lastIndex)
            }
        }
    }

    LaunchedEffect(convo.size, convo.lastOrNull()?.second?.length, streaming) {
        if (convo.isNotEmpty()) runCatching { listState.scrollToItem(convo.size, 100000) }
    }

    val tiles = when (mode) {
        AiContext.HOME -> if (hasUnread) listOf(
            AiTile(ctx.getString(R.string.ai_tile_unread_summary_label), ctx.getString(R.string.ai_tile_unread_summary_sub), AiGlyph.Chats) {
                send(ctx.getString(R.string.ai_tile_unread_summary_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_needs_reply_label), ctx.getString(R.string.ai_tile_needs_reply_sub), AiGlyph.Reply) {
                send(ctx.getString(R.string.ai_tile_needs_reply_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_search_label), ctx.getString(R.string.ai_tile_search_sub), AiGlyph.Search) {
                send(ctx.getString(R.string.ai_tile_search_cmd))
            }
        ) else listOf(
            AiTile(ctx.getString(R.string.ai_tile_recent_summary_label), ctx.getString(R.string.ai_tile_recent_summary_sub), AiGlyph.Chats) {
                send(ctx.getString(R.string.ai_tile_recent_summary_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_search_label), ctx.getString(R.string.ai_tile_search_sub), AiGlyph.Search) {
                send(ctx.getString(R.string.ai_tile_search_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_tips_label), ctx.getString(R.string.ai_tile_tips_sub), AiGlyph.Info) {
                send(ctx.getString(R.string.ai_tile_tips_cmd))
            }
        )
        AiContext.CHAT -> listOf(
            AiTile(ctx.getString(R.string.ai_tile_chat_summary_label), ctx.getString(R.string.ai_tile_chat_summary_sub), AiGlyph.Chats) {
                send(ctx.getString(R.string.ai_tile_chat_summary_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_keypoints_label), ctx.getString(R.string.ai_tile_keypoints_sub), AiGlyph.Info) {
                send(ctx.getString(R.string.ai_tile_keypoints_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_translate_label), ctx.getString(R.string.ai_tile_translate_chat_sub), AiGlyph.Translate) {
                send(ctx.getString(R.string.ai_tile_translate_chat_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_draft_label), ctx.getString(R.string.ai_tile_draft_sub), AiGlyph.Reply) {
                send(ctx.getString(R.string.ai_tile_draft_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_todo_label), ctx.getString(R.string.ai_tile_todo_sub), AiGlyph.Info) {
                send(ctx.getString(R.string.ai_tile_todo_cmd))
            }
        )
        AiContext.MESSAGE -> listOf(
            AiTile(ctx.getString(R.string.ai_tile_reply_label), ctx.getString(R.string.ai_tile_reply_sub), AiGlyph.Reply) {
                send(ctx.getString(R.string.ai_tile_reply_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_translate_label), ctx.getString(R.string.ai_tile_translate_msg_sub), AiGlyph.Translate) {
                send(ctx.getString(R.string.ai_tile_translate_msg_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_explain_label), ctx.getString(R.string.ai_tile_explain_sub), AiGlyph.Info) {
                send(ctx.getString(R.string.ai_tile_explain_cmd))
            },
            AiTile(ctx.getString(R.string.ai_tile_thread_label), ctx.getString(R.string.ai_tile_thread_sub), AiGlyph.Chats) {
                send(ctx.getString(R.string.ai_tile_thread_cmd))
            }
        )
    }

    // In-app overlay, NOT a Dialog. A Dialog renders in its own window where
    // imePadding is unreliable on some OEMs (the keyboard covered the input).
    // In the main window (edge-to-edge + adjustResize) the card lifts above the
    // keyboard via imePadding. Every call site is a top-level sibling in the
    // screen's nav container, so this Box fills the screen and overlays on top.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val hPx = with(density) { maxHeight.toPx() }
            var visible by remember { mutableStateOf(false) }
            var everShown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true; everShown = true }
            LaunchedEffect(visible) { if (!visible && everShown) { delay(180); onDismiss() } }
            val scale by animateFloatAsState(if (visible) 1f else 0.86f, tween(190), label = "scale")
            val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(180), label = "alpha")
            val offsetY = remember { Animatable(0f) }
            val close = { visible = false; Unit }
            val dragState = rememberDraggableState { delta ->
                scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
            }

            // Back button / gesture closes the overlay (the Dialog used to do this).
            BackHandler(enabled = true) { close() }

            // Dimmed scrim behind the card; tapping outside the card dismisses.
            // Fades together with the card via the shared alpha.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * alpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { close() }
            )

            // When the keyboard is up, slide the card down so its input sits just
            // above the keyboard (centered alignment left a big gap below it).
            // Animate the bias so it rides down with the IME instead of snapping.
            val imeUp = WindowInsets.ime.getBottom(density) > 0
            val cardBias by animateFloatAsState(
                if (imeUp) 1f else 0f, tween(220), label = "cardBias"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp),
                contentAlignment = BiasAlignment(0f, cardBias)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.66f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            translationY = offsetY.value
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                    shape = RoundedCornerShape(28.dp),
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
                                Surface(
                                    color = accent.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        convo.clear()
                                        error = null
                                        systemPrompt = null
                                        if (persistKey != null) scope.launch { AiMemory.clear(ctx, persistKey) }
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        Icon(
                                            com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(ctx.getString(R.string.ai_reset), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = accent)
                                    }
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
                                    ) { close() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.X,
                                    contentDescription = ctx.getString(R.string.ai_close_cd),
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
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.Top
                            ) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    if (mode == AiContext.MESSAGE) ctx.getString(R.string.ai_empty_title_message)
                                    else ctx.getString(R.string.ai_empty_title_default),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Serif,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (mode == AiContext.MESSAGE)
                                        ctx.getString(R.string.ai_empty_sub_message)
                                    else
                                        ctx.getString(R.string.ai_empty_sub_default),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(18.dp))
                                tiles.forEach { tile ->
                                    Surface(
                                        color = accent.copy(alpha = 0.07f),
                                        shape = RoundedCornerShape(18.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
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
                                                    .background(accent),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    tile.glyph.icon(),
                                                    contentDescription = null,
                                                    tint = onAccent,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                            Spacer(Modifier.size(14.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    tile.label,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(Modifier.height(2.dp))
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
                            val lastAssistant = convo.lastOrNull()?.takeIf { it.first == "assistant" }?.second
                            val suggestions = remember(lastAssistant, streaming) {
                                if (streaming || lastAssistant.isNullOrBlank()) emptyList()
                                else Regex("(?im)^\\s*SUGGEST::\\s*(.+)$").find(lastAssistant)
                                    ?.groupValues?.get(1)
                                    ?.split("|")
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotBlank() }
                                    ?.take(3)
                                    ?: emptyList()
                            }
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
                                                color = accent.copy(alpha = 0.10f),
                                                shape = RoundedCornerShape(18.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.26f)),
                                                modifier = Modifier.clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) { tile.onClick() }
                                            ) {
                                                Text(
                                                    tile.label,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = accent,
                                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                itemsIndexed(convo, key = { i, _ -> "m$i" }) { index, msg ->
                                    val role = msg.first
                                    val body = msg.second
                                    val isUser = role == "user"
                                    val jumpLink = remember(body) {
                                        if (isUser) null
                                        else Regex("(?:https?://)?t\\.me/(?:c/[0-9]+/[0-9]+|[A-Za-z0-9_]+/[0-9]+)")
                                            .find(body)?.value
                                    }
                                    val isLastMsg = index == convo.lastIndex
                                    val bubbleEnter = remember(index) {
                                        Animatable(if (isLastMsg) 0f else 1f)
                                    }
                                    LaunchedEffect(index, isLastMsg) {
                                        if (isLastMsg && bubbleEnter.value < 1f) {
                                            bubbleEnter.animateTo(1f, tween(300))
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                this.alpha = bubbleEnter.value
                                                translationY = (1f - bubbleEnter.value) * 16f
                                            },
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
                                            modifier = Modifier.widthIn(max = 300.dp)
                                        ) {
                                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                                if (isUser) {
                                                    SelectionContainer {
                                                        Text(
                                                            body,
                                                            fontSize = 14.sp,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                } else {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.padding(bottom = 4.dp)
                                                    ) {
                                                        Icon(
                                                            com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                                                            contentDescription = null,
                                                            tint = accent,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Spacer(Modifier.size(5.dp))
                                                        Text(
                                                            "Novagram AI",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = accent
                                                        )
                                                    }
                                                    val isStreamingLast = streaming && index == convo.lastIndex
                                                    SelectionContainer {
                                                        if (isStreamingLast) {
                                                            TypewriterText(body, accent)
                                                        } else {
                                                            val visibleBody = remember(body) { cleanAiText(body) }
                                                            val rendered = remember(visibleBody) {
                                                                buildAiText(visibleBody, accent, codeColor, onOpenTme)
                                                            }
                                                            Text(
                                                                rendered,
                                                                fontSize = 14.sp,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }
                                                    }
                                                    val citedRefs = remember(body, refs.size) {
                                                        Regex("\\[(\\d+)\\]").findAll(body)
                                                            .mapNotNull { it.groupValues[1].toIntOrNull() }
                                                            .distinct()
                                                            .mapNotNull { n -> refs.getOrNull(n - 1) }
                                                            .take(2)
                                                            .toList()
                                                    }
                                                    if (citedRefs.isNotEmpty() && onJumpMessage != null) {
                                                        Spacer(Modifier.height(9.dp))
                                                        citedRefs.forEachIndexed { ci, r ->
                                                            var cardShown by remember(r.id) { mutableStateOf(false) }
                                                            LaunchedEffect(r.id) {
                                                                delay(140L + ci * 90L)
                                                                cardShown = true
                                                            }
                                                            AnimatedVisibility(
                                                                visible = cardShown,
                                                                enter = fadeIn(tween(240)) +
                                                                    slideInVertically(tween(240)) { it / 3 }
                                                            ) {
                                                              Column {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clip(RoundedCornerShape(12.dp))
                                                                    .background(accent.copy(alpha = 0.08f))
                                                                    .clickable(
                                                                        interactionSource = remember { MutableInteractionSource() },
                                                                        indication = null
                                                                    ) { onJumpMessage?.invoke(r.id); close() }
                                                                    .padding(8.dp)
                                                            ) {
                                                                Avatar(
                                                                    file = r.photo,
                                                                    fallbackText = r.sender,
                                                                    bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(r.colorSeed),
                                                                    size = 30.dp
                                                                )
                                                                Spacer(Modifier.size(9.dp))
                                                                Column(Modifier.weight(1f)) {
                                                                    Text(
                                                                        r.sender,
                                                                        fontSize = 12.sp,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        color = accent,
                                                                        maxLines = 1
                                                                    )
                                                                    Text(
                                                                        r.text,
                                                                        fontSize = 12.sp,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 2,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                                Spacer(Modifier.size(6.dp))
                                                                Icon(
                                                                    com.secondream.novagram.ui.icons.PhosphorIcons.Reply,
                                                                    contentDescription = null,
                                                                    tint = accent,
                                                                    modifier = Modifier.size(15.dp)
                                                                )
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                              }
                                                            }
                                                        }
                                                    }
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
                                                            Text(ctx.getString(R.string.ai_jump_to_message), fontSize = 12.sp, color = accent)
                                                        }
                                                    }
                                                    val proposedReply = remember(body) { extractReply(body) }
                                                    if (!isStreamingLast && body.isNotBlank()) {
                                                        if (proposedReply != null && onReplyDraft != null) {
                                                            Spacer(Modifier.height(9.dp))
                                                            var replyShown by remember(body) { mutableStateOf(false) }
                                                            LaunchedEffect(body) { delay(120L); replyShown = true }
                                                            AnimatedVisibility(
                                                                visible = replyShown,
                                                                enter = fadeIn(tween(240)) +
                                                                    slideInVertically(tween(240)) { it / 3 }
                                                            ) {
                                                                Column(
                                                                    Modifier
                                                                        .fillMaxWidth()
                                                                        .clip(RoundedCornerShape(14.dp))
                                                                        .background(accent.copy(alpha = 0.13f))
                                                                        .clickable(
                                                                            interactionSource = remember { MutableInteractionSource() },
                                                                            indication = null
                                                                        ) {
                                                                            onReplyDraft?.invoke(proposedReply)
                                                                            close()
                                                                        }
                                                                        .padding(12.dp)
                                                                ) {
                                                                    Text(
                                                                        ctx.getString(R.string.ai_proposed_reply),
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        color = accent
                                                                    )
                                                                    Spacer(Modifier.height(5.dp))
                                                                    Text(
                                                                        proposedReply,
                                                                        fontSize = 14.sp,
                                                                        color = MaterialTheme.colorScheme.onSurface
                                                                    )
                                                                    Spacer(Modifier.height(9.dp))
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Icon(
                                                                            com.secondream.novagram.ui.icons.PhosphorIcons.Reply,
                                                                            contentDescription = null,
                                                                            tint = accent,
                                                                            modifier = Modifier.size(15.dp)
                                                                        )
                                                                        Spacer(Modifier.size(6.dp))
                                                                        Text(
                                                                            ctx.getString(R.string.ai_use_as_reply),
                                                                            fontSize = 12.sp,
                                                                            fontWeight = FontWeight.Medium,
                                                                            color = accent
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        Spacer(Modifier.height(8.dp))
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            PillButton(
                                                                ctx.getString(R.string.ai_copy),
                                                                com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
                                                                accent
                                                            ) {
                                                                clipboard.setText(
                                                                    AnnotatedString(proposedReply ?: cleanAiText(body))
                                                                )
                                                            }
                                                            if (onReplyDraft != null && proposedReply == null) {
                                                                PillButton(
                                                                    ctx.getString(R.string.ai_use_as_reply),
                                                                    com.secondream.novagram.ui.icons.PhosphorIcons.Reply,
                                                                    accent
                                                                ) {
                                                                    onReplyDraft?.invoke(cleanAiText(body))
                                                                    close()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (suggestions.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            suggestions.forEach { s ->
                                                PillButton(s, null, accent, filled = true) { send(s) }
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
                                        ctx.getString(R.string.ai_input_placeholder),
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
                                contentDescription = ctx.getString(R.string.ai_send_cd),
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

/** Strips internal AI markers (SUGGEST line, [n] citation tags) so the text is
 *  clean for the user — for display, copy, and "use as reply". */
private fun cleanAiText(s: String): String =
    s.replace(Regex("(?im)^\\s*SUGGEST::.*$"), "")
        .replace(Regex("(?im)^\\s*REPLY::.*$"), "")
        .replace(Regex("\\s?\\[\\d+\\]"), "")
        .trim()

/**
 * Pull out the single ready-to-send message the model optionally marked with a
 * final "REPLY::" line, so ctx.getString(R.string.ai_use_as_reply) inserts only that text (not the
 * whole explanation bubble). Strips surrounding quotes/«». Null if absent.
 */
private fun extractReply(s: String): String? {
    val raw = Regex("(?im)^\\s*REPLY::\\s*(.+)$").find(s)?.groupValues?.getOrNull(1)?.trim()
        ?: return null
    val unq = raw.removeSurrounding("\"")
        .let { if (it.startsWith("«") && it.endsWith("»")) it.removeSurrounding("«", "»") else it }
        .trim()
    return unq.ifBlank { null }
}

@Composable
private fun PillButton(
    label: String,
    icon: ImageVector?,
    accent: Color,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "pill")
    Surface(
        color = accent.copy(alpha = if (filled) 0.16f else 0.10f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(6.dp))
            }
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent)
        }
    }
}

@Composable
private fun TypewriterText(full: String, cursorColor: Color) {
    // Reveal text smoothly char-by-char regardless of how the network delivers
    // chunks, like ChatGPT. `shown` advances toward the current length and
    // accelerates when it falls behind, so it never feels stuck or blocky.
    var shown by remember { mutableStateOf(0) }
    LaunchedEffect(full) {
        if (shown > full.length) shown = full.length
        while (shown < full.length) {
            val remaining = full.length - shown
            shown += (remaining / 6).coerceIn(1, 12)
            delay(16)
        }
    }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) { delay(450); blink = !blink }
    }
    val revealed = full.take(shown.coerceAtMost(full.length))
    val visible = revealed.substringBefore("SUGGEST::").substringBefore("REPLY::")
        .replace(Regex("\\s?\\[\\d+\\]"), "")
        .let { if (it.length < revealed.length) it.trimEnd() else it }
    Text(
        visible + if (blink) "▌" else "",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
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
        l = l.replace(Regex("\\s?\\[\\d+\\]"), "")
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
