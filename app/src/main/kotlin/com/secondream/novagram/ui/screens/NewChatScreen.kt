@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.secondream.novagram.R
import com.secondream.novagram.contacts.PhoneBook
import com.secondream.novagram.td.PhoneContact
import com.secondream.novagram.td.TdClient
import org.drinkless.tdlib.TdApi

private data class TgContact(val user: TdApi.User)

/** Mirror of a phone-book row enriched with the matching Telegram user id (0 = no Telegram). */
private data class MatchedPhoneContact(
    val contact: PhoneContact,
    val telegramUserId: Long,
    // Profile photo file for the matched Telegram user, if any. Populated
    // alongside `telegramUserId` once `importContacts` returns; lets the
    // ContactRow show the real avatar instead of the letter fallback.
    val photoFile: TdApi.File? = null
)

@Composable
fun NewChatScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    // Pending tap target: a userId waiting for the user to decide
    // "normal chat" vs "secret chat" via the dialog below. Null when
    // no choice is in flight; set when any contact row is tapped.
    var pendingChatUserId by remember { mutableStateOf<Long?>(null) }

    // Telegram contacts
    val telegramContacts = remember { mutableStateListOf<TgContact>() }
    var loadingTelegram by remember { mutableStateOf(false) }

    // Phone contacts
    val phoneContacts = remember { mutableStateListOf<MatchedPhoneContact>() }
    var loadingPhone by remember { mutableStateOf(false) }
    var syncingPhone by remember { mutableStateOf(false) }
    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasContactsPermission = granted }

    // Initial load: Telegram contacts.
    LaunchedEffect(Unit) {
        loadingTelegram = true
        runCatching {
            val ids = TdClient.getContacts()
            telegramContacts.clear()
            for (id in ids.take(500)) {
                val u = runCatching { TdClient.getUser(id) }.getOrNull() ?: continue
                telegramContacts.add(TgContact(u))
            }
        }
        loadingTelegram = false
    }

    // Phone tab: read + sync on demand (first time the user switches to it).
    LaunchedEffect(selectedTab, hasContactsPermission) {
        if (selectedTab != 1 || !hasContactsPermission || phoneContacts.isNotEmpty()) return@LaunchedEffect
        loadingPhone = true
        val raw = withContext(Dispatchers.IO) { PhoneBook.read(context) }
        // Show the local list immediately with telegramUserId=0 (unknown), then
        // refine it after importContacts comes back from the server.
        phoneContacts.clear()
        for (c in raw) phoneContacts.add(MatchedPhoneContact(c, 0L))
        loadingPhone = false

        syncingPhone = true
        val matchIds = runCatching { TdClient.importContacts(raw) }.getOrDefault(LongArray(0))
        if (matchIds.size == phoneContacts.size) {
            for (i in phoneContacts.indices) {
                if (matchIds[i] != 0L) {
                    val photo = TdClient.getCachedUser(matchIds[i])?.profilePhoto?.small
                    phoneContacts[i] = phoneContacts[i].copy(
                        telegramUserId = matchIds[i],
                        photoFile = photo
                    )
                }
            }
        }
        syncingPhone = false
    }

    val filteredTelegram = remember(telegramContacts.toList(), query) {
        if (query.isBlank()) telegramContacts.toList()
        else telegramContacts.filter { c ->
            val full = "${c.user.firstName} ${c.user.lastName}".trim()
            full.contains(query, ignoreCase = true) ||
                (c.user.usernames?.editableUsername?.contains(query, ignoreCase = true) == true)
        }
    }
    val filteredPhone = remember(phoneContacts.toList(), query) {
        if (query.isBlank()) phoneContacts.toList()
        else phoneContacts.filter { p ->
            val name = "${p.contact.firstName} ${p.contact.lastName}".trim()
            name.contains(query, ignoreCase = true) || p.contact.phoneNumber.contains(query)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.new_chat_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontStyle = FontStyle.Italic
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SearchInline(value = query, onValueChange = { query = it })
                }
                PillTabs(
                    icons = listOf(
                        com.secondream.novagram.ui.icons.PhosphorIcons.User,
                        com.secondream.novagram.ui.icons.PhosphorIcons.Phone
                    ),
                    contentDescriptions = listOf(
                        stringResource(R.string.new_chat_tab_telegram),
                        stringResource(R.string.new_chat_tab_phone)
                    ),
                    selected = selectedTab,
                    onSelect = { selectedTab = it }
                )
            }
        }
    ) { padding ->
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = selectedTab,
            pageCount = { 2 }
        )
        LaunchedEffect(selectedTab) {
            if (pagerState.currentPage != selectedTab && pagerState.targetPage != selectedTab) {
                pagerState.animateScrollToPage(selectedTab)
            }
        }
        LaunchedEffect(pagerState) {
            androidx.compose.runtime.snapshotFlow { pagerState.targetPage }.collect { target ->
                if (selectedTab != target) selectedTab = target
            }
        }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            when (page) {
                0 -> TelegramList(
                    contacts = filteredTelegram,
                    loading = loadingTelegram,
                    onOpen = { user ->
                        // Defer the actual chat open: the user picks
                        // Normal vs Segreta in the dialog below.
                        pendingChatUserId = user.id
                    }
                )
                1 -> PhoneList(
                    contacts = filteredPhone,
                    loading = loadingPhone,
                    syncing = syncingPhone,
                    hasPermission = hasContactsPermission,
                    onGrantPermission = {
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    },
                    onOpenTelegram = { userId ->
                        pendingChatUserId = userId
                    },
                    onInviteSms = { phone ->
                        runCatching {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                            intent.putExtra(
                                "sms_body",
                                "Ti scrivo da Novagram. Provala anche tu: https://t.me/novagram_messenger"
                            )
                            context.startActivity(intent)
                        }
                    }
                )
            }
        }
    }

    // Chat-type chooser: shown after the user taps a contact row. Two
    // outcomes, both close the dialog and the New Chat screen by
    // forwarding the new chat id up. Normal chat = standard private
    // (createPrivateChat). Secret chat = end-to-end encrypted
    // (CreateNewSecretChat), Telegram counts this as a separate row in
    // the list and the recipient gets a "starting secret chat…" prompt.
    val pendingUid = pendingChatUserId
    if (pendingUid != null) {
        AlertDialog(
            onDismissRequest = { pendingChatUserId = null },
            title = { Text(stringResource(R.string.new_chat_choose_type_title)) },
            text = { Text(stringResource(R.string.new_chat_choose_type_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingChatUserId = null
                    scope.launch {
                        val chat = runCatching { TdClient.createPrivateChat(pendingUid, true) }.getOrNull()
                        if (chat != null) onOpenChat(chat.id)
                    }
                }) { Text(stringResource(R.string.new_chat_type_normal)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingChatUserId = null
                    scope.launch {
                        // Creating a secret chat is a multi-step
                        // handshake on TDLib's side: it allocates the
                        // SecretChat session, exchanges keys, and only
                        // then emits the UpdateNewChat row for the
                        // local list. If we navigate to the chat the
                        // instant CreateNewSecretChat returns, the
                        // chat row may not yet be in the cache and
                        // ChatScreen reads will return nulls. Give
                        // TDLib a beat to settle the row before the
                        // navigation fires.
                        val chat = runCatching {
                            TdClient.createNewSecretChat(pendingUid)
                        }.onFailure {
                            android.widget.Toast.makeText(
                                context,
                                "Impossibile creare la chat segreta",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }.getOrNull()
                        if (chat != null) {
                            kotlinx.coroutines.delay(400)
                            onOpenChat(chat.id)
                        }
                    }
                }) { Text(stringResource(R.string.new_chat_type_secret)) }
            }
        )
    }
}

