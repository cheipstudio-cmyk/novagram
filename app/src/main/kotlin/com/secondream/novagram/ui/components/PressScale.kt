package com.secondream.novagram.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Subtle tactile press feedback: the surface scales down slightly while held
 * and springs back with a light bounce on release. This is exactly the feel
 * the action-sheet tiles already use, factored out so any tappable surface can
 * share one consistent motion instead of re-implementing the boilerplate.
 *
 * Pass the SAME [interactionSource] you hand the clickable / combinedClickable
 * so the scale tracks the real press and the ripple stays in sync. graphicsLayer
 * is a draw-time transform, so layout, measurement and hit-testing are all
 * unaffected — only the pixels scale. Keep [pressedScale] subtle on large
 * surfaces (a wide row at 0.92 looks wrong; ~0.98 reads as a gentle push).
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
