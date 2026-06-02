@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.novagram.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.secondream.novagram.R
import kotlinx.coroutines.launch

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
    // Route the system back gesture through onClose too (not just the X
    // button), otherwise NavHost's default pop bypasses our reopen logic
    // and the chat-info / profile surface wouldn't come back. Placed before
    // the video branch so it covers both image and video viewers.
    androidx.activity.compose.BackHandler { onClose() }
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
    // Swipe-down-to-dismiss offset (only active when the photo is NOT
    // zoomed). The image follows the finger 1:1 in dismissX/dismissY; on
    // release it either flings off-screen + closes (past the threshold) or
    // springs back to centre. Kept as raw float state for lag-free tracking;
    // the spring/fling on release is driven by the suspend `animate`.
    var dismissX by remember { mutableFloatStateOf(0f) }
    var dismissY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    // 0 at rest, 1 when dragged a full "dismiss range" down/up. Drives the
    // background fade and the subtle photo shrink so the picture appears to
    // lift off the black backdrop as it's flung away.
    val dismissFrac = (kotlin.math.abs(dismissY) / 600f).coerceIn(0f, 1f)

    val targetScale by animateFloatAsState(targetValue = scale, label = "viewer-scale")
    val targetOffsetX by animateFloatAsState(targetValue = offsetX, label = "viewer-ox")
    val targetOffsetY by animateFloatAsState(targetValue = offsetY, label = "viewer-oy")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - 0.9f * dismissFrac))
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
                // Unified pinch / pan / swipe-to-dismiss loop. We can't use
                // detectTransformGestures here because it gives no
                // gesture-end callback, and the dismiss decision (fling vs
                // spring back) has to happen on pointer-up. So we run the
                // raw gesture loop: pinch drives zoom, single-finger pan
                // moves the zoomed image, and — only while un-zoomed —
                // vertical drag drives the dismiss offset.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f) {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                        }
                        if (scale > 1.05f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            dismissY += pan.y
                            dismissX += pan.x * 0.35f
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } while (event.changes.any { it.pressed })

                    if (scale <= 1.05f) {
                        if (kotlin.math.abs(dismissY) > 180f) {
                            val dir = if (dismissY >= 0f) 1f else -1f
                            scope.launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = dismissY,
                                    targetValue = dir * 2400f,
                                    animationSpec = androidx.compose.animation.core.tween(170)
                                ) { v, _ -> dismissY = v }
                                onClose()
                            }
                        } else {
                            scope.launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = dismissY,
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.82f,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                ) { v, _ -> dismissY = v }
                            }
                            scope.launch {
                                androidx.compose.animation.core.animate(
                                    initialValue = dismissX,
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.82f,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                ) { v, _ -> dismissX = v }
                            }
                        }
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
                    scaleX = targetScale * (1f - 0.12f * dismissFrac),
                    scaleY = targetScale * (1f - 0.12f * dismissFrac),
                    translationX = targetOffsetX + dismissX,
                    translationY = targetOffsetY + dismissY
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
                com.secondream.novagram.ui.icons.PhosphorIcons.X,
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
    // Reopen-intent flags read by ChatScreen on its OWN lifecycle ON_RESUME
    // (i.e. when the viewer is popped and the chat comes back to the front),
    // rather than via a callback invoked mid-navigation from the router —
    // that cross-composition mutation was timing-fragile and sometimes left
    // the user on the bare chat / popped too far ("torna in home"). The
    // chat-info dialog and the profile sheet are Compose windows NOT on the
    // nav back stack, so a plain popBackStack lands on the chat; these flags
    // tell ChatScreen to bring the originating surface back. The chat-bubble
    // path sets neither → normal return to the chat.
    var reopenInfo: Boolean = false
    var reopenProfileUid: Long? = null
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
    // Guard: if the path is blank or the file isn't on disk yet, ExoPlayer
    // would just render black ("video won't play"). Show a spinner + a close
    // button instead until a real, existing file path arrives.
    val ready = filePath.isNotBlank() &&
        runCatching { java.io.File(filePath).exists() }.getOrDefault(false)
    if (!ready) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White)
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
                    com.secondream.novagram.ui.icons.PhosphorIcons.X,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
        return
    }
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
                com.secondream.novagram.ui.icons.PhosphorIcons.X,
                contentDescription = stringResource(R.string.media_viewer_close),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
