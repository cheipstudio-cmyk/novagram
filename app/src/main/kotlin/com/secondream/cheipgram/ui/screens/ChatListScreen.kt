@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.secondream.cheipgram.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.cheipgram.td.ChatKind
import com.secondream.cheipgram.td.ChatSummary
import com.secondream.cheipgram.R
import androidx.compose.ui.res.stringResource
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TabSpec(val kind: ChatKind, val labelRes: Int)

private val TAB_SPECS = listOf(
    TabSpec(ChatKind.Private, R.string.tab_chats),
    TabSpec(ChatKind.Group, R.string.tab_groups),
    TabSpec(ChatKind.Channel, R.string.tab_channels)
)

@Composable
fun ChatListScreen(
    onChatClick: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onNewChat: () -> Unit = {}
) {
    val allChats by TdClient.chats.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Filter pipeline: tab first (cheap), then local query.
    val visibleChats = remember(allChats, selectedTab, searchQuery) {
        val activeKind = TAB_SPECS[selectedTab].kind
        val byKind = allChats.filter { it.kind == activeKind }
        if (searchQuery.isBlank()) byKind
        else byKind.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChat,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.action_new_chat)
                )
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searchOpen) {
                            SearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it }
                            )
                        } else {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.displayMedium,
                                fontStyle = FontStyle.Italic
                            )
                        }
                    },
                    actions = {
                        if (searchOpen) {
                            IconButton(onClick = {
                                searchOpen = false
                                searchQuery = ""
                            }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.search_close)
                                )
                            }
                        } else {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.search_action)
                                )
                            }
                            IconButton(onClick = onOpenProfile) {
                                Icon(
                                    Icons.Outlined.AccountCircle,
                                    contentDescription = stringResource(R.string.profile_title)
                                )
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.action_settings)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    TAB_SPECS.forEachIndexed { i, spec ->
                        Tab(
                            selected = selectedTab == i,
                            onClick = { selectedTab = i },
                            text = {
                                Text(
                                    stringResource(spec.labelRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selectedTab == i) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (visibleChats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        searchQuery.isNotBlank() ->
                            stringResource(R.string.empty_search_results, searchQuery.trim())
                        selectedTab == 1 -> stringResource(R.string.empty_groups)
                        selectedTab == 2 -> stringResource(R.string.empty_channels)
                        else -> stringResource(R.string.empty_chats)
                    },
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
            items(visibleChats, key = { it.id }) { c ->
                ChatRow(
                    c,
                    onClick = { onChatClick(c.id) },
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
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ink.SurfaceHi)
            .border(0.5.dp, Ink.SurfaceLine, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Search, null,
                tint = Ink.Muted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.search_chats_placeholder),
                        color = Ink.Faint,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Cream),
                    cursorBrush = SolidColor(Ink.Amber),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                when (c.kind) {
                    ChatKind.Group -> {
                        Icon(
                            Icons.Outlined.PeopleAlt,
                            contentDescription = stringResource(R.string.kind_group),
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = Ink.Muted
                        )
                    }
                    ChatKind.Channel -> {
                        Icon(
                            Icons.Outlined.Campaign,
                            contentDescription = stringResource(R.string.kind_channel),
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
        Color(0xFF4A4032),
        Color(0xFF3D4032),
        Color(0xFF323D40),
        Color(0xFF3A3240),
        Color(0xFF40383A),
        Color(0xFF3D3A32),
        Color(0xFF323A3A),
        Color(0xFF40333E)
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
