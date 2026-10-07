package dev.immichwall.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Server URL entry rules: HTTPS only. The API key must never travel in cleartext. */
object ServerUrl {
    /**
     * Normalizes what the user typed. A bare host gets `https://`; the trailing slash is
     * dropped. Returns "" for blank input (the away URL is optional) and null when the
     * value is rejected: an explicit non-HTTPS scheme, or not a URL at all.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        return withScheme.trimEnd('/')
    }
}
