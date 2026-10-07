package dev.immichwall.backup

/** What a screen that restores does with the server address and API key in a backup. */
enum class ServerUse {
    /** Replacing the current ones is the user's choice, off unless chosen: the settings screen. */
    OPTIONAL,

    /** Never applied: first-run setup's source step, where they were entered a step earlier. */
    IGNORED,

    /** Applied whenever they are usable: first-run setup's server screen, in place of typing them. */
    USED,
}

/** What the confirmation says about the backup's server address and API key. */
enum class ServerPart {
    /** The file has none: the current ones are kept. */
    NOT_IN_FILE,

    /** The file has them, and this screen never applies them: they were entered a step earlier. */
    IGNORED,

    /** The file has them: replacing the current ones is the user's choice, off unless chosen. */
    OPTIONAL,

    /** The file has them, usable, and this screen applies them. */
    USED,

    /** This screen would have applied them, but the file has none: they are still to be entered. */
    TO_ENTER,

    /** As [TO_ENTER], but the file has them and [BackupRestore.serverToApply] rejects them. */
    INVALID_TO_ENTER,
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

    /** Restored on a screen that uses the server block, without one: they are still to be entered. */
    RESTORED_SERVER_TO_ENTER,

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
    /** [serverUse] is what the screen showing the confirmation does with a server block. */
    fun restorePrompt(result: BackupDecodeResult, serverUse: ServerUse): RestorePrompt = when (result) {
        BackupDecodeResult.NotABackup -> RestorePrompt.NotABackup
        is BackupDecodeResult.NewerFormat -> RestorePrompt.NewerFormat
        is BackupDecodeResult.Ok -> RestorePrompt.Confirm(
            cycleCount = result.backup.cycleCount,
            scheduleEntryCount = result.backup.scheduleEntryCount,
            replacesOptions = result.backup.options != null,
            server = serverPart(result.backup.server, serverUse),
        )
    }

    private fun serverPart(server: BackupServer?, serverUse: ServerUse): ServerPart = when (serverUse) {
        ServerUse.OPTIONAL -> if (server == null) ServerPart.NOT_IN_FILE else ServerPart.OPTIONAL
        ServerUse.IGNORED -> if (server == null) ServerPart.NOT_IN_FILE else ServerPart.IGNORED
        // Decided before anything is applied, by the rule applying itself uses.
        ServerUse.USED -> when {
            server == null -> ServerPart.TO_ENTER
            BackupRestore.serverToApply(server) == null -> ServerPart.INVALID_TO_ENTER
            else -> ServerPart.USED
        }
    }

    /**
     * Whether confirming asks for the server block to be applied: [server] is what the
     * confirmation said, [ticked] the state of its "also replace" choice (shown for
     * [ServerPart.OPTIONAL] only).
     */
    fun appliesServer(server: ServerPart, ticked: Boolean): Boolean = when (server) {
        ServerPart.USED -> true
        ServerPart.OPTIONAL -> ticked
        ServerPart.NOT_IN_FILE, ServerPart.IGNORED, ServerPart.TO_ENTER, ServerPart.INVALID_TO_ENTER -> false
    }

    /**
     * [applied] and [serverApplied] are what applying [backup] returned; [serverRequested] is
     * what was asked for ([appliesServer]). Success is only ever reported for what was applied.
     * [applied] null = applying threw instead of returning, so nothing is known about what
     * changed. Where the screen uses the server block ([ServerUse.USED]) there is no address
     * and key of the user's to have kept: without one applied they are still to be entered,
     * whether the file had none or its block was rejected.
     */
    fun restoreOutcome(
        backup: Backup,
        serverUse: ServerUse,
        serverRequested: Boolean,
        applied: Boolean?,
        serverApplied: Boolean,
    ): RestoreOutcome = when {
        applied == null -> RestoreOutcome.UNFINISHED
        !applied -> RestoreOutcome.NOTHING_RESTORED
        serverApplied -> RestoreOutcome.RESTORED_WITH_SERVER
        serverUse == ServerUse.USED -> RestoreOutcome.RESTORED_SERVER_TO_ENTER
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
