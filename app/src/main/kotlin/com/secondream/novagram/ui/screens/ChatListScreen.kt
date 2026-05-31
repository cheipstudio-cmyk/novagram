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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
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
            icon = com.secondream.novagram.ui.icons.PhosphorIcons.List,
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
    /**
     * Open a chat. The optional second arg is a message id to jump to;
     * it's non-null when the user arrives via the chat-info modal's
     * "Visualizza in chat" affordance, so the chat opens scrolled to
     * the picked media/message. Null on every other path (regular row
     * tap, notification deep link without a message anchor).
     */
    onChatClick: (chatId: Long, msgId: Long?) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onNewChat: () -> Unit = {}
) {
    val allChats by TdClient.chats.collectAsState()
    val scope = rememberCoroutineScope()
    val appearance by com.secondream.novagram.settings.AppSettings.appearance
        .collectAsState(initial = com.secondream.novagram.settings.AppearancePrefs())
    // Target of the chat-info modal triggered by tapping a row's avatar
    // (as opposed to the row body, which opens the chat normally). Set
    // by [ChatRow.onAvatarClick]; cleared when the user dismisses the
    // dialog. Tracking it as state lets the dialog live at the screen
    // level instead of being recreated per-row.
    var chatInfoTargetId by remember { mutableStateOf<Long?>(null) }
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

    // Collapsing pill-tabs on scroll. Parallax-style: scrolling DOWN
    // inside the chat list pushes the tab pill row up under the
    // TopAppBar (which itself stays full-size and always visible —
    // the title, search, avatar, settings stay reachable at all
    // times); scrolling UP brings the pills straight back into view.
    // Telegram-style "enter-always" behaviour: the pill row reappears
    // the instant the user reverses direction, regardless of scroll
    // depth, so it's never more than a small upward flick away.
    //
    // Mechanism: a NestedScrollConnection intercepts pre-scroll deltas
    // from the LazyColumn (Scaffold dispatches through the modifier
    // attached below). We track `headerOffsetPx` in [-naturalHeight, 0]
    // and a `Modifier.layout` on the pill Box reports a SHRUNK height
    // to its parent Column — so the chat list area genuinely expands,
    // it's not just a visual translation that leaves a gap. Only the
    // pill Box collapses; the TopAppBar sibling above it stays
    // unaffected (it doesn't participate in the layout shrinkage).
    val headerOffsetPx = remember { mutableIntStateOf(0) }
    var headerNaturalHeightPx by remember { mutableIntStateOf(0) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val natural = headerNaturalHeightPx
                if (natural == 0) return Offset.Zero
                val delta = available.y.toInt()
                val cur = headerOffsetPx.intValue
                val newOffset = (cur + delta).coerceIn(-natural, 0)
                val consumed = newOffset - cur
                headerOffsetPx.intValue = newOffset
                return Offset(0f, consumed.toFloat())
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(nestedScrollConnection),
        floatingActionButton = {
            // Springy press feedback: the FAB dips to 85% under the
            // finger and springs back on release. dampingRatio = 0.5f
            // gives a clearly perceivable rebound (one-and-a-half
            // overshoots) without feeling toy-ish; StiffnessMediumLow
            // stretches the motion out to ~350ms perceptual duration
            // so the bounce reads as deliberate physics, not a quick
            // twitch. graphicsLayer-only invalidation keeps the scale
            // off the recomposition path — animation cost is one
            // matrix multiply per frame.
            val fabInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val fabPressed by fabInteraction.collectIsPressedAsState()
            val fabScale by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (fabPressed) 0.85f else 1f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.50f,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
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
                            IconButton(onClick = onOpenProfile) {
                                Avatar(
                                    file = myAvatarFile,
                                    fallbackText = myInitial,
                                    bgColor = MaterialTheme.colorScheme.surfaceVariant,
                                    size = 32.dp
                                )
                            }
                            // Settings gear — gets an accent dot at top-
                            // right when UpdateChecker has flagged a
                            // newer release. The download button used
                            // to live in this slot; we moved the actual
                            // update affordance into the settings screen
                            // (Info section) so the topbar stays clean.
                            val updateAvailable by com.secondream.novagram.update
                                .UpdateChecker.updateAvailable
                                .collectAsState()
                            androidx.compose.foundation.layout.Box {
                                IconButton(onClick = onOpenSettings) {
                                    Icon(
                                        com.secondream.novagram.ui.icons.PhosphorIcons.Gear,
                                        contentDescription = stringResource(R.string.action_settings)
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
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
                com.secondream.novagram.ui.components.OfflineBanner()
                // Per-tab unread totals (sum of unread across chats in
                // that category). MUTED chats are excluded from the
                // aggregate: their per-row badge still shows so the user
                // can see "you have N unread in this muted group", but
                // they don't inflate the top-of-tab count. Matches the
                // user's mental model that the tab badge represents
                // "stuff I should look at" — muted chats by definition
                // are stuff they've deprioritized. Archive tab follows
                // the same rule (an archived AND muted chat shouldn't
                // contribute either).
                val tabBadges = remember(allChats, tabs) {
                    tabs.map { spec ->
                        when {
                            spec.isAll -> allChats.filter { !it.isArchived && !it.isMuted }.sumOf { it.unread }
                            spec.isArchive -> allChats.filter { it.isArchived && !it.isMuted }.sumOf { it.unread }
                            else -> allChats.filter { !it.isArchived && !it.isMuted && it.kind == spec.kind }.sumOf { it.unread }
                        }
                    }
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val natural = placeable.height
                            if (headerNaturalHeightPx != natural) {
                                headerNaturalHeightPx = natural
                            }
                            val offset = headerOffsetPx.intValue
                            val visibleH = (natural + offset).coerceIn(0, natural)
                            layout(placeable.width, visibleH) {
                                placeable.place(0, offset)
                            }
                        }
                ) {
                    PillTabs(
                        icons = tabs.map { it.icon },
                        contentDescriptions = tabs.map { stringResource(it.labelRes) },
                        badges = tabBadges,
                        selected = selectedTab.coerceIn(0, tabs.lastIndex),
                        onSelect = { selectedTab = it }
                    )
                }
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
                // Saved Messages is the first item in pageChats (the
                // sort upstream pinned it there): we render the whole
                // list with a single items(pageChats) block, so the
                // Saved Messages row scrolls naturally with the rest
                // of the chat list. Previous v0.10.55 implementation
                // wrapped it in a stickyHeader so it stayed anchored
                // to the top regardless of scroll — Eugenio asked for
                // that behaviour to be removed: he wants the row to
                // behave like any other chat entry and disappear off-
                // screen as the user scrolls down.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(pageChats, key = { it.id }) { c ->
                        ChatRow(
                            c,
                            onClick = { onChatClick(c.id, null) },
                            onLongClick = { chatActionTarget = c },
                            modifier = Modifier.animateItem(),
                            myUserId = myUserId,
                            onAvatarClick = { chatInfoTargetId = c.id }
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
                                    onOpen = { onChatClick(chat.id, null) },
                                    onJoin = {
                                        scope.launch {
                                            runCatching { TdClient.joinChat(chat.id) }
                                            onChatClick(chat.id, null)
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
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = stringResource(R.string.leave_group_confirm, target.title),
            onDismiss = { leaveConfirmTarget = null },
            tiles = listOf(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(
                        if (target.kind == ChatKind.Channel) R.string.action_leave_channel
                        else R.string.action_leave_group
                    ),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                    destructive = true,
                    onClick = {
                        val cid = target.id
                        leaveConfirmTarget = null
                        scope.launch { runCatching { TdClient.leaveChat(cid) } }
                    }
                ),
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.delete_chat_cancel),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    onClick = { leaveConfirmTarget = null }
                )
            )
        )
    }

    deleteConfirmTarget?.let { target ->
        val isPrivate = target.kind == ChatKind.Private
        val deleteTiles = buildList {
            add(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.action_delete_chat),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                    destructive = true,
                    onClick = {
                        val cid = target.id
                        deleteConfirmTarget = null
                        scope.launch {
                            runCatching {
                                TdClient.deleteChatHistory(cid, removeFromChatList = true, revoke = false)
                            }
                        }
                    }
                )
            )
            if (isPrivate) {
                add(
                    com.secondream.novagram.ui.components.ActionTile(
                        label = stringResource(R.string.delete_chat_for_everyone),
                        icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                        destructive = true,
                        onClick = {
                            val cid = target.id
                            deleteConfirmTarget = null
                            scope.launch {
                                runCatching {
                                    TdClient.deleteChatHistory(cid, removeFromChatList = true, revoke = true)
                                }
                            }
                        }
                    )
                )
            }
            add(
                com.secondream.novagram.ui.components.ActionTile(
                    label = stringResource(R.string.delete_chat_cancel),
                    icon = com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    onClick = { deleteConfirmTarget = null }
                )
            )
        }
        com.secondream.novagram.ui.components.ActionBottomSheet(
            title = stringResource(R.string.delete_chat_confirm_title),
            description = stringResource(R.string.delete_chat_confirm_body),
            onDismiss = { deleteConfirmTarget = null },
            tiles = deleteTiles,
            tilesPerRow = if (isPrivate) 3 else 2
        )
    }
    // CHAT-INFO MODAL triggered by avatar-tap on a row. Reuses the same
    // ChatInfoDialog as the in-chat title-bar tap so the surface is
    // identical (avatar + bio/username/phone + media gallery). The
    // "Visualizza in chat" affordance inside the gallery becomes a
    // chat navigation here, anchored on the picked message so the
    // chat opens scrolled to it.
    chatInfoTargetId?.let { targetId ->
        com.secondream.novagram.ui.screens.ChatInfoDialog(
            chatId = targetId,
            onDismiss = { chatInfoTargetId = null },
            onJumpToMessage = { mid ->
                chatInfoTargetId = null
                onChatClick(targetId, mid)
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
    // Tiles built dynamically from the chat's state. Pin/unpin uses
    // the PushPin icon (filled when active, struck-through when not —
    // here we just toggle the action label since we only have one
    // PushPin glyph). Mute uses Bell ↔ BellSlash to mirror the topbar
    // action. Archive uses the Archive icon. Destructive tile (leave
    // for groups/channels, delete for privates/secrets) gets the
    // destructive treatment.
    val tiles = buildList {
        add(
            com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(
                    if (isPinned) R.string.action_unpin_chat else R.string.action_pin_chat
                ),
                icon = com.secondream.novagram.ui.icons.PhosphorIcons.PushPin,
                onClick = onTogglePin
            )
        )
        add(
            com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(
                    if (isMuted) R.string.action_unmute_chat else R.string.action_mute_chat
                ),
                icon = if (isMuted)
                    com.secondream.novagram.ui.icons.PhosphorIcons.Bell
                else com.secondream.novagram.ui.icons.PhosphorIcons.BellSlash,
                onClick = onToggleMute
            )
        )
        add(
            com.secondream.novagram.ui.components.ActionTile(
                label = stringResource(
                    if (isArchived) R.string.action_unarchive_chat else R.string.action_archive_chat
                ),
                icon = com.secondream.novagram.ui.icons.PhosphorIcons.Archive,
                onClick = onToggleArchive
            )
        )
        // Groups + channels can't be "deleted" from your side — only
        // left. Telegram nukes the chat from your list and stops
        // delivering its messages. Private chats can still be deleted
        // (history wipe + remove from list). Secret chats behave like
        // privates here: 1-to-1 so no "leave", only delete.
        when (chatKind) {
            ChatKind.Group, ChatKind.Channel -> {
                add(
                    com.secondream.novagram.ui.components.ActionTile(
                        label = stringResource(
                            if (chatKind == ChatKind.Channel) R.string.action_leave_channel
                            else R.string.action_leave_group
                        ),
                        icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                        destructive = true,
                        onClick = onLeaveRequest
                    )
                )
            }
            ChatKind.Private, ChatKind.Secret -> {
                add(
                    com.secondream.novagram.ui.components.ActionTile(
                        label = stringResource(R.string.action_delete_chat),
                        icon = com.secondream.novagram.ui.icons.PhosphorIcons.Trash,
                        destructive = true,
                        onClick = onDeleteRequest
                    )
                )
            }
        }
    }
    com.secondream.novagram.ui.components.ActionBottomSheet(
        title = chatTitle,
        onDismiss = onDismiss,
        tiles = tiles
    )
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
    myUserId: Long = 0L,
    /** Fires when the user taps the AVATAR specifically (not the rest
     *  of the row). Routes to the chat-info modal so the user can peek
     *  at bio / shared media / links without entering the chat. The
     *  body-tap [onClick] still opens the chat as usual. */
    onAvatarClick: () -> Unit = onClick
) {
    val isSavedMessages = myUserId != 0L && c.id == myUserId
    Row(
        modifier = modifier
            .fillMaxWidth()
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
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAvatarClick),
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
                size = 48.dp,
                modifier = Modifier.clickable(onClick = onAvatarClick)
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
                            onClick = { onChatClick(summary.id, null) }
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
                    onClick = { if (myUserId != 0L) onChatClick(myUserId, null) },
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
