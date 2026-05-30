package com.secondream.novagram

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.secondream.novagram.notifications.NotificationHelper
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

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

class App : Application(), ImageLoaderFactory {
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
        // Apply the launcher-icon variant matching the user's saved
        // theme on every process start. Idempotent — if nothing
        // changed, PackageManager no-ops. Wrapped in a launch on
        // GlobalScope (not a process-tied scope) because the
        // DataStore read suspends and we don't want to block onCreate.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            IconAliasManager.applyFromSettings(this@App)
        }

        // Best-effort FCM token registration. Wrapped in runCatching
        // and gated on FirebaseApp.getApps(this).isNotEmpty(): when
        // google-services.json is missing, no FirebaseApp exists and
        // this block silently returns. When the JSON is present, the
        // gms plugin auto-initializes FirebaseApp before onCreate runs,
        // we fetch the token, and hand it to TdClient. TdClient itself
        // re-tries with runCatching if TDLib isn't ready yet — token
        // rotation later will catch us up.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val hasFirebase =
                    com.google.firebase.FirebaseApp.getApps(this@App).isNotEmpty()
                if (!hasFirebase) {
                    android.util.Log.i("App", "Firebase not configured — FCM disabled, FGS-only delivery")
                    return@runCatching
                }
                val token = com.google.android.gms.tasks.Tasks.await(
                    com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                )
                android.util.Log.i("App", "FCM token acquired, registering with TDLib")
                TdClient.registerDeviceForFcm(token)
            }.onFailure {
                android.util.Log.w("App", "FCM bootstrap failed (non-fatal)", it)
            }
        }
    }

    /**
     * Single tuned ImageLoader for the whole app. Coil's singleton picks this
     * up because App implements ImageLoaderFactory, so every AsyncImage (chat
     * avatars, message photos/thumbnails, stickers, the media viewer) shares
     * one in-memory + on-disk cache instead of Coil's conservative defaults.
     *
     *  - memory cache 25% of the app heap: avatars in a long chat list and
     *    re-entered chats come straight from RAM, so scrolling doesn't re-decode
     *    the same bitmaps and the list stays at frame rate.
     *  - 150 MB disk cache: thumbnails survive process death, so reopening the
     *    app doesn't re-read every file from TDLib's store.
     *  - respectCacheHeaders(false): our image keys are local file paths whose
     *    bytes never change for a given path, so there is nothing to revalidate;
     *    skipping that check shaves work off every load.
     *  - crossfade(150): images fade in instead of popping, which reads as much
     *    smoother as pictures finish downloading.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(150)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .build()

    companion object {
        lateinit var instance: App
            private set
    }
}
