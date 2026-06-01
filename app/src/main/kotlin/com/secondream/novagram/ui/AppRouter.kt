package com.secondream.novagram.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.secondream.novagram.td.AuthState
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.screens.ApiConfigScreen
import com.secondream.novagram.ui.screens.ChatListScreen
import com.secondream.novagram.ui.screens.ChatScreen
import com.secondream.novagram.ui.screens.LoginScreen
import com.secondream.novagram.ui.screens.MediaViewerHolder
import com.secondream.novagram.ui.screens.MediaViewerScreen
import com.secondream.novagram.ui.screens.NewChatScreen
import com.secondream.novagram.ui.screens.NewGroupScreen
import com.secondream.novagram.ui.screens.ProfileScreen
import com.secondream.novagram.ui.screens.SettingsScreen

object Routes {
    const val CONFIG = "config"
    const val LOGIN = "login"
    const val CHATS = "chats"
    // chat/{chatId} with an optional ?msg=<id> tail used by deep-links
    // (t.me/<user>/<msgId>) and the avatar profile sheet's "go to message"
    // affordance. ChatScreen reads this and auto-scrolls to msg on mount
    // so the user lands on the linked message instead of the chat's tail.
    const val CHAT = "chat/{chatId}?msg={msg}"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val NEW_CHAT = "new_chat"
    const val NEW_GROUP = "new_group"
    const val MEDIA_VIEWER = "media_viewer"
    fun chat(id: Long, msg: Long? = null) =
        if (msg != null && msg != 0L) "chat/$id?msg=$msg" else "chat/$id?msg=0"
}

