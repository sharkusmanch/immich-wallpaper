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

    private val unusable = listOf(
        server.copy(serverUrl = "http://photos.example.test"),
        server.copy(awayUrl = "not a url"),
        server.copy(apiKey = "  "),
    )

    private fun prompt(b: Backup, serverUse: ServerUse) =
        BackupPrompts.restorePrompt(BackupDecodeResult.Ok(b), serverUse)

    private fun serverPart(b: Backup, serverUse: ServerUse) = (prompt(b, serverUse) as RestorePrompt.Confirm).server

    @Test
    fun `refusals pass through, whatever the screen does with a server block`() {
        for (use in ServerUse.entries) {
            assertEquals(RestorePrompt.NotABackup, BackupPrompts.restorePrompt(BackupDecodeResult.NotABackup, use))
            assertEquals(
                RestorePrompt.NewerFormat,
                BackupPrompts.restorePrompt(BackupDecodeResult.NewerFormat(7), use),
            )
        }
    }

    @Test
    fun `the confirmation carries the backup's counts and whether options come along`() {
        assertEquals(
            RestorePrompt.Confirm(cycleCount = 2, scheduleEntryCount = 3, replacesOptions = true, server = ServerPart.NOT_IN_FILE),
            prompt(backup, ServerUse.OPTIONAL),
        )
        assertEquals(
            RestorePrompt.Confirm(2, 0, replacesOptions = false, server = ServerPart.NOT_IN_FILE),
            prompt(backup.copy(schedule = Schedule(), options = null), ServerUse.OPTIONAL),
        )
        assertEquals(
            RestorePrompt.Confirm(2, 3, replacesOptions = true, server = ServerPart.USED),
            prompt(backup.copy(server = server), ServerUse.USED),
        )
    }

    @Test
    fun `a server block is a choice on the settings screen and ignored on the source step`() {
        val withServer = backup.copy(server = server)
        assertEquals(ServerPart.OPTIONAL, serverPart(withServer, ServerUse.OPTIONAL))
        assertEquals(ServerPart.IGNORED, serverPart(withServer, ServerUse.IGNORED))
        assertEquals(ServerPart.NOT_IN_FILE, serverPart(backup, ServerUse.IGNORED))
        // Whether the block could be stored is found out on apply there, and reported then.
        for (bad in unusable) {
            assertEquals(ServerPart.OPTIONAL, serverPart(backup.copy(server = bad), ServerUse.OPTIONAL))
            assertEquals(ServerPart.IGNORED, serverPart(backup.copy(server = bad), ServerUse.IGNORED))
        }
    }

    @Test
    fun `the server screen uses a usable server block and says so`() {
        assertEquals(ServerPart.USED, serverPart(backup.copy(server = server), ServerUse.USED))
        // Usable once normalized, as it will be stored.
        assertEquals(
            ServerPart.USED,
            serverPart(backup.copy(server = BackupServer(" photos.example.test/ ", "", " made-up-key\n")), ServerUse.USED),
        )
    }

    @Test
    fun `on the server screen a backup without a usable server block leaves them to be entered`() {
        assertEquals(ServerPart.TO_ENTER, serverPart(backup, ServerUse.USED))
        for (bad in unusable) {
            assertEquals(ServerPart.INVALID_TO_ENTER, serverPart(backup.copy(server = bad), ServerUse.USED))
        }
    }

    @Test
    fun `the server block is applied when the screen uses it or the user ticked the choice`() {
        for (ticked in listOf(true, false)) {
            assertEquals(true, BackupPrompts.appliesServer(ServerPart.USED, ticked))
            assertEquals(ticked, BackupPrompts.appliesServer(ServerPart.OPTIONAL, ticked))
            for (part in listOf(ServerPart.NOT_IN_FILE, ServerPart.IGNORED, ServerPart.TO_ENTER, ServerPart.INVALID_TO_ENTER)) {
                assertEquals(false, BackupPrompts.appliesServer(part, ticked))
            }
        }
    }

    @Test
    fun `where the server block is used the confirmation shows both addresses as they will be stored`() {
        val both = backup.copy(server = BackupServer(" photos.example.test/ ", "https://away.example.test/", "made-up-key"))
        assertEquals(
            ServerAddresses(ServerEndpoint("photos.example.test", null), ServerEndpoint("away.example.test", null)),
            BackupPrompts.serverAddressesToShow(both, ServerUse.USED),
        )
    }

    @Test
    fun `a blank away address is shown as none`() {
        for (away in listOf("", "   ")) {
            assertEquals(
                ServerAddresses(ServerEndpoint("photos.example.test", null), away = null),
                BackupPrompts.serverAddressesToShow(backup.copy(server = server.copy(awayUrl = away)), ServerUse.USED),
            )
        }
    }

    @Test
    fun `each address is shown as its host first, and in full only when that adds something`() {
        val block = BackupServer(
            "https://photos.a-rather-long-subdomain-name.home-network.example.test:8443/immich/",
            "https://AWAY.example.test:443",
            "made-up-key",
        )
        assertEquals(
            ServerAddresses(
                ServerEndpoint(
                    "photos.a-rather-long-subdomain-name.home-network.example.test:8443",
                    "https://photos.a-rather-long-subdomain-name.home-network.example.test:8443/immich",
                ),
                ServerEndpoint("away.example.test", null),
            ),
            BackupPrompts.serverAddressesToShow(backup.copy(server = block), ServerUse.USED),
        )
    }

    @Test
    fun `the host shown is the one requests go to`() {
        // the first letter is Cyrillic
        val lookalike = backup.copy(server = server.copy(serverUrl = "https://\u0440hotos.example.test"))
        assertEquals(
            ServerAddresses(ServerEndpoint("xn--hotos-uye.example.test", null), away = null),
            BackupPrompts.serverAddressesToShow(lookalike, ServerUse.USED),
        )
    }

    @Test
    fun `an address that shows one host and reaches another is never shown, in either place`() {
        val disguised = listOf(
            "https://photos.example.test@evil.example",
            "https://photos.example.test\n\n\n@evil.example",
            "https://photos.example.test   @evil.example",
            "https://photos.example.test\u202E@evil.example",
            "https://evil.example/\u202Etset.elpmaxe.sotohp",
            "https://evil.example/\nServer URL: https://photos.example.test",
        )
        for (address in disguised) for (use in ServerUse.entries) {
            assertEquals(null, BackupPrompts.serverAddressesToShow(backup.copy(server = server.copy(serverUrl = address)), use), address)
            assertEquals(null, BackupPrompts.serverAddressesToShow(backup.copy(server = server.copy(awayUrl = address)), use), address)
        }
        // and on the server screen such a file is one whose address and key are still to be entered
        for (address in disguised) {
            assertEquals(ServerPart.INVALID_TO_ENTER, serverPart(backup.copy(server = server.copy(serverUrl = address)), ServerUse.USED))
            assertEquals(ServerPart.INVALID_TO_ENTER, serverPart(backup.copy(server = server.copy(awayUrl = address)), ServerUse.USED))
        }
    }

    @Test
    fun `every address of a server block that can be applied is shown`() {
        val broken = "https://photos.example.test․.evil.example"
        val blocks = listOf(
            server,
            server.copy(awayUrl = "https://away.example.test:8443/immich"),
            BackupServer(" PHOTOS.example.test:443/ ", "away.example.test", " made-up-key "),
            server.copy(serverUrl = "https://рhotos.example.test"),
            server.copy(serverUrl = broken),
            server.copy(awayUrl = broken),
            server.copy(serverUrl = "https://photos.example.test⒈.evil.example", awayUrl = broken),
        )
        for (block in blocks) for (use in listOf(ServerUse.USED, ServerUse.OPTIONAL)) {
            val applied = BackupRestore.serverToApply(block)
            val shown = BackupPrompts.serverAddressesToShow(backup.copy(server = block), use)
            if (applied == null) {
                assertEquals(null, shown, block.serverUrl)
                continue
            }
            assertEquals(dev.immichwall.api.ServerUrl.hostAndPort(applied.serverUrl), shown?.primary?.host, block.serverUrl)
            assertEquals(applied.awayUrl.isNotEmpty(), shown?.away != null, block.awayUrl)
            if (applied.awayUrl.isNotEmpty()) {
                assertEquals(dev.immichwall.api.ServerUrl.hostAndPort(applied.awayUrl), shown?.away?.host, block.awayUrl)
            }
        }
        // on the server screen, "will be used" is only ever said with addresses under it
        for (block in blocks) {
            val part = serverPart(backup.copy(server = block), ServerUse.USED)
            assertEquals(part == ServerPart.USED, BackupPrompts.serverAddressesToShow(backup.copy(server = block), ServerUse.USED) != null)
        }
    }

    @Test
    fun `where replacing is a choice the confirmation shows what ticking it would store`() {
        val both = backup.copy(server = server.copy(awayUrl = "https://away.example.test"))
        assertEquals(
            ServerAddresses(ServerEndpoint("photos.example.test", null), ServerEndpoint("away.example.test", null)),
            BackupPrompts.serverAddressesToShow(both, ServerUse.OPTIONAL),
        )
    }

    @Test
    fun `no addresses are shown where the server block is ignored`() {
        val both = backup.copy(server = server.copy(awayUrl = "https://away.example.test"))
        assertEquals(null, BackupPrompts.serverAddressesToShow(both, ServerUse.IGNORED))
    }

    @Test
    fun `no addresses are shown for a server block that would not be applied, or none`() {
        for (use in ServerUse.entries) {
            assertEquals(null, BackupPrompts.serverAddressesToShow(backup, use))
            for (bad in unusable) {
                assertEquals(null, BackupPrompts.serverAddressesToShow(backup.copy(server = bad), use))
            }
        }
    }

    @Test
    fun `nothing applied is never reported as a restore`() {
        for (use in ServerUse.entries) for (requested in listOf(true, false)) for (serverApplied in listOf(true, false)) {
            assertEquals(
                RestoreOutcome.NOTHING_RESTORED,
                BackupPrompts.restoreOutcome(backup.copy(server = server), use, requested, applied = false, serverApplied = serverApplied),
            )
        }
    }

    @Test
    fun `restore outcome says what happened to the server address and key`() {
        val withServer = backup.copy(server = server)
        fun outcome(b: Backup, requested: Boolean, serverApplied: Boolean) =
            BackupPrompts.restoreOutcome(b, ServerUse.OPTIONAL, requested, applied = true, serverApplied = serverApplied)
        assertEquals(RestoreOutcome.RESTORED, outcome(withServer, requested = false, serverApplied = false))
        assertEquals(RestoreOutcome.RESTORED, outcome(backup, requested = false, serverApplied = false))
        assertEquals(RestoreOutcome.RESTORED_WITH_SERVER, outcome(withServer, requested = true, serverApplied = true))
        // Asked for, in the file, but rejected on apply (a bad address or blank key).
        assertEquals(RestoreOutcome.RESTORED_SERVER_KEPT, outcome(withServer, requested = true, serverApplied = false))
        // Asked for a block the file does not have: nothing was refused, nothing to explain.
        assertEquals(RestoreOutcome.RESTORED, outcome(backup, requested = true, serverApplied = false))
        // What apply did is what gets reported.
        assertEquals(RestoreOutcome.RESTORED_WITH_SERVER, outcome(withServer, requested = false, serverApplied = true))
        // The source step never asks for the block.
        assertEquals(
            RestoreOutcome.RESTORED,
            BackupPrompts.restoreOutcome(withServer, ServerUse.IGNORED, serverRequested = false, applied = true, serverApplied = false),
        )
    }

    @Test
    fun `on the server screen a restore either brought the server address and key or leaves them to be entered`() {
        val withServer = backup.copy(server = server)
        fun outcome(b: Backup, requested: Boolean, serverApplied: Boolean) =
            BackupPrompts.restoreOutcome(b, ServerUse.USED, requested, applied = true, serverApplied = serverApplied)
        assertEquals(RestoreOutcome.RESTORED_WITH_SERVER, outcome(withServer, requested = true, serverApplied = true))
        // No block in the file, or one that was not usable, so not asked for.
        assertEquals(RestoreOutcome.RESTORED_SERVER_TO_ENTER, outcome(backup, requested = false, serverApplied = false))
        assertEquals(
            RestoreOutcome.RESTORED_SERVER_TO_ENTER,
            outcome(backup.copy(server = unusable.first()), requested = false, serverApplied = false),
        )
        // Asked for and rejected on apply all the same: the same place, nothing of the user's was "kept".
        assertEquals(RestoreOutcome.RESTORED_SERVER_TO_ENTER, outcome(withServer, requested = true, serverApplied = false))
    }

    @Test
    fun `an apply that threw is neither a restore nor nothing changed`() {
        val withServer = backup.copy(server = server)
        for (b in listOf(backup, withServer)) for (use in ServerUse.entries) for (requested in listOf(true, false)) {
            for (serverApplied in listOf(true, false)) {
                assertEquals(
                    RestoreOutcome.UNFINISHED,
                    BackupPrompts.restoreOutcome(b, use, requested, applied = null, serverApplied = serverApplied),
                )
            }
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
