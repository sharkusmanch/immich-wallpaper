package dev.immichwall.wallpaper

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.util.Logg
import kotlin.math.max

/**
 * Live wallpaper service. One engine serves both home and lock screen (Android 14+).
 *
 * The engine only ever DRAWS: it renders [RotationController.currentBitmap] — a pre-cropped,
 * panel-sized JPEG decoded off the render thread — or a branded placeholder before setup.
 * Advancing happens while the screen is dark (see [RotationController] / [ScreenOffReceiver]),
 * so every wake reveals an already-swapped photo with zero jank.
 */
class PhotoWallpaperService : WallpaperService() {

    private companion object {
        const val TAG = "PhotoWallpaperSvc"

        /** Neutral fill shown between a fresh surface and the first decoded photo (post-setup). */
        const val PLACEHOLDER_FILL = 0xFF10161A.toInt()

        /** Guards the process-wide receiver refcount. */
        val processLock = Any()
        var engineCount = 0
        var screenReceiver: ScreenOffReceiver? = null

        /** How long the first frame may wait for the initial cache decode. */
        const val INITIAL_LOAD_TIMEOUT_MS = 1000L

        /** Register the SCREEN_OFF/SCREEN_ON receiver once per process while >=1 REAL engine exists. */
        fun onEngineCreated(appCtx: Context) {
            synchronized(processLock) {
                engineCount++
                if (screenReceiver == null) {
                    val receiver = ScreenOffReceiver()
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_SCREEN_ON)
                    }
                    appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    screenReceiver = receiver
                    Logg.d(TAG, "ScreenOffReceiver registered (engines=$engineCount)")
                }
            }
        }

        fun onEngineDestroyed(appCtx: Context) {
            synchronized(processLock) {
                engineCount = (engineCount - 1).coerceAtLeast(0)
                if (engineCount == 0) {
                    screenReceiver?.let {
                        try {
                            appCtx.unregisterReceiver(it)
                            Logg.d(TAG, "ScreenOffReceiver unregistered")
                        } catch (t: Throwable) {
                            Logg.w(TAG, "receiver unregister failed: ${t.message}")
                        }
                    }
                    screenReceiver = null
                }
            }
        }
    }

    /** Re-arms the first load when the profile unlocks after a locked-storage bind. */
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // Defense in depth for secondary profiles: if the system ever binds this service
        // before the profile credential is entered, CE storage (cache, settings) is not
        // readable yet — reload once ACTION_USER_UNLOCKED fires. (Muzei does the same.)
        val um = getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Logg.d(TAG, "user unlocked; loading first photo")
                    unregisterUnlockReceiver()
                    RotationController.refreshFromCacheHead(applicationContext)
                }
            }
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
            unlockReceiver = receiver
            Logg.d(TAG, "bound before user unlock; waiting for ACTION_USER_UNLOCKED")
        }
    }

    override fun onDestroy() {
        unregisterUnlockReceiver()
        super.onDestroy()
    }

    private fun unregisterUnlockReceiver() {
        unlockReceiver?.let {
            unlockReceiver = null
            try {
                unregisterReceiver(it)
            } catch (t: Throwable) {
                Logg.w(TAG, "unlock receiver unregister failed: ${t.message}")
            }
        }
    }

    override fun onCreateEngine(): Engine = PhotoEngine()

    private inner class PhotoEngine : Engine() {

        /** Serializes draws across the render thread and the main thread (onSurfaceRedrawNeeded). */
        private val drawLock = Any()
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val drawMatrix = Matrix()
        private var lastDeriveFromPhoto = false
        /** Identity of the bitmap last painted to the current surface; skips redundant redraws. */
        private var lastDrawnBitmap: Bitmap? = null

        /** A spent first-frame wait; once it timed out, further blocking buys nothing. */
        private var initialAwaitTimedOut = false

        private val redrawCallback: () -> Unit = {
            // Invoked on the "wallpaper-render" thread after a bitmap swap: force a repaint.
            drawFrame(force = true)
            // Preview engines must not churn the system's color extraction.
            if (!isPreview && settingsOrNull()?.deriveThemeFromPhoto == true) {
                notifyColorsChangedSafely()
            }
        }

        private fun settings(): SettingsRepository = SettingsRepository.get(applicationContext)

        /** Settings live in CE storage; null while the profile is still locked. */
        private fun settingsOrNull(): SettingsRepository? {
            val um = applicationContext.getSystemService(UserManager::class.java)
            return if (um == null || um.isUserUnlocked) settings() else null
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(false)
            lastDeriveFromPhoto = settingsOrNull()?.deriveThemeFromPhoto ?: false
            // Preview engines (system picker) must render but must NOT join the advance
            // machinery: no screen receiver, no cursor burn from picker browsing.
            if (!isPreview) {
                onEngineCreated(applicationContext)
            }
            // Every engine — preview included — needs the post-decode repaint, or a
            // cold-process picker preview stays stuck on the placeholder fill forever.
            RotationController.addRedrawListener(redrawCallback)
            RotationController.ensureLoaded(applicationContext)
        }

        override fun onDestroy() {
            RotationController.removeRedrawListener(redrawCallback)
            if (!isPreview) {
                onEngineDestroyed(applicationContext)
            }
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // The engine's surface size is the source of truth for crop dimensions.
            // Previews may run at odd sizes on some pickers, so only the real engine persists.
            val s0 = settingsOrNull()
            if (!isPreview && width > 0 && height > 0 && s0 != null) {
                val s = s0
                val differs = s.cropWidth != width || s.cropHeight != height
                // On Android 14+ a second (lock) engine could report smaller dimensions;
                // two engines must not fight over the crop size and re-stale the cache
                // on every flip — only an unset or equal-or-larger surface wins.
                val largest = width.toLong() * height >= s.cropWidth.toLong() * s.cropHeight
                if (differs && (s.cropWidth <= 0 || largest)) {
                    Logg.d(TAG, "surface ${width}x$height (was ${s.cropWidth}x${s.cropHeight}); persisting crop dims")
                    s.cropWidth = width
                    s.cropHeight = height
                }
            }
            awaitFirstPhotoIfNeeded()
            drawFrame(force = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder?) {
            // Contract: MUST have rendered before returning — always force.
            awaitFirstPhotoIfNeeded()
            drawFrame(force = true)
        }

        /**
         * On a fresh process the first decode is still in flight when the surface asks
         * for its first frame; briefly wait for it so the first presented frame is the
         * photo, never a placeholder that pops to the photo a beat later.
         */
        private fun awaitFirstPhotoIfNeeded() {
            // onSurfaceChanged and onSurfaceRedrawNeeded arrive back-to-back in one
            // surface-creation pass; a wait that already timed out must not double the
            // main-thread stall — the redraw listener repaints when the decode lands.
            if (initialAwaitTimedOut) return
            if (RotationController.currentBitmap() == null && settingsOrNull()?.isConfigured == true) {
                initialAwaitTimedOut =
                    !RotationController.awaitInitialLoad(applicationContext, INITIAL_LOAD_TIMEOUT_MS)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                val derive = settingsOrNull()?.deriveThemeFromPhoto ?: lastDeriveFromPhoto
                if (derive != lastDeriveFromPhoto) {
                    lastDeriveFromPhoto = derive
                    notifyColorsChangedSafely()
                }
                // If the photo advanced while dark, the surface already holds it — a redundant
                // repaint here is what shows as a hitch on wake, so only draw if it changed.
                drawFrame(force = false)
            } else if (!isPreview) {
                RotationController.onVisibilityLost(applicationContext)
            }
        }

        override fun onComputeColors(): WallpaperColors =
            StablePaletteProvider.colors(settingsOrNull(), RotationController.currentBitmap())

        private fun notifyColorsChangedSafely() {
            try {
                notifyColorsChanged()
            } catch (t: Throwable) {
                Logg.w(TAG, "notifyColorsChanged failed: ${t.message}")
            }
        }

        // ---- Drawing ----

        private fun drawFrame(force: Boolean) {
            val holder = surfaceHolder ?: return
            synchronized(drawLock) {
                val surface = holder.surface
                if (surface == null || !surface.isValid) return
                val bitmap = RotationController.currentBitmap()
                // Skip a repaint that would produce an identical frame (avoids wake-time hitches);
                // `force` covers surface (re)creation and post-swap redraws that must render.
                if (!force && bitmap != null && bitmap === lastDrawnBitmap) return
                var canvas: Canvas? = null
                var drawnBitmap: Bitmap? = null
                try {
                    canvas = try {
                        holder.lockHardwareCanvas()
                    } catch (t: Throwable) {
                        null
                    } ?: holder.lockCanvas()
                    if (canvas == null) return
                    if (bitmap != null && !bitmap.isRecycled) {
                        val fadeFrom = RotationController.fadeFromBitmap()
                        val alpha = RotationController.fadeAlpha()
                        if (fadeFrom != null && !fadeFrom.isRecycled && alpha < 1f) {
                            // Mid-crossfade: outgoing photo below, incoming on top. The
                            // controller's fade ticker keeps forcing frames; skip the
                            // drawn-bitmap bookkeeping so the final frame repaints fully.
                            drawPhoto(canvas, fadeFrom, 255)
                            drawPhoto(canvas, bitmap, (alpha * 255).toInt().coerceIn(0, 255))
                        } else {
                            drawPhoto(canvas, bitmap, 255)
                            drawnBitmap = bitmap
                        }
                    } else if (settingsOrNull()?.isConfigured != false) {
                        // Configured — or unknown because storage is still locked: a plain
                        // fill avoids the wordy setup card flashing like a pop-up dialog.
                        canvas.drawColor(PLACEHOLDER_FILL)
                    } else {
                        drawPlaceholder(canvas)
                    }
                } catch (t: Throwable) {
                    Logg.e(TAG, "drawFrame failed", t)
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                            // Only a successfully POSTED frame counts for the skip check;
                            // a failed post must not suppress the next non-forced repaint.
                            if (drawnBitmap != null) lastDrawnBitmap = drawnBitmap
                        } catch (t: Throwable) {
                            lastDrawnBitmap = null
                            Logg.w(TAG, "unlockCanvasAndPost failed: ${t.message}")
                        }
                    }
                }
            }
        }

        /**
         * Full-bleed centre-crop. Ready files are exactly panel-sized so this is normally the
         * identity transform; on aspect mismatch (stale crops after a panel-size change) the
         * photo is scaled to cover the surface and the overflow is cropped.
         */
        private fun drawPhoto(canvas: Canvas, bitmap: Bitmap, alpha: Int = 255) {
            val cw = canvas.width.toFloat()
            val ch = canvas.height.toFloat()
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            if (cw <= 0f || ch <= 0f || bw <= 0f || bh <= 0f) return
            if (alpha >= 255) canvas.drawColor(Color.BLACK)
            val scale = max(cw / bw, ch / bh)
            drawMatrix.reset()
            drawMatrix.setScale(scale, scale)
            drawMatrix.postTranslate((cw - bw * scale) / 2f, (ch - bh * scale) / 2f)
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(bitmap, drawMatrix, bitmapPaint)
            bitmapPaint.alpha = 255
        }

        /** Dark gradient + app name + setup hint, shown until the cache has a decodable photo. */
        private fun drawPlaceholder(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            if (w <= 0f || h <= 0f) return
            val gradientPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h,
                    intArrayOf(0xFF37474F.toInt(), 0xFF263238.toInt(), 0xFF10161A.toInt()),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, w, h, gradientPaint)

            val appName = try {
                applicationInfo.loadLabel(packageManager).toString()
            } catch (t: Throwable) {
                "Immich Wallpaper"
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = w * 0.055f
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3FFFFFF.toInt()
                textAlign = Paint.Align.CENTER
                textSize = w * 0.034f
            }
            val cx = w / 2f
            val cy = h * 0.45f
            canvas.drawText(appName, cx, cy, titlePaint)
            canvas.drawText("Open the app to set up your photos", cx, cy + titlePaint.textSize * 1.6f, subtitlePaint)
        }
    }
}
