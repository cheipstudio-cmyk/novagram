package com.secondream.cheipgram

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.runBlocking
import com.secondream.cheipgram.notifications.NotificationHelper
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.TdClient

/**
 * Global flag indicating whether the app is currently visible to the user.
 * NotificationHelper consults this together with [currentChatId] to decide
 * whether to post a heads-up notification:
 *  - app in background → always notify (no chat in view)
 *  - app in foreground but user is on a DIFFERENT chat → notify (so they
 *    see the message arriving in another chat)
 *  - app in foreground AND user is on the same chat the message arrived in
 *    → suppress, the message is already visible in ChatScreen
 *
 * Updated by App.onCreate via ProcessLifecycleOwner. Volatile because the
 * notification path runs on a background coroutine while lifecycle callbacks
 * run on the main thread.
 */
object AppForegroundState {
    @Volatile var isInForeground: Boolean = false
    /**
     * Id of the chat the user is currently viewing in ChatScreen, or 0L if
     * they aren't in any chat. ChatScreen sets this on enter and clears it
     * on dispose via DisposableEffect.
     */
    @Volatile var currentChatId: Long = 0L
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.init(this)

        val tag = runBlocking { AppSettings.currentLanguageTag() }
        val locales = if (tag == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)

        // Foreground / background tracking. ProcessLifecycleOwner aggregates
        // every Activity in the process: onStart fires the first time any
        // activity becomes visible, onStop fires after the LAST activity has
        // become invisible (with a small grace period for rotation etc.) so
        // we don't flap on configuration changes.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { AppForegroundState.isInForeground = true }
            override fun onStop(owner: LifecycleOwner) { AppForegroundState.isInForeground = false }
        })

        NotificationHelper.init(this)
        TdClient.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
