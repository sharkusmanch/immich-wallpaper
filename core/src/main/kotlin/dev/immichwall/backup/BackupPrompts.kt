package dev.immichwall.backup

/** What the confirmation says about the backup's server address and API key. */
enum class ServerPart {
    /** The file has none: the current ones are kept. */
    NOT_IN_FILE,

    /** The file has them, and this screen never applies them. */
    IGNORED,

    /** The file has them: replacing the current ones is the user's choice, off unless chosen. */
    OPTIONAL,
}

/** What to show for a file the user picked to restore from. */
sealed interface RestorePrompt {
    /** Ask before anything changes. */
    data class Confirm(
        val cycleCount: Int,
        val scheduleEntryCount: Int,
        /** False when the file carries no options, so the current ones stay. */
        val replacesOptions: Boolean,
        val server: ServerPart,
    ) : RestorePrompt

    data object NotABackup : RestorePrompt

    /** Written by a newer version of the app. */
    data object NewerFormat : RestorePrompt
}

enum class RestoreOutcome {
    /** Nothing changed. */
    NOTHING_RESTORED,

    /** Cycles, schedule and options restored; the server address and key were not part of it. */
    RESTORED,

    RESTORED_WITH_SERVER,

    /** Restored, but the server block that was asked for was rejected and the current one kept. */
    RESTORED_SERVER_KEPT,

    /** Applying stopped part-way: some of the backup may be in place, or none of it. */
    UNFINISHED,
}

enum class BackupSaved {
    SAVED,
    SAVED_WITH_SERVER,

    /** The server block was asked for, but no address or key is set, so the file has none. */
    SAVED_SERVER_UNSET,
}

/** Which message goes with which result; the app turns these into its string resources. */
object BackupPrompts {
    /** [offerServer] false = this screen never applies a server block (the first-run wizard). */
    fun restorePrompt(result: BackupDecodeResult, offerServer: Boolean): RestorePrompt = when (result) {
        BackupDecodeResult.NotABackup -> RestorePrompt.NotABackup
        is BackupDecodeResult.NewerFormat -> RestorePrompt.NewerFormat
        is BackupDecodeResult.Ok -> RestorePrompt.Confirm(
            cycleCount = result.backup.cycleCount,
            scheduleEntryCount = result.backup.scheduleEntryCount,
            replacesOptions = result.backup.options != null,
            server = when {
                !result.backup.hasServer -> ServerPart.NOT_IN_FILE
                offerServer -> ServerPart.OPTIONAL
                else -> ServerPart.IGNORED
            },
        )
    }

    /**
     * [applied] and [serverApplied] are what applying [backup] returned; [serverRequested] is
     * what the user chose. Success is only ever reported for what was applied. [applied] null =
     * applying threw instead of returning, so nothing is known about what changed.
     */
    fun restoreOutcome(
        backup: Backup,
        serverRequested: Boolean,
        applied: Boolean?,
        serverApplied: Boolean,
    ): RestoreOutcome = when {
        applied == null -> RestoreOutcome.UNFINISHED
        !applied -> RestoreOutcome.NOTHING_RESTORED
        serverApplied -> RestoreOutcome.RESTORED_WITH_SERVER
        serverRequested && backup.hasServer -> RestoreOutcome.RESTORED_SERVER_KEPT
        else -> RestoreOutcome.RESTORED
    }

    /** [backup] is what was written to the file. */
    fun backupSaved(backup: Backup, serverRequested: Boolean): BackupSaved = when {
        backup.hasServer -> BackupSaved.SAVED_WITH_SERVER
        serverRequested -> BackupSaved.SAVED_SERVER_UNSET
        else -> BackupSaved.SAVED
    }
}
