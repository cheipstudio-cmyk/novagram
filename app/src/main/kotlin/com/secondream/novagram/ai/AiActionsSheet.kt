@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * AI actions sheet shown when the user picks the "AI" tile from the
 * message actions grid. Two phases:
 *
 *   PICKING — user sees a list of preset actions (Riassumi / Rispondi
 *   formale / Rispondi casual / Traduci / Spiega) plus a custom prompt
 *   field. Tapping any of them transitions to LOADING.
 *
 *   LOADING/RESULT — a spinner runs while AiClient.complete() resolves;
 *   on success we show the response in a scrollable card with three
 *   actions: Copia (clipboard), Usa come risposta (populates the input
 *   bar via [onUseAsReply]), Invia subito ([onSendDirect]). On error
 *   we render the error text and a "Riprova" button.
 *
 * messageText is the body the user picked the AI on (post-formatting,
 * just the human-readable text); for media-only messages it'll be the
 * caption or empty. context (optional) carries adjacent messages for
 * thread-aware actions like "Riassumi" — currently just plain text
 * lines joined with newlines.
 */
@Composable
fun AiActionsSheet(
    messageText: String,
    senderName: String?,
    context: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onUseAsReply: (String) -> Unit,
    onSendDirect: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var phase by remember { mutableStateOf<Phase>(Phase.Picking) }
    var customPrompt by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastAction by remember { mutableStateOf<AiPreset?>(null) }
    var lastCustom by remember { mutableStateOf<String?>(null) }

    fun fire(preset: AiPreset?, customText: String?) {
        lastAction = preset
        lastCustom = customText
        error = null
        result = null
        phase = Phase.Loading
        // Derive the user's display language (e.g. "italiano", "English",
        // "español") from the device locale so the AI replies in the same
        // language the UI is in.
        val langName = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())
            .ifBlank { "English" }
        scope.launch {
            runCatching {
                val (sys, user) = buildPrompt(preset, customText, messageText, senderName, context, langName)
                AiClient.complete(userPrompt = user, systemPrompt = sys)
            }.onSuccess {
                result = it
                phase = Phase.Result
            }.onFailure {
                error = it.message ?: ctx.getString(com.secondream.novagram.R.string.ai_error)
                phase = Phase.Result
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI",
                    style = MaterialTheme.typography.titleLarge,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))

            when (phase) {
                Phase.Picking -> PickingBody(
                    onPickPreset = { preset -> fire(preset, null) },
                    customPrompt = customPrompt,
                    onCustomPromptChange = { customPrompt = it },
                    onSubmitCustom = {
                        if (customPrompt.isNotBlank()) fire(null, customPrompt)
                    }
                )
                Phase.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AiThinkingIndicator()
                }
                Phase.Result -> ResultBody(
                    result = result,
                    error = error,
                    onRetry = { fire(lastAction, lastCustom) },
                    onCopy = {
                        result?.let {
                            clipboard.setText(AnnotatedString(it))
                            android.widget.Toast.makeText(
                                ctx, ctx.getString(com.secondream.novagram.R.string.ai_copied),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onUseAsReply = {
                        result?.let { onUseAsReply(it) }
                    },
                    onSendDirect = {
                        result?.let { onSendDirect(it) }
                    },
                    onBack = { phase = Phase.Picking }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private enum class Phase { Picking, Loading, Result }

@Composable
private fun PickingBody(
    onPickPreset: (AiPreset) -> Unit,
    customPrompt: String,
    onCustomPromptChange: (String) -> Unit,
    onSubmitCustom: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AiPreset.values().forEach { preset ->
            PresetRow(preset = preset, onClick = { onPickPreset(preset) })
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        stringResource(com.secondream.novagram.R.string.ai_custom_prompt_label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (customPrompt.isEmpty()) {
                Text(
                    stringResource(com.secondream.novagram.R.string.ai_custom_prompt_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = customPrompt,
                onValueChange = onCustomPromptChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        TextButton(
            onClick = onSubmitCustom,
            enabled = customPrompt.isNotBlank()
        ) {
            Text(stringResource(com.secondream.novagram.R.string.ai_custom_prompt_go))
        }
    }
}

@Composable
private fun PresetRow(preset: AiPreset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            preset.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(preset.labelRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(preset.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultBody(
    result: String?,
    error: String?,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onUseAsReply: () -> Unit,
    onSendDirect: () -> Unit,
    onBack: () -> Unit
) {
    if (error != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                .padding(14.dp)
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) { Text(stringResource(com.secondream.novagram.R.string.ai_retry)) }
                TextButton(onClick = onBack) { Text(stringResource(com.secondream.novagram.R.string.ai_change_action)) }
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .heightIn(min = 80.dp, max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(14.dp)
    ) {
        Text(
            result.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Spacer(Modifier.height(10.dp))
    // Action row: 3 tiles like the message-action grid for consistency.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiResultTile(
            icon = com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
            label = stringResource(com.secondream.novagram.R.string.ai_action_copy),
            onClick = onCopy,
            modifier = Modifier.weight(1f)
        )
        AiResultTile(
            icon = com.secondream.novagram.ui.icons.PhosphorIcons.ArrowDown,
            label = stringResource(com.secondream.novagram.R.string.ai_action_use_reply),
            onClick = onUseAsReply,
            modifier = Modifier.weight(1f)
        )
        AiResultTile(
            icon = com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight,
            label = stringResource(com.secondream.novagram.R.string.ai_action_send),
            onClick = onSendDirect,
            modifier = Modifier.weight(1f),
            primary = true
        )
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onBack) {
        Text(stringResource(com.secondream.novagram.R.string.ai_action_other))
    }
}

@Composable
private fun AiResultTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (primary) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) MaterialTheme.colorScheme.onPrimary
                   else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Preset action shown in the picking screen. */
enum class AiPreset(
    val labelRes: Int,
    val descriptionRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Summarise(
        com.secondream.novagram.R.string.ai_preset_summarise,
        com.secondream.novagram.R.string.ai_preset_summarise_desc,
        com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle
    ),
    ReplyFormal(
        com.secondream.novagram.R.string.ai_preset_reply_formal,
        com.secondream.novagram.R.string.ai_preset_reply_formal_desc,
        com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight
    ),
    ReplyCasual(
        com.secondream.novagram.R.string.ai_preset_reply_casual,
        com.secondream.novagram.R.string.ai_preset_reply_casual_desc,
        com.secondream.novagram.ui.icons.PhosphorIcons.PaperPlaneRight
    ),
    Translate(
        com.secondream.novagram.R.string.ai_preset_translate,
        com.secondream.novagram.R.string.ai_preset_translate_desc,
        com.secondream.novagram.ui.icons.PhosphorIcons.Translate
    ),
    Explain(
        com.secondream.novagram.R.string.ai_preset_explain,
        com.secondream.novagram.R.string.ai_preset_explain_desc,
        com.secondream.novagram.ui.icons.PhosphorIcons.Sparkle
    )
}

/**
 * Build the (system, user) prompt pair for the selected action. We keep
 * the system prompt short and the user prompt explicit so Claude has the
 * smallest possible cognitive surface — the result tends to be more
 * useful when the instruction is direct.
 */
private fun buildPrompt(
    preset: AiPreset?,
    customPrompt: String?,
    messageText: String,
    senderName: String?,
    context: List<String>,
    langName: String
): Pair<String?, String> {
    val sender = senderName?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
    val targetBlock = "<message>${sender}$messageText</message>"
    val contextBlock = if (context.isNotEmpty()) {
        "<context>\n" + context.joinToString("\n") + "\n</context>\n"
    } else ""

    return when {
        customPrompt != null -> {
            val system = "You are an AI assistant embedded in a messaging client. " +
                "Always reply in $langName, short and direct, with no preamble."
            val user = "$contextBlock$targetBlock\n\nUser instruction: $customPrompt"
            system to user
        }
        preset == AiPreset.Summarise -> {
            val system = "You are an AI assistant that summarises chat conversations. " +
                "Reply in $langName, in at most 4 lines, with no intro like \"Here is the summary\"."
            val user = "$contextBlock$targetBlock\n\nSummarise the messages above."
            system to user
        }
        preset == AiPreset.ReplyFormal -> {
            val system = "You are an AI assistant that drafts replies to chat messages. " +
                "Reply in $langName, formal, concise and polite. No preamble — only the reply itself."
            val user = "$contextBlock$targetBlock\n\nWrite a formal reply to this message."
            system to user
        }
        preset == AiPreset.ReplyCasual -> {
            val system = "You are an AI assistant that drafts replies to chat messages. " +
                "Reply in $langName, in a friendly and direct tone. No preamble — only the reply itself."
            val user = "$contextBlock$targetBlock\n\nWrite a casual reply to this message."
            system to user
        }
        preset == AiPreset.Translate -> {
            val system = "You are a translator. Translate the provided message into $langName. " +
                "Return only the translation, with no commentary."
            val user = targetBlock
            system to user
        }
        preset == AiPreset.Explain -> {
            val system = "You are an AI assistant. Explain in $langName what the provided " +
                "message means, in at most 3 lines, in simple terms."
            val user = "$contextBlock$targetBlock\n\nExplain this message."
            system to user
        }
        else -> null to messageText
    }
}

/**
 * Beautiful loading state for AI calls. Replaces the plain spinner —
 * the AI feature is the killer differentiator and the wait deserves
 * a richer visual.
 *
 * Renders an accent-tinted orb pulsing at two distinct frequencies
 * (scale + opacity) with a glow halo behind it, plus rotating
 * "Pensando…" / "Sto pensando…" / "Quasi pronto…" labels so the
 * user feels something is happening even when the LLM takes 3-5s
 * to respond. The phases are time-based rather than progress-based
 * because Anthropic's API doesn't stream incremental progress in
 * our complete() call — we just know SOMETHING is happening.
 */
@Composable
internal fun AiThinkingIndicator() {
    val accent = MaterialTheme.colorScheme.primary
    // On weak devices the user can disable heavy motion; the continuous
    // breathing orb (three infinite float animations + a glow) is exactly
    // the kind of thing that stutters there, so fall back to a plain spinner.
    val reduceAnim by com.secondream.novagram.settings.AppSettings.appearance
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs())
    if (reduceAnim.reduceAnimations) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            CircularProgressIndicator(color = accent, modifier = Modifier.size(36.dp))
            Text(
                stringResource(com.secondream.novagram.R.string.ai_thinking_1),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontStyle = FontStyle.Italic
            )
        }
        return
    }
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "ai-think")
    // Scale: slow breathing motion 0.85 → 1.15 over ~1.4s, ease-in-out.
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "ai-scale"
    )
    // Halo alpha: counter-phased so the glow brightens when the orb
    // shrinks. Gives a subtle "exhale" feel.
    val haloAlpha by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.55f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "ai-halo"
    )
    val haloScale by transition.animateFloat(
        initialValue = 1.6f,
        targetValue = 2.4f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 1400,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "ai-halo-scale"
    )

    // Caption that rotates through three phases over ~6 seconds so
    // long AI calls feel like progress instead of a frozen wait.
    var captionIdx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1800)
            captionIdx = (captionIdx + 1) % 3
        }
    }
    val captions = listOf(
        stringResource(com.secondream.novagram.R.string.ai_thinking_1),
        stringResource(com.secondream.novagram.R.string.ai_thinking_2),
        stringResource(com.secondream.novagram.R.string.ai_thinking_3)
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            // Glow halo. Drawn first so the orb paints over it. We use
            // a Box with scaled background that the orb overlays. No
            // Canvas because we want CSS-style ring softness — a
            // radial-fade brush gets us 90% of the way.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .androidx_graphicsLayerScale(haloScale, haloScale)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = haloAlpha),
                                accent.copy(alpha = 0f)
                            )
                        )
                    )
            )
            // The orb itself. Soft accent with a pulsing scale; tiny
            // inner highlight to give it a touch of dimension instead
            // of looking like a flat dot.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .androidx_graphicsLayerScale(scale, scale)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.95f),
                                accent.copy(alpha = 0.55f)
                            ),
                            radius = 80f
                        )
                    )
            )
        }
        Text(
            captions[captionIdx],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontStyle = FontStyle.Italic
        )
    }
}

/**
 * Tiny scale-only graphicsLayer helper to keep the call-site compact
 * (otherwise every pulsing Box would need a full `.graphicsLayer {
 * scaleX = ...; scaleY = ... }` block).
 */
private fun Modifier.androidx_graphicsLayerScale(sx: Float, sy: Float): Modifier =
    this.then(
        Modifier.graphicsLayer(scaleX = sx, scaleY = sy)
    )
