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
    LaunchedEffect(pendingChatId, auth) {
        if (pendingChatId != null && pendingChatId != 0L && auth is AuthState.Ready) {
            nav.navigate(Routes.chat(pendingChatId))
            onChatOpened()
        }
    }

    // Push-style transitions: new screen slides in from the right while the
    // outgoing screen shrinks slightly, giving a sense of depth (like iOS
    // navigation). On pop, the back transition mirrors the entry.
    val durationMs = 280
    NavHost(
        navController = nav,
        startDestination = Routes.LOGIN,
        // Centered scale+fade: each new screen grows in place from 92% to
        // 100% while fading in, the outgoing one shrinks slightly and
        // fades out. Feels closer to a modal reveal than to a directional
        // slide, which matches the "opens at center" feel the user asked
        // for. Pop reverses the curve symmetrically.
        enterTransition = {
            androidx.compose.animation.scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeIn(tween(220))
        },
        exitTransition = {
            androidx.compose.animation.scaleOut(
                targetScale = 1.04f,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeOut(tween(180))
        },
        popEnterTransition = {
            androidx.compose.animation.scaleIn(
                initialScale = 1.04f,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeIn(tween(220))
        },
        popExitTransition = {
            androidx.compose.animation.scaleOut(
                targetScale = 0.92f,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeOut(tween(180))
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