@Composable
private fun PillTabs(
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    contentDescriptions: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    // Icon-only sliding-pill tabs, mirror of the home chat-list ones.
    // Container is wrap-content + horizontally centered, each pill is
    // 44x36dp with a 20dp icon. The accent pill slides between
    // positions on selection via animateFloatAsState with the same
    // low-bouncy spring used elsewhere.
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val animatedSelected by androidx.compose.animation.core.animateFloatAsState(
        targetValue = selected.toFloat(),
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "newchat-tab-slide"
    )
    val tabWidth = 44.dp
    val tabHeight = 36.dp
    val tabGap = 4.dp
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
                .padding(4.dp)
        ) {
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
                    val iconColor = androidx.compose.ui.graphics.lerp(onPrimary, onMuted, distance)
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(width = tabWidth, height = tabHeight)
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
                }
            }
        }
    }
}

@Composable
private fun SearchInline(value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                com.secondream.novagram.ui.icons.PhosphorIcons.MagnifyingGlass, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.new_chat_search_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun TelegramList(
    contacts: List<TgContact>,
    loading: Boolean,
    onOpen: (TdApi.User) -> Unit
) {
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    if (contacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.new_chat_empty_telegram),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(contacts, key = { it.user.id }) { c ->
            ContactRow(
                title = "${c.user.firstName} ${c.user.lastName}".trim().ifEmpty { c.user.usernames?.editableUsername ?: "?" },
                subtitle = c.user.usernames?.editableUsername?.let { "@$it" } ?: "",
                onClick = { onOpen(c.user) },
                trailing = null,
                photoFile = c.user.profilePhoto?.small,
                chatIdForBg = c.user.id
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 80.dp)
            )
        }
    }
}

