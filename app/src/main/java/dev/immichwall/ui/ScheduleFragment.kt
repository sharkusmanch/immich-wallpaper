package dev.immichwall.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import dev.immichwall.BuildConfig
import dev.immichwall.R
import dev.immichwall.schedule.Schedule
import dev.immichwall.schedule.ScheduleApplier
import dev.immichwall.schedule.ScheduleEntry
import dev.immichwall.schedule.ScheduleResolver
import dev.immichwall.schedule.ScheduleText
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.SavedCycle
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.wallpaper.RotationController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Edits the date-of-year schedule: the on/off switch, the default cycle, and the ordered
 * entries (first match wins, so order is the user's to set). "Check a date" answers which
 * entry would win on any day. Every change is stored and applied at once.
 */
class ScheduleFragment : Fragment(R.layout.fragment_schedule) {

    private val dayFormat = DateTimeFormatter.ofPattern("MMM d")
    private val fullDayFormat = DateTimeFormatter.ofPattern("MMM d, yyyy")

    private fun cycles(settings: SettingsRepository): List<SavedCycle> =
        settings.cyclesConsistentWithActiveSpec().sortedBy { it.name.lowercase() }

    /**
     * Exactly the list the default-cycle dropdown is showing. A tapped position is looked
     * up here, not in a fresh read of the settings, which could have changed since.
     */
    private var renderedCycles: List<SavedCycle> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        parentFragmentManager.setFragmentResultListener(
            ScheduleEntryDialog.REQUEST_KEY, viewLifecycleOwner,
        ) { _, _ -> onScheduleEdited() }

        // render() sets the switch too; the listener saves only a real change.
        view.findViewById<MaterialSwitch>(R.id.schedule_enabled).setOnCheckedChangeListener { _, checked ->
            val settings = SettingsRepository.get(requireContext())
            val schedule = settings.schedule
            if (checked != schedule.enabled) {
                // Switching it on is what commits the default the dropdown has been showing.
                val defaultId =
                    if (checked) defaultToShow(schedule, settings, cycles(settings).mapTo(HashSet()) { it.id })
                    else schedule.defaultCycleId
                save(schedule.copy(enabled = checked, defaultCycleId = defaultId))
            }
        }
        // Fires only for a tap on a menu item, never for render()'s setText.
        view.findViewById<MaterialAutoCompleteTextView>(R.id.schedule_default)
            .setOnItemClickListener { _, _, position, _ ->
                val picked = renderedCycles.getOrNull(position)?.id ?: return@setOnItemClickListener
                val schedule = SettingsRepository.get(requireContext()).schedule
                if (picked != schedule.defaultCycleId) save(schedule.copy(defaultCycleId = picked))
            }

        view.findViewById<Button>(R.id.schedule_add).setOnClickListener { editEntry(null) }
        view.findViewById<Button>(R.id.schedule_check).setOnClickListener { pickDate(TAG_CHECK) }

        // A date picker that was open when the activity was recreated (unfolding does that)
        // comes back without its listener; without this its OK button would do nothing.
        for (tag in listOf(TAG_CHECK, TAG_DEBUG)) {
            @Suppress("UNCHECKED_CAST")
            (parentFragmentManager.findFragmentByTag(tag) as? MaterialDatePicker<Long>)?.let { listen(it, tag) }
        }

        if (BuildConfig.DEBUG) {
            val debugButton = view.findViewById<Button>(R.id.schedule_debug_date)
            debugButton.visibility = View.VISIBLE
            // These only STORE the date. Applying it here would switch cycles through the
            // UI path and the screen-off / screen-on path would never get tested: lock the
            // phone after setting a date and that path picks it up.
            debugButton.setOnClickListener { pickDate(TAG_DEBUG) }
            debugButton.setOnLongClickListener {
                SettingsRepository.get(requireContext()).debugToday = ""
                render()
                true
            }
        }

        render()
    }

    /**
     * A schedule needs a cycle to fall back on. Until one is stored (or when the stored one
     * no longer exists) that is the cycle that is running. It is only SHOWN: a stored default
     * protects its cycle from deletion, and merely opening this screen must not do that. It
     * is stored when the user picks one or switches the schedule on.
     */
    private fun defaultToShow(schedule: Schedule, settings: SettingsRepository, known: Set<String>): String =
        if (schedule.defaultCycleId !in known && settings.activeCycleId in known) settings.activeCycleId
        else schedule.defaultCycleId

    private fun save(schedule: Schedule) {
        SettingsRepository.get(requireContext()).schedule = schedule
        onScheduleEdited()
    }

    /** The stored schedule changed: apply it now, start fetching if the cycle switched, redraw. */
    private fun onScheduleEdited() {
        val appCtx = requireContext().applicationContext
        if (ScheduleApplier.applyIfDue(appCtx)) {
            SyncScheduler.kickInitialFill(appCtx)
            RotationController.onActiveCycleChanged(appCtx)
        }
        render()
    }

    private fun editEntry(entry: ScheduleEntry?) {
        if (cycles(SettingsRepository.get(requireContext())).isEmpty()) {
            Toast.makeText(requireContext(), R.string.schedule_no_cycles, Toast.LENGTH_SHORT).show()
            return
        }
        ScheduleEntryDialog.forEntry(entry?.id).show(parentFragmentManager, ScheduleEntryDialog.TAG)
    }

    private fun render() {
        val view = view ?: return
        val settings = SettingsRepository.get(requireContext())
        val cycles = cycles(settings)
        val known = cycles.mapTo(HashSet()) { it.id }
        val names = cycles.associate { it.id to it.name }

        // Exactly what is stored: every row's buttons save a copy of it.
        val schedule = settings.schedule

        view.findViewById<MaterialSwitch>(R.id.schedule_enabled).isChecked = schedule.enabled
        view.findViewById<TextView>(R.id.schedule_summary).text = ScheduleText.summary(requireContext(), settings)

        renderedCycles = cycles
        val defaultDropdown = view.findViewById<MaterialAutoCompleteTextView>(R.id.schedule_default)
        defaultDropdown.setSimpleItems(cycles.map { it.name }.toTypedArray())
        // filter = false: the text is a label, not a query, and must not narrow the list.
        defaultDropdown.setText(names[defaultToShow(schedule, settings, known)].orEmpty(), false)

        val entries = schedule.entries
        view.findViewById<TextView>(R.id.schedule_entries_empty).visibility =
            if (entries.isEmpty()) View.VISIBLE else View.GONE
        val container = view.findViewById<LinearLayout>(R.id.schedule_entries)
        container.removeAllViews()
        val shadowed = ScheduleResolver.shadowedEntryIds(schedule, known)
        val inflater = LayoutInflater.from(requireContext())
        entries.forEachIndexed { index, entry ->
            val row = inflater.inflate(R.layout.item_schedule_entry, container, false)
            // An unnamed entry goes by its cycle's name.
            val label = entry.name.ifBlank { names[entry.cycleId] ?: getString(R.string.schedule_cycle_missing) }
            row.findViewById<TextView>(R.id.entry_name).text = label
            row.findViewById<TextView>(R.id.entry_detail).text = getString(
                R.string.schedule_entry_detail,
                names[entry.cycleId] ?: getString(R.string.schedule_cycle_missing),
                formatDay(entry.start),
                formatDay(entry.end),
            )
            val warning = row.findViewById<TextView>(R.id.entry_warning)
            when {
                entry.cycleId !in known -> {
                    warning.setText(R.string.schedule_entry_missing_cycle)
                    warning.visibility = View.VISIBLE
                }
                entry.id in shadowed -> {
                    warning.setText(R.string.schedule_entry_shadowed)
                    warning.visibility = View.VISIBLE
                }
                else -> warning.visibility = View.GONE
            }
            val up = row.findViewById<Button>(R.id.entry_up)
            up.isEnabled = index > 0
            up.setOnClickListener { save(schedule.copy(entries = entries.swapped(index, index - 1))) }
            val down = row.findViewById<Button>(R.id.entry_down)
            down.isEnabled = index < entries.lastIndex
            down.setOnClickListener { save(schedule.copy(entries = entries.swapped(index, index + 1))) }
            row.findViewById<Button>(R.id.entry_delete).setOnClickListener { confirmDeleteEntry(entry, label) }
            row.setOnClickListener { editEntry(entry) }
            container.addView(row)
        }

        if (BuildConfig.DEBUG) {
            val debugToday = settings.debugToday
            view.findViewById<Button>(R.id.schedule_debug_date).text =
                if (debugToday.isBlank()) getString(R.string.schedule_debug_date)
                else getString(R.string.schedule_debug_date_set, debugToday)
        }
    }

    /**
     * Deleting an entry applies at once: it can switch the active cycle, and the next sync
     * then deletes the photos of the cycle it switched away from. The button sits beside
     * the move arrows, so a slip must not be enough.
     */
    private fun confirmDeleteEntry(entry: ScheduleEntry, label: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.schedule_entry_delete_title)
            .setMessage(getString(R.string.schedule_entry_delete_message, label))
            .setPositiveButton(R.string.schedule_entry_delete_confirm) { _, _ ->
                val schedule = SettingsRepository.get(requireContext()).schedule
                save(schedule.copy(entries = schedule.entries.filter { it.id != entry.id }))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "Nov 26" from a stored `MM-DD`; the raw text if it somehow does not parse. */
    private fun formatDay(monthDay: String): String =
        ScheduleResolver.parseMonthDay(monthDay)?.format(dayFormat) ?: monthDay

    private fun showCheckResult(date: LocalDate) {
        val view = view ?: return
        val settings = SettingsRepository.get(requireContext())
        val cycles = cycles(settings)
        val names = cycles.associate { it.id to it.name }
        // Answered with the default the dropdown shows, stored yet or not.
        val schedule = settings.schedule.let { it.copy(defaultCycleId = defaultToShow(it, settings, names.keys)) }
        val resolution = ScheduleResolver.resolve(schedule, date, names.keys)
        val day = date.format(fullDayFormat)
        view.findViewById<TextView>(R.id.schedule_check_result).text =
            if (resolution == null) {
                getString(R.string.schedule_check_none, day)
            } else {
                getString(
                    R.string.schedule_check_result,
                    day,
                    ScheduleText.entryLabel(requireContext(), schedule, resolution, names),
                    names[resolution.cycleId].orEmpty(),
                )
            }
    }

    /** Opens a date picker whose result is routed by [tag], so it can be re-attached after recreation. */
    private fun pickDate(tag: String) {
        val picker = MaterialDatePicker.Builder.datePicker().build()
        listen(picker, tag)
        picker.show(parentFragmentManager, tag)
    }

    private fun listen(picker: MaterialDatePicker<Long>, tag: String) {
        picker.addOnPositiveButtonClickListener { millis ->
            // The picker reports the chosen day as midnight UTC.
            onDatePicked(tag, Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
        }
    }

    private fun onDatePicked(tag: String, date: LocalDate) {
        if (view == null) return
        when (tag) {
            TAG_CHECK -> showCheckResult(date)
            // Only STORES the date (see onViewCreated): the lock/unlock path applies it.
            TAG_DEBUG -> {
                SettingsRepository.get(requireContext()).debugToday = date.toString()
                render()
            }
        }
    }

    private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
        toMutableList().also { list ->
            val moved = list[a]
            list[a] = list[b]
            list[b] = moved
        }

    private companion object {
        const val TAG_CHECK = "schedule-check"
        const val TAG_DEBUG = "schedule-debug"
    }
}
