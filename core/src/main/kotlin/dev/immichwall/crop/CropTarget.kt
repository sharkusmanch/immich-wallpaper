package dev.immichwall.crop

/**
 * Crop size for devices with more than one panel shape (foldables, rotation). Every photo
 * is prepared at the union box of all surface sizes the wallpaper engine has reported —
 * widest width × tallest height, centred on the faces — and the engine's scale-to-cover
 * centre-crop then shows the face-centred middle of that one file on each surface.
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
