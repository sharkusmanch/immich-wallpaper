package dev.immichwall.api

/** Immich asset ids are UUIDs. Anything else is refused before it reaches a URL path or a file name. */
object AssetIds {
    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isUuid(id: String): Boolean = UUID_RE.matches(id)

    /** Throws [ApiException] (code 0) so callers skip the asset the same way they skip a failed one. */
    fun require(id: String) {
        if (!isUuid(id)) throw ApiException(0, "refusing asset id that is not a UUID")
    }
}
