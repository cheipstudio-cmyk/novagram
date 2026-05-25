package com.secondream.turbogram

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
import com.secondream.turbogram.notifications.TdService
import com.secondream.turbogram.settings.AppSettings
import com.secondream.turbogram.td.AuthState
import com.secondream.turbogram.td.TdClient
import com.secondream.turbogram.ui.AppRouter
import com.secondream.turbogram.ui.theme.TurbogramTheme

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
            TurbogramTheme {
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
            val hasBakedCfg = com.secondream.turbogram.BuildConfig.TG_API_ID != 0 &&
                com.secondream.turbogram.BuildConfig.TG_API_HASH.isNotBlank()
            if (hasUserCfg || hasBakedCfg) {
                val intent = Intent(this@MainActivity, TdService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }
}
