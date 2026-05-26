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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.secondream.cheipgram.notifications.TdService
import com.secondream.cheipgram.settings.AppSettings
import com.secondream.cheipgram.td.AuthState
import com.secondream.cheipgram.td.TdClient
import com.secondream.cheipgram.ui.AppRouter
import com.secondream.cheipgram.ui.theme.CheipGramTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice handled implicitly */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotifPermissionIfNeeded()
        startTdServiceIfPossible()

        setContent {
            val appearance by AppSettings.appearance.collectAsState(
                initial = com.secondream.cheipgram.settings.AppearancePrefs()
            )
            CheipGramTheme(
                themeMode = appearance.themeMode,
                accentColor = appearance.accentColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Unspecified
                ) {
                    AppRouter()
                }
            }
        }
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
