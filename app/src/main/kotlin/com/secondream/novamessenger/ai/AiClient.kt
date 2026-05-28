package com.secondream.novamessenger.ai

import com.secondream.novamessenger.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny Anthropic Messages API client used by the in-chat AI actions
 * (summarise / draft reply / translate / explain a selected message).
 *
 * Why HttpURLConnection: no new gradle dependency, ships with the JDK,
 * supports HTTPS out of the box, and our payloads are tiny (a few KB
 * up, a few KB down). The non-streaming endpoint is enough for v1 —
 * the UI shows a spinner and then the full response. Streaming via SSE
 * is a future iteration.
 *
 * Errors surface as exceptions so the caller can show a clean message
 * (bad key, no network, rate limit, etc) without parsing magic strings.
 */
object AiClient {

    /** Default model — Claude Sonnet 4.5 sweet-spot for chat-style use. */
    private const val DEFAULT_MODEL = "claude-sonnet-4-5"
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 1024

    /**
     * Send a single user message (with an optional system prompt for
     * persona/style) and return the assistant's text. Throws on any
     * non-2xx response with a message extracted from the JSON error.
     */
    suspend fun complete(
        userPrompt: String,
        systemPrompt: String? = null,
        model: String = DEFAULT_MODEL
    ): String = withContext(Dispatchers.IO) {
        val apiKey = AppSettings.appearance.first().anthropicApiKey
            ?: throw IllegalStateException("Chiave API mancante")

        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", MAX_TOKENS)
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }.toString()

        val url = URL(API_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
            setRequestProperty("content-type", "application/json")
            setRequestProperty("accept", "application/json")
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload)
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                ?: ""
            if (code !in 200..299) {
                // Try to surface the JSON error message; fall back to raw.
                val err = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw RuntimeException(err ?: "HTTP $code")
            }
            // Response shape:
            //   { content: [ { type: "text", text: "..." }, ... ], ... }
            val obj = JSONObject(body)
            val parts = obj.optJSONArray("content") ?: return@withContext ""
            buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.optString("type") == "text") {
                        if (isNotEmpty()) append('\n')
                        append(part.optString("text"))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
