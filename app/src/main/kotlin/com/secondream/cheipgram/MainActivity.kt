package com.secondream.cheipgram

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.secondream.cheipgram.notifications.TdService
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.AppRouter
import com.secondream.cheipgram.ui.theme.CheipGramTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice handled implicitly */ }

    /**
     * When a notification is tapped, NotificationHelper stuffs the source
     * chat id into the launch Intent. We surface it here as a StateFlow so
     * AppRouter can navigate as soon as the auth state is Ready. It's reset
     * to null after navigation so subsequent re-renders don't keep jumping
     * back into the same chat.
     */
    private val pendingChatId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotifPermissionIfNeeded()
        startTdServiceIfPossible()
        pendingChatId.value = intent?.getLongExtra("chatId", 0L)?.takeIf { it != 0L }

        setContent {
            val appearance by AppSettings.appearance.collectAsState(
                initial = com.secondream.cheipgram.settings.AppearancePrefs()
            )
            val chatToOpen by pendingChatId.collectAsState()
            CheipGramTheme(
                themeMode = appearance.themeMode,
                accentColor = appearance.accentColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Unspecified
                ) {
                    AppRouter(
                        pendingChatId = chatToOpen,
                        onChatOpened = { pendingChatId.value = null }
                    )
                }
            }
        }
    }

    /**
     * Called when an existing MainActivity receives a fresh Intent — most
     * commonly because the user tapped a notification while the app was
     * already in the foreground or back stack.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val cid = intent.getLongExtra("chatId", 0L)
        if (cid != 0L) pendingChatId.value = cid
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startTdServiceIfPossible() {
        lifecycleScope.launch {
            val config = AppSettings.apiConfig.first()
            val hasUserCfg = config.apiId != 0 && config.apiHash.isNotBlank()
            val hasBakedCfg = com.secondream.cheipgram.BuildConfig.TG_API_ID != 0 &&
                com.secondream.cheipgram.BuildConfig.TG_API_HASH.isNotBlank()
            if (hasUserCfg || hasBakedCfg) {
                val intent = Intent(this@MainActivity, TdService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }
}