@Composable
private fun PhoneList(
    contacts: List<MatchedPhoneContact>,
    loading: Boolean,
    syncing: Boolean,
    hasPermission: Boolean,
    onGrantPermission: () -> Unit,
    onOpenTelegram: (Long) -> Unit,
    onInviteSms: (String) -> Unit
) {
    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.new_chat_permission_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.new_chat_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGrantPermission) {
                Text(stringResource(R.string.action_grant_permission))
            }
        }
        return
    }
    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    if (contacts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.new_chat_empty_phone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = syncing, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.new_chat_syncing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(contacts, key = { it.contact.deviceContactId.toString() + it.contact.phoneNumber }) { p ->
                val isOnTelegram = p.telegramUserId != 0L
                val title = "${p.contact.firstName} ${p.contact.lastName}".trim()
                    .ifEmpty { p.contact.phoneNumber }
                ContactRow(
                    title = title,
                    subtitle = p.contact.phoneNumber,
                    onClick = {
                        if (isOnTelegram) onOpenTelegram(p.telegramUserId)
                        else onInviteSms(p.contact.phoneNumber)
                    },
                    trailing = {
                        if (isOnTelegram) {
                            Text(
                                stringResource(R.string.new_chat_phone_on_telegram),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                stringResource(R.string.new_chat_phone_invite),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    photoFile = p.photoFile,
                    chatIdForBg = p.telegramUserId
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 80.dp)
                )
            }
        }
    }
}

@Composable
private fun ContactRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)?,
    photoFile: TdApi.File? = null,
    chatIdForBg: Long = 0L
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Real profile photo via the shared Avatar component — falls
        // back to a coloured circle with the first letter when the
        // server hasn't sent a photo file (or hasn't been downloaded
        // yet). Same affordance the chat list rows use, so phone-book
        // entries that ARE on Telegram now look like contacts rather
        // than letter placeholders.
        com.secondream.novagram.ui.components.Avatar(
            file = photoFile,
            fallbackText = title,
            bgColor = if (chatIdForBg != 0L)
                com.secondream.novagram.ui.screens.avatarBackgroundFor(chatIdForBg)
            else MaterialTheme.colorScheme.surfaceVariant,
            size = 48.dp
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
