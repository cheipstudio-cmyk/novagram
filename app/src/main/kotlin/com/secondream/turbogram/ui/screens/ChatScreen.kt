@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.turbogram.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.secondream.turbogram.td.TdClient
import com.secondream.turbogram.ui.components.MessageBubble
import com.secondream.turbogram.ui.theme.Ink
import com.secondream.turbogram.util.FileUtils
import com.secondream.turbogram.util.VoiceRecorder
import org.drinkless.tdlib.TdApi

@Composable
fun ChatScreen(chatId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<TdApi.Message>() }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var needMicPermission by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val recorder = remember { VoiceRecorder(context) }

    val chatTitle by produceState(initialValue = "Chat", chatId) {
        value = withContext(Dispatchers.IO) {
            runCatching { TdClient.getChat(chatId).title }.getOrDefault("Chat")
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) needMicPermission = false }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        showAttach = false
        uri?.let { handlePickedMedia(scope, context, chatId, it, asPhoto = true) }
    }

    val docLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        showAttach = false
        uri?.let { handlePickedMedia(scope, context, chatId, it, asPhoto = false) }
    }

    DisposableEffect(chatId) {
        scope.launch { runCatching { TdClient.openChat(chatId) } }
        onDispose {
            scope.launch { runCatching { TdClient.closeChat(chatId) } }
        }
    }

    // Initial history load
    LaunchedEffect(chatId) {
        loading = true
        runCatching {
            val res = TdClient.getChatHistory(chatId, 0L, 50)
            messages.clear()
            messages.addAll(res.messages.toList())
            val ids = messages.filter { !it.isOutgoing }.map { it.id }.toLongArray()
            if (ids.isNotEmpty()) runCatching { TdClient.viewMessages(chatId, ids) }
        }
        loading = false
    }

    // Listen for new messages in this chat
    LaunchedEffect(chatId) {
        TdClient.newMessages.collect { msg ->
            if (msg.chatId == chatId) {
                messages.add(0, msg)
                if (!msg.isOutgoing) {
                    runCatching { TdClient.viewMessages(chatId, longArrayOf(msg.id)) }
                }
            }
        }
    }

    // Auto load older when scroll near top of reversed list (bottom of memory)
    LaunchedEffect(listState, chatId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= messages.size - 5 && messages.isNotEmpty() && !loadingMore && !noMore }
            .collect {
                loadingMore = true
                val oldest = messages.lastOrNull()?.id ?: 0L
                runCatching {
                    val res = TdClient.getChatHistory(chatId, oldest, 30)
                    if (res.messages.isEmpty()) noMore = true
                    else messages.addAll(res.messages.toList())
                }
                loadingMore = false
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        chatTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
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
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { _, msg ->
                    MessageBubble(message = msg)
                }
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        input = ""
                        scope.launch { runCatching { TdClient.sendText(chatId, text) } }
                    }
                },
                onAttach = { showAttach = true },
                onMicDown = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        needMicPermission = true
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        runCatching {
                            recorder.start()
                            recording = true
                        }
                    }
                },
                onMicUp = { send ->
                    if (recording) {
                        recording = false
                        if (send) {
                            val res = recorder.stop()
                            if (res != null) {
                                scope.launch {
                                    runCatching {
                                        TdClient.sendVoiceNote(chatId, res.file.absolutePath, res.durationSeconds)
                                    }
                                }
                            }
                        } else {
                            recorder.cancel()
                        }
                    }
                },
                recording = recording
            )
        }
    }

    if (showAttach) {
        AttachSheet(
            onDismiss = { showAttach = false },
            onPickPhoto = {
                photoLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                    )
                )
            },
            onPickDocument = { docLauncher.launch(arrayOf("*/*")) }
        )
    }

    if (needMicPermission) {
        AlertDialog(
            onDismissRequest = { needMicPermission = false },
            title = { Text("Microfono") },
            text = { Text("Servono i permessi del microfono per registrare note vocali.") },
            confirmButton = {
                TextButton(onClick = { needMicPermission = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: (sendIt: Boolean) -> Unit,
    recording: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (recording) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(Ink.Error)
                )
                Spacer(Modifier.width(10.dp))
                Text("Registrazione in corso… rilascia per inviare, scorri per annullare",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.Muted,
                    modifier = Modifier.weight(1f),
                    maxLines = 2)
                MicButton(recording = true, onDown = onMicDown, onUp = onMicUp)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Outlined.AttachFile, null, tint = Ink.Muted)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Ink.SurfaceHi)
                        .border(0.5.dp, Ink.SurfaceLine, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (value.isEmpty()) {
                        Text("Messaggio", color = Ink.Faint, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Cream),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.Amber),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (value.isNotBlank()) {
                    IconButton(onClick = onSend) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Ink.Amber),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                null,
                                tint = Ink.OnAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    MicButton(recording = false, onDown = onMicDown, onUp = onMicUp)
                }
            }
        }
    }
}

@Composable
private fun MicButton(recording: Boolean, onDown: () -> Unit, onUp: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (recording) Ink.Error else Ink.Amber)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onDown()
                        val released = tryAwaitRelease()
                        onUp(released)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Mic, null, tint = if (recording) Ink.Cream else Ink.OnAmber)
    }
}

@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickDocument: () -> Unit
) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Ink.Surface
    ) {
        Column(modifier = Modifier.padding(20.dp).navigationBarsPadding()) {
            Text("Allega", style = MaterialTheme.typography.titleLarge, fontStyle = FontStyle.Italic)
            Spacer(Modifier.height(16.dp))
            AttachOption("Foto o video", Icons.Outlined.Image, onPickPhoto)
            Spacer(Modifier.height(4.dp))
            AttachOption("Documento o file", Icons.Outlined.Description, onPickDocument)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AttachOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.SurfaceHi)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(Ink.Bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Ink.Amber, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = Ink.Cream)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClick) {
            Text("Apri", color = Ink.Amber)
        }
    }
}

private fun handlePickedMedia(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    chatId: Long,
    uri: Uri,
    asPhoto: Boolean
) {
    scope.launch(Dispatchers.IO) {
        val file = FileUtils.copyUriToCache(context, uri) ?: return@launch
        if (asPhoto && isImage(file.name)) {
            runCatching { TdClient.sendPhoto(chatId, file.absolutePath) }
        } else {
            runCatching { TdClient.sendDocument(chatId, file.absolutePath) }
        }
    }
}

private fun isImage(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".heic")
}
