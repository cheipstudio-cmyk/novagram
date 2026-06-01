package com.secondream.novagram.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
                title = { Text(stringResource(R.string.new_group_title)) },
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
