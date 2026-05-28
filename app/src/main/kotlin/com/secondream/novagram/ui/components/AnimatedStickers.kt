package com.secondream.novagram.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.secondream.novagram.td.TdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * TGS is "Telegram Animated Sticker" — gzipped Lottie JSON with a .tgs
 * extension. We trigger TDLib to download the file (if not already cached),
 * gunzip the bytes off the main thread, and hand the resulting JSON string
 * to Lottie. The animation loops forever; if the file isn't downloaded yet
 * or the JSON fails to parse, we fall back to the sticker's emoji.
 */
@Composable
fun AnimatedTgsSticker(file: TdApi.File, fallbackEmoji: String) {
    var json by remember(file.id) { mutableStateOf<String?>(null) }
    var currentFile by remember(file.id) { mutableStateOf(file) }

    LaunchedEffect(file.id) {
        val downloaded = runCatching { TdClient.downloadFile(file.id) }.getOrNull()
        if (downloaded != null) currentFile = downloaded
        TdClient.fileUpdates.collect { upd ->
            if (upd.id == file.id) currentFile = upd
        }
    }

    LaunchedEffect(currentFile.local.path, currentFile.local.isDownloadingCompleted) {
        if (currentFile.local.isDownloadingCompleted && currentFile.local.path.isNotBlank()) {
            json = withContext(Dispatchers.IO) {
                runCatching {
                    GZIPInputStream(File(currentFile.local.path).inputStream()).use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }
        }
    }

    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        val spec = json?.let { LottieCompositionSpec.JsonString(it) }
        if (spec != null) {
            val composition by rememberLottieComposition(spec)
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(fallbackEmoji, style = MaterialTheme.typography.displayMedium)
        }
    }
}

/**
 * WebM is Telegram's "video sticker" format: a tiny, looping, alpha-channel
 * video. We use ExoPlayer in muted/auto-play/loop mode inside a PlayerView
 * with no controls. The player is released in onDispose so we don't leak
 * decoders when the user scrolls past the sticker.
 *
 * If the file isn't downloaded yet we show the sticker's emoji as a
 * placeholder, same fallback as the TGS path.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun WebmVideoSticker(file: TdApi.File, fallbackEmoji: String) {
    val ctx = LocalContext.current
    var currentFile by remember(file.id) { mutableStateOf(file) }

    LaunchedEffect(file.id) {
        val downloaded = runCatching { TdClient.downloadFile(file.id) }.getOrNull()
        if (downloaded != null) currentFile = downloaded
        TdClient.fileUpdates.collect { upd ->
            if (upd.id == file.id) currentFile = upd
        }
    }

    val ready = currentFile.local.isDownloadingCompleted && currentFile.local.path.isNotBlank()
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        if (!ready) {
            Text(fallbackEmoji, style = MaterialTheme.typography.displayMedium)
            return@Box
        }

        val player = remember(currentFile.local.path) {
            ExoPlayer.Builder(ctx).build().apply {
                setMediaItem(MediaItem.fromUri(File(currentFile.local.path).toUri()))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                volume = 0f
                prepare()
            }
        }
        DisposableEffect(player) {
            onDispose { player.release() }
        }
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
