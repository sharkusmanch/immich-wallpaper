package dev.immichwall.backup

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

/** The backup as a file: what it is called and how much of one is read. */
object BackupFile {
    /** 1 MB. A real backup is a few kilobytes; anything larger is not one. */
    const val MAX_BYTES = 1024 * 1024

    /** `immich-wallpaper-backup-YYYY-MM-DD.json` ([LocalDate.toString] is ISO, ASCII digits). */
    fun suggestedName(date: LocalDate): String = "immich-wallpaper-backup-$date.json"

    /**
     * Reads and decodes [input] as UTF-8, ignoring a leading byte-order mark (some editors
     * add one, and the codec takes it for garbage). A file over [MAX_BYTES], or one that is
     * not UTF-8, is [BackupDecodeResult.NotABackup]; at most one byte past the limit is ever
     * held. Does not close [input]; its I/O errors propagate.
     */
    fun read(input: InputStream): BackupDecodeResult {
        val buffer = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        if (size > MAX_BYTES) return BackupDecodeResult.NotABackup
        val start = if (size >= BOM.size && BOM.indices.all { buffer[it] == BOM[it] }) BOM.size else 0
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer, start, size - start))
                .toString()
        } catch (e: CharacterCodingException) {
            return BackupDecodeResult.NotABackup
        }
        return BackupCodec.decode(text)
    }

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
