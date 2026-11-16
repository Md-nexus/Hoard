package com.hoard.hoard

import android.net.Uri

/** Platforms Hoard knows about (for optional login and per-platform cookies). */
enum class SocialPlatform(
    val id: String,
    val label: String,
    val loginUrl: String,
    val cookieUrl: String,
    val cookieDomain: String,
    private val hosts: List<String>,
) {
    INSTAGRAM(
        "instagram", "Instagram",
        "https://www.instagram.com/accounts/login/",
        "https://www.instagram.com", ".instagram.com",
        listOf("instagram.com", "instagr.am"),
    ),
    TIKTOK(
        "tiktok", "TikTok",
        "https://www.tiktok.com/login",
        "https://www.tiktok.com", ".tiktok.com",
        listOf("tiktok.com"),
    );

    companion object {
        fun fromId(id: String?): SocialPlatform? = values().firstOrNull { it.id == id }

        fun fromUrl(url: String): SocialPlatform? {
            val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return null
            return values().firstOrNull { p -> p.hosts.any { host == it || host.endsWith(".$it") } }
        }
    }
}
