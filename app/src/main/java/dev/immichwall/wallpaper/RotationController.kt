package dev.immichwall.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import dev.immichwall.cache.PhotoCacheManager
import dev.immichwall.crop.BitmapPipeline
import dev.immichwall.util.Logg

/**
 * Owns the advance state machine (DESIGN.md "Core mechanism").
 *
 * All mutable state (current bitmap, per-off-cycle latch, pending settle runnable, wakelock)
 * is confined to a dedicated, lazily started [HandlerThread] named "wallpaper-render"; every
 * public entry point hops onto that thread via the handler. [currentBitmap] is the single
 * cross-thread read and is backed by a volatile reference.
 *
 * Advance sequence (triggered by SCREEN_OFF broadcast, or visibility-loss while non-interactive):
 *  1. Debounce: if an advance already ran this off-cycle (latch) or a settle is pending, ignore.
 *  2. Gate: skip when [UserManager.isUserForeground] is false (another profile owns the screen).
 *  3. Acquire a PARTIAL_WAKE_LOCK with a 3s timeout so the CPU cannot suspend mid-decode.
 *  4. Wait 300ms ("settle"), cancelled by SCREEN_ON — guards double-tap-to-wake and AOD glances.
 *  5. Advance the cache cursor, decode; corrupt entries are removed and skipped (max 3 attempts).
 *  6. Swap the bitmap, set the latch, redraw, recycle the old bitmap, release the wakelock.
 */
object RotationController {

    private const val TAG = "RotationController"
    private const val SETTLE_DELAY_MS = 300L
    private const val WAKE_LOCK_TIMEOUT_MS = 3000L
    private const val WAKE_LOCK_TAG = "immichwall:advance"
    private const val MAX_DECODE_ATTEMPTS = 3

    /** Crossfade length for photo changes the user can see (approach modeled on Muzei, Apache-2.0). */
    private const val FADE_DURATION_MS = 700L
    private const val FADE_FRAME_MS = 16L

    private val initLock = Any()

    @Volatile
    private var handler: Handler? = null

    /** Written only on the render thread; readable from any thread (engine draw path). */
    @Volatile
    private var current: Bitmap? = null

    /**
     * Outgoing bitmap during a visible crossfade; null when no fade is running.
     * Screen-off advances never fade (the swap happens in the dark); this only serves
     * photo changes the user can watch (initial fill landing, source switch).
     */
    @Volatile
    private var fadeFrom: Bitmap? = null

    @Volatile
    private var fadeStartUptime: Long = 0L

    /**
     * One redraw listener per live engine. Multiple engines can coexist (real + picker
     * preview, or separate home/lock engines), and every one of them must repaint after
     * a bitmap swap — a single-slot callback left preview surfaces permanently stale.
     */
    private val redrawListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // ---- State below is confined to the "wallpaper-render" thread. ----
    private var advancedThisOffCycle = false

    /** The date the schedule was last evaluated on this thread; null = not since process start. */
    private var lastScheduleDate: java.time.LocalDate? = null
    private var pendingSettle: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun renderHandler(): Handler {
        handler?.let { return it }
        synchronized(initLock) {
            handler?.let { return it }
            val thread = HandlerThread("wallpaper-render")
            thread.start()
            return Handler(thread.looper).also { handler = it }
        }
    }

