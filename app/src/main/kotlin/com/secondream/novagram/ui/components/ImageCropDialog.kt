package com.secondream.novagram.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.secondream.novagram.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Telegram-style circular photo cropper. Shows [imageUri] inside a fixed
 * square viewport with a circular mask; the user pans (drag) and zooms
 * (pinch) the image, and "Salva" writes the cropped square as a JPEG into
 * the cache, handing its path back via [onCropped]. Used for the profile
 * photo, the group photo (admin/owner) and new-group creation.
 *
 * The image is loaded via Coil so EXIF orientation is applied and large
 * photos are downsampled (max 2048px) — keeps the gesture surface smooth.
 * The crop maths and the on-screen draw share the SAME viewport-px and
 * base-scale so what you frame is exactly what gets saved.
 */
@Composable
fun ImageCropDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onCropped: (String) -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var bitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(imageUri) { mutableStateOf(false) }
    var scale by remember(imageUri) { mutableStateOf(1f) }
    var offset by remember(imageUri) { mutableStateOf(Offset.Zero) }
    var saving by remember(imageUri) { mutableStateOf(false) }

    LaunchedEffect(imageUri) {
        val bmp = withContext(Dispatchers.IO) { loadCropBitmap(ctx, imageUri) }
        if (bmp != null) bitmap = bmp else loadFailed = true
    }

    // Fixed viewport. vpPx is THE single source of truth shared by the draw,
    // the gesture clamps and the final crop.
    val viewportDp = 300.dp
    val vpPx = with(density) { viewportDp.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF121212))
                .padding(20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.crop_title),
                style = MaterialTheme.typography.titleLarge,
                fontStyle = FontStyle.Italic,
                color = Color.White
            )
            Spacer(Modifier.height(18.dp))

            val bmp = bitmap
            if (bmp == null) {
                Box(modifier = Modifier.size(viewportDp), contentAlignment = Alignment.Center) {
                    if (loadFailed) {
                        Text(
                            stringResource(R.string.crop_load_failed),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            } else {
                val img = remember(bmp) { bmp.asImageBitmap() }
                val bw = bmp.width.toFloat()
                val bh = bmp.height.toFloat()
                // Scale that makes the image COVER the square viewport.
                val baseScale = remember(bmp) { max(vpPx / bw, vpPx / bh) }
                Canvas(
                    modifier = Modifier
                        .size(viewportDp)
                        .clipToBounds()
                        .pointerInput(bmp) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 6f)
                                val eff = baseScale * newScale
                                val sW = bw * eff
                                val sH = bh * eff
                                val maxX = ((sW - vpPx) / 2f).coerceAtLeast(0f)
                                val maxY = ((sH - vpPx) / 2f).coerceAtLeast(0f)
                                scale = newScale
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            }
                        }
                ) {
                    val eff = baseScale * scale
                    val scaledW = bw * eff
                    val scaledH = bh * eff
                    val left = vpPx / 2f + offset.x - scaledW / 2f
                    val top = vpPx / 2f + offset.y - scaledH / 2f
                    drawImage(
                        image = img,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(scaledW.roundToInt(), scaledH.roundToInt())
                    )
                    // Dim everything outside the circle (rect minus oval, even-odd).
                    val mask = Path().apply {
                        addRect(Rect(0f, 0f, vpPx, vpPx))
                        addOval(Rect(0f, 0f, vpPx, vpPx))
                        fillType = PathFillType.EvenOdd
                    }
                    drawPath(mask, Color.Black.copy(alpha = 0.6f))
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = vpPx / 2f - 1.5f,
                        center = Offset(vpPx / 2f, vpPx / 2f),
                        style = Stroke(width = 3f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    enabled = bitmap != null && !saving,
                    onClick = {
                        val src = bitmap ?: return@Button
                        saving = true
                        val sBase = max(vpPx / src.width, vpPx / src.height)
                        val sScale = scale
                        val sOffset = offset
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                runCatching {
                                    val cropped = cropToSquare(src, vpPx, sBase, sScale, sOffset)
                                    val f = File(ctx.cacheDir, "crop_${System.currentTimeMillis()}.jpg")
                                    FileOutputStream(f).use {
                                        cropped.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                    }
                                    if (cropped !== src) cropped.recycle()
                                    f.absolutePath
                                }.getOrNull()
                            }
                            saving = false
                            if (path != null) onCropped(path)
                        }
                    }
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

/** Load [uri] as a software, EXIF-corrected, downsampled bitmap via Coil. */
private suspend fun loadCropBitmap(ctx: Context, uri: Uri): Bitmap? {
    val request = ImageRequest.Builder(ctx)
        .data(uri)
        .allowHardware(false)   // needed so we can read pixels for cropping
        .size(2048, 2048)
        .build()
    val result = ctx.imageLoader.execute(request)
    return (result as? SuccessResult)?.drawable?.toBitmap()
}

/**
 * Extract the square region currently framed by the circular viewport and
 * scale it to 800x800. [baseScale]/[scale]/[offset]/[vpPx] mirror the draw
 * so the saved image matches the preview exactly.
 */
private fun cropToSquare(
    src: Bitmap,
    vpPx: Float,
    baseScale: Float,
    scale: Float,
    offset: Offset,
    out: Int = 800
): Bitmap {
    val bw = src.width.toFloat()
    val bh = src.height.toFloat()
    val eff = baseScale * scale
    val cropSize = vpPx / eff
    val sizePx = cropSize.coerceAtMost(min(bw, bh))
    val leftF = bw / 2f - (vpPx / 2f + offset.x) / eff
    val topF = bh / 2f - (vpPx / 2f + offset.y) / eff
    val x = leftF.roundToInt().coerceIn(0, (src.width - 1).coerceAtLeast(0))
    val y = topF.roundToInt().coerceIn(0, (src.height - 1).coerceAtLeast(0))
    val w = sizePx.roundToInt().coerceIn(1, src.width - x)
    val h = sizePx.roundToInt().coerceIn(1, src.height - y)
    val region = Bitmap.createBitmap(src, x, y, w, h)
    val scaled = Bitmap.createScaledBitmap(region, out, out, true)
    if (scaled !== region) region.recycle()
    return scaled
}
