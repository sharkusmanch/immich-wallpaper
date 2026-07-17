package dev.immichwall.ui.onboarding

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dev.immichwall.R
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.StatusFragment
import dev.immichwall.util.Logg
import dev.immichwall.wallpaper.PhotoWallpaperService

/**
 * Final wizard step: marks the app configured, kicks the initial expedited
 * fill + periodic refresh, then walks the live-wallpaper intent ladder
 * (CHANGE_LIVE_WALLPAPER → LIVE_WALLPAPER_CHOOSER → manual-steps dialog) and
 * lands on the status screen.
 */
class ApplyFragment : Fragment(R.layout.fragment_apply) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository.get(requireContext())

        val summary = view.findViewById<TextView>(R.id.apply_summary)
        val applyButton = view.findViewById<Button>(R.id.apply_button)

        summary.text = getString(
            R.string.apply_summary,
            settings.sourceSpec?.summaryLabel() ?: getString(R.string.status_source_none),
            settings.targetCacheCount,
            settings.refreshIntervalHours,
        )

        applyButton.setOnClickListener {
            val appCtx = requireContext().applicationContext
            settings.isConfigured = true
            // Pre-seed crop dimensions so the very first fill crops at true panel size;
            // maximumWindowMetrics is the full display regardless of multi-window state
            // (currentWindowMetrics would seed half-size bounds in split-screen), but it
            // follows the CURRENT rotation — normalize back to the display's natural
            // orientation so an Apply in landscape doesn't seed swapped dims that the
            // engine immediately marks stale. onSurfaceChanged remains the authority
            // and corrects any drift.
            if (settings.cropWidth <= 0 || settings.cropHeight <= 0) {
                val bounds = requireActivity().windowManager.maximumWindowMetrics.bounds
                var width = bounds.width()
                var height = bounds.height()
                val rotation = requireActivity().display?.rotation ?: Surface.ROTATION_0
                if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                    val swap = width
                    width = height
                    height = swap
                }
                if (width > 0 && height > 0) {
                    settings.cropWidth = width
                    settings.cropHeight = height
                }
            }
            SyncScheduler.kickInitialFill(appCtx)
            SyncScheduler.ensurePeriodic(appCtx)
            LiveWallpaperLauncher.launch(requireActivity())
            val mainActivity = requireActivity() as MainActivity
            mainActivity.resetTo(StatusFragment())
            // The health system becomes relevant now — ask for POST_NOTIFICATIONS once.
            mainActivity.maybeRequestNotificationPermission()
        }
    }
}

/**
 * [FIX] intent ladder from DESIGN.md: GrapheneOS has no Google wallpaper
 * picker and the secondary-profile picker may be minimal, so each rung is
 * wrapped and the last resort is a manual-instructions dialog.
 */
internal object LiveWallpaperLauncher {

    private const val TAG = "LiveWallpaperLauncher"

    /**
     * Clearing a lock-specific wallpaper is destructive and has no undo, so it only
     * happens after explicit confirmation. When no separate lock wallpaper exists
     * (the common case — lock already follows the system wallpaper) the picker opens
     * directly, keeping the first-time Apply path one-tap. Declining cancels the
     * whole launch: nothing is cleared and no picker opens.
     *
     * The confirmation is a [LockReplaceConfirmDialog] (DialogFragment), so a
     * configuration change mid-confirmation re-shows it instead of leaking the
     * Activity window; the positive result arrives via the fragment-result
     * listener MainActivity registers, which calls [confirmAndOpen].
     */
    fun launch(activity: FragmentActivity) {
        if (!hasLockWallpaper(activity)) {
            openPicker(activity)
            return
        }
        val fm = activity.supportFragmentManager
        if (fm.findFragmentByTag(LockReplaceConfirmDialog.TAG) == null) {
            LockReplaceConfirmDialog().show(fm, LockReplaceConfirmDialog.TAG)
        }
    }

    /** Confirmed lock-replace path — invoked from MainActivity's result listener. */
    fun confirmAndOpen(activity: Activity) {
        clearLockWallpaper(activity)
        openPicker(activity)
    }

    private fun openPicker(activity: Activity) {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(activity, PhotoWallpaperService::class.java),
            )
            activity.startActivity(intent)
        } catch (e: Exception) {
            Logg.w(TAG, "ACTION_CHANGE_LIVE_WALLPAPER failed: ${e.message}")
            try {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Logg.w(TAG, "ACTION_LIVE_WALLPAPER_CHOOSER failed: ${e2.message}")
                try {
                    // Generic wallpaper-capable chooser (offers "Wallpaper & style" on
                    // AOSP/GrapheneOS) — last automated rung before manual instructions.
                    activity.startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
                } catch (e3: Exception) {
                    Logg.w(TAG, "ACTION_SET_WALLPAPER failed: ${e3.message}")
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.apply_manual_title)
                        .setMessage(R.string.apply_manual_message)
                        .setPositiveButton(R.string.apply_manual_ok, null)
                        .show()
                }
            }
        }
    }

    /** True when a lock-specific wallpaper record exists (id >= 0). */
    private fun hasLockWallpaper(activity: Activity): Boolean = try {
        WallpaperManager.getInstance(activity)
            .getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0
    } catch (t: Throwable) {
        Logg.w(TAG, "lock wallpaper check failed: ${t.message}")
        false
    }

    /**
     * A device with a lock-specific static wallpaper keeps showing it even after a live
     * wallpaper becomes the system wallpaper — the exact "it doesn't seem to be the actual
     * wallpaper" trap. Clearing the lock-specific wallpaper (SET_WALLPAPER permission) makes
     * the lock screen follow the system wallpaper, so one engine serves both.
     */
    private fun clearLockWallpaper(activity: Activity) {
        try {
            val wm = WallpaperManager.getInstance(activity)
            if (wm.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0) {
                wm.clear(WallpaperManager.FLAG_LOCK)
                Logg.d(TAG, "cleared lock-specific wallpaper so the live engine covers lock too")
            }
        } catch (t: Throwable) {
            Logg.w(TAG, "clearing lock wallpaper failed: ${t.message}")
        }
    }
}
