package com.secondream.novagram.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.secondream.novagram.R
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.components.Avatar
import com.secondream.novagram.ui.components.NovaSnackbar
import com.secondream.novagram.ui.icons.PhosphorIcons
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Create-group flow: pick a name, multi-select members from the Telegram
 * contact list, tap Crea. Lands in the freshly created group. The group
 * photo isn't set here — it can be added afterwards from the chat's
 * "modifica gruppo" sheet (which already supports it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val contacts = remember { mutableStateListOf<TdApi.User>() }
    var loading by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<Long>() }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val ids = TdClient.getContacts()
            contacts.clear()
            for (id in ids.take(500)) {
                val u = runCatching { TdClient.getUser(id) }.getOrNull() ?: continue
                contacts.add(u)
            }
        }
        loading = false
    }

    fun displayName(u: TdApi.User): String {
        val nm = "${u.firstName} ${u.lastName}".trim()
        if (nm.isNotBlank()) return nm
        return u.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "—"
    }

    val filtered = contacts.filter { u ->
        if (query.isBlank()) true
        else {
            val nm = "${u.firstName} ${u.lastName}".trim()
            val un = u.usernames?.activeUsernames?.firstOrNull() ?: ""
            nm.contains(query, ignoreCase = true) || un.contains(query, ignoreCase = true)
        }
    }
    val canCreate = groupName.isNotBlank() && selected.isNotEmpty() && !creating

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.new_group_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontStyle = FontStyle.Italic
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(PhosphorIcons.CaretLeft, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        enabled = canCreate,
                        onClick = {
                            creating = true
                            scope.launch {
                                val chatId = TdClient.createGroup(selected.toList(), groupName.trim())
                                creating = false
                                if (chatId != null) {
                                    NovaSnackbar.show(
                                        R.string.snack_group_created,
                                        PhosphorIcons.Check
                                    )
                                    onOpenChat(chatId)
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.new_group_create)) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                singleLine = true,
                leadingIcon = {
                    Icon(PhosphorIcons.UsersThree, contentDescription = null)
                },
                label = { Text(stringResource(R.string.new_group_name)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (selected.isNotEmpty()) {
                Text(
                    stringResource(R.string.new_group_selected, selected.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = {
                    Icon(PhosphorIcons.MagnifyingGlass, contentDescription = null)
                },
                placeholder = { Text(stringResource(R.string.search_action)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { u ->
                        val isSel = selected.contains(u.id)
                        val name = displayName(u)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSel) selected.remove(u.id) else selected.add(u.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(
                                file = u.profilePhoto?.small,
                                fallbackText = name,
                                size = 44.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Checkbox(
                                checked = isSel,
                                onCheckedChange = {
                                    if (isSel) selected.remove(u.id) else selected.add(u.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Group-creation body for use as a TAB inside NewChatScreen (no Scaffold/app
 * bar of its own). Member search reuses NewChatScreen's header search [query];
 * the Crea button lives at the bottom of the content. Lands in the new group
 * via [onOpenChat].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupContent(
    query: String,
    onOpenChat: (Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    val contacts = remember { mutableStateListOf<TdApi.User>() }
    var loading by remember { mutableStateOf(true) }
    val selected = remember { mutableStateListOf<Long>() }
    var creating by remember { mutableStateOf(false) }
    var isPublic by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var statusRes by remember { mutableStateOf(0) }
    var permsOpen by remember { mutableStateOf(false) }
    var defaultPerms by remember { mutableStateOf<TdApi.ChatPermissions?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var photoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var photoPath by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            photoUri = uri
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                photoPath = com.secondream.novagram.util.FileUtils
                    .copyUriToCache(ctx, uri)?.absolutePath
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            val ids = TdClient.getContacts()
            contacts.clear()
            for (id in ids.take(500)) {
                val u = runCatching { TdClient.getUser(id) }.getOrNull() ?: continue
                contacts.add(u)
            }
        }
        loading = false
    }

    LaunchedEffect(username, isPublic) {
        if (!isPublic) { available = null; statusRes = 0; checking = false; return@LaunchedEffect }
        val u = username.trim()
        if (u.isEmpty()) { available = null; statusRes = 0; checking = false; return@LaunchedEffect }
        if (u.length < 5) { available = false; statusRes = R.string.group_username_short; checking = false; return@LaunchedEffect }
        checking = true; statusRes = 0
        delay(450)
        val res = runCatching { TdClient.checkChatUsername(0, u) }.getOrNull()
        checking = false
        when (res) {
            is TdApi.CheckChatUsernameResultOk -> { available = true; statusRes = R.string.group_username_ok }
            is TdApi.CheckChatUsernameResultUsernameOccupied -> { available = false; statusRes = R.string.group_username_taken }
            is TdApi.CheckChatUsernameResultUsernameInvalid -> { available = false; statusRes = R.string.group_username_invalid }
            null -> { available = null; statusRes = 0 }
            else -> { available = false; statusRes = R.string.group_username_unavailable }
        }
    }

    fun displayName(u: TdApi.User): String {
        val nm = "${u.firstName} ${u.lastName}".trim()
        if (nm.isNotBlank()) return nm
        return u.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "—"
    }

    val filtered = contacts.filter { u ->
        if (query.isBlank()) true
        else {
            val nm = "${u.firstName} ${u.lastName}".trim()
            val un = u.usernames?.activeUsernames?.firstOrNull() ?: ""
            nm.contains(query, ignoreCase = true) || un.contains(query, ignoreCase = true)
        }
    }
    val canCreate = groupName.isNotBlank() && selected.isNotEmpty() && !creating &&
        (!isPublic || available == true)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { photoPicker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (photoUri != null) {
                    AsyncImage(
                        model = photoUri,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        PhosphorIcons.Camera,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                singleLine = true,
                label = { Text(stringResource(R.string.new_group_name)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !isPublic,
                onClick = { isPublic = false },
                label = { Text(stringResource(R.string.group_type_private)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
            FilterChip(
                selected = isPublic,
                onClick = { isPublic = true },
                label = { Text(stringResource(R.string.group_type_public)) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
        AnimatedVisibility(visible = isPublic) {
            Column {
                OutlinedTextField(
                    value = username,
                    onValueChange = { v -> username = v.filter { it.isLetterOrDigit() || it == '_' } },
                    label = { Text(stringResource(R.string.group_username_label)) },
                    prefix = { Text("t.me/") },
                    singleLine = true,
                    isError = available == false,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        when {
                            checking -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                            )
                            available == true -> Icon(
                                PhosphorIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            else -> {}
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (statusRes != 0) {
                    Text(
                        stringResource(statusRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (available == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { permsOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                PhosphorIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (defaultPerms != null) stringResource(R.string.new_group_perms_custom)
                else stringResource(R.string.new_group_default_perms),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (selected.isNotEmpty()) {
            Text(
                stringResource(R.string.new_group_selected, selected.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { u ->
                        val isSel = selected.contains(u.id)
                        val name = displayName(u)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isSel) selected.remove(u.id) else selected.add(u.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(
                                file = u.profilePhoto?.small,
                                fallbackText = name,
                                size = 44.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Checkbox(
                                checked = isSel,
                                onCheckedChange = {
                                    if (isSel) selected.remove(u.id) else selected.add(u.id)
                                }
                            )
                        }
                    }
                }
            }
        }
        Button(
            enabled = canCreate,
            onClick = {
                creating = true
                scope.launch {
                    val chatId = if (isPublic)
                        TdClient.createPublicGroup(groupName.trim(), username.trim(), selected.toList())
                    else
                        TdClient.createGroup(selected.toList(), groupName.trim())
                    if (chatId != null) {
                        defaultPerms?.let { runCatching { TdClient.setChatPermissions(chatId, it) } }
                        photoPath?.let { p -> runCatching { TdClient.setChatPhoto(chatId, p) } }
                        creating = false
                        NovaSnackbar.show(R.string.snack_group_created, PhosphorIcons.Check)
                        onOpenChat(chatId)
                    } else {
                        creating = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.new_group_create))
        }
        if (permsOpen) {
            GroupPermissionsDialog(
                title = stringResource(R.string.new_group_default_perms),
                initial = defaultPerms ?: buildGroupPermissions(true, true, true, true, true),
                onSave = { defaultPerms = it },
                onDismiss = { permsOpen = false }
            )
        }
    }
}
