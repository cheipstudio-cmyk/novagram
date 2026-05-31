package com.secondream.novagram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.drinkless.tdlib.TdApi

/**
 * Renders the inline-keyboard reply markup attached to a bot message. Each
 * row of TdApi.InlineKeyboardButton becomes a Row of equal-weight pill
 * buttons (matching Telegram's rendering convention). Tapping a button
 * fires [onClick] with the corresponding button object; the caller is
 * responsible for routing by button.type (Callback / Url / SwitchInline
 * / etc).
 *
 * The grid sits flush under the bubble with the same max width so it
 * looks attached. Pending state per button is internal: while a tap
 * is in flight (Callback round-trips through the bot's server) we show
 * a small spinner on the active button so the user gets immediate
 * feedback that their tap landed. The parent flips off the pending
 * flag through [pendingButtonKey] — null = no button pending.
 */
@Composable
fun InlineKeyboard(
    markup: TdApi.ReplyMarkupInlineKeyboard,
    pendingButtonKey: String? = null,
    onClick: (TdApi.InlineKeyboardButton, String) -> Unit
) {
    val rows = markup.rows
    Column(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for ((rowIdx, row) in rows.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for ((colIdx, button) in row.withIndex()) {
                    val key = "$rowIdx:$colIdx:${button.text}"
                    InlineKeyboardButton(
                        button = button,
                        isPending = pendingButtonKey == key,
                        modifier = Modifier.weight(1f),
                        onClick = { onClick(button, key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineKeyboardButton(
    button: TdApi.InlineKeyboardButton,
    isPending: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    // Subtle right-side "↗" glyph as a URL hint — drawn via the
    // arrow-up icon rotated 45°. Keeps the buttonbar visually
    // consistent without bringing in a brand-new icon.
    val isExternal = button.type is TdApi.InlineKeyboardButtonTypeUrl ||
        button.type is TdApi.InlineKeyboardButtonTypeLoginUrl
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(0.5.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable(enabled = !isPending, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isPending) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = button.text,
                    style = MaterialTheme.typography.labelLarge,
                    color = onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isExternal) {
                    androidx.compose.foundation.layout.Spacer(
                        Modifier.size(4.dp)
                    )
                    Text(
                        text = "↗",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent.copy(alpha = 0.75f),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
