package com.secondream.novagram.notifications

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.secondream.novagram.td.TdClient

class TdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.SERVICE_NOTIF_ID,
                NotificationHelper.buildServiceNotification(),
                // REMOTE_MESSAGING is the foreground service type Android
                // 14 (API 34) added specifically for messaging clients
                // that hold an open connection to a chat backend to
                // deliver incoming messages in real-time. It's the
                // correct match for what TdService actually does (keep
                // TDLib's mtproto link alive while the app is
                // backgrounded so notifications fire instantly), and
                // crucially the Play Console treats it under the
                // "Messaging app" foreground-service category — which
                // is pre-approved without a demo video, unlike the
                // generic dataSync "Other" bucket we were stuck on.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(
                NotificationHelper.SERVICE_NOTIF_ID,
                NotificationHelper.buildServiceNotification()
            )
        }

        collectJob = scope.launch {
            TdClient.newMessages.collect { msg ->
                NotificationHelper.showMessage(msg)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
