package com.secondream.novagram.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV color wheel picker. The wheel covers hue (angle) and saturation
 * (distance from center); a separate brightness slider underneath
 * controls Value.
 *
 * Why HSV instead of three RGB sliders: HSV maps naturally to a 2D
 * geometric input (the wheel) — dragging the thumb gives the user a
 * direct, predictable mapping between the gesture and the resulting hue,
 * which RGB sliders fundamentally don't. The brightness slider is kept
 * separate because compressing V into a small wheel radius makes
 * dark/light variations hard to pick.
 *
 * The picker is uncontrolled internally: it owns h/s/v state and emits
 * onColorChanged on every change. Parent passes the initial color as ARGB.
 */
@Composable
fun ColorWheelPicker(
    initialArgb: Int,
    onColorChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val initHsv = remember(initialArgb) {
        val r = android.graphics.Color.red(initialArgb)
        val g = android.graphics.Color.green(initialArgb)
        val b = android.graphics.Color.blue(initialArgb)
        val out = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, out)
        out
    }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2]) }

    fun emit() {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        onColorChanged(argb)
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .padding(8.dp)
        ) {
            // The wheel itself. We size the Canvas to a square and let it
            // expand inside its parent Box. Hit-testing uses canvas pixel
            // coordinates so the thumb stays under the finger regardless of
            // density.
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .pointerInput(Unit) {
                        // detectDragGestures with onDragStart handles both
                        // single-tap (touch down + immediate up = no drag
                        // events, but onDragStart still fires) and drag.
                        // Avoids detectTapGestures.onPress whose receiver
                        // is PressGestureScope and can't take a plain
                        // (Offset) -> Unit lambda.
                        fun update(pos: Offset) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = pos.x - cx
                            val dy = pos.y - cy
                            val r = sqrt(dx * dx + dy * dy)
                            val radius = min(cx, cy)
                            var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (angle < 0f) angle += 360f
                            hue = angle
                            sat = (r / radius).coerceIn(0f, 1f)
                            emit()
                        }
                        detectDragGestures(
                            onDragStart = { update(it) },
                            onDrag = { change, _ -> update(change.position) }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height
                val radius = min(w, h) / 2f
                val center = Offset(w / 2f, h / 2f)
                // Hue ring via sweep gradient. SweepGradient's constructor
                // is internal in Compose so we go through Brush.sweepGradient.
                val sweep = Brush.sweepGradient(
                    colors = listOf(
                        Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red
                    ),
                    center = center
                )
                drawCircle(brush = sweep, radius = radius, center = center)
                // White-to-transparent radial overlay creates the saturation
                // axis: center reads as white, edge reads as fully saturated.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
                // Thumb indicator: position derived from current hue+sat.
                val rad = (hue * Math.PI / 180.0).toFloat()
                val tx = center.x + radius * sat * cos(rad)
                val ty = center.y + radius * sat * sin(rad)
                drawCircle(
                    color = Color.White,
                    radius = 14f,
                    center = Offset(tx, ty),
                    style = Stroke(width = 5f)
                )
                drawCircle(
                    color = Color.Black,
                    radius = 14f,
                    center = Offset(tx, ty),
                    style = Stroke(width = 2f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // Brightness slider. Acts on V only; H and S stay where the wheel
        // put them. Track is a horizontal gradient from black to the current
        // hue+sat so the user can preview the affected range at a glance.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "B",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 8.dp)
            )
            Slider(
                value = value,
                onValueChange = { value = it; emit() },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        Spacer(Modifier.height(8.dp))
        // Live preview swatch.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))))
        )
    }
}
