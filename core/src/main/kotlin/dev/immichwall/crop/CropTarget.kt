package dev.immichwall.crop

/**
 * Crop size for devices with more than one panel shape (foldables, rotation). Every photo
 * is prepared at the union box of all surface sizes the wallpaper engines have reported —
 * widest width × tallest height, centred on the faces. On each surface the engine scales
 * that one file to cover and slides it to the photo's focus point ([offset]), so the faces
 * stay in view on every panel.
 */
object CropTarget {
    const val MAX_SURFACES = 6

    data class Size(val width: Int, val height: Int)

    /** Adds a surface size, keeping the [MAX_SURFACES] most recently added distinct sizes. */
    fun remember(seen: List<Size>, width: Int, height: Int): List<Size> {
        if (width <= 0 || height <= 0) return seen
        val size = Size(width, height)
        if (size in seen) return seen
        return (seen + size).takeLast(MAX_SURFACES)
    }

    /**
     * The list to store when the engine reports a [width] × [height] surface. An empty
     * [seen] beside a crop that is already set ([cropWidth] × [cropHeight]: seeded at
     * onboarding, or left behind by an older list) is first seeded with that crop, so a
     * first report can only grow the box. Without this, one panel reporting alone would
     * shrink the box to itself and every cached photo would be prepared again at the wrong
     * size, and once more when the other panel reported.
     */
    fun rememberReported(seen: List<Size>, cropWidth: Int, cropHeight: Int, width: Int, height: Int): List<Size> {
        val known = if (seen.isEmpty() && cropWidth > 0 && cropHeight > 0) listOf(Size(cropWidth, cropHeight)) else seen
        return remember(known, width, height)
    }

    /** Widest width × tallest height over [seen]; null when nothing has been reported yet. */
    fun unionBox(seen: List<Size>): Size? {
        if (seen.isEmpty()) return null
        return Size(seen.maxOf { it.width }, seen.maxOf { it.height })
    }

    /**
     * Translation, along one axis, of a photo [scaled] pixels long on a surface [canvas]
     * pixels long: puts the point at fraction [focus] of the photo at the middle of the
     * surface, as far as the photo's edges allow (the surface is always fully covered).
     */
    fun offset(canvas: Float, scaled: Float, focus: Float): Float {
        if (scaled <= canvas) return (canvas - scaled) / 2f
        return (canvas / 2f - focus.coerceIn(0f, 1f) * scaled).coerceIn(canvas - scaled, 0f)
    }

    fun encode(seen: List<Size>): String = seen.joinToString(",") { "${it.width}x${it.height}" }

    fun decode(raw: String): List<Size> = raw.split(',').mapNotNull { part ->
        val bits = part.trim().split('x')
        val w = bits.getOrNull(0)?.toIntOrNull()
        val h = bits.getOrNull(1)?.toIntOrNull()
        if (bits.size == 2 && w != null && h != null && w > 0 && h > 0) Size(w, h) else null
    }
}
