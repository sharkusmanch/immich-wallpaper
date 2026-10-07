package dev.immichwall.backup

import dev.immichwall.schedule.Schedule
import dev.immichwall.source.SavedCycle
import kotlinx.serialization.Serializable

/**
 * What a settings backup file holds. Never in it: photos, the manual schedule override, the
 * debug date, crop sizes, sync bookkeeping.
 *
 * [options] null = "leave the current options alone"; [server] null = no connection details.
 * [exportedAt] is informational (ISO-8601 text supplied by the caller).
 */
data class Backup(
    val exportedAt: String,
    val cycles: List<SavedCycle>,
    val activeCycleId: String,
    val schedule: Schedule,
    val options: BackupOptions? = null,
    val server: BackupServer? = null,
) {
    val hasServer: Boolean get() = server != null
    val cycleCount: Int get() = cycles.size
    val scheduleEntryCount: Int get() = schedule.entries.size
}

/** The six options, typed and meant as in [dev.immichwall.settings.SettingsRepository]. */
@Serializable
data class BackupOptions(
    val targetCacheCount: Int,
    val refreshIntervalHours: Int,
    val rotationMinIntervalMinutes: Int,
    val qualityFilterEnabled: Boolean,
    val syncOverCellular: Boolean,
    val deriveThemeFromPhoto: Boolean,
)

/** Connection details; [awayUrl] and [apiKey] are empty strings when unset, as in settings. */
@Serializable
data class BackupServer(
    val serverUrl: String,
    val awayUrl: String,
    val apiKey: String,
)

sealed interface BackupDecodeResult {
    data class Ok(val backup: Backup) : BackupDecodeResult

    /** Not this app's backup, or damaged beyond use. */
    data object NotABackup : BackupDecodeResult

    /** Written by a build with a higher format number ([format]) than this one reads. */
    data class NewerFormat(val format: Int) : BackupDecodeResult
}
