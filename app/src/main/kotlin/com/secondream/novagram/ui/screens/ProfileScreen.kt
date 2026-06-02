@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
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
import com.secondream.novagram.R
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.util.FileUtils
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
    // When TDLib pushes a fresh copy of MY user record — e.g. once a newly
    // set profile photo finishes uploading server-side — swap in the new
    // avatar and trigger its big-variant download so it actually renders.
    // The picker callback can't do this synchronously: SetProfilePhoto
    // returns Ok before the upload completes, so an immediate getMe() still
    // sees the OLD photo. This is the reliable signal.
    LaunchedEffect(Unit) {
        TdClient.userUpdates.collect { u ->
            val cur = me
            if (cur != null && u.id == cur.id) {
                me = u
                val big = u.profilePhoto?.big
                photoFile = big
                if (big != null && !big.local.isDownloadingCompleted && !big.local.isDownloadingActive) {
                    runCatching { TdClient.downloadFile(big.id) }
                }
            }
        }
    }

    var profileCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // Optimistic local preview. SetProfilePhoto uploads asynchronously and the
    // circle only swaps to the server copy once UpdateUser pushes it back
    // (seconds later), so without this the avatar stays blank right after a
    // crop and the change looks like it silently failed — exactly the
    // "non lo carica nel cerchio" report. We show the freshly cropped file at
    // once; the downloaded server photo takes over the moment it lands, and on
    // an actual upload failure we drop the preview and surface a notice.
    var pendingAvatarPath by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // Route the picked image through the circular cropper first.
        if (uri != null) profileCropUri = uri
    }
    profileCropUri?.let { uri ->
        com.secondream.novagram.ui.components.ImageCropDialog(
            imageUri = uri,
            onDismiss = { profileCropUri = null },
            onCropped = { path ->
                profileCropUri = null
                pendingAvatarPath = path
                saving = true
                scope.launch {
                    // SetProfilePhoto is async; the userUpdates collector above
                    // swaps in (and downloads) the new avatar when TDLib pushes it.
                    val ok = runCatching { TdClient.setProfilePhoto(path, isPublic = true) }.isSuccess
                    saving = false
                    if (!ok) {
                        pendingAvatarPath = null
                        com.secondream.novagram.ui.components.NovaSnackbar.show(
                            R.string.photo_set_failed,
                            com.secondream.novagram.ui.icons.PhosphorIcons.X
                        )
                    }
                }
            }
        )
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
                            com.secondream.novagram.ui.icons.PhosphorIcons.CaretLeft,
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            // Avatar with a soft accent gradient ring.
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            )
                        )
                    )
                    .padding(3.dp)
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
                val serverReady = !path.isNullOrBlank() && photoFile?.local?.isDownloadingCompleted == true
                val pending = pendingAvatarPath
                if (serverReady) {
                    AsyncImage(
                        model = path,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else if (pending != null) {
                    // Optimistic: show the just-cropped local file until the
                    // uploaded server photo finishes downloading and takes over.
                    AsyncImage(
                        model = java.io.File(pending),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
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
                if (saving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                // Camera badge removed (was redundant): the whole avatar
                // box is the click target for the photo picker, and we
                // already show a "Cambia foto" label right below it. The
                // little circular icon inside the photo was just visual
                // noise covering the user's actual avatar.
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.profile_change_photo),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = !saving) {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )

            Spacer(Modifier.height(24.dp))

            // Grouped fields in a single rounded card for a cleaner look.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                val fieldColors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    focusedLabelColor = MaterialTheme.colorScheme.primary
                )
                OutlinedTextField(
                    value = firstName,
                    onValueChange = { firstName = it },
                    label = { Text(stringResource(R.string.profile_first_name)) },
                    leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.User, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lastName,
                    onValueChange = { lastName = it },
                    label = { Text(stringResource(R.string.profile_last_name)) },
                    leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.User, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                    label = { Text(stringResource(R.string.profile_username)) },
                    leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.At, null) },
                    supportingText = { Text(stringResource(R.string.profile_username_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it.take(140) },
                    label = { Text(stringResource(R.string.profile_bio)) },
                    leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Info, null) },
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = "+${me?.phoneNumber ?: ""}",
                    onValueChange = {},
                    label = { Text(stringResource(R.string.profile_phone)) },
                    leadingIcon = { Icon(com.secondream.novagram.ui.icons.PhosphorIcons.Phone, null) },
                    singleLine = true,
                    readOnly = true,
                    enabled = false,
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    )
                )
            }

            Spacer(Modifier.height(24.dp))
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
                        saving = false
                        if (failed) {
                            toast = failMsg ?: "Errore"
                        } else {
                            // Success → go back, as requested.
                            onBack()
                        }
                    }
                },
                enabled = !saving && me != null,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(
                    stringResource(R.string.action_save),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
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
