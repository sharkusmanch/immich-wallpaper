package dev.immichwall.backup

import dev.immichwall.api.ApiJson
import dev.immichwall.schedule.Schedule
import dev.immichwall.source.SavedCycle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reads and writes the backup file: one pretty-printed JSON document. */
object BackupCodec {
    const val FORMAT = 1
    const val MARKER = "immich-wallpaper-backup"

    /** [ApiJson]'s configuration (so [dev.immichwall.source.SourceSpec] reads as persisted) with absent blocks left out, not null. */
    private val json = Json(ApiJson.json) {
        prettyPrint = true
        explicitNulls = false
    }

    @Serializable
    private data class Wire(
        val marker: String,
        val format: Int,
        val exportedAt: String,
        val cycles: List<SavedCycle>,
        val activeCycleId: String,
        val schedule: Schedule,
        val options: BackupOptions? = null,
        val server: BackupServer? = null,
    ) {
        fun toBackup() = Backup(exportedAt, cycles, activeCycleId, schedule, options, server)
    }

    fun encode(backup: Backup): String =
        with(backup) {
            json.encodeToString(
                Wire.serializer(),
                Wire(MARKER, FORMAT, exportedAt, cycles, activeCycleId, schedule, options, server),
            )
        }

    /** Never throws: anything unreadable is [BackupDecodeResult.NotABackup]. */
    fun decode(text: String): BackupDecodeResult {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return BackupDecodeResult.NotABackup
        if ((root["marker"] as? JsonPrimitive)?.takeIf { it.isString }?.content != MARKER) {
            return BackupDecodeResult.NotABackup
        }
        val format = (root["format"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: return BackupDecodeResult.NotABackup
        if (format > FORMAT) return BackupDecodeResult.NewerFormat(format)
        val backup = try {
            json.decodeFromJsonElement(Wire.serializer(), root).toBackup()
        } catch (e: Exception) {
            return BackupDecodeResult.NotABackup
        }
        if (backup.cycles.isEmpty()) return BackupDecodeResult.NotABackup
        val active = backup.activeCycleId.takeIf { id -> backup.cycles.any { it.id == id } }
            ?: backup.cycles.first().id
        return BackupDecodeResult.Ok(backup.copy(activeCycleId = active))
    }
}