@Composable
fun AppRouter(
    pendingChatId: Long? = null,
    pendingMsgId: Long? = null,
    onChatOpened: () -> Unit = {}
) {
    val nav = rememberNavController()
    val auth by TdClient.authState.collectAsState()

    LaunchedEffect(auth) {
        when (auth) {
            is AuthState.NeedApiConfig -> nav.navigate(Routes.CONFIG) {
                popUpTo(0) { inclusive = true }
            }
            is AuthState.WaitPhoneNumber,
            is AuthState.WaitCode,
            is AuthState.WaitPassword,
            is AuthState.WaitRegistration -> {
                val current = nav.currentBackStackEntry?.destination?.route
                if (current != Routes.LOGIN) {
                    nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                }
            }
            is AuthState.Ready -> {
                val current = nav.currentBackStackEntry?.destination?.route
                if (current == null || current == Routes.LOGIN || current == Routes.CONFIG) {
                    nav.navigate(Routes.CHATS) { popUpTo(0) { inclusive = true } }
                }
            }
            is AuthState.LoggingOut, is AuthState.Closed -> {
                nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
            else -> { }
        }
    }

    // Notification tap → open the linked chat directly. Wait until auth is
    // Ready so we don't try to navigate while the splash/login flow is still
    // running. After navigation we call onChatOpened to clear the request so
    // a future recomposition (e.g. theme change) doesn't re-open the chat.
    //
    // CRITICAL: popUpTo(CHATS, inclusive=false) flattens the stack to
    // [chats, chatX] every time. Without it, opening 3 different chats
    // from 3 notifications builds [chats, chatA, chatB, chatC], and the
    // back button walks the stack one chat at a time instead of always
    // returning to the chat list (which is what users expect from a
    // notification tap — "back from notification = home").
    //
    // launchSingleTop = true prevents the same chat from being pushed
    // twice if the user taps the same notification rapidly — the
    // existing instance is reused so we don't waste a TDLib openChat
    // pair and so popping out of it never lands on a duplicate.
    LaunchedEffect(pendingChatId, pendingMsgId, auth) {
        if (pendingChatId != null && pendingChatId != 0L && auth is AuthState.Ready) {
            nav.navigate(Routes.chat(pendingChatId, pendingMsgId)) {
                popUpTo(Routes.CHATS) { inclusive = false }
                launchSingleTop = true
            }
            onChatOpened()
        }
    }

    // v0.10.64 motion: rewritten on spring physics for a "kinetic"
    // feel. Three layers, all running in parallel:
    //
    //   1. Slide — critically damped spring (dampingRatio=1.0). NO
    //      bounce on the slide axis because horizontal screen-wide
    //      bouncing reads as a "rubber-band glitch", not richness.
    //      StiffnessMediumLow gives ~280ms perceptual settle on
    //      enter; StiffnessMedium snaps the exiting screen out in
    //      ~220ms so it doesn't drag.
    //
    //   2. Scale — UNDER-damped spring (dampingRatio=0.70) with
    //      visible overshoot. The incoming screen scales 0.90→1.0
    //      and overshoots to ~1.015 before settling. This is the
    //      "richness" the previous tween-based pass was missing —
    //      the chat doesn't just slide into place, it LANDS with a
    //      tiny punch. Outgoing scale stays critically damped at
    //      1.0→0.94 so it just recedes smoothly.
    //
    //   3. Fade — tween, very short (180ms in / 140ms out). Just
    //      enough to soften the leading edge of the slide. Without
    //      a fade the incoming screen's left edge looks like a hard
    //      cut. No delay this time — flat parallel motion reads
    //      cleaner than staggered layers on short durations.
    //
    // Springs also carry velocity across rapid back-and-forth
    // navigation: if the user mashes "open chat → back → open chat"
    // the second slide picks up from wherever the first ended, no
    // restart-from-zero canned-curve feel.
    //
    // Outgoing parallax kept at 30% — the chat-list peeks from the
    // left during the swap so it's clearly "underneath" not "gone".
    val slideEnterSpring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
        // Snappier (was StiffnessMediumLow, which felt sluggish/"laggosa" on
        // chat open now that there's no scale masking it). Medium gives a crisp
        // push that's over quickly, shortening the window where the chat's
        // first-frame composition can drop frames. Still critically damped.
        dampingRatio = 1.0f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
    )
    val slideExitSpring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 1.0f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
    )
    val fadeInSpec = tween<Float>(durationMillis = 180)
    val fadeOutSpec = tween<Float>(durationMillis = 140)
    NavHost(
        navController = nav,
        startDestination = Routes.LOGIN,
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(
                animationSpec = slideEnterSpring,
                initialOffsetX = { fullWidth -> fullWidth }
            ) + androidx.compose.animation.fadeIn(fadeInSpec)
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                animationSpec = slideExitSpring,
                targetOffsetX = { fullWidth -> -(fullWidth * 0.30f).toInt() }
            ) + androidx.compose.animation.fadeOut(fadeOutSpec)
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally(
                animationSpec = slideEnterSpring,
                initialOffsetX = { fullWidth -> -(fullWidth * 0.30f).toInt() }
            ) + androidx.compose.animation.fadeIn(fadeInSpec)
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                animationSpec = slideExitSpring,
                targetOffsetX = { fullWidth -> fullWidth }
            ) + androidx.compose.animation.fadeOut(fadeOutSpec)
        }
    ) {
        composable(Routes.CONFIG) { ApiConfigScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
        composable(
            Routes.CHATS,
            popEnterTransition = {
                // Returning to the chat list, the data often reflows right then
                // (the chat you just read jumps to the top, unread clears). The
                // full 30% parallax slide made that reflow visibly "jump"
                // mid-slide — Eugenio's "strano glitch della lista alla
                // chiusura". A gentle fade with a tiny 6% slide lands the list
                // at its final spot so the reorder (animateItem on each row)
                // resolves cleanly instead of compounding with a big slide.
                androidx.compose.animation.slideInHorizontally(
                    animationSpec = slideEnterSpring,
                    initialOffsetX = { fullWidth -> -(fullWidth * 0.06f).toInt() }
                ) + androidx.compose.animation.fadeIn(fadeInSpec)
            }
        ) {
            val wide = androidx.compose.ui.platform.LocalConfiguration
                .current.screenWidthDp >= 600
            if (wide) {
                // Tablet / landscape: persistent chat list on the left, the
                // selected chat (or an empty placeholder) on the right. Phones
                // in portrait (< 600dp) fall through to the UNCHANGED single
                // pane below, so their behaviour is untouched.
                TwoPaneChats(nav)
            } else {
                ChatListScreen(
                    onChatClick = { id, msg ->
                        // msg is non-null when the user came in via the
                        // chat-info modal's "Visualizza in chat" — we
                        // route through Routes.chat which handles both
                        // anchored and anchorless opens.
                        nav.navigate(Routes.chat(id, msg))
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenProfile = { nav.navigate(Routes.PROFILE) },
                    onNewChat = { nav.navigate(Routes.NEW_CHAT) }
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NEW_CHAT) {
            NewChatScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.chat(id))
                },
                onNewGroup = { nav.navigate(Routes.NEW_GROUP) }
            )
        }
        composable(Routes.NEW_GROUP) {
            NewGroupScreen(
                onBack = { nav.popBackStack() },
                onOpenChat = { id ->
                    // Land in the new group and flatten the picker stack so
                    // back goes to the chat list, not the contact picker.
                    nav.navigate(Routes.chat(id)) {
                        popUpTo(Routes.CHATS)
                    }
                }
            )
        }
        composable(
            Routes.MEDIA_VIEWER,
            // Media opens by sliding UP from the bottom (and slides back down
            // on close) instead of the global left-to-right page slide —
            // Eugenio: "deve salire dal basso, non da sinistra a destra". The
            // chat underneath holds still (see the CHAT route's conditional
            // exit/popEnter below) so it reads as a sheet rising over the
            // stationary conversation.
            // Media opens by sliding UP from the bottom (and slides back down
            // on close) — Eugenio: "deve salire dal basso". BUT when the viewer
            // was opened from the chat-info dialog or the profile sheet (Compose
            // windows that had to be torn down to show it), the slide-DOWN on
            // close just exposes ~400ms of bare chat before the surface is
            // restored — the "brutto e inconsistente" flash. For those sources
            // we make the close INSTANT (the flags on MediaViewerHolder are
            // still set at this point — ChatScreen clears them only after it
            // reopens the surface), so the dialog/sheet underneath reappears
            // with no perceptible gap. Chat-bubble media keeps the slide.
            enterTransition = {
                if (MediaViewerHolder.reopenInfo || MediaViewerHolder.reopenProfileUid != null) {
                    androidx.compose.animation.fadeIn(fadeInSpec)
                } else {
                    androidx.compose.animation.slideInVertically(
                        animationSpec = slideEnterSpring,
                        initialOffsetY = { fullHeight -> fullHeight }
                    ) + androidx.compose.animation.fadeIn(fadeInSpec)
                }
            },
            popExitTransition = {
                if (MediaViewerHolder.reopenInfo || MediaViewerHolder.reopenProfileUid != null) {
                    androidx.compose.animation.ExitTransition.None
                } else {
                    androidx.compose.animation.slideOutVertically(
                        animationSpec = slideExitSpring,
                        targetOffsetY = { fullHeight -> fullHeight }
                    ) + androidx.compose.animation.fadeOut(fadeOutSpec)
                }
            }
        ) {
            // Capture the path ONCE. Previously onClose nulled
            // MediaViewerHolder.currentPath, which recomposed this block
            // with path==null and fired the else-branch popBackStack — a
            // SECOND pop on top of onClose's, kicking the user all the way
            // out of the chat. Remembering the path means the holder reset
            // can't change what we render, so onClose pops exactly once.
            val path = remember { MediaViewerHolder.currentPath }
            if (path != null) {
                MediaViewerScreen(
                    filePath = path,
                    onClose = {
                        // Just pop. If this viewer was opened from the chat-info
                        // dialog or profile sheet, ChatScreen sees the reopen
                        // flags on its ON_RESUME (when it comes back to front)
                        // and restores that surface itself — robust against the
                        // navigation timing that previously dropped us on the
                        // bare chat or popped too far.
                        nav.popBackStack()
                    }
                )
            } else {
                androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }
        composable(
            Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.LongType },
                navArgument("msg") { type = NavType.LongType; defaultValue = 0L }
            ),
            // The chat holds perfectly still while the media viewer rises over
            // it (the viewer is opaque, so a horizontal slide of the chat
            // underneath would just look like jank peeking past the rising
            // sheet). Returning null for every OTHER destination defers to the
            // NavHost's global horizontal page slide, so chat↔chat and
            // chat↔list keep their normal motion.
            exitTransition = {
                if (targetState.destination.route == Routes.MEDIA_VIEWER)
                    androidx.compose.animation.ExitTransition.None
                else null
            },
            popEnterTransition = {
                if (initialState.destination.route == Routes.MEDIA_VIEWER)
                    androidx.compose.animation.EnterTransition.None
                else null
            }
        ) { entry ->
            val id = entry.arguments?.getLong("chatId") ?: 0L
            val msg = entry.arguments?.getLong("msg") ?: 0L
            // Evict the per-chat message cache when this back-stack
            // entry is destroyed — i.e. the user really left the chat
            // (back to the chat list, or popped past it via
            // popUpTo(CHATS) from a notification tap). Pushing to
            // MediaViewer / Profile keeps the entry alive in the
            // stack, so the cache survives that and the scroll
            // position is restored on pop back. ON_DESTROY only fires
            // on actual removal, which is exactly the eviction
            // boundary we want.
            androidx.compose.runtime.DisposableEffect(entry) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                        com.secondream.novagram.ui.screens.ChatMessageCache.evict(id)
                    }
                }
                entry.lifecycle.addObserver(observer)
                onDispose { entry.lifecycle.removeObserver(observer) }
            }
            ChatScreen(
                chatId = id,
                targetMessageId = msg.takeIf { it != 0L },
                onBack = { nav.popBackStack() },
                onOpenMediaViewer = { nav.navigate(Routes.MEDIA_VIEWER) },
                onOpenChat = { other, otherMsg ->
                    // Used by the avatar profile sheet's "Inizia chat"
                    // button and by t.me deep-link handling: replace
                    // ourselves on the back stack so back returns to the
                    // chat list, not to the previous group we came from.
                    nav.navigate(Routes.chat(other, otherMsg)) {
                        popUpTo(Routes.CHATS)
                    }
                }
            )
        }
    }
}

