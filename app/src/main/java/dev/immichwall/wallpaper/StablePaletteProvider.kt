package dev.immichwall.wallpaper

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.util.Logg

/**
 * WallpaperColors source for the engine's `onComputeColors`.
 *
 * By default returns a FIXED blue-grey palette so Material You does not re-theme the whole
 * UI on every wake. When the user opts into [SettingsRepository.deriveThemeFromPhoto], the
 * palette is derived from the current photo instead (accepting theme churn per swap).
 */
object StablePaletteProvider {

    private const val TAG = "StablePalette"

    private val fixedPalette: WallpaperColors by lazy {
        WallpaperColors(
            Color.valueOf(0xFF37474F.toInt()),
            Color.valueOf(0xFF546E7A.toInt()),
            Color.valueOf(0xFF263238.toInt())
        )
    }

    /** Identity of the bitmap the cached palette was derived from. */
    @Volatile
    private var cacheSource: Bitmap? = null

    @Volatile
    private var cachedDerived: WallpaperColors? = null

    fun colors(settings: SettingsRepository?, bitmap: Bitmap?): WallpaperColors {
        if (settings != null && settings.deriveThemeFromPhoto && bitmap != null && !bitmap.isRecycled) {
            // onComputeColors is binder-driven and may be asked repeatedly; deriving from
            // the full panel bitmap each time is ~100ms of quantization. Derive once per
            // photo from a tiny copy (Muzei uses ~110px) and cache by bitmap identity.
            cachedDerived?.takeIf { cacheSource === bitmap }?.let { return it }
            try {
                val h = (SMALL_EDGE_PX.toFloat() * bitmap.height / bitmap.width).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(bitmap, SMALL_EDGE_PX, h, true)
                val derived = WallpaperColors.fromBitmap(small)
                if (small !== bitmap) small.recycle()
                cacheSource = bitmap
                cachedDerived = derived
                return derived
            } catch (t: Throwable) {
                Logg.w(TAG, "fromBitmap failed; falling back to fixed palette: ${t.message}")
            }
        }
        return fixedPalette
    }

    private const val SMALL_EDGE_PX = 96
}
