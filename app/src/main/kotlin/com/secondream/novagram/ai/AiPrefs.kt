package com.secondream.novagram.ai

import android.content.Context

/**
 * User-facing model tiers, all served by Anthropic. Names are branded
 * ("Novagram AI ...") so the raw model strings never leak into the UI:
 * Basic is the fast/cheap model, Business the top one (default).
 */
enum class AiTier(
    val display: String,
    val short: String,
    val model: String
) {
    BASIC("Novagram AI Basic", "Basic", "claude-haiku-4-5-20251001"),
    PRO("Novagram AI Pro", "Pro", "claude-sonnet-4-6"),
    BUSINESS("Novagram AI Business", "Business", "claude-opus-4-8")
}

/** Tiny SharedPreferences store for the chosen tier. */
object AiPrefs {
    private const val PREFS = "novagram_ai"
    private const val K_TIER = "tier"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getTier(ctx: Context): AiTier {
        val name = prefs(ctx).getString(K_TIER, AiTier.BUSINESS.name) ?: AiTier.BUSINESS.name
        return runCatching { AiTier.valueOf(name) }.getOrDefault(AiTier.BUSINESS)
    }

    fun setTier(ctx: Context, tier: AiTier) {
        prefs(ctx).edit().putString(K_TIER, tier.name).apply()
    }
}
