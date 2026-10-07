package dev.immichwall.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.immichwall.R
import dev.immichwall.cache.PhotoCacheManager
import dev.immichwall.schedule.ScheduleApplier
import dev.immichwall.schedule.ScheduleText
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.CycleKeys
import dev.immichwall.source.SavedCycle
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.ui.onboarding.LiveWallpaperLauncher
import dev.immichwall.ui.onboarding.OptionsFragment
import dev.immichwall.ui.onboarding.SourcePickerFragment
import dev.immichwall.util.HealthChecker
import dev.immichwall.util.HealthIssue
import dev.immichwall.wallpaper.RotationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Steady-state screen: current wallpaper photo, the active cycle's stats and actions,
 * the saved-cycles list (build/switch/delete without disturbing the running cycle),
 * settings gear, health banners and the battery-optimization hint.
 */
class StatusFragment : Fragment(R.layout.fragment_status) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.status_refresh).setOnClickListener {
            SyncScheduler.kickManualRefresh(requireContext().applicationContext)
            Toast.makeText(requireContext(), R.string.status_refresh_started, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.status_new_cycle).setOnClickListener {
            // Fresh draft: never inherit another cycle's edit state.
            val vm = androidx.lifecycle.ViewModelProvider(requireActivity())[WizardViewModel::class.java]
            vm.editingCycleId = null
            vm.cyclePeoplePreference = "prefer"
            (requireActivity() as MainActivity).navigateTo(SourcePickerFragment())
        }
        view.findViewById<Button>(R.id.status_reapply).setOnClickListener {
            LiveWallpaperLauncher.launch(requireActivity())
        }
        view.findViewById<ImageButton>(R.id.status_settings).setOnClickListener {
            (requireActivity() as MainActivity).navigateTo(OptionsFragment.standalone())
        }
        view.findViewById<Button>(R.id.status_battery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.status_battery_error, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.status_schedule_edit).setOnClickListener {
            (requireActivity() as MainActivity).navigateTo(ScheduleFragment())
        }
        view.findViewById<Button>(R.id.status_schedule_resume).setOnClickListener {
            val appCtx = requireContext().applicationContext
            if (ScheduleApplier.resumeSchedule(appCtx)) {
                SyncScheduler.kickInitialFill(appCtx)
                RotationController.onActiveCycleChanged(appCtx)
            }
            reload()
        }
        view.findViewById<Button>(R.id.status_clear_cache).setOnClickListener { confirmClearCache() }

        // Self-refresh: reload whenever any refresh pipeline changes state, so
        // "Refresh now" and the post-onboarding initial fill update the screen
        // without the user having to navigate away and back. reload() is
        // idempotent and view-lifecycle-scoped, so extra emissions are safe.
        val workManager = WorkManager.getInstance(requireContext().applicationContext)
        val workNames = listOf(
            SyncScheduler.MANUAL_WORK_NAME,
            SyncScheduler.INITIAL_FILL_WORK_NAME,
            SyncScheduler.TOP_UP_WORK_NAME,
            SyncScheduler.PERIODIC_WORK_NAME,
        )
        for (name in workNames) {
            workManager.getWorkInfosForUniqueWorkLiveData(name)
                .observe(viewLifecycleOwner) { reload() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app on a boundary day must not show yesterday's cycle as the active one.
        val appCtx = requireContext().applicationContext
        if (ScheduleApplier.applyIfDue(appCtx)) {
            SyncScheduler.kickInitialFill(appCtx)
            RotationController.onActiveCycleChanged(appCtx)
        }
        reload()
    }

    private fun reload() {
        val view = view ?: return
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { buildState(appCtx) }
            render(view, state)
        }
    }

