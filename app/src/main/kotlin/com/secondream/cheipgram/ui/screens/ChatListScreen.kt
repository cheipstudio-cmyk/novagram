@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.secondream.cheipgram.ui.screens
import org.drinkless.tdlib.TdApi
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import com.secondream.cheipgram.td.ChatKind
import com.secondream.cheipgram.td.ChatSummary
import com.secondream.cheipgram.R
import androidx.compose.ui.res.stringResource
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.components.Avatar
import com.secondream.cheipgram.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TabSpec(val kind: ChatKind?, val labelRes: Int, val isHome: Boolean = false)

private val TAB_SPECS = listOf(
    TabSpec(kind = null, labelRes = R.string.tab_home, isHome = true),
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
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var chatActionTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var leaveConfirmTarget by remember { mutableStateOf<ChatSummary?>(null) }

    // Current-user avatar shown in the TopBar's profile button. We fetch
    // once when the screen lands and let TdClient.fileUpdates refresh it.
    var myAvatarFile by remember { mutableStateOf<org.drinkless.tdlib.TdApi.File?>(null) }
    var myInitial by remember { mutableStateOf("?") }
    // myUserId is also used to filter Saved Messages out of the Chats tab
    // (Saved Messages is a private chat where chatId == userId; Eugenio
    // wants it accessible only via the Storage card on the home page).
    var myUserId by remember { mutableStateOf(0L) }
    // First name fuels the top-bar greeting on the Home tab ("Ciao, X").
    // Lives at screen scope (rather than inside HomePage) so the Scaffold
    // can read it without re-fetching every time the tab swaps.
    var myFirstName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            val me = TdClient.getMe()
            myInitial = me.firstName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            myAvatarFile = me.profilePhoto?.small
            myUserId = me.id
            myFirstName = me.firstName.trim().ifBlank { null }
        }
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
                            // Title swaps per tab. On Home we surface the
                            // personalised greeting where the brand name used
                            // to be — same italic SemiBold treatment so it
                            // still reads like a heading, not body copy. On
                            // the other tabs we just show the tab name
                            // ("Chat" / "Gruppi" / "Canali") so the user
                            // always knows where they are without scrolling.
                            val spec = TAB_SPECS[selectedTab]
                            if (spec.isHome) {
                                Text(
                                    text = myFirstName?.let {
                                        stringResource(R.string.home_greeting, it)
                                    } ?: stringResource(R.string.home_greeting_anon),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontStyle = FontStyle.Italic,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(
                                    stringResource(spec.labelRes),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                                Avatar(
                                    file = myAvatarFile,
                                    fallbackText = myInitial,
                                    bgColor = MaterialTheme.colorScheme.surfaceVariant,
                                    size = 32.dp
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
                PillTabs(
                    titles = TAB_SPECS.map { stringResource(it.labelRes) },
                    selected = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }
        }
    ) { padding ->
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = selectedTab,
            pageCount = { TAB_SPECS.size }
        )
        // Tap on a pill -> animate the pager to that page.
        LaunchedEffect(selectedTab) {
            if (pagerState.currentPage != selectedTab && pagerState.targetPage != selectedTab) {
                pagerState.animateScrollToPage(selectedTab)
            }
        }
        // Swipe gesture -> sync the pill highlight without a 200ms lag.
        // pagerState.targetPage flips to the destination as soon as the
        // gesture passes the threshold (well before currentPage updates),
        // so the highlight follows the user's finger instead of trailing it.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.targetPage }.collect { target ->
                if (selectedTab != target) selectedTab = target
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            val spec = TAB_SPECS[page]
            if (spec.isHome) {
                HomePage(
                    allChats = allChats,
                    onChatClick = onChatClick,
                    onNewChat = onNewChat
                )
                return@HorizontalPager
            }
            val pageKind = spec.kind!!
            val pageChats = remember(allChats, page, searchQuery, myUserId) {
                val q = searchQuery.trim()
                allChats
                    .filter { it.kind == pageKind }
                    // Saved Messages is the user's chat with themself — TDLib
                    // exposes it like any other private chat. We hide it from
                    // the Chats tab because it lives on the home page already
                    // (the Storage shortcut). Comparing against 0L is a no-op
                    // while myUserId is still being fetched on first launch.
                    .filter { myUserId == 0L || it.id != myUserId }
                    .let { list ->
                        if (q.isBlank()) list
                        else list.filter { it.title.contains(q, ignoreCase = true) }
                    }
            }
            if (pageChats.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            searchQuery.isNotBlank() ->
                                stringResource(R.string.empty_search_results, searchQuery.trim())
                            pageKind == ChatKind.Group -> stringResource(R.string.empty_groups)
                            pageKind == ChatKind.Channel -> stringResource(R.string.empty_channels)
                            else -> stringResource(R.string.empty_chats)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(pageChats, key = { it.id }) { c ->
                        ChatRow(
                            c,
                            onClick = { onChatClick(c.id) },
                            onLongClick = { chatActionTarget = c },
                            modifier = Modifier.animateItem()
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 88.dp)
                        )
                    }
                }
            }
        }
    }

    chatActionTarget?.let { target ->
        val cachedChat = TdClient.getCachedChat(target.id)
        val isMuted = (cachedChat?.notificationSettings?.muteFor ?: 0) > 0
        ChatActionSheet(
            chatTitle = target.title,
            chatKind = target.kind,
            isMuted = isMuted,
            onDismiss = { chatActionTarget = null },
            onToggleMute = {
                val cid = target.id
                chatActionTarget = null
                scope.launch {
                    runCatching { TdClient.setChatMuted(cid, !isMuted) }
                }
            },
            onDeleteRequest = {
                deleteConfirmTarget = target
                chatActionTarget = null
            },
            onLeaveRequest = {
                leaveConfirmTarget = target
                chatActionTarget = null
            }
        )
    }

    leaveConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { leaveConfirmTarget = null },
            title = { Text(stringResource(R.string.leave_group_confirm, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    val cid = target.id
                    leaveConfirmTarget = null
                    scope.launch { runCatching { TdClient.leaveChat(cid) } }
                }) {
                    Text(
                        stringResource(
                            if (target.kind == ChatKind.Channel) R.string.action_leave_channel
                            else R.string.action_leave_group
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirmTarget = null }) {
                    Text(stringResource(R.string.delete_chat_cancel))
                }
            }
        )
    }

    deleteConfirmTarget?.let { target ->
        var alsoRevoke by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = { Text(stringResource(R.string.delete_chat_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.delete_chat_confirm_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (target.kind == ChatKind.Private) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { alsoRevoke = !alsoRevoke }
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = alsoRevoke,
                                onCheckedChange = { alsoRevoke = it }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.delete_chat_for_everyone),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val cid = target.id
                    val revoke = alsoRevoke && target.kind == ChatKind.Private
                    deleteConfirmTarget = null
                    scope.launch {
                        runCatching {
                            TdClient.deleteChatHistory(cid, removeFromChatList = true, revoke = revoke)
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.action_delete_chat),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirmTarget = null }) {
                    Text(stringResource(R.string.delete_chat_cancel))
                }
            }
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatActionSheet(
    chatTitle: String,
    chatKind: ChatKind,
    isMuted: Boolean,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onDeleteRequest: () -> Unit,
    onLeaveRequest: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding()
        ) {
            Text(
                chatTitle,
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic
            )
            Spacer(Modifier.height(20.dp))
            ChatActionRow(
                label = stringResource(
                    if (isMuted) R.string.action_unmute_chat else R.string.action_mute_chat
                ),
                onClick = onToggleMute
            )
            Spacer(Modifier.height(4.dp))
            // Groups + channels can't be "deleted" from your side — only
            // left. Telegram nukes the chat from your list and stops
            // delivering its messages. Private chats can still be deleted
            // (history wipe + remove from list).
            when (chatKind) {
                ChatKind.Group, ChatKind.Channel -> {
                    ChatActionRow(
                        label = stringResource(
                            if (chatKind == ChatKind.Channel) R.string.action_leave_channel
                            else R.string.action_leave_group
                        ),
                        destructive = true,
                        onClick = onLeaveRequest
                    )
                }
                ChatKind.Private -> {
                    ChatActionRow(
                        label = stringResource(R.string.action_delete_chat),
                        destructive = true,
                        onClick = onDeleteRequest
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ChatActionRow(
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PillTabs(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val animatedPrimary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceMuted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        titles.forEachIndexed { i, title ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(if (selected == i) animatedPrimary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected == i) onPrimary else onSurfaceMuted,
                    fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Medium
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    c: ChatSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bg = avatarBackgroundFor(c.id)
        Avatar(
            file = c.chat.photo?.small,
            fallbackText = c.title,
            bgColor = bg,
            size = 48.dp
        )
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
                // Show a "bell off" icon if the chat is muted, between title
                // and timestamp. Reads live from chat.notificationSettings,
                // which is refreshed via the UpdateChatNotificationSettings
                // handler so toggling mute updates this immediately.
                val isMuted = (c.chat.notificationSettings?.muteFor ?: 0) > 0
                if (isMuted) {
                    Icon(
                        Icons.Outlined.NotificationsOff,
                        contentDescription = stringResource(R.string.action_unmute_chat),
                        modifier = Modifier.size(14.dp).padding(start = 6.dp),
                        tint = Ink.Muted
                    )
                }
                if (c.lastMessageTimestamp > 0) {
                    Text(
                        formatTime(c.lastMessageTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Ink.Muted,
                        modifier = Modifier.padding(start = 6.dp)
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
internal fun avatarBackgroundFor(chatId: Long): Color {
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

/**
 * Landing page of the chat list. Shows a personalised greeting, a stats
 * card with unread totals, and a "Recent unread" list of the five most
 * recently active chats that still have unread messages.
 *
 * Designed to feel like a dashboard: rounded surfaces, big numbers, a
 * deliberate amount of whitespace. The list of recent-unread items
 * navigates straight into each chat on tap.
 */
@Composable
private fun HomePage(
    allChats: List<ChatSummary>,
    onChatClick: (Long) -> Unit,
    onNewChat: () -> Unit = {}
) {
    val unread = allChats.filter { it.unread > 0 }
    val totalUnread = unread.sumOf { it.unread }
    val recentUnread = unread.take(5)

    // We grab our own userId here for the Storage shortcut tile below
    // (Saved Messages is a private chat where chatId == userId, so tapping
    // the card just opens that chat). The greeting/firstName previously
    // rendered at the top of this page now lives in the screen-level
    // TopAppBar instead — replacing the old "CheipGram" brand title.
    var myUserId by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val me = runCatching { TdClient.getMe() }.getOrNull()
        myUserId = me?.id ?: 0L
    }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Summary card with two stat tiles side-by-side. Sits at the top
            // of the page now that the greeting moved to the TopAppBar.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(18.dp)
            ) {
                Text(
                    stringResource(R.string.home_summary).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HomeStatTile(
                        value = unread.size,
                        label = stringResource(R.string.home_unread_chats),
                        modifier = Modifier.weight(1f)
                    )
                    HomeStatTile(
                        value = totalUnread,
                        label = stringResource(R.string.home_total_unread),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        // ── Recent unread, lifted up directly under the summary ──
        // Eugenio asked for the "Nuovi messaggi" block to sit right under
        // the Riepilogo card instead of being the LAST section on the home
        // page — it's the most actionable content so it deserves the
        // prominent position.
        item {
            Text(
                stringResource(R.string.home_recent_unread).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
        if (recentUnread.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.home_no_unread),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    recentUnread.forEachIndexed { i, summary ->
                        if (i > 0) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            )
                        }
                        HomeChatItem(
                            summary = summary,
                            onClick = { onChatClick(summary.id) }
                        )
                    }
                }
            }
        }
        // Quick actions. The Search shortcut used to live here but Eugenio
        // removed it — search is already available via the top-bar icon on
        // every tab, so showing it twice on the home was redundant. What
        // remains: Storage (Saved Messages alias) and Nuova chat as the
        // primary pair, plus an optional Unisciti a CheipGram card when the
        // user hasn't joined the community channel yet.
        item {
            val ctx = LocalContext.current
            var cheipgramJoined by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(allChats.size) {
                if (cheipgramJoined == true) return@LaunchedEffect
                cheipgramJoined = runCatching {
                    val res = TdClient.searchPublicChats("cheipgram")
                    val match = res.firstOrNull { c ->
                        c.title.equals("CheipGram", ignoreCase = true)
                    } ?: return@runCatching false
                    allChats.any { it.id == match.id }
                }.getOrDefault(false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeShortcutTile(
                        icon = Icons.Outlined.BookmarkBorder,
                        label = stringResource(R.string.home_storage_title),
                        onClick = { if (myUserId != 0L) onChatClick(myUserId) },
                        modifier = Modifier.weight(1f)
                    )
                    HomeShortcutTile(
                        icon = Icons.Outlined.Edit,
                        label = stringResource(R.string.home_shortcut_new_chat),
                        onClick = onNewChat,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (cheipgramJoined == false) {
                    // Full-width call to action only when the user isn't a
                    // CheipGram member yet. Once they join (or while we're
                    // still checking) we skip the row entirely so the home
                    // stays compact instead of leaving an empty placeholder.
                    HomeShortcutTile(
                        icon = Icons.Outlined.Campaign,
                        label = stringResource(R.string.home_card_join_title),
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://t.me/cheipgram")
                            )
                            runCatching { ctx.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        accentBackground = true
                    )
                }
            }
        }
        // Bottom safe-area spacing so the last card isn't flush with the
        // navigation gesture bar.
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HomeStatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(14.dp)
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HomeChatItem(summary: ChatSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            file = summary.chat.photo?.small,
            fallbackText = summary.title,
            bgColor = avatarBackgroundFor(summary.id),
            size = 44.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                summary.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                summary.lastMessagePreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // Unread count pill.
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                summary.unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun HomeShortcutTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * When true, the tile uses the accent color as background (with
     * onPrimary text + icon) instead of the neutral surface look. Used
     * for the "Unisciti a CheipGram" call-to-action so it stands out
     * inside the otherwise uniform grid without breaking the rhythm.
     */
    accentBackground: Boolean = false
) {
    val bg = if (accentBackground) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surface
    val fg = if (accentBackground) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface
    val iconBg = if (accentBackground)
        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.18f)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val iconTint = if (accentBackground) MaterialTheme.colorScheme.onPrimary
                   else MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .let {
                if (accentBackground) it
                else it.border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(18.dp)
                )
            }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}
