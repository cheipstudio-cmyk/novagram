@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.secondream.cheipgram.ui.screens

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
import com.secondream.cheipgram.R

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
 * `currentPath` on composition and resets it on close.
 */
object MediaViewerHolder {
    var currentPath: String? = null
}