    private fun buildState(ctx: Context): StatusState {
        val settings = SettingsRepository.get(ctx)

        val preview: Bitmap? = runCatching {
            val cache = PhotoCacheManager.get(ctx)
            cache.currentEntry()?.let { entry ->
                PreviewGridAdapter.decodeSampled(cache.readyFile(entry), PREVIEW_MIN_EDGE_PX)
            }
        }.getOrNull()

        val readyCount = runCatching { PhotoCacheManager.get(ctx).readyCount() }.getOrDefault(0)
        // Photos of the ACTIVE cycle; null when no cycle is configured. The cache can also
        // hold the next cycle's photos, prefetched, and those must not count toward the
        // "N of target" line below.
        val activeCount = runCatching {
            CycleKeys.activeKey(settings)?.let { PhotoCacheManager.get(ctx).countFor(it) }
        }.getOrNull()
        val cacheMb = runCatching {
            val bytes = PhotoCacheManager.get(ctx).readyBytes()
            (bytes / (1024L * 1024L)).toInt()
        }.getOrDefault(0)
        // ctx (application context) rather than fragment getString: this runs on
        // IO and must not depend on the fragment still being attached.
        val cacheLine =
            ctx.getString(R.string.status_cache_stats, activeCount ?: readyCount, settings.targetCacheCount, cacheMb)

        val manifest = runCatching { PhotoCacheManager.get(ctx).manifest() }.getOrNull()
        val syncLine = if (manifest == null || manifest.lastSyncAt <= 0L) {
            ctx.getString(R.string.status_sync_never)
        } else {
            val relative = DateUtils.getRelativeTimeSpanString(
                manifest.lastSyncAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            if (manifest.lastSyncResult.isBlank()) relative
            else ctx.getString(R.string.status_sync_line, relative, manifest.lastSyncResult)
        }

        val sourceLabel =
            settings.sourceSpec?.summaryLabel() ?: ctx.getString(R.string.status_source_none)

        // notify=false: this screen already renders the same issues as banners, so a
        // UI-driven check must not fire a heads-up notification mid-use.
        val issues = runCatching { HealthChecker.check(ctx, notify = false) }.getOrDefault(emptyList())

        val cycles = runCatching { settings.cyclesConsistentWithActiveSpec() }
            .getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }
        val activeId = settings.activeCycleId

        var scheduleText = runCatching { ScheduleText.summary(ctx, settings) }.getOrDefault("")
        // The schedule switched to a cycle with nothing cached yet: say why the old photos are still up.
        if (activeCount == 0 && readyCount > 0) {
            scheduleText += "\n" + ctx.getString(R.string.status_schedule_waiting)
        }
        val overrideActive = runCatching { ScheduleApplier.plan(settings).overrideActive }.getOrDefault(false)

        return StatusState(preview, sourceLabel, cacheLine, syncLine, issues, cycles, activeId, scheduleText, overrideActive)
    }

