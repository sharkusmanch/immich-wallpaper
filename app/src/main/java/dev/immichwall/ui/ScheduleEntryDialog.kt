package dev.immichwall.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import dev.immichwall.R
import dev.immichwall.schedule.ScheduleApplier
import dev.immichwall.schedule.ScheduleEntry
import dev.immichwall.schedule.ScheduleResolver
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.SavedCycle
import java.time.Month
import java.time.MonthDay
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Creates or edits one schedule entry and writes it straight into the stored schedule;
 * the host learns about the change through the fragment result [REQUEST_KEY].
 */
class ScheduleEntryDialog : DialogFragment() {

    /** Selected month and day positions, in [SLOT_START_MONTH]..[SLOT_END_DAY] order. */
    private var dates = IntArray(SLOT_COUNT)

    /**
     * The chosen cycle, by id — never by list position: the list is read again at save
     * time and after the process is recreated, and a position would then point at whatever
     * sits there. Null = none chosen.
     */
    private var cycleId: String? = null

    private fun cycles(): List<SavedCycle> =
        SettingsRepository.get(requireContext()).cyclesConsistentWithActiveSpec().sortedBy { it.name.lowercase() }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val settings = SettingsRepository.get(requireContext())
        val cycles = cycles()
        val existing = arguments?.getString(ARG_ENTRY_ID)
            ?.let { id -> settings.schedule.entries.firstOrNull { it.id == id } }

        // Not this fragment's layoutInflater: asking a DialogFragment for it inside
        // onCreateDialog re-enters onCreateDialog.
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_schedule_entry, null)

        val months = Month.values().map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
        val days = (1..31).map { it.toString() }
        val today = MonthDay.from(ScheduleApplier.today(settings))
        val start = existing?.let { ScheduleResolver.parseMonthDay(it.start) } ?: today
        val end = existing?.let { ScheduleResolver.parseMonthDay(it.end) } ?: start

        dates = savedInstanceState?.getIntArray(STATE_DATES)?.takeIf { it.size == SLOT_COUNT }
            ?: intArrayOf(start.monthValue - 1, start.dayOfMonth - 1, end.monthValue - 1, end.dayOfMonth - 1)
        cycleId = when {
            savedInstanceState != null -> savedInstanceState.getString(STATE_CYCLE_ID)
            // A new entry starts on the first cycle.
            existing == null -> cycles.firstOrNull()?.id
            // An entry whose cycle was deleted starts with none chosen, so that Save cannot
            // quietly re-point it at whichever cycle happens to be first.
            else -> existing.cycleId.takeIf { id -> cycles.any { it.id == id } }
        }

        fun bind(id: Int, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
            val dropdown = content.findViewById<MaterialAutoCompleteTextView>(id)
            dropdown.setSimpleItems(items.toTypedArray())
            // filter = false: the text is a label, not a query, and must not narrow the list.
            items.getOrNull(selected)?.let { dropdown.setText(it, false) }
            dropdown.setOnItemClickListener { _, _, position, _ -> onPick(position) }
        }

        content.findViewById<TextInputEditText>(R.id.entry_edit_name).setText(existing?.name.orEmpty())
        // `cycles` here is the very list the dropdown shows, so its position is safe to use.
        bind(R.id.entry_edit_cycle, cycles.map { it.name }, cycles.indexOfFirst { it.id == cycleId }) { position ->
            cycleId = cycles[position].id
        }
        bind(R.id.entry_edit_start_month, months, dates[SLOT_START_MONTH]) { dates[SLOT_START_MONTH] = it }
        bind(R.id.entry_edit_start_day, days, dates[SLOT_START_DAY]) { dates[SLOT_START_DAY] = it }
        bind(R.id.entry_edit_end_month, months, dates[SLOT_END_MONTH]) { dates[SLOT_END_MONTH] = it }
        bind(R.id.entry_edit_end_day, days, dates[SLOT_END_DAY]) { dates[SLOT_END_DAY] = it }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.schedule_entry_new else R.string.schedule_entry_edit)
            .setView(content)
            // Wired in onStart instead, so an impossible date keeps the dialog open.
            .setPositiveButton(R.string.schedule_entry_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putIntArray(STATE_DATES, dates)
        outState.putString(STATE_CYCLE_ID, cycleId)
    }

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as AlertDialog
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        saveButton.setOnClickListener {
            if (save(dialog)) {
                // dismiss() takes effect asynchronously; a second tap must not add a second entry.
                saveButton.isEnabled = false
                dismiss()
            }
        }
    }

    /**
     * Validates and stores the entry. False (with a toast, dialog left open) when no cycle
     * is chosen or a date such as Feb 30 was picked.
     */
    private fun save(dialog: AlertDialog): Boolean {
        val cycle = cycles().firstOrNull { it.id == cycleId }
        if (cycle == null) {
            Toast.makeText(requireContext(), R.string.schedule_entry_pick_cycle, Toast.LENGTH_SHORT).show()
            return false
        }
        val start = monthDay(dates[SLOT_START_MONTH], dates[SLOT_START_DAY])
        val end = monthDay(dates[SLOT_END_MONTH], dates[SLOT_END_DAY])
        if (ScheduleResolver.parseMonthDay(start) == null || ScheduleResolver.parseMonthDay(end) == null) {
            Toast.makeText(requireContext(), R.string.schedule_entry_bad_date, Toast.LENGTH_SHORT).show()
            return false
        }

        val settings = SettingsRepository.get(requireContext())
        val schedule = settings.schedule
        val existing = arguments?.getString(ARG_ENTRY_ID)?.let { id -> schedule.entries.firstOrNull { it.id == id } }
        val typedName = dialog.findViewById<TextInputEditText>(R.id.entry_edit_name)?.text?.toString()?.trim().orEmpty()
        val entry = ScheduleEntry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            // Blank stays blank: an unnamed entry is shown under its cycle's name, and so
            // keeps following the cycle if that is changed later.
            name = typedName,
            cycleId = cycle.id,
            start = start,
            end = end,
        )
        settings.schedule = schedule.copy(
            entries = if (existing == null) schedule.entries + entry
            else schedule.entries.map { if (it.id == entry.id) entry else it }
        )
        parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
        return true
    }

    /** `MM-DD` from zero-based positions; Locale.ROOT keeps the digits ASCII. */
    private fun monthDay(monthIndex: Int, dayIndex: Int): String =
        String.format(Locale.ROOT, "%02d-%02d", monthIndex + 1, dayIndex + 1)

    companion object {
        const val REQUEST_KEY = "schedule_entry_saved"
        const val TAG = "ScheduleEntryDialog"
        private const val ARG_ENTRY_ID = "entryId"
        private const val STATE_DATES = "dates"
        private const val STATE_CYCLE_ID = "cycleId"

        private const val SLOT_START_MONTH = 0
        private const val SLOT_START_DAY = 1
        private const val SLOT_END_MONTH = 2
        private const val SLOT_END_DAY = 3
        private const val SLOT_COUNT = 4

        /** [entryId] null = a new entry. */
        fun forEntry(entryId: String?): ScheduleEntryDialog =
            ScheduleEntryDialog().apply { arguments = bundleOf(ARG_ENTRY_ID to entryId) }
    }
}
