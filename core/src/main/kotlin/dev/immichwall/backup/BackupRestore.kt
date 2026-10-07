package dev.immichwall.backup

import dev.immichwall.api.ServerUrl
import dev.immichwall.settings.OptionChoices

/**
 * Decisions about what a restored backup may become. The codec does not validate values, so a
 * hand-edited file can carry anything; these rules keep the stored settings to what the app's
 * own screens could have stored.
 */
object BackupRestore {
    /**
     * Cache count clamped to the slider range and snapped down to its step; a cadence the
     * screen never offers becomes the default, as the screen itself would show it.
     */
    fun sanitizeOptions(options: BackupOptions): BackupOptions = options.copy(
        targetCacheCount = options.targetCacheCount
            .coerceIn(OptionChoices.CACHE_COUNT_MIN, OptionChoices.CACHE_COUNT_MAX)
            .let { it / OptionChoices.CACHE_COUNT_STEP * OptionChoices.CACHE_COUNT_STEP },
        refreshIntervalHours = options.refreshIntervalHours.takeIf { it in OptionChoices.REFRESH_HOURS }
            ?: OptionChoices.DEFAULT_REFRESH_HOURS,
        rotationMinIntervalMinutes = options.rotationMinIntervalMinutes.takeIf { it in OptionChoices.ROTATION_MINUTES }
            ?: OptionChoices.DEFAULT_ROTATION_MINUTES,
    )

    /**
     * The server block as it should be stored, or null when it must not be applied: the
     * primary address is empty or rejected, the key is blank, or a non-blank away address is
     * rejected (the whole block is then left out rather than half-applied). The key is
     * trimmed as the setup screen does. The addresses are [ServerUrl.canonical]: a file's
     * text is not taken at its word, so what is stored, and shown before it is, names the
     * host requests really go to.
     */
    fun serverToApply(server: BackupServer): BackupServer? {
        val primary = ServerUrl.canonical(server.serverUrl)?.takeIf { it.isNotEmpty() } ?: return null
        val away = ServerUrl.canonical(server.awayUrl) ?: return null
        val key = server.apiKey.trim().takeIf { it.isNotEmpty() } ?: return null
        return BackupServer(primary, away, key)
    }

    /**
     * Whether a restore attempt is followed by what every cycle change starts (the periodic
     * refresh, a first fill, the wallpaper moving to the active cycle).
     *
     * Never before setup is finished ([configured] false): the wizard's last step starts all
     * of it, from whatever is stored by then. Otherwise whenever the cycles were, or may have
     * been, replaced ([applied] null = applying threw part-way), and always when syncing was
     * stopped for the restore, since nothing else would start it again.
     */
    fun startsSyncing(configured: Boolean, applied: Boolean?, syncsStopped: Boolean): Boolean =
        configured && (applied != false || syncsStopped)

    /**
     * Whether first-run setup goes from the server screen straight to the options step:
     * when cycles are already stored (a backup was restored, on either screen that offers
     * it, or a first cycle was built before going back), there is no photo source left to
     * choose. From stored state, so it holds after the app was closed mid-setup. Never once
     * setup is finished ([configured]).
     */
    fun setupSkipsSourceStep(configured: Boolean, cycleCount: Int): Boolean = !configured && cycleCount > 0
}