/**
 * Tablet / landscape master-detail for the chat list. The list lives in a
 * fixed-width left rail; tapping a chat fills the right pane with [ChatScreen]
 * (or an empty placeholder). Reuses the same screens as the phone single-pane
 * flow — only the *container* differs — so the two layouts never diverge in
 * behaviour. Phones in portrait never reach this (gated at < 600dp upstream).
 */
@Composable
private fun TwoPaneChats(nav: NavHostController) {
    var selectedChatId by remember { mutableStateOf<Long?>(null) }
    var selectedMsgId by remember { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Narrower rail on medium widths (phone landscape, small tablets) so
        // the conversation pane keeps usable room; full 360dp on expanded.
        val listWidth = if (maxWidth >= 840.dp) 360.dp else 300.dp
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) {
                ChatListScreen(
                    onChatClick = { id, msg ->
                        selectedChatId = id
                        selectedMsgId = msg?.takeIf { it != 0L }
                    },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenProfile = { nav.navigate(Routes.PROFILE) },
                    onNewChat = { nav.navigate(Routes.NEW_CHAT) }
                )
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val cid = selectedChatId
                if (cid != null) {
                    // key(cid): swapping the selected chat fully disposes the
                    // previous ChatScreen (its openChat/closeChat + state reset
                    // run exactly as in the NavHost flow) and builds a fresh one.
                    key(cid) {
                        ChatScreen(
                            chatId = cid,
                            targetMessageId = selectedMsgId,
                            onBack = { selectedChatId = null },
                            onOpenMediaViewer = { nav.navigate(Routes.MEDIA_VIEWER) },
                            onOpenChat = { other, otherMsg ->
                                selectedChatId = other
                                selectedMsgId = otherMsg?.takeIf { it != 0L }
                            }
                        )
                    }
                } else {
                    EmptyDetailPane()
                }
            }
        }
    }
}

/** Placeholder shown in the detail pane before a chat is selected. */
@Composable
private fun EmptyDetailPane() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            androidx.compose.ui.res.stringResource(
                com.secondream.novagram.R.string.two_pane_select_chat
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
