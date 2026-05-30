@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.secondream.novagram.ui.screens
import org.drinkless.tdlib.TdApi
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TextButton

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
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
import com.secondream.novagram.td.ChatKind
import com.secondream.novagram.td.ChatSummary
import com.secondream.novagram.R
import androidx.compose.ui.res.stringResource
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.components.Avatar
import com.secondream.novagram.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TabSpec(
    val kind: ChatKind?,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val isArchive: Boolean = false,
    /**
     * When true, this tab shows ALL chats regardless of kind (private +
     * group + channel + secret), excluding only archived ones. Used by
     * the "Tutto" tab so users have one stream that mirrors official
     * Telegram's default chat list view.
     */
    val isAll: Boolean = false
)

/**
 * The chat-list tabs. Order: Tutto (everything), Chat, Gruppi, Canali,
 * optional Segrete (data-driven), optional Archiviati (settings toggle).
 * Tutto is always the first tab so the user can flip into "show me
 * everything" mode without scrolling through category-specific tabs.
 *
 * Each tab carries its own icon — labelRes is still used for the
 * search-empty-state ("Nessun risultato in Chat") and content-description
 * accessibility text, but the visible tab itself is icon-only.
 */
private fun buildTabs(
    showAll: Boolean,
    showArchive: Boolean,
    hasSecret: Boolean
): List<TabSpec> = buildList {
    if (showAll) add(
        TabSpec(
            kind = null,
            labelRes = R.string.tab_all,
            icon = com.secondream.novagram.ui.icons.PhosphorIcons.Chats,
            isAll = true
        )
    )
    add(TabSpec(ChatKind.Private, R.string.tab_chats, com.secondream.novagram.ui.icons.PhosphorIcons.ChatCircle))
    add(TabSpec(ChatKind.Group, R.string.tab_groups, com.secondream.novagram.ui.icons.PhosphorIcons.UsersThree))
    add(TabSpec(ChatKind.Channel, R.string.tab_channels, com.secondream.novagram.ui.icons.PhosphorIcons.Megaphone))
    // Secret-chats tab is data-driven: appears only when the user has
    // at least one secret chat, vanishes again when they delete the
    // last one.
    if (hasSecret) add(TabSpec(ChatKind.Secret, R.string.tab_secret, com.secondream.novagram.ui.icons.PhosphorIcons.Lock))
    if (showArchive) add(
        TabSpec(
            kind = null,
            labelRes = R.string.tab_archived,
            icon = com.secondream.novagram.ui.icons.PhosphorIcons.Archive,
            isArchive = true
        )
    )
}

