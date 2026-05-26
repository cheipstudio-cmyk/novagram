package com.secondream.cheipgram.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.secondream.cheipgram.td.AuthState
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.screens.ApiConfigScreen
import com.secondream.cheipgram.ui.screens.ChatListScreen
import com.secondream.cheipgram.ui.screens.ChatScreen
import com.secondream.cheipgram.ui.screens.LoginScreen
import com.secondream.cheipgram.ui.screens.MediaViewerHolder
import com.secondream.cheipgram.ui.screens.MediaViewerScreen
import com.secondream.cheipgram.ui.screens.NewChatScreen
import com.secondream.cheipgram.ui.screens.ProfileScreen
import com.secondream.cheipgram.ui.screens.SettingsScreen

object Routes {
    const val CONFIG = "config"
    const val LOGIN = "login"
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val NEW_CHAT = "new_chat"
    const val MEDIA_VIEWER = "media_viewer"
    fun chat(id: Long) = "chat/$id"
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
    val durationMs = 300
    NavHost(
        navController = nav,
        startDestination = Routes.LOGIN,
        enterTransition = {
            slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeIn(tween(220))
        },
        exitTransition = {
            androidx.compose.animation.scaleOut(
                targetScale = 0.92f,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeOut(tween(220))
        },
        popEnterTransition = {
            androidx.compose.animation.scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeIn(tween(220))
        },
        popExitTransition = {
            slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(durationMs)
            ) + androidx.compose.animation.fadeOut(tween(220))
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
            val path = MediaViewerHolder.currentPath
            if (path != null) {
                MediaViewerScreen(
                    filePath = path,
                    onClose = {
                        MediaViewerHolder.currentPath = null
                        nav.popBackStack()
                    }
                )
            } else {
                // Path was lost (process death etc) — just go back.
                androidx.compose.runtime.LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("chatId") ?: 0L
            ChatScreen(
                chatId = id,
                onBack = { nav.popBackStack() },
                onOpenMediaViewer = { nav.navigate(Routes.MEDIA_VIEWER) }
            )
        }
    }
}
