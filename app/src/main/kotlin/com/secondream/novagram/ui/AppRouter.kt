package com.secondream.novagram.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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
    const val MEDIA_VIEWER = "media_viewer"
    fun chat(id: Long, msg: Long? = null) =
        if (msg != null && msg != 0L) "chat/$id?msg=$msg" else "chat/$id?msg=0"
}

@Composable
fun AppRouter(
    pendingChatId: Long? = null,
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
            is AuthState.WaitPassword -> {
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
    LaunchedEffect(pendingChatId, auth) {
        if (pendingChatId != null && pendingChatId != 0L && auth is AuthState.Ready) {
            nav.navigate(Routes.chat(pendingChatId)) {
                popUpTo(Routes.CHATS) { inclusive = false }
                launchSingleTop = true
            }
            onChatOpened()
        }
    }

    // Push-style transitions, now spring-driven. The incoming screen slides
    // in from the right while the outgoing one shifts slightly left (parallax)
    // and fades; pop mirrors it. The horizontal motion uses a physics spring
    // (StiffnessMediumLow, dampingRatio 0.9 = smooth settle, no perceptible
    // bounce) instead of a fixed-duration tween. A spring carries velocity, so
    // rapid back-and-forth navigation feels continuous and "alive" rather than
    // restarting a canned curve each time — the motion vocabulary Material 3
    // Expressive standardised on. Fades stay short tweens so the cross-dissolve
    // reads crisp regardless of the spring's natural duration.
    val slideSpring = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = 0.9f,
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
    )
    NavHost(
        navController = nav,
        startDestination = Routes.LOGIN,
        enterTransition = {
            androidx.compose.animation.slideInHorizontally(
                animationSpec = slideSpring,
                initialOffsetX = { fullWidth -> (fullWidth * 0.35f).toInt() }
            ) + androidx.compose.animation.fadeIn(tween(180))
        },
        exitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                animationSpec = slideSpring,
                targetOffsetX = { fullWidth -> -(fullWidth * 0.18f).toInt() }
            ) + androidx.compose.animation.fadeOut(tween(140))
        },
        popEnterTransition = {
            androidx.compose.animation.slideInHorizontally(
                animationSpec = slideSpring,
                initialOffsetX = { fullWidth -> -(fullWidth * 0.18f).toInt() }
            ) + androidx.compose.animation.fadeIn(tween(180))
        },
        popExitTransition = {
            androidx.compose.animation.slideOutHorizontally(
                animationSpec = slideSpring,
                targetOffsetX = { fullWidth -> (fullWidth * 0.35f).toInt() }
            ) + androidx.compose.animation.fadeOut(tween(140))
        }
    ) {
        composable(Routes.CONFIG) { ApiConfigScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
        composable(Routes.CHATS) {
            ChatListScreen(
                onChatClick = { id -> nav.navigate(Routes.chat(id)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenProfile = { nav.navigate(Routes.PROFILE) },
                onNewChat = { nav.navigate(Routes.NEW_CHAT) }
            )
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
                }
            )
        }
        composable(Routes.MEDIA_VIEWER) {
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
                    onClose = { nav.popBackStack() }
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
            )
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
