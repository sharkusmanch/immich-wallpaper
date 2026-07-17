package dev.immichwall.source

import android.graphics.Bitmap
import android.graphics.Rect
import dev.immichwall.api.AssetDto
import dev.immichwall.api.AssetFaceDto
import dev.immichwall.crop.FaceCropCalculator
import dev.immichwall.util.Logg

/**
 * Heuristic "will this look good as a wallpaper?" scoring, used by the refresh pipeline
 * to prefer real, sharp, well-framed photos over screenshots, memes, crowd shots and
 * blurry frames. Two stages:
 *
 *  1. [preDownloadVerdict] — metadata only (search fields + asset-detail EXIF + face
 *     boxes), BEFORE the expensive download. Hard-rejects obvious non-photos and scores
 *     the rest; the caller ingests passing candidates and keeps rejects around for a
 *     relaxation pass so small pools (Favorites, Memories) never starve the cache.
 *  2. [bitmapReject] — computed on the finished panel bitmap at ingest (which the
 *     pipeline decodes anyway): rejects the hopelessly blurry / too dark / blown out.
 *
 * Scores are 0..1 with 0.5 = "neutral photo, fine to show". Thresholds are deliberately
 * conservative: this filter should only ever remove photos a person would also skip.
 */
object PhotoScorer {

    private const val TAG = "PhotoScorer"

    /** Candidates below this pre-download score are deferred to the relaxation pass. */
    const val PASS_THRESHOLD = 0.45f

    /** Hard floor: anything with a shorter edge below this can't fill a modern panel. */
    private const val MIN_SHORT_EDGE_PX = 1000

    // Face-band: union of priority faces should occupy a meaningful part of the frame.
    private const val FACE_FRACTION_MIN = 0.06f
    private const val FACE_FRACTION_MAX = 0.55f

    // Post-decode gates (0-255 luma space, sampled at ~128px). Deliberately lax.
    private const val LUMA_DARK_LIMIT = 22f
    private const val LUMA_BLOWN_LIMIT = 242f
    private const val SHARPNESS_MIN_VARIANCE = 3.0

    data class Verdict(val score: Float, val reject: String?) {
        val passes: Boolean get() = reject == null && score >= PASS_THRESHOLD
    }

    /**
     * Metadata-stage verdict. [detail] is the full asset (with exifInfo) when available;
     * [faces] the asset's face boxes (may be empty). A null [reject] with a low score
     * means "not disqualified, just unremarkable" — eligible for relaxation.
     */
    /** How face presence weighs into scoring; mirrors SettingsRepository.PEOPLE_PREF_*. */
    const val PEOPLE_OFF = "off"
    const val PEOPLE_PREFER = "prefer"
    const val PEOPLE_REQUIRE = "require"