@Composable
fun ChatListScreen(
    onChatClick: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onNewChat: () -> Unit = {}
) {
    val allChats by TdClient.chats.collectAsState()
    val scope = rememberCoroutineScope()
    val appearance by com.secondream.novagram.settings.AppSettings.appearance
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs())
    // Tabs are dynamic now: Chat/Gruppi/Canali, plus Segrete when the
    // user has ≥1 secret chat live in their list, plus Archiviati when
    // they've turned that toggle on in Settings.
    val hasSecret = remember(allChats) {
        allChats.any { it.kind == ChatKind.Secret }
    }
    val tabs = remember(appearance.showAllTab, appearance.showArchivedTab, hasSecret) {
        buildTabs(appearance.showAllTab, appearance.showArchivedTab, hasSecret)
    }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Server-side public search results — public users (in Chat tab),
    // public groups (in Gruppi), or public channels (in Canali). Populated
    // by a debounced LaunchedEffect downstream and rendered under the
    // local-filtered list when the search bar is open.
    var publicResults by remember { mutableStateOf<List<org.drinkless.tdlib.TdApi.Chat>>(emptyList()) }
    var publicSearching by remember { mutableStateOf(false) }
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

    // Debounced public search. Whenever the user types in the search bar
    // (or switches tab while still searching), wait a beat and then ask
    // TDLib for matching public chats. Filter the response down to the
    // ChatKind of the current tab so the Chat tab only surfaces people,
    // Gruppi only surfaces groups, and Canali only channels.
    LaunchedEffect(searchOpen, searchQuery, selectedTab) {
        if (!searchOpen) {
            publicResults = emptyList()
            publicSearching = false
            return@LaunchedEffect
        }
        val q = searchQuery.trim()
        if (q.length < 2) {
            publicResults = emptyList()
            publicSearching = false
            return@LaunchedEffect
        }
        val spec = tabs.getOrNull(selectedTab) ?: return@LaunchedEffect
        if (spec.isArchive) {
            // Archive tab — no public search needed, those are already in
            // the local list and there's no public concept for archive.
            publicResults = emptyList()
            return@LaunchedEffect
        }
        publicSearching = true
        kotlinx.coroutines.delay(250)
        val raw = runCatching { TdClient.searchPublicChats(q) }.getOrNull().orEmpty()
        // Map to ChatKind locally (TdClient's resolveKind is private). Same
        // logic: private/secret → Private, basic group or non-channel
        // supergroup → Group, channel supergroup → Channel.
        publicResults = raw.filter { chat ->
            val k = when (val t = chat.type) {
                is org.drinkless.tdlib.TdApi.ChatTypePrivate -> ChatKind.Private
                is org.drinkless.tdlib.TdApi.ChatTypeBasicGroup -> ChatKind.Group
                is org.drinkless.tdlib.TdApi.ChatTypeSupergroup -> if (t.isChannel) ChatKind.Channel else ChatKind.Group
                is org.drinkless.tdlib.TdApi.ChatTypeSecret -> ChatKind.Secret
                else -> ChatKind.Private
            }
            k == spec.kind && chat.id != myUserId  // hide self in user results
        }
        publicSearching = false
    }

    Scaffold(
        floatingActionButton = {
            // Springy press feedback: the FAB dips to 88% under the finger and
            // springs back on release. The scale runs through a graphicsLayer
            // block (layer-only, no recomposition) and the low damping ratio
            // gives a small, satisfying rebound without looking toy-ish.
            val fabInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val fabPressed by fabInteraction.collectIsPressedAsState()
            val fabScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (fabPressed) 0.88f else 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.55f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                ),
                label = "fabScale"
            )
            FloatingActionButton(
                onClick = onNewChat,
                interactionSource = fabInteraction,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.graphicsLayer {
                    scaleX = fabScale
                    scaleY = fabScale
                }
            ) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.Plus,
                    contentDescription = stringResource(R.string.action_new_chat)
                )
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (searchOpen) {
                            val safeTab = selectedTab.coerceIn(0, tabs.lastIndex)
                            val spec = tabs[safeTab]
                            val placeholder = when {
                                spec.isArchive -> stringResource(R.string.search_in_archive)
                                spec.kind == ChatKind.Channel -> stringResource(R.string.search_public_channels)
                                spec.kind == ChatKind.Group -> stringResource(R.string.search_public_groups)
                                else -> stringResource(R.string.search_public_users)
                            }
                            SearchField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = placeholder
                            )
                        } else {
                            val safeTab = selectedTab.coerceIn(0, tabs.lastIndex)
                            val spec = tabs[safeTab]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val isLightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(
                                        if (isLightTheme) com.secondream.novagram.R.drawable.ic_novagram_light
                                        else com.secondream.novagram.R.drawable.ic_novagram_dark
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .padding(end = 8.dp)
                                )
                                Text(
                                    stringResource(spec.labelRes),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontStyle = FontStyle.Italic,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        // Search is available on every tab now (no Home).
                        if (searchOpen) {
                            IconButton(onClick = {
                                searchOpen = false
                                searchQuery = ""
                            }) {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.X,
                                    contentDescription = stringResource(R.string.search_close)
                                )
                            }
                        } else {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(
                                    com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass,
                                    contentDescription = stringResource(R.string.search_action)
                                )
                            }
                            // Download button — opens GitHub releases page
                            // in browser. Decorated with an accent dot when
                            // UpdateChecker has flagged a newer release as
                            // available. The Box wraps the IconButton so
                            // the dot can be anchored at TopEnd of the
                            // icon's bounding box, just outside the glyph.
                            val updateAvailable by com.secondream.novagram.update
                                .UpdateChecker.updateAvailable
                                .collectAsState()
                            val updateCtx = LocalContext.current
                            androidx.compose.foundation.layout.Box {
                                IconButton(onClick = {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            com.secondream.novagram.update
                                                .UpdateChecker.RELEASES_PAGE
                                        )
                                    )
                                    runCatching { updateCtx.startActivity(intent) }
                                }) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.DownloadSimple,
                                        contentDescription = stringResource(R.string.action_check_updates)
                                    )
                                }
                                if (updateAvailable) {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 8.dp, end = 8.dp)
                                            .size(8.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
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
                                    com.secondream.novagram.ui.icons.PhosphorIcons.Gear,
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
                com.secondream.novagram.ui.components.OfflineBanner()
                // Per-tab unread totals (sum of unread across chats in
                // that category). Archive sums archived chats; the others
                // sum their kind among non-archived chats.
                val tabBadges = remember(allChats, tabs) {
                    tabs.map { spec ->
                        when {
                            spec.isAll -> allChats.filter { !it.isArchived }.sumOf { it.unread }
                            spec.isArchive -> allChats.filter { it.isArchived }.sumOf { it.unread }
                            else -> allChats.filter { !it.isArchived && it.kind == spec.kind }.sumOf { it.unread }
                        }
                    }
                }
                PillTabs(
                    icons = tabs.map { it.icon },
                    contentDescriptions = tabs.map { stringResource(it.labelRes) },
                    badges = tabBadges,
                    selected = selectedTab.coerceIn(0, tabs.lastIndex),
                    onSelect = { selectedTab = it }
                )
            }
        }
    ) { padding ->
        // Keep selectedTab valid if the archive tab was toggled off while
        // it was selected.
        LaunchedEffect(tabs.size) {
            if (selectedTab > tabs.lastIndex) selectedTab = tabs.lastIndex
        }
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = selectedTab.coerceIn(0, tabs.lastIndex),
            pageCount = { tabs.size }
        )
        LaunchedEffect(selectedTab) {
            if (pagerState.currentPage != selectedTab && pagerState.targetPage != selectedTab) {
                runCatching { pagerState.animateScrollToPage(selectedTab.coerceIn(0, tabs.lastIndex)) }
            }
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.targetPage }.collect { target ->
                if (selectedTab != target) selectedTab = target
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            val spec = tabs[page.coerceIn(0, tabs.lastIndex)]
            val pageChats = remember(allChats, page, searchQuery, myUserId, spec) {
                val q = searchQuery.trim()
                val base = when {
                    spec.isAll -> {
                        // "Tutto" tab: every kind, every chat — only
                        // archived ones get filtered out (they belong
                        // to the Archive tab). Saved Messages is lifted
                        // to the top of this view too, matching the
                        // Chat-tab behaviour, so it's the very first
                        // entry the user sees regardless of recency.
                        val all = allChats.filter { !it.isArchived }
                        if (myUserId != 0L) {
                            val saved = all.filter { it.id == myUserId }
                            val rest = all.filter { it.id != myUserId }
                            saved + rest
                        } else all
                    }
                    spec.isArchive -> {
                        // Archive tab: every archived chat regardless of kind.
                        allChats.filter { it.isArchived }
                    }
                    else -> {
                        allChats
                            .filter { !it.isArchived && it.kind == spec.kind }
                            // Saved Messages (chat with self) is pinned to the
                            // TOP of the Chat tab instead of being hidden — so
                            // we DON'T filter it out here for Private; we lift
                            // it to the front below.
                            .let { list ->
                                if (spec.kind == ChatKind.Private && myUserId != 0L) {
                                    val saved = list.filter { it.id == myUserId }
                                    val rest = list.filter { it.id != myUserId }
                                    saved + rest
                                } else list
                            }
                    }
                }
                if (q.isBlank()) base
                else base.filter { it.title.contains(q, ignoreCase = true) }
            }
            val hasActiveSearch = searchOpen && searchQuery.trim().isNotBlank()
            val showingNoResults = pageChats.isEmpty() &&
                !hasActiveSearch &&
                publicResults.isEmpty()
            if (showingNoResults) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            spec.isArchive -> stringResource(R.string.empty_archived)
                            spec.kind == ChatKind.Group -> stringResource(R.string.empty_groups)
                            spec.kind == ChatKind.Channel -> stringResource(R.string.empty_channels)
                            else -> stringResource(R.string.empty_chats)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Saved Messages is split out from the regular pageChats
                // list and rendered as a stickyHeader so it stays anchored
                // to the top of the viewport regardless of scroll position.
                // Telegram pins the Saved Messages pseudo-chat the same
                // way — it's the "notes to self" surface, the user
                // expects to find it always at hand no matter how deep
                // they've scrolled into their chat list. Without the
                // sticky behavior, returning from a chat would land the
                // user at their previous scroll position, with Saved
                // Messages potentially scrolled off-screen above.
                val savedMsgsChat = remember(pageChats, myUserId) {
                    if (myUserId != 0L) pageChats.firstOrNull { it.id == myUserId } else null
                }
                val otherChats = remember(pageChats, savedMsgsChat) {
                    if (savedMsgsChat != null) pageChats.filter { it.id != savedMsgsChat.id }
                    else pageChats
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    if (savedMsgsChat != null) {
                        stickyHeader(key = "saved_messages_sticky") {
                            // Wrap in a Box with explicit background so
                            // the row reads as a solid card sitting above
                            // the scrolling items — without the bg, the
                            // sticky header would show the items
                            // bleeding through underneath as they scroll
                            // past, breaking the "always at top" illusion.
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                Column {
                                    ChatRow(
                                        savedMsgsChat,
                                        onClick = { onChatClick(savedMsgsChat.id) },
                                        onLongClick = { chatActionTarget = savedMsgsChat },
                                        myUserId = myUserId
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
                    items(otherChats, key = { it.id }) { c ->
                        ChatRow(
                            c,
                            onClick = { onChatClick(c.id) },
                            onLongClick = { chatActionTarget = c },
                            modifier = Modifier.animateItem(),
                            myUserId = myUserId
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(start = 88.dp)
                        )
                    }
                    if (hasActiveSearch) {
                        // Server-side results: people / public groups /
                        // public channels matched by Telegram itself, not
                        // by our local cache. Header + list with a join
                        // affordance per item.
                        item(key = "public_results_header") {
                            Text(
                                stringResource(R.string.search_public_results),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 18.dp, top = 16.dp, bottom = 6.dp)
                            )
                        }
                        if (publicSearching && publicResults.isEmpty()) {
                            item(key = "public_searching") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        } else if (publicResults.isEmpty()) {
                            item(key = "public_empty") {
                                Text(
                                    stringResource(R.string.search_public_no_results),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 12.dp)
                                )
                            }
                        } else {
                            items(publicResults, key = { "public_${it.id}" }) { chat ->
                                PublicResultRow(
                                    chat = chat,
                                    onOpen = { onChatClick(chat.id) },
                                    onJoin = {
                                        scope.launch {
                                            runCatching { TdClient.joinChat(chat.id) }
                                            onChatClick(chat.id)
                                        }
                                    }
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
        }
    }

    chatActionTarget?.let { target ->
        val cachedChat = TdClient.getCachedChat(target.id)
        val isMuted = (cachedChat?.notificationSettings?.muteFor ?: 0) > 0
        ChatActionSheet(
            chatTitle = target.title,
            chatKind = target.kind,
            isMuted = isMuted,
            isArchived = target.isArchived,
            isPinned = target.isPinned,
            onDismiss = { chatActionTarget = null },
            onToggleMute = {
                val cid = target.id
                chatActionTarget = null
                scope.launch {
                    runCatching { TdClient.setChatMuted(cid, !isMuted) }
                }
            },
            onTogglePin = {
                val cid = target.id
                val wasPinned = target.isPinned
                chatActionTarget = null
                scope.launch {
                    runCatching { TdClient.toggleChatIsPinned(cid, !wasPinned) }
                }
            },
            onToggleArchive = {
                val cid = target.id
                val wasArchived = target.isArchived
                chatActionTarget = null
                scope.launch {
                    runCatching { TdClient.archiveChat(cid, !wasArchived) }
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
    isArchived: Boolean,
    isPinned: Boolean,
    onDismiss: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit,
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
                    if (isPinned) R.string.action_unpin_chat else R.string.action_pin_chat
                ),
                onClick = onTogglePin
            )
            Spacer(Modifier.height(4.dp))
            ChatActionRow(
                label = stringResource(
                    if (isMuted) R.string.action_unmute_chat else R.string.action_mute_chat
                ),
                onClick = onToggleMute
            )
            Spacer(Modifier.height(4.dp))
            ChatActionRow(
                label = stringResource(
                    if (isArchived) R.string.action_unarchive_chat else R.string.action_archive_chat
                ),
                onClick = onToggleArchive
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
                ChatKind.Private, ChatKind.Secret -> {
                    // Secret chats behave like privates here: there is no
                    // "leave" because they're 1-to-1, and the only
                    // destructive action is to delete the conversation.
                    // For secret chats TDLib also closes the encrypted
                    // session under the hood when the chat is removed,
                    // which is what we want.
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
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    contentDescriptions: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badges: List<Int> = emptyList()
) {
    // Sliding-pill icon tabs. The container is wrap-content + centered
    // horizontally — no longer full-width — so the bar reads as a
    // compact control floating under the title rather than a full-
    // bleed segmented bar. Each tab is a fixed 44x36dp pill with the
    // 20dp icon centered inside; the accent pill that marks the
    // selected tab still slides between positions via animateFloatAsState
    // the same way the old text-pill version did. Per-tab unread badges
    // sit as overlays at the top-right of each pill — see comment
    // inside the per-tab block for the inversion rule on the selected
    // pill.
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurfaceMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val pageBg = MaterialTheme.colorScheme.background
    val animatedSelected by androidx.compose.animation.core.animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "tab-slide"
    )
    val tabWidth = 44.dp
    val tabHeight = 36.dp
    val tabGap = 4.dp
    val containerPadding = 4.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(containerPadding)
        ) {
            // Sliding accent pill — sits underneath the icons, slides
            // between positions when the selected index changes. Width
            // matches one tab; offset is selectedIdx * (tabWidth + gap).
            Box(
                modifier = Modifier
                    .offset(x = (tabWidth + tabGap) * animatedSelected)
                    .width(tabWidth)
                    .height(tabHeight)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(primary)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(tabGap)) {
                icons.forEachIndexed { i, icon ->
                    val distance = kotlin.math.abs(animatedSelected - i).coerceIn(0f, 1f)
                    val iconColor = androidx.compose.ui.graphics.lerp(onPrimary, onSurfaceMuted, distance)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(width = tabWidth, height = tabHeight)
                    ) {
                        // Tap target + icon
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null
                                ) { onSelect(i) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = contentDescriptions.getOrNull(i),
                                tint = iconColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Unread badge overlay at top-right corner of
                        // the pill, sticker-style (slightly outside the
                        // pill bounds). Hidden when count = 0. When
                        // this tab is currently selected, the badge
                        // inverts color so it's still legible on top
                        // of the accent-colored sliding pill: dark
                        // background + light text + accent ring,
                        // instead of the default accent background +
                        // dark text + page-bg ring used on the
                        // unselected pills.
                        val badge = badges.getOrNull(i) ?: 0
                        if (badge > 0) {
                            val badgeBg = if (i == selected) MaterialTheme.colorScheme.onPrimary else primary
                            val badgeFg = if (i == selected) primary else onPrimary
                            val badgeRing = if (i == selected) primary else pageBg
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 3.dp, y = (-3).dp)
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                                    .background(badgeRing)
                                    .padding(2.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                                    .background(badgeBg)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (badge > 99) "99+" else badge.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeFg,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
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
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null
) {
    // Theme-aware search bubble. On light themes we paint pure white so it
    // stands out against the off-white background like Telegram's iOS skin
    // does, with black text and a grey placeholder. On dark themes we keep
    // the existing elevated surface tone so the bar reads against the chat
    // backdrop rather than disappearing into it.
    val cs = MaterialTheme.colorScheme
    val isLight = cs.background.luminance() > 0.5f
    val bubbleBg = if (isLight) androidx.compose.ui.graphics.Color.White else Ink.SurfaceHi
    val border = if (isLight) cs.outline.copy(alpha = 0.35f) else Ink.SurfaceLine
    val textColor = if (isLight) androidx.compose.ui.graphics.Color.Black else Ink.Cream
    val placeholderColor = if (isLight) {
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)
    } else Ink.Faint
    val iconTint = if (isLight) cs.onSurfaceVariant else Ink.Muted
    // Pop the soft keyboard open the moment the field enters composition.
    // Without this the user has to tap the bar a second time after the
    // magnifying-glass icon button to get the IME — annoying when you
    // just want to start typing. Same focus-then-show trick we use in
    // the in-chat search bar: requestFocus first, then a short delay,
    // then explicit keyboard.show() because some Android builds don't
    // pop the IME on focus alone.
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        kotlinx.coroutines.delay(30)
        runCatching { keyboard?.show() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(bubbleBg)
            .border(0.5.dp, border, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass, null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        placeholder ?: stringResource(R.string.search_chats_placeholder),
                        color = placeholderColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                    cursorBrush = SolidColor(cs.primary),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                )
            }
        }
    }
}

/** Compact search field reused by the chat list and the new-chat screen. */

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
/**
 * Row for a public-search hit: avatar + title + @username on the left, and
 * a contextual action button on the right. Channels and groups show
 * "Unisciti" (which joinChats then opens), private users show "Apri"
 * (just opens). Tapping the row itself also opens.
 */
@Composable
private fun PublicResultRow(
    chat: org.drinkless.tdlib.TdApi.Chat,
    onOpen: () -> Unit,
    onJoin: () -> Unit
) {
    val type = chat.type
    val isPrivate = type is org.drinkless.tdlib.TdApi.ChatTypePrivate ||
        type is org.drinkless.tdlib.TdApi.ChatTypeSecret
    val isChannel = type is org.drinkless.tdlib.TdApi.ChatTypeSupergroup && type.isChannel
    // Heuristic for "already member": TDLib only includes a chat in chat
    // lists (positions) once you're in it. searchPublicChats returns
    // both joinable and joined chats; for the latter, positions is
    // non-empty.
    val alreadyMember = chat.positions != null && chat.positions.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            file = chat.photo?.small,
            fallbackText = chat.title,
            bgColor = avatarBackgroundFor(chat.id),
            size = 48.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                chat.title.ifBlank { stringResource(R.string.unknown_chat) },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    isPrivate -> stringResource(R.string.search_public_kind_user)
                    isChannel -> stringResource(R.string.search_public_kind_channel)
                    else -> stringResource(R.string.search_public_kind_group)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        // Action pill on the right. Private chats and already-member groups
        // get "Apri"; non-member groups/channels get "Unisciti al gruppo"
        // / "Unisciti al canale" which calls JoinChat first.
        val (label, action) = when {
            isPrivate || alreadyMember ->
                stringResource(R.string.search_public_open) to onOpen
            isChannel ->
                stringResource(R.string.search_public_join_channel) to onJoin
            else ->
                stringResource(R.string.search_public_join_group) to onJoin
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable { action() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ChatRow(
    c: ChatSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Current user's id. When the chat id matches, the row renders as
     *  the "Saved Messages" pseudo-chat (bookmark icon + localized
     *  label) instead of the user's own avatar + name. */
    myUserId: Long = 0L
) {
    val isSavedMessages = myUserId != 0L && c.id == myUserId
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Pinned-chat highlight: 6% accent overlay so the row stands
            // out at a glance without competing with the unread badges
            // or the title text. Sits BEHIND the click ripple, so the
            // click feedback still reads cleanly on top of the tint.
            .then(
                if (c.isPinned)
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSavedMessages) {
            // Saved Messages avatar: solid accent disc with a bookmark
            // glyph centered. Mirrors official Telegram's treatment so
            // the chat is recognizable across clients.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    com.secondream.novagram.ui.icons.PhosphorIcons.BookmarkSimple,
                    contentDescription = stringResource(R.string.saved_messages),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            val bg = avatarBackgroundFor(c.id)
            Avatar(
                file = c.chat.photo?.small,
                fallbackText = c.title,
                bgColor = bg,
                size = 48.dp
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (c.kind) {
                    ChatKind.Group -> {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.UsersThree,
                            contentDescription = stringResource(R.string.kind_group),
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = Ink.Muted
                        )
                    }
                    ChatKind.Channel -> {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.Megaphone,
                            contentDescription = stringResource(R.string.kind_channel),
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = Ink.Muted
                        )
                    }
                    ChatKind.Secret -> {
                        // Lock icon makes the e2e nature of the chat
                        // visible in the list row, the same way the
                        // official Telegram client does.
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.Lock,
                            contentDescription = stringResource(R.string.tab_secret),
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = Ink.Muted
                        )
                    }
                    ChatKind.Private -> Unit
                }
                Text(
                    if (isSavedMessages) stringResource(R.string.saved_messages) else c.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Pinned-to-top indicator. Sits to the right of the title,
                // before the mute bell + timestamp. Matches Telegram's
                // visual cue that this chat was explicitly anchored at
                // the top of the list by the user.
                if (c.isPinned) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.PushPin,
                        contentDescription = stringResource(R.string.action_pin_chat),
                        modifier = Modifier.size(14.dp).padding(start = 6.dp),
                        tint = Ink.Muted
                    )
                }
                // Show a "bell off" icon if the chat is muted, between title
                // and timestamp. Reads live from chat.notificationSettings,
                // which is refreshed via the UpdateChatNotificationSettings
                // handler so toggling mute updates this immediately.
                val isMuted = (c.chat.notificationSettings?.muteFor ?: 0) > 0
                if (isMuted) {
                    Icon(
                        com.secondream.novagram.ui.icons.PhosphorIcons.BellSlash,
                        contentDescription = stringResource(R.string.action_unmute_chat),
                        modifier = Modifier.size(16.dp).padding(start = 6.dp),
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
                // Three independent badges that can stack in the row:
                // @-mentions, ♥-reactions, and the numeric unread count.
                // Mentions / reactions always render in accent (Telegram
                // doesn't gray these out even for muted chats — they're
                // direct pings to YOU). The numeric badge follows the
                // mute rules from before.
                val muted = TdClient.isChatMuted(c.chat)
                val hasMentions = c.unreadMentionCount > 0
                val hasReactions = c.unreadReactionCount > 0
                val showNumericBadge = c.unread > 0
                val showMarkedDot = !showNumericBadge && c.isMarkedAsUnread
                if (hasMentions) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "@",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (hasReactions) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            com.secondream.novagram.ui.icons.PhosphorIcons.Smiley,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                if (showNumericBadge) {
                    Spacer(Modifier.width(8.dp))
                    // Muted chats get a neutral badge; only un-muted chats keep
                    // the accent colour, so a glance down the list shows which
                    // conversations actually pinged you (same language official
                    // Telegram uses). Pill shape with a min size grows cleanly
                    // for 2–3 digit counts instead of cramming "99+" into a dot.
                    val badgeBg = if (muted) MaterialTheme.colorScheme.surfaceVariant
                                  else MaterialTheme.colorScheme.primary
                    val badgeFg = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
                                  else MaterialTheme.colorScheme.onPrimary
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(badgeBg)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (c.unread > 99) "99+" else c.unread.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeFg,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (showMarkedDot) {
                    // "Mark as unread" with no actual unread messages:
                    // render a 10dp dot in accent / surfaceVariant so the
                    // user still sees the unread affordance even though
                    // there's nothing to count. Mirrors Telegram exactly.
                    Spacer(Modifier.width(8.dp))
                    val dotBg = if (muted) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(dotBg)
                    )
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
    // TopAppBar instead — replacing the old "Novagram" brand title.
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
        // primary pair, plus an optional Unisciti a Nova card when the
        // user hasn't joined the community channel yet.
        item {
            val ctx = LocalContext.current
            var novagramJoined by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(allChats.size) {
                if (novagramJoined == true) return@LaunchedEffect
                novagramJoined = runCatching {
                    val res = TdClient.searchPublicChats("novagram_messenger")
                    val match = res.firstOrNull { c ->
                        c.title.equals("Novagram", ignoreCase = true)
                    } ?: return@runCatching false
                    allChats.any { it.id == match.id }
                }.getOrDefault(false)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Storage shortcut spans the full width now that Nuova chat
                // has been removed (it duplicated the FAB at the bottom of
                // the screen — having both was just noise). Eugenio asked
                // for the cleaner version.
                HomeShortcutTile(
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.BookmarkSimple,
                    label = stringResource(R.string.home_storage_title),
                    onClick = { if (myUserId != 0L) onChatClick(myUserId) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (novagramJoined == false) {
                    // Full-width call to action only when the user isn't a
                    // Nova member yet. Once they join (or while we're
                    // still checking) we skip the row entirely so the home
                    // stays compact instead of leaving an empty placeholder.
                    HomeShortcutTile(
                        icon = com.secondream.novagram.ui.icons.PhosphorIcons.Megaphone,
                        label = stringResource(R.string.home_card_join_title),
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://t.me/novagram_messenger")
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
     * for the "Unisciti a Nova" call-to-action so it stands out
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
