package com.secondream.novagram.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.secondream.novagram.R

/**
 * Tiny in-app sound effects: a "toc" when the user sends a message and a
 * "ding" when one arrives in the open chat.
 *
 * Backed by [SoundPool] rather than MediaPlayer because these are short,
 * fire-and-forget UI chirps — SoundPool keeps the decoded PCM resident and
 * plays with sub-millisecond latency, and a single instance can overlap
 * several plays without the per-call allocation MediaPlayer would incur.
 *
 * The two clips ship as 16-bit PCM WAVs in res/raw and are preloaded once
 * via [init] (called from App.onCreate). If a play() somehow lands before
 * the asynchronous load finishes — only possible in the first few hundred
 * ms after a cold start — SoundPool simply drops it, which is fine for a
 * decorative blip. All call sites gate on the AppearancePrefs.messageSounds
 * toggle, so when the user turns sounds off we never reach play() at all.
 */
object SoundFx {
    private var pool: SoundPool? = null
    private var sendId: Int = 0
    private var receiveId: Int = 0

    /** Build the SoundPool and start loading both clips. Idempotent. */
    fun init(context: Context) {
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
        val app = context.applicationContext
        sendId = sp.load(app, R.raw.msg_send, 1)
        receiveId = sp.load(app, R.raw.msg_receive, 1)
        pool = sp
    }

    private fun play(soundId: Int) {
        val sp = pool ?: return
        if (soundId == 0) return
        // Slightly under unity so the blip sits beneath voice/media volume.
        runCatching { sp.play(soundId, 0.55f, 0.55f, 1, 0, 1f) }
    }

    /** "toc" — message sent. */
    fun playSend(context: Context) {
        if (pool == null) init(context)
        play(sendId)
    }

    /** "ding" — message received in the open chat. */
    fun playReceive(context: Context) {
        if (pool == null) init(context)
        play(receiveId)
    }
}
