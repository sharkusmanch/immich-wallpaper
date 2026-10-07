package dev.immichwall.backup

import dev.immichwall.schedule.Schedule
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupFileTest {
    private val backup = Backup(
        exportedAt = "2026-10-07T10:00:00Z",
        cycles = listOf(SavedCycle("c1", "Été à la mer", SourceSpec.Favorites)),
        activeCycleId = "c1",
        schedule = Schedule(),
    )
    private val text = BackupCodec.encode(backup)

    private fun read(bytes: ByteArray) = BackupFile.read(ByteArrayInputStream(bytes))

    @Test
    fun `suggested name carries the date with zero-padded month and day`() {
        assertEquals("immich-wallpaper-backup-2026-10-07.json", BackupFile.suggestedName(LocalDate.of(2026, 10, 7)))
        assertEquals("immich-wallpaper-backup-2027-01-02.json", BackupFile.suggestedName(LocalDate.of(2027, 1, 2)))
    }

    @Test
    fun `a backup reads back as written, non-ASCII names included`() {
        assertEquals(BackupDecodeResult.Ok(backup), read(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a leading byte-order mark is ignored`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(BackupDecodeResult.Ok(backup), read(bom + text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `garbage, an empty file and bytes that are not UTF-8 are not backups`() {
        assertEquals(BackupDecodeResult.NotABackup, read("hello".toByteArray()))
        assertEquals(BackupDecodeResult.NotABackup, read(ByteArray(0)))
        // Otherwise valid, but the first byte of the cycle name's "É" is one UTF-8 never uses.
        val bytes = text.toByteArray(Charsets.UTF_8)
        bytes[bytes.indexOf(0xC3.toByte())] = 0xFF.toByte()
        assertEquals(BackupDecodeResult.NotABackup, read(bytes))
    }

    @Test
    fun `a newer format is reported as such`() {
        val newer = text.replace("\"format\": 1", "\"format\": 2")
        assertEquals(BackupDecodeResult.NewerFormat(2), read(newer.toByteArray()))
    }

    @Test
    fun `a file of exactly the limit is read and one byte more is refused`() {
        val body = text.toByteArray(Charsets.UTF_8)
        // Trailing spaces keep the document valid JSON at any length.
        val atLimit = body + ByteArray(BackupFile.MAX_BYTES - body.size) { ' '.code.toByte() }
        assertIs<BackupDecodeResult.Ok>(read(atLimit))
        assertEquals(BackupDecodeResult.NotABackup, read(atLimit + ' '.code.toByte()))
    }

    @Test
    fun `an oversized file is refused without being read to its end`() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = ' '.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(' '.code.toByte(), off, off + len)
                served += len
                return len
            }
        }
        assertEquals(BackupDecodeResult.NotABackup, BackupFile.read(endless))
        assertTrue(served <= BackupFile.MAX_BYTES + 64 * 1024, "read $served bytes")
    }

    @Test
    fun `a stream that hands out a few bytes at a time is read whole`() {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val trickle = object : InputStream() {
            private var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= bytes.size) return -1
                val n = minOf(len, 3, bytes.size - at)
                bytes.copyInto(b, off, at, at + n)
                at += n
                return n
            }
        }
        assertEquals(BackupDecodeResult.Ok(backup), BackupFile.read(trickle))
    }
}
