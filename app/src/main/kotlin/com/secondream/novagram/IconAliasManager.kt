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

        // Idempotency check: PackageManager.setComponentEnabledSetting
        // is NOT free on a hot path — every call goes to system_server,
        // serializes through a binder transaction, and (despite the
        // DONT_KILL_APP flag) can still kill our process when the
        // currently-active LAUNCHER alias is the one being disabled.
        // That kill is exactly what Eugenio kept hitting on every
        // theme switch: the activity died mid-recreate, taking the
        // user back to the launcher with a freshly-changed icon and
        // forcing them to reopen the app.
        //
        // The fix is dual:
        //   (1) skip the work entirely when both aliases are already
        //       in the desired state — most theme switches involve no
        //       icon swap at all (e.g. Dark → Amoled, Light → System
        //       on a phone in light mode).
        //   (2) ENABLE the new variant BEFORE DISABLING the old one,
        //       so the OS always sees at least one launcher entry
        //       point alive and never decides we have no home screen
        //       presence to keep alive.
        runCatching {
            val curDark = pm.getComponentEnabledSetting(darkComp)
            val curLight = pm.getComponentEnabledSetting(lightComp)
            val wantDarkEnabled = useDark
            val wantLightEnabled = !useDark

            // PackageManager returns DEFAULT (0) before any explicit
            // setting; we treat DEFAULT as "follows the manifest"
            // which for ic_launcher_dark is enabled=true (declared
            // as the active alias in the manifest), and for
            // ic_launcher_light enabled=false. Map to a concrete
            // boolean so the comparison below is well-defined.
            fun resolve(state: Int, manifestDefault: Boolean): Boolean = when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                else -> manifestDefault
            }
            val isDarkEnabled = resolve(curDark, manifestDefault = true)
            val isLightEnabled = resolve(curLight, manifestDefault = false)

            if (isDarkEnabled == wantDarkEnabled && isLightEnabled == wantLightEnabled) {
                Log.i(TAG, "icon variant unchanged (mode=$mode, useDark=$useDark) — skipping pm call")
                return@runCatching
            }

            // Enable target FIRST so a LAUNCHER intent-filter is always
            // alive, THEN disable the other. This sequencing minimises
            // the "no launcher entry point" window that can trip the
            // process-kill heuristic.
            val toEnable = if (useDark) darkComp else lightComp
            val toDisable = if (useDark) lightComp else darkComp
            pm.setComponentEnabledSetting(
                toEnable,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                toDisable,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
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
