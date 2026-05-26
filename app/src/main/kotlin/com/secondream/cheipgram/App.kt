package com.secondream.cheipgram

import android.app.Application
import com.secondream.cheipgram.notifications.NotificationHelper
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.TdClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.init(this)
        NotificationHelper.init(this)
        TdClient.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
