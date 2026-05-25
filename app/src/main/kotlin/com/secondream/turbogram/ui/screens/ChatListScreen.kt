@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.secondream.turbogram.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.secondream.turbogram.td.ChatKind
import com.secondream.turbogram.td.ChatSummary
import com.secondream.turbogram.td.TdClient
import com.secondream.turbogram.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatListScreen(onChatClick: (Long) -> Unit) {
    val chats by TdClient.chats.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Chat",
                        style = MaterialTheme.typography.displayMedium,
                        fontStyle = FontStyle.Italic
                    )
                },
                actions = {
                    IconButton(onClick = { scope.launch { TdClient.logOut() } }) {
                        Icon(Icons.Outlined.Logout, contentDescription = "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Nessuna chat ancora.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(chats, key = { it.id }) { c ->
                ChatRow(
                    c,
                    onClick = { onChatClick(c.id) },
                    // animateItem positions items smoothly when the chat order changes
                    // (e.g. new message bumps a chat to the top). Foundation 1.7+ API.
                    modifier = Modifier.animateItem()
                )
                HorizontalDivider(
                    color = Ink.SurfaceLine,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(start = 88.dp)
                )
            }
        }
    }
}

@Composable
private fun ChatRow(
    c: ChatSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar placeholder with deterministic colour per chat id
        val bg = avatarBackgroundFor(c.id)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = c.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Cream,
                fontStyle = FontStyle.Italic
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Kind indicator: tiny icon for groups and channels (private chats: none)
                when (c.kind) {
                    ChatKind.Group -> {
                        Icon(
                            Icons.Outlined.PeopleAlt,
                            contentDescription = "Gruppo",
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = Ink.Muted
                        )
                    }
                    ChatKind.Channel -> {
                        Icon(
                            Icons.Outlined.Campaign,
                            contentDescription = "Canale",
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = Ink.Muted
                        )
                    }
                    ChatKind.Private -> Unit
                }
                Text(
                    c.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (c.lastMessageTimestamp > 0) {
                    Text(
                        formatTime(c.lastMessageTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Muted
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Muted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (c.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Ink.Amber),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (c.unread > 99) "99+" else c.unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.OnAmber,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stable, deterministic avatar background color per chat id. Picks one of
 * 8 muted tones that fit the Editorial Dark palette.
 */
private fun avatarBackgroundFor(chatId: Long): Color {
    val palette = listOf(
        Color(0xFF4A4032), // warm brown
        Color(0xFF3D4032), // moss
        Color(0xFF323D40), // teal
        Color(0xFF3A3240), // plum
        Color(0xFF40383A), // mauve
        Color(0xFF3D3A32), // olive
        Color(0xFF323A3A), // slate
        Color(0xFF40333E)  // mulberry
    )
    val idx = ((chatId.hashCode() and 0x7fffffff) % palette.size)
    return palette[idx]
}

private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return if (diff < 24 * 60 * 60 * 1000L) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    } else if (diff < 7 * 24 * 60 * 60 * 1000L) {
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
    } else {
        SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
}
