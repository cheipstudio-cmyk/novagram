package com.secondream.novagram.util

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.secondream.novagram.ui.theme.LocalHapticsEnabled

/**
 * Central, toggle-aware haptics. Every vibration in the app routes through here
 * so the single "Vibrazione" switch in Settings (AppearancePrefs.hapticsEnabled,
 * surfaced via LocalHapticsEnabled) governs all of it.
 *
 * Two backends:
 *  - Compose [HapticFeedback] (LongPress / TextHandleMove): no permission, no
 *    API gating, used for standard press feedback.
 *  - The system [Vibrator]: crisper custom patterns (a tick on send, a
 *    double-tap "confirm" on reactions). VIBRATE is declared in the manifest.
 *
 * Every method is a no-op when haptics are disabled or no vibrator exists.
 */
class Haptics(
    private val enabled: Boolean,
    private val hf: HapticFeedback,
    private val vibrator: Vibrator?,
) {
    /** Light selection tick — tab switches, toggles, minor selection. */
    fun light() {
        if (!enabled) return
        hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Standard press — long-press menus, meaningful taps. */
    fun longPress() {
        if (!enabled) return
        hf.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Short crisp tick via the vibrator (e.g. sending a message). */
    fun tick(ms: Long = 16L) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /** A satisfying double-tap to confirm an action (e.g. reaction added). */
    fun confirm() {
        if (!enabled) return
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            hf.performHapticFeedback(HapticFeedbackType.LongPress)
            return
        }
        runCatching {
            // createWaveform is API 26+, so no version gate needed (minSdk 26).
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 12L, 40L, 12L), -1))
        }
    }
}

/**
 * Build a [Haptics] bound to the current haptics-enabled flag and a vibrator.
 * Cheap to call from any composable; recomposes only when the toggle flips.
 */
@Composable
fun rememberHaptics(): Haptics {
    val enabled = LocalHapticsEnabled.current
    val hf = LocalHapticFeedback.current
    val context = LocalContext.current
    val vibrator = remember(context) {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
    return remember(enabled, hf, vibrator) { Haptics(enabled, hf, vibrator) }
}
