package dev.immichwall.backup

import dev.immichwall.api.ServerUrl

/**
 * Decisions about what a restored backup may become. The codec does not validate values, so a
 * hand-edited file can carry anything; these rules keep the stored settings to what the app's
 * own screens could have stored.
 */
object BackupRestore {
    const val CACHE_COUNT_MIN = 50
    const val CACHE_COUNT_MAX = 300
    const val CACHE_COUNT_STEP = 10

    /** The refresh intervals (hours) the options screen offers. */
    val REFRESH_HOURS_CHOICES = listOf(3, 6, 12, 24)
    const val DEFAULT_REFRESH_HOURS = 6

    /** The rotation cadences (minutes) the options screen offers; 0 = at every wake. */
    val ROTATION_MINUTES_CHOICES = listOf(0, 5, 60, 360, 1440)
    const val DEFAULT_ROTATION_MINUTES = 0

    /**
     * Cache count clamped to the slider range and snapped down to its step; a cadence the
     * screen never offers becomes the default, as the screen itself would show it.
     */
    fun sanitizeOptions(options: BackupOptions): BackupOptions = options.copy(
        targetCacheCount = options.targetCacheCount
            .coerceIn(CACHE_COUNT_MIN, CACHE_COUNT_MAX)
            .let { it / CACHE_COUNT_STEP * CACHE_COUNT_STEP },
        refreshIntervalHours = options.refreshIntervalHours.takeIf { it in REFRESH_HOURS_CHOICES }
            ?: DEFAULT_REFRESH_HOURS,
        rotationMinIntervalMinutes = options.rotationMinIntervalMinutes.takeIf { it in ROTATION_MINUTES_CHOICES }
            ?: DEFAULT_ROTATION_MINUTES,
    )

    /**
     * The server block as it should be stored (addresses normalized, key trimmed as the setup screen does), or null when it must not
     * be applied: the primary address is empty or rejected, the key is blank, or a non-blank
     * away address is rejected (the whole block is then left out rather than half-applied).
     */
    fun serverToApply(server: BackupServer): BackupServer? {
        val primary = ServerUrl.normalize(server.serverUrl)?.takeIf { it.isNotEmpty() } ?: return null
        val away = ServerUrl.normalize(server.awayUrl) ?: return null
        val key = server.apiKey.trim().takeIf { it.isNotEmpty() } ?: return null
        return BackupServer(primary, away, key)
    }
}
