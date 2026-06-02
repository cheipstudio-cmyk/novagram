package com.secondream.novagram.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (currently the user's Anthropic API key) at rest
 * using a hardware-backed AES-256/GCM key in the Android Keystore. The
 * Keystore key never leaves secure hardware, so even a rooted device or a
 * raw copy of the DataStore file yields only ciphertext.
 *
 * Format of an encrypted value: "v1:" + base64(iv) + ":" + base64(cipher+tag).
 *
 * Safety: encryption degrades gracefully. If the Keystore is somehow
 * unavailable, [encrypt] returns the plaintext unchanged (storage never
 * breaks) and [decrypt] passes through any non-"v1:" value as legacy
 * plaintext — so keys saved before this was added keep working and get
 * re-encrypted the next time the user saves.
 */
object SecretCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "novagram_secret_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val PREFIX = "v1:"
    private const val GCM_TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * Encrypt [plain] for at-rest storage. On ANY failure returns [plain]
     * unchanged so persistence never breaks — we degrade to plaintext rather
     * than risk losing the user's key.
     */
    fun encrypt(plain: String): String = runCatching {
        if (plain.isEmpty()) return plain
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrDefault(plain)

    /**
     * Decrypt a value produced by [encrypt]. Legacy/plaintext values (no
     * "v1:" prefix) are returned as-is. Returns null only when a v1 value
     * can't be decrypted (e.g. the Keystore key was wiped on a factory
     * reset) — the caller treats that as "no key configured".
     */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return null
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}
