package com.secondream.turbogram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.secondream.turbogram.td.AuthState
import com.secondream.turbogram.td.TdClient
import com.secondream.turbogram.ui.screens.ApiConfigScreen
import com.secondream.turbogram.ui.screens.ChatListScreen
import com.secondream.turbogram.ui.screens.ChatScreen
import com.secondream.turbogram.ui.screens.LoginScreen

object Routes {
    const val CONFIG = "config"
    const val LOGIN = "login"
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    fun chat(id: Long) = "chat/$id"
}

@Composable
fun AppRouter() {
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

    NavHost(navController = nav, startDestination = Routes.LOGIN) {
        composable(Routes.CONFIG) { ApiConfigScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
        composable(Routes.CHATS) {
            ChatListScreen(onChatClick = { id -> nav.navigate(Routes.chat(id)) })
        }
        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("chatId") ?: 0L
            ChatScreen(chatId = id, onBack = { nav.popBackStack() })
        }
    }
}
