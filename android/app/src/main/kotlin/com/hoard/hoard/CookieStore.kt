package com.hoard.hoard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-platform login cookies, stored as a Netscape cookies.txt encrypted with an Android Keystore
 * key (AES-256-GCM). The plaintext only exists briefly in the cache dir while a download runs.
 */
object CookieStore {
    /** The WebView login and yt-dlp use the same UA so the session is less likely to be flagged. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private const val ALIAS = "hoard_cookie_key"
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun file(ctx: Context, p: SocialPlatform) = File(ctx.filesDir, "cookies_${p.id}.enc")

    fun has(ctx: Context, p: SocialPlatform): Boolean = file(ctx, p).exists()

    /** True when the WebView currently holds a logged-in session for [p]. */
    fun webViewHasSession(p: SocialPlatform): Boolean =
        CookieManager.getInstance().getCookie(p.cookieUrl)?.contains("sessionid=") == true

    /** Exports the WebView cookies for [p] into encrypted storage. Returns false if not logged in. */
    fun saveFromWebView(ctx: Context, p: SocialPlatform): Boolean {
        CookieManager.getInstance().flush()
        val raw = CookieManager.getInstance().getCookie(p.cookieUrl) ?: return false
        if (!raw.contains("sessionid=")) return false
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        for (part in raw.split(";")) {
            val kv = part.trim()
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val name = kv.substring(0, eq)
            val value = kv.substring(eq + 1)
            sb.append("${p.cookieDomain}\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(sb.toString().toByteArray())
        file(ctx, p).writeBytes(out)
        return true
    }

    /** Decrypts the cookies to [dest] for one yt-dlp run. Caller must delete [dest] afterwards. */
    fun writeTemp(ctx: Context, p: SocialPlatform, dest: File): Boolean {
        val f = file(ctx, p)
        if (!f.exists()) return false
        return try {
            val data = f.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, IV_LEN))
            )
            dest.writeBytes(cipher.doFinal(data, IV_LEN, data.size - IV_LEN))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(ctx: Context, p: SocialPlatform) {
        file(ctx, p).delete()
    }
}
