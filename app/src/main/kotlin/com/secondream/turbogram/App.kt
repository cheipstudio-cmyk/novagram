package com.secondream.turbogram

import android.app.Application
import com.secondream.turbogram.notifications.NotificationHelper
import com.secondream.turbogram.settings.AppSettings
import com.secondream.turbogram.td.TdClient

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
