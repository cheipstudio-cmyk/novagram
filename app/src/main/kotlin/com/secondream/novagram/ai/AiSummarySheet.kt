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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secondream.novagram.R
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class SummaryPhase { Picking, Loading, Result }

/**
 * Home-screen AI sheet opened from the small Sparkle FAB. Phase 1 shows a
 * grid of AI actions (currently just "Riepilogo"); picking it gathers the
 * unread messages from the user's most-recent chats — read from OUTSIDE any
 * open chat via [TdClient.recentUnreadDigest] — and asks Claude for a short
 * recap. The wait uses the same orb animation as the in-chat AI; the result
 * lands in a scrollable, copyable card.
 *
 * No-key and no-unread are handled as friendly inline states rather than
 * raw errors so the feature is self-explanatory on first tap.
 */
@Composable
fun AiSummarySheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var phase by remember { mutableStateOf(SummaryPhase.Picking) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runSummary() {
        error = null
        result = null
        phase = SummaryPhase.Loading
        val langName = java.util.Locale.getDefault()
            .getDisplayLanguage(java.util.Locale.getDefault())
            .ifBlank { "English" }
        scope.launch {
            runCatching {
                val key = AppSettings.appearance.first().anthropicApiKey
                if (key.isNullOrBlank()) {
                    error = ctx.getString(R.string.ai_summary_nokey)
                    phase = SummaryPhase.Result
                    return@launch
                }
                val digest = TdClient.recentUnreadDigest()
                if (digest.isEmpty()) {
                    error = ctx.getString(R.string.ai_summary_empty)
                    phase = SummaryPhase.Result
                    return@launch
                }
                val block = digest.joinToString("\n\n") { d ->
                    "## " + d.title + "\n" + d.lines.joinToString("\n")
                }
                val sys = "You are an assistant inside a messaging app. The user wants a quick " +
                    "recap of their unread messages across several chats. Reply in " + langName +
                    ". Group by chat with a short bold chat name, then one or two concise bullets " +
                    "of what's new and anything that needs a reply. No preamble, no closing remark."
                val answer = AiClient.complete(
                    userPrompt = "<unread>\n" + block + "\n</unread>\n\nRecap my unread messages.",
                    systemPrompt = sys
                )
                result = answer
                phase = SummaryPhase.Result
            }.onFailure {
                error = it.message ?: ctx.getString(R.string.ai_error)
                phase = SummaryPhase.Result
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
                SummaryPhase.Picking -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { runSummary() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.Chats,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.ai_summary_tile),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                stringResource(R.string.ai_summary_tile_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                SummaryPhase.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AiThinkingIndicator()
                }

                SummaryPhase.Result -> {
                    val err = error
                    if (err != null) {
                        Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { runSummary() }) {
                                Text(stringResource(R.string.ai_retry))
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.ai_summary_close))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                result.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = {
                                    result?.let {
                                        clipboard.setText(AnnotatedString(it))
                                        android.widget.Toast.makeText(
                                            ctx,
                                            ctx.getString(R.string.ai_copied),
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.Copy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.ai_action_copy))
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(R.string.ai_summary_close))
                            }
                        }
                    }
                }
            }
        }
    }
}
