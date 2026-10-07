package dev.immichwall.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackupRestoreTest {
    private val options = BackupOptions(
        targetCacheCount = 120,
        refreshIntervalHours = 12,
        rotationMinIntervalMinutes = 60,
        qualityFilterEnabled = false,
        syncOverCellular = true,
        deriveThemeFromPhoto = true,
    )

    @Test
    fun `offered option values pass through unchanged`() {
        assertEquals(options, BackupRestore.sanitizeOptions(options))
        for (h in listOf(3, 6, 12, 24)) {
            assertEquals(h, BackupRestore.sanitizeOptions(options.copy(refreshIntervalHours = h)).refreshIntervalHours)
        }
        for (m in listOf(0, 5, 60, 360, 1440)) {
            assertEquals(m, BackupRestore.sanitizeOptions(options.copy(rotationMinIntervalMinutes = m)).rotationMinIntervalMinutes)
        }
    }

    @Test
    fun `cache count is clamped to the slider range and snapped to its step`() {
        fun cache(n: Int) = BackupRestore.sanitizeOptions(options.copy(targetCacheCount = n)).targetCacheCount
        assertEquals(50, cache(-5))
        assertEquals(50, cache(0))
        assertEquals(50, cache(55))
        assertEquals(300, cache(10_000))
        assertEquals(300, cache(Int.MAX_VALUE))
        assertEquals(50, cache(Int.MIN_VALUE))
        assertEquals(150, cache(157))
        assertEquals(300, cache(300))
    }

    @Test
    fun `never-offered cadence values become the defaults`() {
        fun o(hours: Int, minutes: Int) =
            BackupRestore.sanitizeOptions(options.copy(refreshIntervalHours = hours, rotationMinIntervalMinutes = minutes))
        assertEquals(6, o(-1, 0).refreshIntervalHours)
        assertEquals(6, o(0, 0).refreshIntervalHours)
        assertEquals(6, o(5, 0).refreshIntervalHours)
        assertEquals(6, o(1000, 0).refreshIntervalHours)
        assertEquals(0, o(6, -10).rotationMinIntervalMinutes)
        assertEquals(0, o(6, 7).rotationMinIntervalMinutes)
        assertEquals(0, o(6, Int.MAX_VALUE).rotationMinIntervalMinutes)
    }

    @Test
    fun `switches are kept as saved`() {
        val s = BackupRestore.sanitizeOptions(options)
        assertEquals(false, s.qualityFilterEnabled)
        assertEquals(true, s.syncOverCellular)
        assertEquals(true, s.deriveThemeFromPhoto)
    }

    @Test
    fun `a valid server block is stored normalized`() {
        val got = BackupRestore.serverToApply(BackupServer(" photos.example.test/ ", "https://away.example.test/", "key-1"))
        assertEquals(BackupServer("https://photos.example.test", "https://away.example.test", "key-1"), got)
    }

    @Test
    fun `the key is trimmed`() {
        assertEquals("key-1", BackupRestore.serverToApply(BackupServer("https://photos.example.test", "", " key-1\n"))?.apiKey)
    }

    @Test
    fun `an empty away address stays empty`() {
        val got = BackupRestore.serverToApply(BackupServer("https://photos.example.test", "", "key-1"))
        assertEquals("", got?.awayUrl)
        assertEquals("", BackupRestore.serverToApply(BackupServer("https://photos.example.test", "   ", "key-1"))?.awayUrl)
    }

    @Test
    fun `a server block that cannot be used is not applied at all`() {
        val ok = BackupServer("https://photos.example.test", "https://away.example.test", "key-1")
        assertNull(BackupRestore.serverToApply(ok.copy(serverUrl = "")))
        assertNull(BackupRestore.serverToApply(ok.copy(serverUrl = "   ")))
        assertNull(BackupRestore.serverToApply(ok.copy(serverUrl = "http://photos.example.test")))
        assertNull(BackupRestore.serverToApply(ok.copy(serverUrl = "not a url")))
        assertNull(BackupRestore.serverToApply(ok.copy(apiKey = "")))
        assertNull(BackupRestore.serverToApply(ok.copy(apiKey = "  \n")))
        // a non-blank away address that does not normalize voids the whole block
        assertNull(BackupRestore.serverToApply(ok.copy(awayUrl = "http://away.example.test")))
        assertNull(BackupRestore.serverToApply(ok.copy(awayUrl = "not a url")))
    }
}
