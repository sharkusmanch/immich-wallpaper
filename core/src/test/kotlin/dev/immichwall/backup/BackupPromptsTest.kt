package dev.immichwall.backup

import dev.immichwall.schedule.Schedule
import dev.immichwall.schedule.ScheduleEntry
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupPromptsTest {
    private val options = BackupOptions(150, 6, 0, true, false, false)
    private val server = BackupServer("https://photos.example.test", "", "made-up-key")
    private val backup = Backup(
        exportedAt = "2026-10-07T10:00:00Z",
        cycles = listOf(
            SavedCycle("c1", "One", SourceSpec.Favorites),
            SavedCycle("c2", "Two", SourceSpec.EverythingRandom),
        ),
        activeCycleId = "c1",
        schedule = Schedule(
            enabled = true,
            defaultCycleId = "c1",
            entries = listOf(
                ScheduleEntry("e1", "", "c1", "01-01", "01-31"),
                ScheduleEntry("e2", "", "c2", "06-01", "06-30"),
                ScheduleEntry("e3", "", "c2", "12-01", "12-31"),
            ),
        ),
        options = options,
    )

    private fun prompt(b: Backup, offerServer: Boolean) =
        BackupPrompts.restorePrompt(BackupDecodeResult.Ok(b), offerServer)

    @Test
    fun `refusals pass through, whatever the screen offers`() {
        for (offer in listOf(true, false)) {
            assertEquals(RestorePrompt.NotABackup, BackupPrompts.restorePrompt(BackupDecodeResult.NotABackup, offer))
            assertEquals(
                RestorePrompt.NewerFormat,
                BackupPrompts.restorePrompt(BackupDecodeResult.NewerFormat(7), offer),
            )
        }
    }

    @Test
    fun `the confirmation carries the backup's counts and whether options come along`() {
        assertEquals(
            RestorePrompt.Confirm(cycleCount = 2, scheduleEntryCount = 3, replacesOptions = true, server = ServerPart.NOT_IN_FILE),
            prompt(backup, offerServer = true),
        )
        assertEquals(
            RestorePrompt.Confirm(2, 0, replacesOptions = false, server = ServerPart.NOT_IN_FILE),
            prompt(backup.copy(schedule = Schedule(), options = null), offerServer = true),
        )
    }

    @Test
    fun `a server block is a choice only where the screen offers it`() {
        val withServer = backup.copy(server = server)
        assertEquals(ServerPart.OPTIONAL, (prompt(withServer, offerServer = true) as RestorePrompt.Confirm).server)
        assertEquals(ServerPart.IGNORED, (prompt(withServer, offerServer = false) as RestorePrompt.Confirm).server)
        assertEquals(ServerPart.NOT_IN_FILE, (prompt(backup, offerServer = false) as RestorePrompt.Confirm).server)
    }

    @Test
    fun `nothing applied is never reported as a restore`() {
        for (requested in listOf(true, false)) for (serverApplied in listOf(true, false)) {
            assertEquals(
                RestoreOutcome.NOTHING_RESTORED,
                BackupPrompts.restoreOutcome(backup.copy(server = server), requested, applied = false, serverApplied = serverApplied),
            )
        }
    }

    @Test
    fun `restore outcome says what happened to the server address and key`() {
        val withServer = backup.copy(server = server)
        fun outcome(b: Backup, requested: Boolean, serverApplied: Boolean) =
            BackupPrompts.restoreOutcome(b, requested, applied = true, serverApplied = serverApplied)
        assertEquals(RestoreOutcome.RESTORED, outcome(withServer, requested = false, serverApplied = false))
        assertEquals(RestoreOutcome.RESTORED, outcome(backup, requested = false, serverApplied = false))
        assertEquals(RestoreOutcome.RESTORED_WITH_SERVER, outcome(withServer, requested = true, serverApplied = true))
        // Asked for, in the file, but rejected on apply (a bad address or blank key).
        assertEquals(RestoreOutcome.RESTORED_SERVER_KEPT, outcome(withServer, requested = true, serverApplied = false))
        // Asked for a block the file does not have: nothing was refused, nothing to explain.
        assertEquals(RestoreOutcome.RESTORED, outcome(backup, requested = true, serverApplied = false))
        // What apply did is what gets reported.
        assertEquals(RestoreOutcome.RESTORED_WITH_SERVER, outcome(withServer, requested = false, serverApplied = true))
    }

    @Test
    fun `an apply that threw is neither a restore nor nothing changed`() {
        val withServer = backup.copy(server = server)
        for (b in listOf(backup, withServer)) for (requested in listOf(true, false)) for (serverApplied in listOf(true, false)) {
            assertEquals(
                RestoreOutcome.UNFINISHED,
                BackupPrompts.restoreOutcome(b, requested, applied = null, serverApplied = serverApplied),
            )
        }
    }

    @Test
    fun `backup outcome says whether the server block went in`() {
        assertEquals(BackupSaved.SAVED, BackupPrompts.backupSaved(backup, serverRequested = false))
        assertEquals(BackupSaved.SAVED_WITH_SERVER, BackupPrompts.backupSaved(backup.copy(server = server), serverRequested = true))
        // Asked for, but no address or key is set, so the file has none.
        assertEquals(BackupSaved.SAVED_SERVER_UNSET, BackupPrompts.backupSaved(backup, serverRequested = true))
    }
}
