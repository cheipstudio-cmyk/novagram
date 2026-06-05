package com.secondream.novagram.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny on-disk store for AI conversations so the context survives closing the
 * modal and restarting the app. Keyed per surface: "home" for the global
 * recap, "chat:<id>" for a specific chat. Message-context exchanges are
 * ephemeral and never persisted. Everything is kept in a single small JSON
 * file in filesDir; access is synchronized and best-effort (a corrupt or
 * missing file just yields an empty conversation).
 */
object AiMemory {
    private const val FILE = "ai_conversations.json"
    private val cache = HashMap<String, MutableList<Pair<String, String>>>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) return@runCatching
            val root = JSONObject(f.readText())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = root.optJSONArray(key) ?: continue
                val list = ArrayList<Pair<String, String>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(o.optString("r", "user") to o.optString("t", ""))
                }
                cache[key] = list
            }
        }
    }

    @Synchronized
    fun load(ctx: Context, key: String): List<Pair<String, String>> {
        ensureLoaded(ctx)
        return cache[key]?.toList() ?: emptyList()
    }

    @Synchronized
    fun save(ctx: Context, key: String, convo: List<Pair<String, String>>) {
        ensureLoaded(ctx)
        if (convo.isEmpty()) cache.remove(key) else cache[key] = convo.toMutableList()
        persist(ctx)
    }

    @Synchronized
    fun clear(ctx: Context, key: String) {
        ensureLoaded(ctx)
        cache.remove(key)
        persist(ctx)
    }

    private fun persist(ctx: Context) {
        runCatching {
            val root = JSONObject()
            cache.forEach { (key, list) ->
                val arr = JSONArray()
                list.forEach { (r, t) -> arr.put(JSONObject().put("r", r).put("t", t)) }
                root.put(key, arr)
            }
            File(ctx.filesDir, FILE).writeText(root.toString())
        }
    }
}
