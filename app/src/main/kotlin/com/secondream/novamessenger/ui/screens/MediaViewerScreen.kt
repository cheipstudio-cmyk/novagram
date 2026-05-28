@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novamessenger.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.secondream.novamessenger.R

/**
 * Full-screen image viewer.
 *
 * Gestures:
 *  - Pinch + drag: continuous zoom (1x–5x) and pan when zoomed.
 *  - Double tap: toggle between 1x and 2.5x at the tap point.
 *  - Tap: does NOT close the viewer — Eugenio wants to be able to tap
 *    around to dismiss UI without losing the photo. Use the explicit X
 *    button (top-left) or the system back gesture to close.
 *
 * The X is placed inside statusBarsPadding so it doesn't sit under the
 * notch / status bar; the previous 16dp absolute padding made the touch
 * target too high on most phones.
 */
@Composable
fun MediaViewerScreen(filePath: String, onClose: () -> Unit) {
    // Branch: ExoPlayer for video, the existing AsyncImage zoomer for
    // photos. We read the flag from the holder rather than from
    // path-extension sniffing because some TDLib downloads land without
    // a recognisable extension.
    if (MediaViewerHolder.isVideo) {
        VideoViewer(filePath, onClose)
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val targetScale by animateFloatAsState(targetValue = scale, label = "viewer-scale")
    val targetOffsetX by animateFloatAsState(targetValue = offsetX, label = "viewer-ox")
    val targetOffsetY by animateFloatAsState(targetValue = offsetY, label = "viewer-oy")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    // Single tap intentionally does nothing — see kdoc above.
                    onDoubleTap = { tapPos ->
                        if (scale > 1.05f) {
                            // Zoom out fully and recenter.
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            // Zoom to 2.5x focused on the tap location:
                            // shift the content so the tapped point ends up
                            // at the screen centre.
                            scale = 2.5f
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            offsetX = (w / 2f - tapPos.x) * (scale - 1f)
                            offsetY = (h / 2f - tapPos.y) * (scale - 1f)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
    ) {
        AsyncImage(
            model = filePath,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = targetScale,
                    scaleY = targetScale,
                    translationX = targetOffsetX,
                    translationY = targetOffsetY
                )
        )
        // Close button on a subtle dark scrim circle so it stays visible
        // against bright photos. statusBarsPadding pushes it under the
        // status bar/notch on edge-to-edge devices.
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.media_viewer_close),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Tiny global holder so the chat screen can hand the file path to the viewer
 * without serialising it into a navigation argument. The viewer reads
 * `currentPath` on composition and resets it on close. `isVideo` flips the
 * viewer into the embedded ExoPlayer renderer for .mp4/.mov media —
 * previously videos went through the system Intent which felt jarring
 * (user briefly leaves Nova).
 */
object MediaViewerHolder {
    var currentPath: String? = null
    var isVideo: Boolean = false
}

/**
 * Embedded full-screen video player. Built on AndroidView wrapping
 * androidx.media3 PlayerView with default controls — gives us
 * play/pause, seek bar, current/total time, and fullscreen aspect
 * handling for free, on top of an ExoPlayer that's owned by this
 * composable's lifecycle.
 *
 * Release is critical: leaking ExoPlayer instances holds audio focus
 * and the codec until GC, so DisposableEffect calls .release() on
 * dispose. autoPlay=true reflects what the user expects when tapping
 * a video bubble — they wanted to watch it.
 */
@androidx.compose.runtime.Composable
private fun VideoViewer(filePath: String, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = androidx.compose.runtime.remember(filePath) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(
                android.net.Uri.fromFile(java.io.File(filePath))
            ))
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                androidx.media3.ui.PlayerView(c).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 2500
                    setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    // Fit so vertical videos don't get cropped — letterbox
                    // is the standard expectation.
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        )
        IconButton(
            onClick = onClose,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 8.dp)
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.media_viewer_close),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
