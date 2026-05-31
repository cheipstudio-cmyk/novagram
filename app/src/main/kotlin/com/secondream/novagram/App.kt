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
        // Preload the send/receive sound effects so the first blip is instant.
        com.secondream.novagram.util.SoundFx.init(this)

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
            override fun onStart(owner: LifecycleOwner) {
                AppForegroundState.isInForeground = true
                // Re-read connectivity state on foreground entry. Some OEM
                // ROMs throttle our NetworkCallback while the app is
                // backgrounded so a transition that happened during that
                // window can be missed. A synchronous re-read here makes
                // the banner / send-gating accurate the instant the user
                // returns to the app, independent of callback timing.
                com.secondream.novagram.connectivity.ConnectivityState.recheck()
                // Re-run the GitHub release check on every foreground
                // entry. The check inside onCreate above only fires on
                // cold start, but Android can keep this process alive
                // for days across many app->home->app cycles. Without a
                // re-check the accent dot on Settings would still show
                // the version we saw at process start, missing fresh
                // builds the user shipped while the app was backgrounded.
                // 60 req/h anonymous rate limit + once-per-foreground
                // frequency = plenty of headroom.
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    com.secondream.novagram.update.UpdateChecker.check()
                }
            }
            override fun onStop(owner: LifecycleOwner) { AppForegroundState.isInForeground = false }
        })

        NotificationHelper.init(this)
        TdClient.init(this)
        com.secondream.novagram.connectivity.ConnectivityState.start(this)
        // Apply the launcher-icon variant matching the user's saved
        // theme on every process start. Idempotent — if nothing
        // changed, PackageManager no-ops. Wrapped in a launch on
        // GlobalScope (not a process-tied scope) because the
        // DataStore read suspends and we don't want to block onCreate.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            IconAliasManager.applyFromSettings(this@App)
        }

        // Re-apply the launcher icon every time the app goes BACKGROUND.
        // Reason: the v0.10.51 fix removed the runtime icon swap from
        // setThemeMode (to stop the mid-session crash), so the icon
        // only re-evaluates on cold start. But Android frequently keeps
        // the process alive between recents-swipe-away and re-open, so
        // a user who changes theme + reopens the app fast never gets a
        // fresh onCreate and the launcher icon stays on the old variant.
        // ProcessLifecycleOwner.ON_STOP fires when the LAST visible
        // surface leaves the screen — i.e. the user is now on the home
        // screen / another app, not in Novagram. That's exactly the
        // moment when pm.setComponentEnabledSetting is safe to call:
        // any kill it triggers on hostile OEM ROMs happens invisibly,
        // and the next time the user opens the app from the launcher,
        // they see the right icon already applied.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        IconAliasManager.applyFromSettings(this@App)
                    }
                }
            }
        )

        // Update check against GitHub Releases — pure background, the
        // result lands in UpdateChecker.updateAvailable which the chat-
        // list topbar's download button watches to show its accent dot.
        // Best-effort: any network / parse / rate-limit failure leaves
        // the flow at false (= no dot, button still works for manual
        // visit to releases page).
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.secondream.novagram.update.UpdateChecker.check()
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
