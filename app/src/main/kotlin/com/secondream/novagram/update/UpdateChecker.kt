package com.secondream.novagram.update

import android.util.Log
import com.secondream.novagram.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls the project's GitHub Releases API to see whether a newer
 * version than the one currently installed is available. Exposed via
 * a [StateFlow] so any UI can subscribe and surface a "download new
 * version" affordance — currently the dot on the topbar download icon
 * in [ChatListScreen].
 *
 * Owner-side decisions baked in here:
 *  - We compare against the LATEST published release tag (not pre-
 *    releases / drafts), so a manual `git tag vX` push without an
 *    associated Release won't trigger the dot.
 *  - Versions are compared semantically (split on '.', integers,
 *    leading-zero-tolerant): "0.10.50" > "0.10.9" the way the user
 *    intuitively expects, NOT lexicographically.
 *  - GitHub anonymous rate limit is 60 req/h per IP, more than
 *    enough for one check per app start; we don't poll repeatedly.
 *  - Everything is best-effort. Network failure, parse failure,
 *    invalid tag — all silently leave `updateAvailable` at false so
 *    nothing visible breaks for the user.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val RELEASES_API =
        "https://api.github.com/repos/cheipstudio-cmyk/novagram/releases/latest"

    /** Browser-friendly URL the topbar download button opens on tap. */
    const val RELEASES_PAGE =
        "https://play.google.com/store/apps/details?id=com.secondream.novagram"

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable = _updateAvailable.asStateFlow()

    private val _latestVersion = MutableStateFlow<String?>(null)
    val latestVersion = _latestVersion.asStateFlow()

    /**
     * Hit GitHub once and update the flows. Safe to call from any
     * coroutine — internally switches to IO. Idempotent / re-runnable.
     */
    suspend fun check() {
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(RELEASES_API)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Novagram-UpdateCheck")
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                if (conn.responseCode != 200) {
                    Log.i(TAG, "GitHub returned ${conn.responseCode}, skipping")
                    return@runCatching
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").removePrefix("v")
                if (tag.isBlank()) {
                    Log.i(TAG, "No tag_name in response, skipping")
                    return@runCatching
                }
                _latestVersion.value = tag
                val installed = BuildConfig.VERSION_NAME.removePrefix("v")
                val cmp = compareVersions(installed, tag)
                _updateAvailable.value = cmp < 0
                Log.i(TAG, "Installed=$installed, Latest=$tag, updateAvailable=${cmp < 0}")
            }.onFailure { Log.w(TAG, "Update check failed (non-fatal)", it) }
        }
    }

    /**
     * Compare two semver-ish strings part by part. Returns negative if
     * [a] < [b], 0 if equal, positive if a > b. Missing parts are
     * treated as 0 so "0.10" < "0.10.1".
     */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val da = pa.getOrElse(i) { 0 }
            val db = pb.getOrElse(i) { 0 }
            if (da != db) return da - db
        }
        return 0
    }
}
