package com.secondream.novagram

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import com.secondream.novagram.settings.AppSettings
import com.secondream.novagram.settings.ThemeMode
import kotlinx.coroutines.flow.firstOrNull

/**
 * Swaps the launcher icon between dark and light brand variants to
 * match the current effective theme.
 *
 * Android can't hot-swap a single icon resource based on dark / light
 * mode — the system launcher caches the icon at install time. The
 * standard workaround is the activity-alias pattern: declare one alias
 * per icon, enable only ONE at any moment via
 * PackageManager.setComponentEnabledSetting. The launcher picks up the
 * change on its next refresh cycle (Pixel Launcher usually within
 * seconds; some OEM launchers may need an icon re-cache or restart).
 *
 * Mapping from app preferences to icon:
 *  - ThemeMode.Dark   → LauncherDark
 *  - ThemeMode.Amoled → LauncherDark
 *  - ThemeMode.Light  → LauncherLight
 *  - ThemeMode.System → follow UI_MODE_NIGHT_*:
 *      yes → LauncherDark, no → LauncherLight
 *
 * On a fresh install the manifest enables LauncherDark by default, so
 * a user who never visits Settings keeps the dark icon — matching the
 * default themeMode of System and the assumption most modern Android
 * phones run in dark mode.
 *
 * Call [apply] from App.onCreate (once) and whenever the user changes
 * themeMode in the Settings screen. Calling with no actual change is
 * cheap — PackageManager no-ops.
 */
object IconAliasManager {
    private const val TAG = "IconAliasManager"
    private const val ALIAS_DARK = "com.secondream.novagram.LauncherDark"
    private const val ALIAS_LIGHT = "com.secondream.novagram.LauncherLight"

    private fun shouldUseDark(ctx: Context, mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.Dark, ThemeMode.Amoled -> true
        ThemeMode.Light -> false
        ThemeMode.System -> {
            val night = ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
            night == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /**
     * Apply the icon variant matching [mode]. Idempotent — calling on
     * every Application onCreate AND every theme change is fine.
     *
     * DONT_KILL_APP keeps our process alive across the alias flip
     * (without it Android would kill us, dropping the user from
     * mid-chat the moment they change theme).
     */
    fun apply(ctx: Context, mode: ThemeMode) {
        val pm = ctx.packageManager
        val darkComp = ComponentName(ctx, ALIAS_DARK)
        val lightComp = ComponentName(ctx, ALIAS_LIGHT)
        val useDark = shouldUseDark(ctx, mode)
        runCatching {
            pm.setComponentEnabledSetting(
                darkComp,
                if (useDark) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                lightComp,
                if (useDark) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.i(TAG, "icon variant applied: ${if (useDark) "dark" else "light"} (mode=$mode)")
        }.onFailure { Log.w(TAG, "icon alias switch failed", it) }
    }

    /**
     * Read the current themeMode out of AppSettings and apply the
     * matching variant. Used by App.onCreate so we don't have to
     * re-read prefs at every call site.
     */
    suspend fun applyFromSettings(ctx: Context) {
        val mode = runCatching {
            AppSettings.appearance.firstOrNull()?.themeMode
        }.getOrNull() ?: ThemeMode.System
        apply(ctx, mode)
    }
}
