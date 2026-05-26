@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.secondream.cheipgram.R
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.util.FileUtils
import org.drinkless.tdlib.TdApi

@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var me by remember { mutableStateOf<TdApi.User?>(null) }
    var fullInfo by remember { mutableStateOf<TdApi.UserFullInfo?>(null) }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var photoFile by remember { mutableStateOf<TdApi.File?>(null) }
    var saving by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Load my profile when the screen opens.
    LaunchedEffect(Unit) {
        runCatching {
            val u = TdClient.getMe()
            me = u
            firstName = u.firstName
            lastName = u.lastName
            // active usernames live under usernames.editableUsername, with the
            // older user.username field deprecated. Fall back gracefully.
            username = u.usernames?.editableUsername ?: ""
            photoFile = u.profilePhoto?.big
            val full = runCatching { TdClient.getUserFullInfo(u.id) }.getOrNull()
            fullInfo = full
            bio = full?.bio?.text ?: ""
            // Trigger photo download if not present
            val pf = u.profilePhoto?.big
            if (pf != null && !pf.local.isDownloadingCompleted && !pf.local.isDownloadingActive) {
                runCatching { TdClient.downloadFile(pf.id) }
            }
        }
    }
    // React to file download progress for the avatar.
    LaunchedEffect(photoFile?.id) {
        val pf = photoFile ?: return@LaunchedEffect
        TdClient.fileUpdates.collect { updated ->
            if (updated.id == pf.id) photoFile = updated
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            saving = true
            val local = withContext(Dispatchers.IO) { FileUtils.copyUriToCache(context, uri) }
            if (local != null) {
                runCatching { TdClient.setProfilePhoto(local.absolutePath, isPublic = true) }
                    .onSuccess {
                        // Refresh me to get the new chatPhoto.
                        val u = runCatching { TdClient.getMe() }.getOrNull()
                        if (u != null) {
                            me = u
                            photoFile = u.profilePhoto?.big
                        }
                    }
            }
            saving = false
        }
    }

    val errUsernameTaken = stringResource(R.string.profile_username_taken)
    val savedMsg = stringResource(R.string.profile_saved)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.profile_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontStyle = FontStyle.Italic
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !saving) {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                val path = photoFile?.local?.path
                if (!path.isNullOrBlank() && photoFile?.local?.isDownloadingCompleted == true) {
                    AsyncImage(
                        model = path,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val initial = me?.firstName?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    Text(
                        initial,
                        fontSize = 56.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Light
                    )
                }
                AnimatedVisibility(
                    visible = saving,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                // Camera badge bottom-right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.profile_change_photo),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.profile_change_photo),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(28.dp))

            // First name
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text(stringResource(R.string.profile_first_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            // Last name
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text(stringResource(R.string.profile_last_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            // Username
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                label = { Text(stringResource(R.string.profile_username)) },
                supportingText = { Text(stringResource(R.string.profile_username_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            // Bio
            OutlinedTextField(
                value = bio,
                onValueChange = { bio = it.take(140) },
                label = { Text(stringResource(R.string.profile_bio)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            Spacer(Modifier.height(14.dp))
            // Phone (read-only)
            OutlinedTextField(
                value = "+${me?.phoneNumber ?: ""}",
                onValueChange = {},
                label = { Text(stringResource(R.string.profile_phone)) },
                singleLine = true,
                readOnly = true,
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                )
            )

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = {
                    val u = me ?: return@Button
                    saving = true
                    toast = null
                    scope.launch {
                        val firstChanged = firstName.trim() != u.firstName
                        val lastChanged = lastName.trim() != u.lastName
                        val currentUsername = u.usernames?.editableUsername ?: ""
                        val usernameChanged = username.trim() != currentUsername
                        val currentBio = fullInfo?.bio?.text ?: ""
                        val bioChanged = bio.trim() != currentBio

                        var failed = false
                        var failMsg: String? = null
                        if (firstChanged || lastChanged) {
                            runCatching {
                                TdClient.setName(firstName.trim(), lastName.trim())
                            }.onFailure { failed = true; failMsg = it.message }
                        }
                        if (!failed && usernameChanged) {
                            runCatching { TdClient.setUsername(username.trim()) }
                                .onFailure {
                                    failed = true
                                    failMsg = errUsernameTaken
                                }
                        }
                        if (!failed && bioChanged) {
                            runCatching { TdClient.setBio(bio.trim()) }
                                .onFailure { failed = true; failMsg = it.message }
                        }
                        toast = if (failed) failMsg ?: "Errore" else savedMsg
                        saving = false
                    }
                },
                enabled = !saving && me != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_save))
            }

            AnimatedVisibility(visible = toast != null, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        toast.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (toast == savedMsg) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
