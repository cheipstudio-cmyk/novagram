@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ai

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Send
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
        scope.launch {
            runCatching {
                val (sys, user) = buildPrompt(preset, customText, messageText, senderName, context)
                AiClient.complete(userPrompt = user, systemPrompt = sys)
            }.onSuccess {
                result = it
                phase = Phase.Result
            }.onFailure {
                error = it.message ?: "Errore"
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
                    Icons.Outlined.AutoAwesome,
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
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                Phase.Result -> ResultBody(
                    result = result,
                    error = error,
                    onRetry = { fire(lastAction, lastCustom) },
                    onCopy = {
                        result?.let {
                            clipboard.setText(AnnotatedString(it))
                            android.widget.Toast.makeText(
                                ctx, "Copiato",
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
        "Prompt personalizzato",
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
                    "Es. \"riscrivi in tono più gentile\"",
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
            Text("Vai")
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
                preset.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                preset.description,
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
                TextButton(onClick = onRetry) { Text("Riprova") }
                TextButton(onClick = onBack) { Text("Cambia azione") }
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
            icon = Icons.Outlined.ContentCopy,
            label = "Copia",
            onClick = onCopy,
            modifier = Modifier.weight(1f)
        )
        AiResultTile(
            icon = Icons.Outlined.Translate,  // reuse — visually "edit/insert"
            label = "Usa come risposta",
            onClick = onUseAsReply,
            modifier = Modifier.weight(1f)
        )
        AiResultTile(
            icon = Icons.Outlined.Send,
            label = "Invia",
            onClick = onSendDirect,
            modifier = Modifier.weight(1f),
            primary = true
        )
    }
    Spacer(Modifier.height(6.dp))
    TextButton(onClick = onBack) {
        Text("← Altra azione")
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
    val label: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Summarise(
        "Riassumi il thread",
        "Sintesi rapida degli ultimi messaggi",
        Icons.Outlined.AutoAwesome
    ),
    ReplyFormal(
        "Rispondi formale",
        "Bozza di risposta in tono professionale",
        Icons.Outlined.Send
    ),
    ReplyCasual(
        "Rispondi casual",
        "Bozza di risposta in tono amichevole",
        Icons.Outlined.Send
    ),
    Translate(
        "Traduci in italiano",
        "Traduzione del messaggio selezionato",
        Icons.Outlined.Translate
    ),
    Explain(
        "Spiega",
        "Spiegazione semplice del contenuto",
        Icons.Outlined.AutoAwesome
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
    context: List<String>
): Pair<String?, String> {
    val sender = senderName?.takeIf { it.isNotBlank() }?.let { "$it: " } ?: ""
    val targetBlock = "<messaggio>${sender}$messageText</messaggio>"
    val contextBlock = if (context.isNotEmpty()) {
        "<contesto>\n" + context.joinToString("\n") + "\n</contesto>\n"
    } else ""

    return when {
        customPrompt != null -> {
            val system = "Sei un assistente AI integrato in un client di messaggistica. " +
                "Rispondi sempre in italiano, breve e diretto, senza preamboli."
            val user = "$contextBlock$targetBlock\n\nIstruzione dell'utente: $customPrompt"
            system to user
        }
        preset == AiPreset.Summarise -> {
            val system = "Sei un assistente AI che riassume conversazioni di chat. " +
                "Rispondi in italiano, in massimo 4 righe, senza intro tipo \"Ecco il riassunto\"."
            val user = "$contextBlock$targetBlock\n\nRiassumi i messaggi qui sopra."
            system to user
        }
        preset == AiPreset.ReplyFormal -> {
            val system = "Sei un assistente AI che redige risposte a messaggi di chat. " +
                "Rispondi in italiano, in tono formale, conciso e cortese. Niente preamboli, " +
                "solo la risposta da inviare."
            val user = "$contextBlock$targetBlock\n\nScrivi una risposta formale a questo messaggio."
            system to user
        }
        preset == AiPreset.ReplyCasual -> {
            val system = "Sei un assistente AI che redige risposte a messaggi di chat. " +
                "Rispondi in italiano, in tono amichevole e diretto. Niente preamboli, " +
                "solo la risposta da inviare."
            val user = "$contextBlock$targetBlock\n\nScrivi una risposta casual a questo messaggio."
            system to user
        }
        preset == AiPreset.Translate -> {
            val system = "Sei un traduttore. Traduci in italiano il messaggio fornito. " +
                "Restituisci solo la traduzione, senza commenti o spiegazioni."
            val user = targetBlock
            system to user
        }
        preset == AiPreset.Explain -> {
            val system = "Sei un assistente AI. Spiega in italiano cosa significa il " +
                "messaggio fornito, in massimo 3 righe, in modo semplice."
            val user = "$contextBlock$targetBlock\n\nSpiega questo messaggio."
            system to user
        }
        else -> null to messageText
    }
}
