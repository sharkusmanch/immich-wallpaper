package dev.immichwall.crop

import android.graphics.Rect
import dev.immichwall.api.AssetFaceDto
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Computes the face-centered crop rectangle for a wallpaper, in source-bitmap pixel space.
 *
 * Selection ladder:
 *  1. faces whose `person.id` is in [priorityPersonIds] (when any match),
 *  2. else ALL detected faces,
 *  3. else a centered rect of the target aspect.
 *
 * Face box coordinates arrive in each face's own `imageWidth x imageHeight` space (the
 * ML-input size, NOT necessarily the decoded size) and are scaled into `srcW x srcH`
 * before use.
 */
object FaceCropCalculator {

    /** The union box is extended upward by this fraction of its height (headroom). */
    private const val HEADROOM_FRACTION = 0.35f

    /** Vertical placement of the face-union center within the crop (0.5 = dead center). */
    private const val FACE_VERTICAL_POSITION = 0.40f

    /**
     * The faces a crop (or score) should consider: priority people's usable boxes when
     * any match, else all usable boxes. Public so the quality scorer judges the same
     * faces the crop will actually be framed around.
     */
    fun relevantFaces(faces: List<AssetFaceDto>, priorityPersonIds: List<String>): List<AssetFaceDto> {
        val usable = faces.filter {
            it.imageWidth > 0 && it.imageHeight > 0 &&
                it.boundingBoxX2 > it.boundingBoxX1 && it.boundingBoxY2 > it.boundingBoxY1
        }
        val prioritized = if (priorityPersonIds.isEmpty()) emptyList() else usable.filter { face ->
            val personId = face.person?.id
            personId != null && personId in priorityPersonIds
        }
        return prioritized.ifEmpty { usable }
    }

    fun cropRect(
        faces: List<AssetFaceDto>,
        priorityPersonIds: List<String>,
        srcW: Int,
        srcH: Int,
        targetAspectWOverH: Float,
    ): Rect {
        if (srcW <= 0 || srcH <= 0) return Rect(0, 0, max(1, srcW), max(1, srcH))
        if (targetAspectWOverH <= 0f || !targetAspectWOverH.isFinite()) return Rect(0, 0, srcW, srcH)

        val chosen = relevantFaces(faces, priorityPersonIds)
        if (chosen.isEmpty()) return centeredRect(srcW, srcH, targetAspectWOverH)

        // Union of all chosen boxes, scaled from face-detection space into source space.
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (face in chosen) {
            val sx = srcW.toFloat() / face.imageWidth
            val sy = srcH.toFloat() / face.imageHeight
            left = min(left, face.boundingBoxX1 * sx)
            top = min(top, face.boundingBoxY1 * sy)
            right = max(right, face.boundingBoxX2 * sx)
            bottom = max(bottom, face.boundingBoxY2 * sy)
        }

        // Headroom: extend the top upward by 35% of the union height.
        top -= (bottom - top) * HEADROOM_FRACTION

        // MAXIMUM-CONTEXT crop: always use the LARGEST rect of the target aspect that fits
        // inside the image (never zoom tighter than the image allows), then position it so
        // the face union sits near the photographic sweet spot — horizontally centered,
        // vertically at ~40% of crop height. Faces guide the crop; they don't define it.
        val maxRect = centeredRect(srcW, srcH, targetAspectWOverH)
        val cropW = maxRect.width().toFloat()
        val cropH = maxRect.height().toFloat()

        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val cropLeft = (centerX - cropW / 2f).coerceIn(0f, max(0f, srcW - cropW))
        val cropTop = (centerY - cropH * FACE_VERTICAL_POSITION).coerceIn(0f, max(0f, srcH - cropH))

        val l = cropLeft.roundToInt().coerceIn(0, srcW - 1)
        val t = cropTop.roundToInt().coerceIn(0, srcH - 1)
        val r = (cropLeft + cropW).roundToInt().coerceIn(l + 1, srcW)
        val b = (cropTop + cropH).roundToInt().coerceIn(t + 1, srcH)
        return Rect(l, t, r, b)
    }

    /**
     * Where the faces sit inside [crop], as fractions of its width and height: `[x, y]`,
     * the centre when there are none. Uses the same faces as [cropRect] but their true
     * centre (no headroom), because this is the point the engine keeps in view.
     */
    fun focusWithin(
        crop: Rect,
        faces: List<AssetFaceDto>,
        priorityPersonIds: List<String>,
        srcW: Int,
        srcH: Int,
    ): FloatArray {
        val chosen = relevantFaces(faces, priorityPersonIds)
        if (chosen.isEmpty() || crop.width() <= 0 || crop.height() <= 0) return floatArrayOf(0.5f, 0.5f)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (face in chosen) {
            val sx = srcW.toFloat() / face.imageWidth
            val sy = srcH.toFloat() / face.imageHeight
            left = min(left, face.boundingBoxX1 * sx)
            top = min(top, face.boundingBoxY1 * sy)
            right = max(right, face.boundingBoxX2 * sx)
            bottom = max(bottom, face.boundingBoxY2 * sy)
        }
        val x = ((left + right) / 2f - crop.left) / crop.width()
        val y = ((top + bottom) / 2f - crop.top) / crop.height()
        return floatArrayOf(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    /** Largest centered rect of [aspect] that fits in `srcW x srcH`. */
    private fun centeredRect(srcW: Int, srcH: Int, aspect: Float): Rect {
        val srcAspect = srcW.toFloat() / srcH
        val w: Float
        val h: Float
        if (srcAspect >= aspect) {
            h = srcH.toFloat()
            w = h * aspect
        } else {
            w = srcW.toFloat()
            h = w / aspect
        }
        val left = ((srcW - w) / 2f).coerceAtLeast(0f)
        val top = ((srcH - h) / 2f).coerceAtLeast(0f)
        val l = left.roundToInt().coerceIn(0, srcW - 1)
        val t = top.roundToInt().coerceIn(0, srcH - 1)
        val r = (left + w).roundToInt().coerceIn(l + 1, srcW)
        val b = (top + h).roundToInt().coerceIn(t + 1, srcH)
        return Rect(l, t, r, b)
    }
}
