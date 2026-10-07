package dev.immichwall.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Server URL entry rules: HTTPS only. The API key must never travel in cleartext. */
object ServerUrl {
    /**
     * Normalizes what the user typed. A bare host gets `https://`; the trailing slash is
     * dropped. Returns "" for blank input (the away URL is optional) and null when the
     * value is rejected: an explicit non-HTTPS scheme, not a URL at all, or an address
     * that can read as one host and reach another (see [parse]).
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        parse(withScheme) ?: return null
        return withScheme.trimEnd('/')
    }

    /**
     * [raw] as a request would use it, built from the parsed URL and not from the text:
     * `https://`, the host as it is resolved (lower case; an internationalized name in its
     * `xn--` form), the port unless it is 443, and the path unless it is `/`, percent-encoded
     * (a server may live under a sub-path: API paths are appended to the whole address). No
     * query and no fragment. "" for blank input, null for what [normalize] rejects. For an
     * address that comes from a file, where the text cannot be taken at its word.
     *
     * Also null unless the result parses back to exactly itself: host name mapping can turn
     * a character into a dot and leave a name that reads once but is not a URL when rebuilt.
     * So a non-blank result is always an address that can be stored, shown and requested.
     */
    fun canonical(raw: String): String? {
        val normalized = normalize(raw) ?: return null
        if (normalized.isEmpty()) return ""
        val rebuilt = rebuild(parse(normalized) ?: return null)
        return rebuilt.takeIf { parse(it)?.let(::rebuild) == it }
    }

    private fun rebuild(url: HttpUrl): String = "https://" + hostAndPort(url) + url.encodedPath.trimEnd('/')

    /** The host requests to [address] go to, with the port unless it is 443; null when [address] is blank or rejected. */
    fun hostAndPort(address: String): String? {
        val normalized = normalize(address)?.takeIf { it.isNotEmpty() } ?: return null
        return parse(normalized)?.let(::hostAndPort)
    }

    private fun hostAndPort(url: HttpUrl): String {
        // An IPv6 host comes back without its brackets.
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return if (url.port == HttpUrl.defaultPort("https")) host else "$host:${url.port}"
    }

    /**
     * The parsed HTTPS URL, or null. Also null for what no server address needs and what
     * can make the text read as another host than the one requests go to: user info (in
     * `https://a.example@b.example` the host is b.example), and any control, whitespace or
     * invisible format character (line breaks, right-to-left overrides, zero-width marks).
     */
    private fun parse(withScheme: String): HttpUrl? {
        // By code point: format characters exist outside the basic plane too.
        val hidden = withScheme.codePoints().anyMatch {
            Character.isISOControl(it) || Character.isWhitespace(it) || Character.isSpaceChar(it) ||
                Character.getType(it) == Character.FORMAT.toInt() || Character.getType(it) == Character.SURROGATE.toInt()
        }
        if (hidden) return null
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()) return null
        return url
    }
}
