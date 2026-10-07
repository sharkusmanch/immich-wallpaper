package dev.immichwall.util

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.immichwall.App
import dev.immichwall.cache.PhotoCacheManager
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.wallpaper.PhotoWallpaperService

data class HealthIssue(val id: String, val severity: Int, val message: String)

/**
 * Self-healing health checks that ride along with every refresh (and the status screen).
 *
 * Severity 2 = actionable breakage (wallpaper replaced/cleared, cache empty) — surfaced via a
 * notification on the "health" channel. Severity 1 = degraded but self-correcting — surfaced
 * only on the status screen.
 */
object HealthChecker {

    private const val TAG = "HealthChecker"
    private const val NOTIFICATION_ID = 2001
    private const val UNIQUE_REFRESH_WORK = "cache-refresh"

    /**
     * Runs all checks. [notify] gates the notification side effect: UI-driven calls
     * (status screen, which already shows the same issues as banners) pass false so
     * merely opening the app never fires a heads-up; background refreshes keep the
     * default. Even with notify=false a stale notification is cancelled once no
     * severe issue remains.
     */
    @JvmOverloads
    fun check(ctx: Context, notify: Boolean = true): List<HealthIssue> {
        val issues = mutableListOf<HealthIssue>()

        // 1. Is our live wallpaper still the active one?
        try {
            val info = WallpaperManager.getInstance(ctx).wallpaperInfo
            val expected = ComponentName(ctx, PhotoWallpaperService::class.java)
            if (info == null || info.component != expected) {
                issues += HealthIssue(
                    id = "wallpaper-not-set",
                    severity = 2,
                    message = "Immich Wallpaper is not the active wallpaper. Open the app to re-apply it."
                )
            }
        } catch (t: Throwable) {
            Logg.w(TAG, "wallpaper component check failed: ${t.message}")
        }

        // 2/3. Cache level.
        val cache = PhotoCacheManager.get(ctx)
        // The ACTIVE cycle's photos are what the wallpaper rotates through; prefetched
        // photos of the next cycle must not hide an active cycle that is nearly empty.
        // While the active cycle has none at all, rotation falls back to everything
        // cached, so then the whole cache is the right number.
        val readyCount = try {
            val activeKey = dev.immichwall.source.CycleKeys.activeKey(SettingsRepository.get(ctx))
            val active = activeKey?.let { cache.countFor(it) } ?: 0
            if (active > 0) active else cache.readyCount()
        } catch (t: Throwable) {
            Logg.w(TAG, "readyCount failed: ${t.message}")
            -1
        }
        if (readyCount == 0) {
            issues += HealthIssue(
                id = "cache-empty",
                severity = 2,
                message = "No photos are cached. The wallpaper cannot rotate until a sync succeeds."
            )
        } else if (readyCount in 1 until PhotoCacheManager.HARD_FLOOR) {
            issues += HealthIssue(
                id = "cache-low",
                severity = 1,
                message = "Only $readyCount photos cached (floor is ${PhotoCacheManager.HARD_FLOOR}). Waiting on refresh."
            )
        }

        // 4. Is the periodic refresh work still scheduled? Re-enqueue if lost.
        try {
            val workInfos = WorkManager.getInstance(ctx)
                .getWorkInfosForUniqueWork(UNIQUE_REFRESH_WORK)
                .get()
            val lost = workInfos.isEmpty() || workInfos.all { it.state == WorkInfo.State.CANCELLED }
            if (lost) {
                Logg.w(TAG, "periodic refresh work missing; re-enqueueing")
                SyncScheduler.ensurePeriodic(ctx)
                issues += HealthIssue(
                    id = "work-lost",
                    severity = 1,
                    message = "Background refresh schedule was missing and has been restored."
                )
            }
        } catch (t: Throwable) {
            Logg.w(TAG, "work schedule check failed: ${t.message}")
        }

        // 5. Sync staleness: last successful sync older than 3x the refresh interval.
        try {
            val lastSyncAt = cache.manifest().lastSyncAt
            val intervalHours = SettingsRepository.get(ctx).refreshIntervalHours
            if (lastSyncAt > 0L && intervalHours > 0) {
                val ageMs = System.currentTimeMillis() - lastSyncAt
                val staleAfterMs = 3L * intervalHours * 3_600_000L
                if (ageMs > staleAfterMs) {
                    val ageHours = ageMs / 3_600_000L
                    issues += HealthIssue(
                        id = "sync-stale",
                        severity = 1,
                        message = "Last successful sync was ${ageHours}h ago (refresh interval is ${intervalHours}h)."
                    )
                }
            }
        } catch (t: Throwable) {
            Logg.w(TAG, "staleness check failed: ${t.message}")
        }

        val severe = issues.filter { it.severity >= 2 }
        // notify=false must never post, but clearing a now-stale notification is fine.
        if (notify || severe.isEmpty()) updateNotification(ctx, severe)
        return issues
    }

    /** Posts a "health" channel notification for severity-2 issues; clears it when healthy. */
    private fun updateNotification(ctx: Context, severe: List<HealthIssue>) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (severe.isEmpty()) {
            try {
                nm.cancel(NOTIFICATION_ID)
            } catch (t: Throwable) {
                Logg.w(TAG, "notification cancel failed: ${t.message}")
            }
            return
        }
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Logg.w(TAG, "POST_NOTIFICATIONS not granted; skipping health notification")
            return
        }
        try {
            // Channel is created (with its canonical user-visible name) in App.onCreate,
            // which always runs in this process before any HealthChecker call — never
            // re-create it here or the display name silently changes in system settings.
            val text = severe.joinToString("\n") { it.message }
            val contentIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                PendingIntent.getActivity(
                    ctx, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notification = NotificationCompat.Builder(ctx, App.CHANNEL_HEALTH)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Wallpaper needs attention")
                .setContentText(severe.first().message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Logg.w(TAG, "health notification failed: ${t.message}")
        }
    }
}