    private fun render(view: View, state: StatusState) {
        val previewImage = view.findViewById<ImageView>(R.id.status_preview)
        val noPhoto = view.findViewById<TextView>(R.id.status_no_photo)
        if (state.preview != null) {
            previewImage.setImageBitmap(state.preview)
            previewImage.visibility = View.VISIBLE
            noPhoto.visibility = View.GONE
        } else {
            previewImage.visibility = View.GONE
            noPhoto.visibility = View.VISIBLE
        }

        view.findViewById<TextView>(R.id.status_source).text = state.sourceLabel
        view.findViewById<TextView>(R.id.status_cache).text = state.cacheLine
        view.findViewById<TextView>(R.id.status_sync).text = state.syncLine

        val banners = view.findViewById<LinearLayout>(R.id.status_banners)
        banners.removeAllViews()
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val gap = (8 * density).toInt()
        for (issue in state.issues) {
            val banner = TextView(requireContext())
            banner.text = getString(R.string.status_banner_text, issue.message)
            banner.setPadding(pad, pad, pad, pad)
            banner.setBackgroundColor(
                if (issue.severity >= 2) BANNER_BG_ERROR else BANNER_BG_WARNING
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.bottomMargin = gap
            banners.addView(banner, lp)
        }
        banners.visibility = if (state.issues.isEmpty()) View.GONE else View.VISIBLE

        view.findViewById<TextView>(R.id.status_schedule).text = state.scheduleText
        view.findViewById<Button>(R.id.status_schedule_resume).visibility =
            if (state.overrideActive) View.VISIBLE else View.GONE

        renderCycles(view, state.cycles, state.activeCycleId)
    }

    /** One tappable card per saved cycle: radio = active, tap = activate, trash = delete. */
    private fun renderCycles(view: View, cycles: List<SavedCycle>, activeId: String) {
        val container = view.findViewById<LinearLayout>(R.id.status_cycles)
        container.removeAllViews()
        if (cycles.isEmpty()) {
            val empty = TextView(requireContext())
            empty.setText(R.string.status_cycles_empty)
            empty.setTextColor(requireContext().getColor(android.R.color.darker_gray))
            container.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        for (cycle in cycles) {
            val row = inflater.inflate(R.layout.item_cycle, container, false)
            val isActive = cycle.id == activeId
            row.findViewById<TextView>(R.id.cycle_name).text = cycle.name
            row.findViewById<RadioButton>(R.id.cycle_active).isChecked = isActive
            val stateText = row.findViewById<TextView>(R.id.cycle_state)
            stateText.visibility = if (isActive) View.VISIBLE else View.GONE
            if (isActive) stateText.setText(R.string.cycle_active_label)

            // Row opens the detail page; the radio is the quick activate.
            row.setOnClickListener {
                (requireActivity() as MainActivity).navigateTo(CycleDetailFragment.forCycle(cycle.id))
            }
            val radio = row.findViewById<RadioButton>(R.id.cycle_active)
            radio.isClickable = true
            radio.setOnClickListener {
                if (cycle.id == activeId) return@setOnClickListener
                activateCycle(cycle)
            }
            row.findViewById<ImageButton>(R.id.cycle_delete).setOnClickListener {
                if (cycle.id == activeId) {
                    Toast.makeText(requireContext(), R.string.cycle_delete_active, Toast.LENGTH_SHORT).show()
                } else if (SettingsRepository.get(requireContext()).isCycleScheduled(cycle.id)) {
                    Toast.makeText(requireContext(), R.string.cycle_delete_scheduled, Toast.LENGTH_SHORT).show()
                } else {
                    confirmDeleteCycle(cycle)
                }
            }
            container.addView(row)
        }
    }

    private fun activateCycle(cycle: SavedCycle) {
        val appCtx = requireContext().applicationContext
        // With the schedule on, a manual pick holds until the schedule next changes.
        ScheduleApplier.activateManually(appCtx, cycle.id)
        // Photos already cached for this cycle show at once; the refill fetches the rest,
        // and the previous cycle's photos go once this one has something to show.
        RotationController.onActiveCycleChanged(appCtx)
        SyncScheduler.kickInitialFill(appCtx)
        Toast.makeText(
            requireContext(),
            getString(R.string.cycle_activated, cycle.name),
            Toast.LENGTH_SHORT,
        ).show()
        reload()
    }

    private fun confirmDeleteCycle(cycle: SavedCycle) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycle_delete_title)
            .setMessage(getString(R.string.cycle_delete_message, cycle.name))
            .setPositiveButton(R.string.cycle_delete_confirm) { _, _ ->
                SettingsRepository.get(requireContext()).deleteCycle(cycle.id)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.status_clear_cache_title)
            .setMessage(R.string.status_clear_cache_message)
            .setPositiveButton(R.string.status_clear_cache_confirm) { _, _ ->
                val appCtx = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        PhotoCacheManager.get(appCtx).clearAll()
                        // The shapes are learned again as the phone is folded and rotated.
                        SettingsRepository.get(appCtx).seenSurfaces = emptyList()
                    }
                    RotationController.onCacheCleared()
                    SyncScheduler.kickInitialFill(appCtx)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private data class StatusState(
        val preview: Bitmap?,
        val sourceLabel: String,
        val cacheLine: String,
        val syncLine: String,
        val issues: List<HealthIssue>,
        val cycles: List<SavedCycle>,
        val activeCycleId: String,
        val scheduleText: String,
        val overrideActive: Boolean,
    )

    companion object {
        private const val PREVIEW_MIN_EDGE_PX = 720
        private const val BANNER_BG_ERROR = 0x33FF5252
        private const val BANNER_BG_WARNING = 0x33FFB300
    }
}
