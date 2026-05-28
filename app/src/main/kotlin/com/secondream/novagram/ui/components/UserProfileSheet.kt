@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Compact profile preview sheet shown when the user taps a sender's
 * avatar inside a group/supergroup. Reuses the same bottom-sheet shape as
 * the rest of the app for visual consistency.
 *
 * Layout (top to bottom):
 *   - Big circular avatar
 *   - Display name (italic, semi-bold)
 *   - @username (only if set)
 *   - Bio / "Informazioni" line (from UserFullInfo, only if non-blank)
 *   - "Inizia chat" button — only when the underlying user is a regular
 *     person (TdApi.UserTypeRegular). Bots, deleted accounts, and unknown
 *     types either don't accept private chats normally or aren't useful
 *     to PM, so we hide the button instead of letting the user tap it
 *     and then bail with an error toast.
 *
 * onStartChat is invoked with the resolved chatId after we ask TDLib to
 * create the private chat. We do the createPrivateChat call here rather
 * than just handing back the userId so the calling screen can navigate
 * by chatId directly — the same id the chat list uses.
 */
@Composable
fun UserProfileSheet(
    userId: Long,
    onDismiss: () -> Unit,
    onStartChat: (Long) -> Unit
) {
    var user by remember(userId) { mutableStateOf<TdApi.User?>(null) }
    var fullInfo by remember(userId) { mutableStateOf<TdApi.UserFullInfo?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var creatingChat by remember(userId) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(userId) {
        // Hydrate from cache first so the sheet draws immediately, then
        // background-fetch the full info (bio, blocks, etc) so the bio
        // line and any future privacy flags refresh after first paint.
        user = TdClient.getCachedUser(userId)
            ?: runCatching { TdClient.getUser(userId) }.getOrNull()
        loading = false
        fullInfo = runCatching { TdClient.getUserFullInfo(userId) }.getOrNull()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (loading && user == null) {
                Spacer(Modifier.height(40.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(40.dp))
                return@Column
            }
            val u = user
            if (u == null) {
                // User truly unknown to TDLib (deleted account, blocked us,
                // never seen). Show a neutral placeholder rather than empty
                // space so the tap is acknowledged.
                Spacer(Modifier.height(20.dp))
                Text(
                    "Profilo non disponibile",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(20.dp))
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            Avatar(
                file = u.profilePhoto?.small,
                fallbackText = u.firstName.ifBlank { "?" },
                bgColor = com.secondream.novagram.ui.screens.avatarBackgroundFor(userId),
                size = 96.dp
            )
            Spacer(Modifier.height(14.dp))
            val displayName = "${u.firstName} ${u.lastName}".trim()
                .ifBlank { "Utente" }
            Text(
                displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // TDLib exposes usernames as a structured list now (active +
            // disabled). We surface the first active one if any — that's
            // the @-handle people actually use.
            val username = u.usernames?.activeUsernames?.firstOrNull()?.takeIf { it.isNotBlank() }
            if (username != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val bio = fullInfo?.bio?.text?.takeIf { it.isNotBlank() }
            if (!bio.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    bio,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Start-chat button only for regular humans. Bots get hidden
            // because the conversation flow there is /start-driven and
            // belongs in the New Chat screen instead; deleted accounts
            // can't receive messages at all; unknown types we err on the
            // safe side and skip.
            val canStartChat = u.type is TdApi.UserTypeRegular
            if (canStartChat) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (creatingChat) return@Button
                        creatingChat = true
                        scope.launch {
                            val chat = runCatching {
                                TdClient.createPrivateChat(userId)
                            }.getOrNull()
                            creatingChat = false
                            if (chat != null) onStartChat(chat.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !creatingChat
                ) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Inizia chat")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
