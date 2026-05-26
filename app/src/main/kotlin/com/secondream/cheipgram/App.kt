package com.secondream.cheipgram

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.runBlocking
import com.secondream.cheipgram.notifications.NotificationHelper
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.TdClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.init(this)

        // Apply persisted language before any UI is shown. AppCompatDelegate
        // remembers the choice across process restarts via its own storage,
        // but reading it back from DataStore here keeps the source of truth
        // single (DataStore), which is what the Settings screen edits.
        val tag = runBlocking { AppSettings.currentLanguageTag() }
        val locales = if (tag == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)

        NotificationHelper.init(this)
        TdClient.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