    /** Focus point of each decoded bitmap. Weakly keyed, so an entry lives exactly as long as its bitmap. */
    private val focusByBitmap: MutableMap<Bitmap, FloatArray> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Bitmap, FloatArray>())

    private val centerFocus = floatArrayOf(0.5f, 0.5f)

    /** Where the faces sit in [bitmap] as `[x, y]` fractions; the centre when unknown. Any thread. */
    fun focusFor(bitmap: Bitmap): FloatArray = focusByBitmap[bitmap] ?: centerFocus

    /** The bitmap the engine should draw right now; null until the cache has a decodable entry. */
    fun currentBitmap(): Bitmap? = current

    /** The outgoing bitmap while a crossfade runs; null otherwise. */
    fun fadeFromBitmap(): Bitmap? = fadeFrom

    /**
     * Incoming-bitmap opacity for the running crossfade, 0..1 (1 = no fade / fade done).
     * Cosine ease-in-out, computed from wall time so dropped frames don't slow the fade.
     */
    fun fadeAlpha(): Float {
        if (fadeFrom == null) return 1f
        val t = (SystemClock.uptimeMillis() - fadeStartUptime).toFloat() / FADE_DURATION_MS
        if (t >= 1f) return 1f
        if (t <= 0f) return 0f
        return (0.5f - 0.5f * kotlin.math.cos(t * Math.PI.toFloat()))
    }

    /** Engines register their draw trigger here; invoked on the render thread after every swap. */
    fun addRedrawListener(cb: () -> Unit) {
        redrawListeners.addIfAbsent(cb)
    }

    fun removeRedrawListener(cb: () -> Unit) {
        redrawListeners.remove(cb)
    }

    /**
     * Top-level guard for render-thread runnables. Disk-full IOExceptions and decode
     * OutOfMemoryErrors must degrade to "keep showing the current photo" — an escaped
     * throwable on this HandlerThread would kill the whole wallpaper process.
     */
    private inline fun guarded(label: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            Logg.e(TAG, "$label failed; keeping current state", t)
        }
    }

    /** Load the entry at the cursor (NO advance) if nothing is loaded yet, then redraw. */
    fun ensureLoaded(ctx: Context) {
        val app = ctx.applicationContext
        renderHandler().post {
            guarded("ensureLoaded") {
                if (current == null) loadFromCursor(app)
            }
        }
    }

    /**
     * Reload the entry at the cursor (NO advance) — used after initial fill / source change.
     * The user is typically looking at the wallpaper when this fires, so the change
     * crossfades instead of hard-cutting (screen-off advances stay instant).
     */
    fun refreshFromCacheHead(ctx: Context) {
        val app = ctx.applicationContext
        renderHandler().post {
            guarded("refreshFromCacheHead") { loadFromCursor(app, animate = true) }
        }
    }

    /**
     * The active cycle changed while the screen may be on (manual pick, schedule edit).
     * Shows that cycle's freshest cached photo now, with a crossfade. A no-op when nothing
     * is cached for it yet — the sync's end-of-run jump covers that case.
     */
    fun onActiveCycleChanged(ctx: Context) {
        val app = ctx.applicationContext
        renderHandler().post {
            guarded("onActiveCycleChanged") {
                if (alignCursorWithActiveCycle(app)) loadFromCursor(app, animate = true)
            }
        }
    }

    /** The cache was emptied: drop the bitmap so engines draw the placeholder until a sync refills. */
    fun onCacheCleared() {
        renderHandler().post {
            fadeFrom = null
            current = null
            notifyRedraw()
        }
    }

    /**
     * Bounded synchronous wait for the first decode. Called from the engine's
     * onSurfaceRedrawNeeded contract path so the first frame ever presented on a fresh
     * surface is the photo, not a placeholder that pops to the photo a beat later.
     * The posted runnable queues behind any in-flight [ensureLoaded] decode on the same
     * handler, so this returns as soon as that decode lands (typically well under 200ms).
     */
    fun awaitInitialLoad(ctx: Context, timeoutMs: Long): Boolean {
        if (current != null) return true
        val app = ctx.applicationContext
        val latch = java.util.concurrent.CountDownLatch(1)
        renderHandler().post {
            try {
                guarded("awaitInitialLoad") {
                    if (current == null) loadFromCursor(app)
                }
            } finally {
                // Never strand the waiting engine thread for the full timeout.
                latch.countDown()
            }
        }
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return current != null
    }

    /** PRIMARY trigger: runtime-registered ACTION_SCREEN_OFF receiver. */
    fun onScreenOff(ctx: Context) {
        val app = ctx.applicationContext
        renderHandler().post { scheduleAdvance(app) }
    }

    /**
     * SECONDARY trigger: engine visibility loss. Only acts when the display is actually off
     * (`!PowerManager.isInteractive`) — an app occluding the launcher must not burn a photo.
     * Funnels into the same debounced path as [onScreenOff].
     */
    fun onVisibilityLost(ctx: Context) {
        val app = ctx.applicationContext
        val pm = app.getSystemService(PowerManager::class.java)
        if (pm == null || pm.isInteractive) return
        renderHandler().post { scheduleAdvance(app) }
    }

    /**
     * Cancels a pending settle, releases the wakelock and re-arms the per-off-cycle latch.
     * Then checks the cycle: the photo this wake reveals was chosen at the last screen-off,
     * which may have been yesterday. On the first wake of a boundary day it crossfades to
     * the new cycle about a second in; on every other wake this is a no-op.
     */
    fun onScreenOn(ctx: Context) {
        val app = ctx.applicationContext
        val h = renderHandler()
        // Front of queue so a due-but-not-yet-run settle is removed before it can execute.
        h.postAtFrontOfQueue {
            pendingSettle?.let { h.removeCallbacks(it) }
            pendingSettle = null
            releaseWakeLock()
            advancedThisOffCycle = false
        }
        h.post {
            guarded("onScreenOn") {
                // Settings and cache live in credential-encrypted storage.
                val um = app.getSystemService(UserManager::class.java)
                if ((um == null || um.isUserUnlocked) && alignCursorWithActiveCycle(app)) {
                    loadFromCursor(app, animate = true)
                }
            }
        }
    }

    // ---- Render-thread internals ----

    private fun scheduleAdvance(ctx: Context) {
        // A trigger that runs after the SCREEN_ON reset (queue inversion via
        // postAtFrontOfQueue on a busy thread) must not arm a settle while the screen
        // is on — it would hold the wakelock pointlessly and swallow the next real
        // SCREEN_OFF's trigger via the pendingSettle debounce.
        val pmEarly = ctx.getSystemService(PowerManager::class.java)
        if (pmEarly != null && pmEarly.isInteractive) {
            Logg.d(TAG, "screen interactive; ignoring stale advance trigger")
            return
        }
        val umLock = ctx.getSystemService(UserManager::class.java)
        if (umLock != null && !umLock.isUserUnlocked) {
            Logg.d(TAG, "user storage locked; skipping advance")
            return
        }
        if (advancedThisOffCycle) {
            Logg.d(TAG, "advance already ran this off-cycle; ignoring trigger")
            return
        }
        if (pendingSettle != null) {
            Logg.d(TAG, "settle already pending; ignoring duplicate trigger")
            return
        }
        // [FIX] Don't burn photos on other profiles' screen events.
        val um = ctx.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserForeground) {
            Logg.d(TAG, "user not in foreground; skipping advance")
            return
        }
        acquireWakeLock(ctx)
        val settle = Runnable {
            pendingSettle = null
            // Renew the wakelock so the 3s window bounds the decode work remaining,
            // not the time since the trigger (dispatch may have slipped on a busy
            // thread). Non-refcounted, so this just extends the timeout.
            acquireWakeLock(ctx)
            try {
                // Authoritative re-check at fire time: a SCREEN_ON delivered in the same
                // instant as SCREEN_OFF can beat the queued cancel (observed on emulator),
                // so never trust queue ordering — ask the power manager directly.
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (pm != null && pm.isInteractive) {
                    Logg.d(TAG, "screen interactive at settle time; advance aborted")
                } else {
                    performAdvance(ctx)
                }
            } catch (t: Throwable) {
                Logg.e(TAG, "advance failed; keeping current bitmap", t)
            } finally {
                releaseWakeLock()
            }
        }
        pendingSettle = settle
        renderHandler().postDelayed(settle, SETTLE_DELAY_MS)
    }

    /**
     * Render thread; storage must be unlocked. Makes the cursor point into the active cycle:
     * runs the schedule when the calendar day has changed since the last look (once a day is
     * enough here — edits and manual picks apply themselves through the UI, and every sync
     * applies it too), then, if the photo under the cursor belongs to another cycle and the
     * active one has photos, moves the cursor to its freshest. True when the cursor moved;
     * the caller reloads. Cheap when nothing is due: a few settings reads and one hash.
     */
    private fun alignCursorWithActiveCycle(ctx: Context): Boolean {
        val settings = dev.immichwall.settings.SettingsRepository.get(ctx)
        val today = dev.immichwall.schedule.ScheduleApplier.today(settings)
        if (today != lastScheduleDate) {
            if (dev.immichwall.schedule.ScheduleApplier.applyIfDue(ctx)) {
                // Tops the new cycle up; its prefetched photos are already on disk.
                dev.immichwall.sync.SyncScheduler.kickInitialFill(ctx)
            }
            // Only once it worked: a throw above must not write the day off.
            lastScheduleDate = today
        }
        val key = dev.immichwall.source.CycleKeys.activeKey(settings) ?: return false
        val cache = PhotoCacheManager.get(ctx)
        val underCursor = cache.currentEntry() ?: return false
        if (underCursor.sourceKey == key) return false
        return cache.jumpToNewest(key) != null
    }

    private fun performAdvance(ctx: Context) {
        // The advance for this off-cycle is now considered spent, whatever the outcome.
        advancedThisOffCycle = true
        // Cycle first. When the photo on screen belongs to a cycle that is no longer the
        // active one (the date rolled over, or a sync switched cycles and could not reach
        // the server), show the active cycle now, whatever the rotation cadence says.
        if (alignCursorWithActiveCycle(ctx)) {
            loadFromCursor(ctx)
            return
        }
        // Rotation cadence: with a minimum interval set, wakes inside the window reveal
        // the same photo again — the change waits for the first screen-off after it.
        val settings = dev.immichwall.settings.SettingsRepository.get(ctx)
        val minIntervalMs = settings.rotationMinIntervalMinutes * 60_000L
        if (minIntervalMs > 0) {
            val last = settings.lastAdvanceAt
            val elapsed = System.currentTimeMillis() - last
            if (last > 0 && elapsed in 0 until minIntervalMs) {
                Logg.d(TAG, "rotation interval not elapsed (${elapsed / 1000}s < ${minIntervalMs / 1000}s); keeping current photo")
                return
            }
        }
        val cache = PhotoCacheManager.get(ctx)
        // Only the active cycle's photos are eligible; while it has none cached the cycle
        // on screen keeps rotating (see CachePolicy.nextToShow).
        val activeKey = dev.immichwall.source.CycleKeys.activeKey(settings).orEmpty()
        // Peek-then-commit: nothing (cursor, shown-marks) moves until the new photo is
        // decoded AND the screen is still off, so an aborted advance leaves no trace.
        var entry = cache.peekNextShown(activeKey)
        var decoded: Bitmap? = null
        var attempts = 0
        while (entry != null && attempts < MAX_DECODE_ATTEMPTS) {
            attempts++
            decoded = try {
                BitmapPipeline.decodeReady(cache.readyFile(entry))
            } catch (oom: OutOfMemoryError) {
                // Transient memory pressure, NOT corruption — the entry stays; the
                // current photo simply survives this off-cycle.
                Logg.w(TAG, "OOM decoding ${entry.assetId}; keeping current bitmap")
                return
            }
            if (decoded != null) break
            Logg.w(TAG, "corrupt ready file for ${entry.assetId}; removing and retrying")
            cache.removeEntry(entry)
            entry = cache.peekNextShown(activeKey)
        }
        val shown = entry
        if (decoded == null || shown == null) {
            Logg.w(TAG, "advance found no decodable entry after $attempts attempt(s); keeping current bitmap")
            return
        }
        // The decode window is 100-300ms; a wake landing inside it can't be preempted
        // (same thread). Re-check before the swap so we never repaint a surface the
        // user is already looking at — nothing was committed, so nothing to roll back.
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && pm.isInteractive) {
            Logg.d(TAG, "screen became interactive during decode; suppressing swap")
            return
        }
        cache.commitShown(shown)
        settings.lastAdvanceAt = System.currentTimeMillis()
        focusByBitmap[decoded] = floatArrayOf(shown.focusX, shown.focusY)
        swapAndRedraw(decoded)
        Logg.d(TAG, "advanced to ${shown.assetId}")
    }

    /** Load the cursor entry without advancing; heals corrupt cursor entries via removeEntry. */
    private fun loadFromCursor(ctx: Context, animate: Boolean = false) {
        // Cache + settings live in credential-encrypted storage. Touching them before the
        // profile is unlocked would construct the cache singleton against unreadable
        // directories (poisoning it empty until process death) — defer; the service's
        // ACTION_USER_UNLOCKED receiver reloads once storage is available.
        val um = ctx.getSystemService(UserManager::class.java)
        if (um != null && !um.isUserUnlocked) {
            Logg.d(TAG, "user storage locked; deferring cursor load until unlock")
            return
        }
        val cache = PhotoCacheManager.get(ctx)
        // A load must never bring back a photo of a cycle that is no longer active.
        alignCursorWithActiveCycle(ctx)
        var attempts = 0
        while (attempts < MAX_DECODE_ATTEMPTS) {
            attempts++
            val entry = cache.currentEntry()
            if (entry == null) {
                Logg.d(TAG, "loadFromCursor: cache empty")
                return
            }
            val decoded = try {
                BitmapPipeline.decodeReady(cache.readyFile(entry))
            } catch (oom: OutOfMemoryError) {
                Logg.w(TAG, "OOM decoding cursor entry ${entry.assetId}; leaving cache untouched")
                return
            }
            if (decoded != null) {
                // Fade only when someone could actually watch the change.
                val pm = ctx.getSystemService(PowerManager::class.java)
                focusByBitmap[decoded] = floatArrayOf(entry.focusX, entry.focusY)
                swapAndRedraw(decoded, animate = animate && pm != null && pm.isInteractive)
                Logg.d(TAG, "loaded cursor entry ${entry.assetId}")
                return
            }
            Logg.w(TAG, "cursor entry ${entry.assetId} not decodable; removing")
            cache.removeEntry(entry)
        }
        Logg.w(TAG, "loadFromCursor gave up after $attempts attempts")
    }

    /**
     * Swap in the new bitmap and invoke the redraw listeners. With [animate] and a photo
     * already on a screen the user is watching, the swap runs as a short crossfade
     * (render-thread ticker repaints until done); otherwise it is an instant cut — the
     * screen-off advance path never fades. The old bitmap is deliberately NOT recycled:
     * a hardware canvas may still consume it asynchronously after the draw call returns,
     * and explicit recycle() risks a use-after-recycle there. Panel-sized bitmaps are
     * ~15MB and the GC reclaims them promptly once unreferenced.
     */
    private fun swapAndRedraw(newBitmap: Bitmap, animate: Boolean = false) {
        if (animate && current != null && current !== newBitmap) {
            fadeFrom = current
            fadeStartUptime = SystemClock.uptimeMillis()
            current = newBitmap
            scheduleFadeTick()
        } else {
            fadeFrom = null
            current = newBitmap
        }
        notifyRedraw()
    }

    private fun notifyRedraw() {
        for (cb in redrawListeners) {
            try {
                cb.invoke()
            } catch (t: Throwable) {
                Logg.e(TAG, "redraw listener failed", t)
            }
        }
    }

    /** Repaints every ~frame until the fade completes, then clears fade state. */
    private fun scheduleFadeTick() {
        renderHandler().postDelayed({
            if (fadeFrom == null) return@postDelayed
            if (SystemClock.uptimeMillis() - fadeStartUptime >= FADE_DURATION_MS) {
                fadeFrom = null
                notifyRedraw()   // final steady frame
            } else {
                notifyRedraw()
                scheduleFadeTick()
            }
        }, FADE_FRAME_MS)
    }

    private fun acquireWakeLock(ctx: Context) {
        try {
            val wl = wakeLock ?: run {
                val pm = ctx.getSystemService(PowerManager::class.java) ?: return
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
                    it.setReferenceCounted(false)
                    wakeLock = it
                }
            }
            wl.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            Logg.w(TAG, "wakelock acquire failed: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        val wl = wakeLock ?: return
        try {
            if (wl.isHeld) wl.release()
        } catch (t: Throwable) {
            Logg.w(TAG, "wakelock release failed: ${t.message}")
        }
    }
}
