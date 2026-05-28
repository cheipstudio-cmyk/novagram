package com.secondream.novamessenger

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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.secondream.novamessenger.notifications.TdService
import com.secondream.novamessenger.R
import com.secondream.novamessenger.settings.AppSettings
import com.secondream.novamessenger.td.TdClient
import com.secondream.novamessenger.ui.AppRouter
import com.secondream.novamessenger.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice handled implicitly */ }

    private val pendingChatId = MutableStateFlow<Long?>(null)

    /**
     * Apply the user's per-app locale before any onCreate runs, so resource
     * lookups (stringResource, etc.) see the right strings.xml. AppCompat's
     * setApplicationLocales does not work on ComponentActivity, so we wrap
     * the base context manually via LocaleHelper.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        AppSettings.init(newBase)
        val tag = kotlinx.coroutines.runBlocking { AppSettings.currentLanguageTag() }
        super.attachBaseContext(com.secondream.novamessenger.util.LocaleHelper.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotifPermissionIfNeeded()
        startTdServiceIfPossible()
        // App-wide transfer tracker: starts collecting fileUpdates once
        // TDLib is up. Idempotent — multiple calls are no-ops. Lives for
        // the process lifetime so the persistent panel can stay current
        // across screen changes.
        com.secondream.novamessenger.transfer.TransferTracker.start()
        // Push the stored auto-download preference down to TDLib so its
        // own background auto-download honors the user's toggle (our
        // per-bubble gating alone can't stop TDLib's internal presets).
        kotlinx.coroutines.GlobalScope.launch {
            val pref = com.secondream.novamessenger.settings.AppSettings.appearance
                .first().autoDownloadMedia
            runCatching { com.secondream.novamessenger.td.TdClient.applyAutoDownloadSetting(pref) }
        }
        pendingChatId.value = intent?.getLongExtra("chatId", 0L)?.takeIf { it != 0L }
        handleThemeDeeplink(intent)
        handleTmeDeeplink(intent)

        setContent {
            val appearance by AppSettings.appearance.collectAsState(
                initial = com.secondream.novamessenger.settings.AppearancePrefs()
            )
            val chatToOpen by pendingChatId.collectAsState()
            NovaTheme(
                themeMode = appearance.themeMode,
                accentColor = appearance.accentColor,
                customAccentArgb = appearance.customAccentArgb,
                customBgArgb = appearance.customBgArgb,
                textScale = appearance.textScale
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Unspecified
                ) {
                    // Box wraps both the nav graph and the persistent
                    // transfer panel overlay. The panel sits at the
                    // bottom and is only painted when there are active
                    // transfers; otherwise it's invisible and consumes
                    // no layout space.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AppRouter(
                            pendingChatId = chatToOpen,
                            onChatOpened = { pendingChatId.value = null }
                        )
                        // Anchored to the TOP (notification-banner style) so
                        // it never overlaps the message input bar / send
                        // button at the bottom of a chat.
                        com.secondream.novamessenger.transfer.TransferPanel(
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        )
                    }
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
        handleThemeDeeplink(intent)
        handleTmeDeeplink(intent)
    }

    /**
     * Handle t.me / telegram.me / telegram.dog / tg: URLs that route into
     * this app via the manifest intent-filter. Parses the URL into either
     * a public @username or a join-hash, then resolves through TDLib and
     * pushes the resulting chatId onto pendingChatId so AppRouter navigates
     * there.
     *
     * Supported forms:
     *  - t.me/USERNAME                 → public chat
     *  - t.me/USERNAME/123             → public chat (msg id ignored for now)
     *  - t.me/joinchat/HASH            → join private group/channel
     *  - t.me/+HASH                    → idem (newer Telegram format)
     *  - tg://resolve?domain=USERNAME  → public chat
     *  - tg://join?invite=HASH         → join private
     *
     * Anything else (sticker sets, settings deeplinks, etc.) is silently
     * ignored — we don't want to throw at the user, just no-op.
     */
    private fun handleTmeDeeplink(intent: Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme ?: return
        val host = data.host ?: ""
        // Reject anything that isn't a Telegram-flavored URL or our own
        // theme scheme (which has its own handler).
        val isWeb = scheme in listOf("http", "https") &&
            host in listOf("t.me", "telegram.me", "telegram.dog")
        val isTg = scheme == "tg"
        if (!isWeb && !isTg) return

        // Pull username / joinHash out of the URL.
        var username: String? = null
        var inviteLink: String? = null
        if (isWeb) {
            // path looks like "/USERNAME" or "/joinchat/HASH" or "/+HASH"
            val segments = data.pathSegments.orEmpty()
            val first = segments.firstOrNull()
            when {
                first.isNullOrBlank() -> return
                first == "joinchat" && segments.size >= 2 ->
                    inviteLink = "https://t.me/joinchat/${segments[1]}"
                first.startsWith("+") ->
                    inviteLink = "https://t.me/$first"
                else -> username = first
            }
        } else {
            when (data.host) {
                "resolve" -> username = data.getQueryParameter("domain")
                "join" -> {
                    val invite = data.getQueryParameter("invite")
                    if (invite != null) inviteLink = "https://t.me/+$invite"
                }
                else -> return
            }
        }

        lifecycleScope.launch {
            val chatId = runCatching {
                when {
                    inviteLink != null -> {
                        com.secondream.novamessenger.td.TdClient
                            .joinChatByInviteLink(inviteLink).id
                    }
                    username != null -> {
                        com.secondream.novamessenger.td.TdClient
                            .searchPublicChat(username).id
                    }
                    else -> null
                }
            }.getOrNull()
            if (chatId != null && chatId != 0L) {
                pendingChatId.value = chatId
            }
        }
    }

    /**
     * Handle nova://theme?data=<base64-url-safe-json> intents. The data
     * payload is the same JSON we produce in SettingsScreen.buildThemeShareJson,
     * base64-encoded with URL_SAFE | NO_WRAP so it survives plain-text channels
     * like Telegram and email line wrapping.
     *
     * Anything that fails to decode or parse is treated as a no-op with a
     * Toast — we never half-apply a theme.
     */
    private fun handleThemeDeeplink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "cheipgram" || data.host != "theme") return
        val encoded = data.getQueryParameter("data") ?: return
        lifecycleScope.launch {
            val applied = runCatching {
                val bytes = android.util.Base64.decode(
                    encoded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                val json = String(bytes, Charsets.UTF_8)
                com.secondream.novamessenger.ui.screens.parseThemeJson(json)
            }.getOrNull()
            if (applied != null) {
                AppSettings.applyAppearance(applied)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(R.string.theme_paste_success),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    getString(R.string.theme_paste_error),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
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
            val hasBakedCfg = com.secondream.novamessenger.BuildConfig.TG_API_ID != 0 &&
                com.secondream.novamessenger.BuildConfig.TG_API_HASH.isNotBlank()
            if (hasUserCfg || hasBakedCfg) {
                val intent = Intent(this@MainActivity, TdService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }
}
