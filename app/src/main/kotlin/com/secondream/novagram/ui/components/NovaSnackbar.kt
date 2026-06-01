package com.secondream.novagram.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide lightweight snackbar. Any code (composable or not — e.g. a
 * coroutine after a TDLib call) can fire one via [show]; a single host
 * rendered at MainActivity scope animates it in/out. Messages are string
 * resources so they stay translated, with an optional leading icon.
 *
 * Deliberately not Material3's SnackbarHostState: that needs a host+scope
 * threaded through every screen. This is a global the action sites can poke
 * from anywhere ("chat eliminata", "membro bannato", "messaggio fissato", …)
 * without plumbing.
 */
object NovaSnackbar {
    data class Msg(val textRes: Int, val icon: ImageVector?, val id: Long)

    private val _current = MutableStateFlow<Msg?>(null)
    val current: StateFlow<Msg?> = _current.asStateFlow()

    /** Show [textRes] (optionally with [icon]) for a couple of seconds. */
    fun show(textRes: Int, icon: ImageVector? = null) {
        _current.value = Msg(textRes, icon, System.nanoTime())
    }

    fun dismiss() {
        _current.value = null
    }
}

/**
 * The single host. Place once at the app root, anchored to the bottom. Slides
 * up + fades on show, reverses on auto-dismiss. [shown] is held across the
 * exit so the outgoing message keeps rendering while it animates away.
 */
@Composable
fun NovaSnackbarHost(modifier: Modifier = Modifier) {
    val msg by NovaSnackbar.current.collectAsState()
    var shown by remember { mutableStateOf<NovaSnackbar.Msg?>(null) }
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        shown = m
        delay(2600)
        // Only clear if no newer message replaced this one in the meantime.
        if (NovaSnackbar.current.value?.id == m.id) NovaSnackbar.dismiss()
    }
    AnimatedVisibility(
        visible = msg != null,
        enter = fadeIn() + slideInVertically(
            animationSpec = spring(
                dampingRatio = 0.72f,
                stiffness = Spring.StiffnessMediumLow
            ),
            initialOffsetY = { it / 2 }
        ),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        shown?.let { m ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    m.icon?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = androidx.compose.ui.res.stringResource(m.textRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