    fun preDownloadVerdict(
        asset: AssetDto,
        detail: AssetDto?,
        faces: List<AssetFaceDto>,
        priorityPersonIds: List<String>,
        cropW: Int,
        cropH: Int,
        peoplePreference: String = PEOPLE_OFF,
    ): Verdict {
        val w = asset.width ?: detail?.width ?: 0
        val h = asset.height ?: detail?.height ?: 0

        if (w in 1 until MIN_SHORT_EDGE_PX && w <= h) return Verdict(0f, "short edge ${w}px")
        if (h in 1 until MIN_SHORT_EDGE_PX && h < w) return Verdict(0f, "short edge ${h}px")

        val exif = detail?.exifInfo
        // No camera make/model in EXIF is the single strongest screenshot/meme/forwarded-
        // image signal. Only a hard reject when we actually HAVE detail data to judge by.
        if (detail != null && exif?.make.isNullOrBlank() && exif?.model.isNullOrBlank()) {
            return Verdict(0.2f, "no camera EXIF (screenshot/forward?)")
        }

        var score = 0.5f

        // People preference: a real camera photo of a menu, sign or receipt sails past
        // the EXIF check but has no faces — this is what catches it.
        val anyFaces = FaceCropCalculator.relevantFaces(faces, emptyList()).isNotEmpty()
        when (peoplePreference) {
            PEOPLE_REQUIRE -> if (!anyFaces) return Verdict(0.15f, "no people in photo")
            PEOPLE_PREFER -> score += if (anyFaces) 0.1f else -0.2f
        }

        // Panel is portrait: portrait sources crop least destructively.
        if (h > w) score += 0.15f else if (h == w) score += 0.05f

        if (asset.isFavorite || detail?.isFavorite == true) score += 0.1f
        exif?.rating?.let { if (it >= 4) score += 0.15f }
        exif?.fNumber?.let { if (it <= 2.0) score += 0.1f }
        exif?.iso?.let { if (it >= 2500) score -= 0.15f }
        exif?.lensModel?.let { if (it.contains("front", ignoreCase = true)) score -= 0.1f }

        if (faces.isNotEmpty() && w > 0 && h > 0) {
            val relevant = FaceCropCalculator.relevantFaces(faces, priorityPersonIds)
            if (relevant.isNotEmpty()) {
                val union = unionBox(relevant)
                val faceSpaceH = relevant.first().imageHeight.toFloat().coerceAtLeast(1f)
                val fraction = union.height() / faceSpaceH
                score += if (fraction in FACE_FRACTION_MIN..FACE_FRACTION_MAX) 0.2f else -0.1f
                if (relevant.size <= 3) score += 0.05f

                // Centerability: would our actual crop clip the faces?
                if (cropW > 0 && cropH > 0) {
                    val aspect = cropW.toFloat() / cropH
                    val crop = FaceCropCalculator.cropRect(faces, priorityPersonIds, w, h, aspect)
                    val scaleX = w.toFloat() / relevant.first().imageWidth.coerceAtLeast(1)
                    val scaleY = h.toFloat() / relevant.first().imageHeight.coerceAtLeast(1)
                    val unionInSrc = Rect(
                        (union.left * scaleX).toInt(),
                        (union.top * scaleY).toInt(),
                        (union.right * scaleX).toInt(),
                        (union.bottom * scaleY).toInt(),
                    )
                    if (!crop.contains(unionInSrc)) score -= 0.25f
                }
            }
        }

        return Verdict(score.coerceIn(0f, 1f), null)
    }

    /**
     * Post-decode gate on the finished panel bitmap. Returns a rejection reason or null.
     * Sampled at ~128px so it costs ~1ms; thresholds only catch hopeless cases.
     */
    fun bitmapReject(bitmap: Bitmap): String? {
        val sw = 128
        val sh = (sw.toLong() * bitmap.height / bitmap.width).toInt().coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val luma = FloatArray(sw * sh)
            var sum = 0.0
            for (i in pixels.indices) {
                val p = pixels[i]
                val l = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                luma[i] = l
                sum += l
            }
            val mean = (sum / luma.size).toFloat()
            if (mean < LUMA_DARK_LIMIT) return "too dark (luma %.0f)".format(mean)
            if (mean > LUMA_BLOWN_LIMIT) return "blown out (luma %.0f)".format(mean)

            // Variance of the 4-neighbour Laplacian — near-zero means no edges anywhere.
            var lapSum = 0.0
            var lapSqSum = 0.0
            var n = 0
            for (y in 1 until sh - 1) {
                for (x in 1 until sw - 1) {
                    val i = y * sw + x
                    val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - sw] - luma[i + sw]
                    lapSum += lap
                    lapSqSum += lap * lap
                    n++
                }
            }
            if (n > 0) {
                val meanLap = lapSum / n
                val variance = lapSqSum / n - meanLap * meanLap
                if (variance < SHARPNESS_MIN_VARIANCE) {
                    return "blurry (lapVar %.1f)".format(variance)
                }
            }
            return null
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    private fun unionBox(faces: List<AssetFaceDto>): Rect {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (f in faces) {
            if (f.boundingBoxX1 < l) l = f.boundingBoxX1
            if (f.boundingBoxY1 < t) t = f.boundingBoxY1
            if (f.boundingBoxX2 > r) r = f.boundingBoxX2
            if (f.boundingBoxY2 > b) b = f.boundingBoxY2
        }
        return Rect(l, t, r, b)
    }
}
