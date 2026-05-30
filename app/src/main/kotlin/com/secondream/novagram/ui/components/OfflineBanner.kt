package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.secondream.novagram.R
import com.secondream.novagram.connectivity.ConnectivityState

/**
 * One-line "no connection" banner. Watches the realtime
 * [ConnectivityState.isOnline] flow and slides in/out at the top of
 * its container the moment the OS reports a connectivity transition.
 *
 * Caller is expected to place this DIRECTLY under their TopAppBar
 * (or above their main content) so the slide animation reads as the
 * banner "dropping down" from the top edge — same affordance Telegram,
 * WhatsApp, Signal, and most messaging clients use for transient
 * connectivity loss.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val online by ConnectivityState.isOnline.collectAsState()
    AnimatedVisibility(
        visible = !online,
        enter = expandVertically(
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 360,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
            )
        ) + fadeIn(
            animationSpec = androidx.compose.animation.core.tween(320, delayMillis = 40)
        ),
        exit = shrinkVertically(
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 280,
                easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
            )
        ) + fadeOut(
            animationSpec = androidx.compose.animation.core.tween(200)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}
