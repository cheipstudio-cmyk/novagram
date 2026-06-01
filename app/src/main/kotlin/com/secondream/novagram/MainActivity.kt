package com.secondream.novagram

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.secondream.novagram.notifications.TdService
import com.secondream.novagram.R
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.td.TdClient
import com.secondream.novagram.ui.AppRouter
import com.secondream.novagram.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice handled implicitly */ }

    /**
     * Carries the chat to open and (optionally) the in-chat message to
     * scroll to. Used by the deep-link path so t.me/<user>/<msg> can
     * thread the msg id all the way into ChatScreen.targetMessageId —
     * previously this was Long? with only chatId and the msg id was
     * silently dropped at parse time, leaving the link broken on the
     * message-jump dimension.
     */
    private data class PendingOpen(val chatId: Long, val msgId: Long? = null)

    private val pendingChatId = MutableStateFlow<PendingOpen?>(null)

    /**
     * Apply the user's per-app locale before any onCreate runs, so resource
     * lookups (stringResource, etc.) see the right strings.xml. AppCompat's
     * setApplicationLocales does not work on ComponentActivity, so we wrap
     * the base context manually via LocaleHelper.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        AppSettings.init(newBase)
        val tag = kotlinx.coroutines.runBlocking { AppSettings.currentLanguageTag() }
        super.attachBaseContext(com.secondream.novagram.util.LocaleHelper.wrap(newBase, tag))
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
        com.secondream.novagram.transfer.TransferTracker.start()
        // Push the stored auto-download preference down to TDLib so its
        // own background auto-download honors the user's toggle (our
        // per-bubble gating alone can't stop TDLib's internal presets).
        kotlinx.coroutines.GlobalScope.launch {
            val pref = com.secondream.novagram.settings.AppSettings.appearance
                .first().autoDownloadMedia
            runCatching { com.secondream.novagram.td.TdClient.applyAutoDownloadSetting(pref) }
        }
        pendingChatId.value = intent?.getLongExtra("chatId", 0L)
            ?.takeIf { it != 0L }
            ?.let { PendingOpen(it) }
        handleThemeDeeplink(intent)
        handleTmeDeeplink(intent)

        setContent {
            val appearance by AppSettings.appearance.collectAsState(
                initial = com.secondream.novagram.settings.AppearancePrefs()
            )
            val chatToOpen by pendingChatId.collectAsState()
            // Download-panel "jump to message" requests: the global transfer
            // tracker raises (chatId, messageId) when the user taps a transfer
            // row; we feed it through the same pendingChatId path a deep link
            // uses, so AppRouter opens the chat and ChatScreen scrolls to the
            // message that's downloading the file.
            val transferJump by com.secondream.novagram.transfer.TransferTracker
                .jumpRequest.collectAsState()
            androidx.compose.runtime.LaunchedEffect(transferJump) {
                transferJump?.let { (cid, mid) ->
                    pendingChatId.value = PendingOpen(cid, mid)
                    com.secondream.novagram.transfer.TransferTracker.consumeJump()
                }
            }
            NovaTheme(
                themeMode = appearance.themeMode,
                accentColor = appearance.accentColor,
                customAccentArgb = appearance.customAccentArgb,
                customBgArgb = appearance.customBgArgb,
                textScale = appearance.textScale
            ) {
                // Sync the Activity's window background to the Compose theme
                // background. The static @color/ink_bg in themes.xml is dark,
                // so when the user is in light mode every recomposition that
                // briefly exposes the window (transitions, IME shifts) would
                // flash dark. SideEffect re-runs on every successful
                // composition and applies the live theme color.
                val bgArgb = MaterialTheme.colorScheme.background.toArgb()
                androidx.compose.runtime.SideEffect {
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgArgb))
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // CRITICAL: must paint the theme background, not be
                    // transparent. With Color.Unspecified the Surface drew
                    // nothing, so during the AppRouter scaleIn transition
                    // (92% → 100%) the 8% margin around the new screen
                    // revealed the window's hardcoded dark @color/ink_bg
                    // — a black flash on every navigation in light themes.
                    // Tracking colorScheme.background means the empty area
                    // is always the right color for the active theme.
                    color = MaterialTheme.colorScheme.background
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
                            pendingChatId = chatToOpen?.chatId,
                            pendingMsgId = chatToOpen?.msgId,
                            onChatOpened = { pendingChatId.value = null }
                        )
                        // Anchored to the TOP (notification-banner style) so
                        // it never overlaps the message input bar / send
                        // button at the bottom of a chat.
                        com.secondream.novagram.transfer.TransferPanel(
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        )
                        // Global animated snackbar ("chat eliminata", "membro
                        // bannato", "messaggio fissato", …). Bottom-anchored,
                        // above the system nav bar.
                        com.secondream.novagram.ui.components.NovaSnackbarHost(
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp)
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
        if (cid != 0L) pendingChatId.value = PendingOpen(cid)
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
     *  - t.me/USERNAME                       → public chat
     *  - t.me/USERNAME/123                   → public chat + scroll to msg 123
     *  - t.me/c/123456/789                   → private supergroup msg 789
     *  - t.me/joinchat/HASH                  → join private group/channel
     *  - t.me/+HASH                          → idem (newer Telegram format)
     *  - tg://resolve?domain=USERNAME        → public chat
     *  - tg://resolve?domain=USERNAME&post=N → public chat + scroll to msg N
     *  - tg://join?invite=HASH               → join private
     *
     * For msg-id forms, the server-side id from the URL is shifted left
     * 20 bits before being passed downstream — TDLib stores the canonical
     * message id as (serverId shl 20), reserving the low 20 bits for
     * channel-subdivision routing. Without the shift, the jump target
     * never matches anything in the loaded window and the scroll bails
     * out silently — exactly what the previous code did when it dropped
     * segments[1] entirely.
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
        // Server-side message id pulled from the deep-link, if any. We
        // translate to TDLib's internal representation below (server id
        // is shifted left 20 bits — TDLib reserves the lower 20 bits
        // for channel subdivision routing; without the shift, the id
        // we'd hand to ChatScreen.targetMessageId / getChatHistory
        // doesn't match anything in the loaded window and the jump
        // bails out silently). Stays null when the URL only points
        // to a chat, no specific message.
        var serverMessageId: Long? = null
        // For private channels (/c/<internalId>/<msgId>) the chat is
        // identified by an internal numeric id, not a username. We
        // resolve via TDLib's getSupergroup using `1000000000000L +
        // internalId` which is the canonical chatId encoding for
        // supergroups, then jump to the linked msg.
        var privateChannelInternalId: Long? = null
        if (isWeb) {
            // path looks like "/USERNAME", "/USERNAME/123",
            // "/joinchat/HASH", "/+HASH", or "/c/<id>/<msg>".
            val segments = data.pathSegments.orEmpty()
            val first = segments.firstOrNull()
            when {
                first.isNullOrBlank() -> return
                first == "joinchat" && segments.size >= 2 ->
                    inviteLink = "https://t.me/joinchat/${segments[1]}"
                first.startsWith("+") ->
                    inviteLink = "https://t.me/$first"
                // Private channel deep-link form: /c/<internalId>/<msgId>.
                // The numeric internalId is the supergroup id without the
                // -100... prefix; TDLib chatId = -100<internalId>·10^N
                // for the canonical conversion. We parse and let the
                // resolver below convert to chatId via getSupergroup.
                first == "c" && segments.size >= 2 -> {
                    privateChannelInternalId = segments[1].toLongOrNull()
                    if (segments.size >= 3) {
                        serverMessageId = segments[2].toLongOrNull()
                    }
                }
                else -> {
                    username = first
                    // segments[1] (when present) is the public message id
                    // — e.g. t.me/durov/123 → username=durov, msg=123.
                    if (segments.size >= 2) {
                        serverMessageId = segments[1].toLongOrNull()
                    }
                }
            }
        } else {
            when (data.host) {
                "resolve" -> {
                    username = data.getQueryParameter("domain")
                    // tg://resolve?domain=USERNAME&post=123 — same
                    // server-id semantics as the web /USERNAME/123 form.
                    serverMessageId = data.getQueryParameter("post")?.toLongOrNull()
                }
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
                        com.secondream.novagram.td.TdClient
                            .joinChatByInviteLink(inviteLink).id
                    }
                    privateChannelInternalId != null -> {
                        // The canonical supergroup chatId for internalId N
                        // is -1_000_000_000_000 - N (TDLib convention).
                        // We open it directly; if we're not a member yet
                        // ChatScreen will show the Join CTA upstream.
                        val cid = -1_000_000_000_000L - privateChannelInternalId
                        runCatching { com.secondream.novagram.td.TdClient.getChat(cid) }
                        cid
                    }
                    username != null -> {
                        com.secondream.novagram.td.TdClient
                            .searchPublicChat(username).id
                    }
                    else -> null
                }
            }.getOrNull()
            if (chatId != null && chatId != 0L) {
                // Apply the shl 20 transform iff we have a server-side
                // msg id from the URL. ChatScreen.targetMessageId then
                // matches what getChatHistory returns and the jump
                // lands correctly.
                val tdMsgId = serverMessageId?.takeIf { it > 0 }?.let { it shl 20 }
                pendingChatId.value = PendingOpen(chatId, tdMsgId)
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
        if (data.scheme != "novagram" || data.host != "theme") return
        val encoded = data.getQueryParameter("data") ?: return
        lifecycleScope.launch {
            val applied = runCatching {
                val bytes = android.util.Base64.decode(
                    encoded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
                )
                val json = String(bytes, Charsets.UTF_8)
                com.secondream.novagram.ui.screens.parseThemeJson(json)
            }.getOrNull()
            if (applied != null) {
                // Save to the user's themes list rather than apply
                // directly — protects the currently-active theme from
                // being silently overwritten by an incoming deep link.
                // The user activates from Settings → Temi salvati when
                // they're ready to switch.
                AppSettings.importAppearanceAsSavedTheme(
                    appearance = applied,
                    baseName = getString(R.string.theme_imported_default_name)
                )
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
            val hasBakedCfg = com.secondream.novagram.BuildConfig.TG_API_ID != 0 &&
                com.secondream.novagram.BuildConfig.TG_API_HASH.isNotBlank()
            if (hasUserCfg || hasBakedCfg) {
                val intent = Intent(this@MainActivity, TdService::class.java)
                ContextCompat.startForegroundService(this@MainActivity, intent)
            }
        }
    }
}
