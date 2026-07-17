package dev.immichwall.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import dev.immichwall.api.AssetFaceDto
import dev.immichwall.util.Logg
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Decode / crop / scale / encode pipeline (decode core ported from the sibling project's
 * `ImmichDreamService`): EXIF-orientation probe (all 8 orientations), `inJustDecodeBounds`
 * dimension probe, power-of-2 subsampling, upright transform, face-crop, bilinear scale,
 * JPEG encode.
 *
 * [prepareWallpaper] runs at ingest (worker thread); [decodeReady] is the only decode the
 * wake path ever performs; [verifyDecodable] is the promotion gate that keeps corrupt
 * files out of `ready/`.
 */
object BitmapPipeline {

    private const val TAG = "BitmapPipeline"
    private const val JPEG_QUALITY = 90

    /** Decode at >= this multiple of the output size so the face crop has headroom. */
    private const val DECODE_HEADROOM = 2

    /** Edge length of the probe region used by [verifyDecodable]. */
    private const val VERIFY_REGION_EDGE = 16

    /**
     * Full ingest pipeline: probe dims → subsampled decode (target >= 2x outW/outH for
     * crop headroom) → EXIF upright transform (rotation + mirror) → face crop (computed
     * against post-transform dims) → bilinear scale to exactly `outW x outH` → JPEG q90 →
     * atomic tmp+rename to [dest].
     *
     * One retry at double the sample size on [OutOfMemoryError]; false on any failure.
     */
    fun prepareWallpaper(
        src: File,
        faces: List<AssetFaceDto>,
        priorityPersonIds: List<String>,
        outW: Int,
        outH: Int,
        dest: File,
    ): Boolean {
        if (outW <= 0 || outH <= 0 || !src.isFile) return false
        var wallpaper: Bitmap? = null
        return try {
            val transform = readExifTransform(src)

            // First pass: dimensions only.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(src).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Logg.w(TAG, "prepareWallpaper: bounds probe failed for ${src.name}")
                return false
            }

            val sampleSize = calculateSampleSize(
                bounds.outWidth, bounds.outHeight,
                outW * DECODE_HEADROOM, outH * DECODE_HEADROOM
            )

            wallpaper = try {
                renderScaledCrop(src, transform, sampleSize, faces, priorityPersonIds, outW, outH)
            } catch (oom: OutOfMemoryError) {
                Logg.w(TAG, "prepareWallpaper: OOM at sample=$sampleSize for ${src.name}, retrying at ${sampleSize * 2}")
                renderScaledCrop(src, transform, sampleSize * 2, faces, priorityPersonIds, outW, outH)
            }
            if (wallpaper == null) {
                Logg.w(TAG, "prepareWallpaper: decode returned null for ${src.name}")
                return false
            }
            writeJpegAtomic(wallpaper, dest)
        } catch (t: Throwable) {
            Logg.e(TAG, "prepareWallpaper failed for ${src.name}", t)
            false
        } finally {
            wallpaper?.recycle()
        }
    }

    /**
     * Plain full decode of a finished wallpaper. Returns null ONLY on genuine decode failure
     * (missing/corrupt file); [OutOfMemoryError] propagates so callers can tell transient
     * memory pressure apart from corruption — the wake path probes with [verifyDecodable]
     * (a ~1KB region decode that succeeds even under pressure) before deleting anything.
     */
    fun decodeReady(file: File): Bitmap? = try {
        if (!file.isFile) null
        else FileInputStream(file).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /** Bounds-decode plus a tiny region decode — the cheap corruption gate at ingest. */
    fun verifyDecodable(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return false

            FileInputStream(file).use { stream ->
                val decoder: BitmapRegionDecoder? = BitmapRegionDecoder.newInstance(stream)
                if (decoder == null) return false
                try {
                    val region = decoder.decodeRegion(
                        Rect(0, 0, min(VERIFY_REGION_EDGE, width), min(VERIFY_REGION_EDGE, height)),
                        null
                    ) ?: return false
                    region.recycle()
                    true
                } finally {
                    decoder.recycle()
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Subsampled decode → EXIF upright transform → face crop → bilinear scale to
     * `outW x outH`. Recycles every intermediate on all paths; may throw (incl.
     * [OutOfMemoryError]).
     */
    private fun renderScaledCrop(
        src: File,
        transform: ExifTransform,
        sampleSize: Int,
        faces: List<AssetFaceDto>,
        priorityPersonIds: List<String>,
        outW: Int,
        outH: Int,
    ): Bitmap? {
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        val decoded = FileInputStream(src).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: return null

        // Upright the bitmap (rotation + mirror) so the crop is computed in the same upright
        // space as the ML face boxes; createBitmap swaps dimensions itself for 90/270.
        val upright = if (!transform.isIdentity) {
            val rotated = try {
                val matrix = Matrix().apply {
                    if (transform.flipped) postScale(-1f, 1f)
                    postRotate(transform.rotationDegrees.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } catch (t: Throwable) {
                decoded.recycle()
                throw t
            }
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }

        val crop = try {
            FaceCropCalculator.cropRect(
                faces, priorityPersonIds,
                upright.width, upright.height,
                outW.toFloat() / outH.toFloat()
            )
        } catch (t: Throwable) {
            upright.recycle()
            throw t
        }

        // Defensive clamp — the calculator already stays in bounds.
        val left = crop.left.coerceIn(0, upright.width - 1)
        val top = crop.top.coerceIn(0, upright.height - 1)
        val width = crop.width().coerceIn(1, upright.width - left)
        val height = crop.height().coerceIn(1, upright.height - top)

        val cropped = try {
            if (left == 0 && top == 0 && width == upright.width && height == upright.height) upright
            else Bitmap.createBitmap(upright, left, top, width, height)
        } catch (t: Throwable) {
            upright.recycle()
            throw t
        }
        if (cropped !== upright) upright.recycle()

        val scaled = try {
            Bitmap.createScaledBitmap(cropped, outW, outH, /* filter = bilinear */ true)
        } catch (t: Throwable) {
            cropped.recycle()
            throw t
        }
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /** EXIF orientation reduced to the upright transform: rotation degrees + mirror flag. */
    private data class ExifTransform(val rotationDegrees: Int, val flipped: Boolean) {
        val isIdentity: Boolean get() = rotationDegrees == 0 && !flipped
    }

    private fun readExifTransform(src: File): ExifTransform = try {
        FileInputStream(src).use { stream ->
            val exif = ExifInterface(stream)
            // androidx ExifInterface decomposes all 8 orientations — including TRANSPOSE
            // and TRANSVERSE — into a rotation plus an optional horizontal mirror.
            ExifTransform(exif.rotationDegrees, exif.isFlipped)
        }
    } catch (t: Throwable) {
        ExifTransform(0, false)
    }

    /**
     * Power-of-2 sample size keeping the decoded image at or above `targetWidth x
     * targetHeight` (ported from the sibling project's `ImmichDreamService`).
     */
    private fun calculateSampleSize(
        imageWidth: Int,
        imageHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        if (imageWidth > targetWidth * 2 || imageHeight > targetHeight * 2) {
            val halfWidth = imageWidth / 2
            val halfHeight = imageHeight / 2
            while ((halfWidth / sampleSize) >= targetWidth && (halfHeight / sampleSize) >= targetHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /** JPEG q90 to `dest.tmp` → flush → fd.sync() → rename over [dest]. */
    private fun writeJpegAtomic(bitmap: Bitmap, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        var ok = false
        try {
            FileOutputStream(tmp).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)) {
                    Logg.w(TAG, "writeJpegAtomic: compress failed for ${dest.name}")
                    return false
                }
                fos.flush()
                fos.fd.sync()
            }
            ok = tmp.renameTo(dest)
            if (!ok) {
                dest.delete()
                ok = tmp.renameTo(dest)
            }
            if (!ok) Logg.w(TAG, "writeJpegAtomic: rename failed for ${dest.name}")
            return ok
        } finally {
            if (!ok) tmp.delete()
        }
    }
}
