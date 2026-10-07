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

    /** Selected positions, in [SLOT_CYCLE]..[SLOT_END_DAY] order. Survives rotation and unfolding via saved state. */
    private var selection = IntArray(SLOT_COUNT)

    /** Same order in [onCreateDialog] and [save]: the cycle dropdown stores a position. */
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

        selection = savedInstanceState?.getIntArray(STATE_SELECTION)?.takeIf { it.size == SLOT_COUNT }
            ?: intArrayOf(
                cycles.indexOfFirst { it.id == existing?.cycleId }.coerceAtLeast(0),
                start.monthValue - 1,
                start.dayOfMonth - 1,
                end.monthValue - 1,
                end.dayOfMonth - 1,
            )

        fun bind(slot: Int, id: Int, items: List<String>) {
            val dropdown = content.findViewById<MaterialAutoCompleteTextView>(id)
            dropdown.setSimpleItems(items.toTypedArray())
            // filter = false: the text is a label, not a query, and must not narrow the list.
            items.getOrNull(selection[slot])?.let { dropdown.setText(it, false) }
            dropdown.setOnItemClickListener { _, _, position, _ -> selection[slot] = position }
        }

        content.findViewById<TextInputEditText>(R.id.entry_edit_name).setText(existing?.name.orEmpty())
        bind(SLOT_CYCLE, R.id.entry_edit_cycle, cycles.map { it.name })
        bind(SLOT_START_MONTH, R.id.entry_edit_start_month, months)
        bind(SLOT_START_DAY, R.id.entry_edit_start_day, days)
        bind(SLOT_END_MONTH, R.id.entry_edit_end_month, months)
        bind(SLOT_END_DAY, R.id.entry_edit_end_day, days)

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
        outState.putIntArray(STATE_SELECTION, selection)
    }

    override fun onStart() {
        super.onStart()
        val dialog = requireDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (save(dialog)) dismiss()
        }
    }

    /** Validates and stores the entry. False (with a toast) when a date such as Feb 30 was picked. */
    private fun save(dialog: AlertDialog): Boolean {
        val start = monthDay(selection[SLOT_START_MONTH], selection[SLOT_START_DAY])
        val end = monthDay(selection[SLOT_END_MONTH], selection[SLOT_END_DAY])
        if (ScheduleResolver.parseMonthDay(start) == null || ScheduleResolver.parseMonthDay(end) == null) {
            Toast.makeText(requireContext(), R.string.schedule_entry_bad_date, Toast.LENGTH_SHORT).show()
            return false
        }
        val cycle = cycles().getOrNull(selection[SLOT_CYCLE]) ?: return false

        val settings = SettingsRepository.get(requireContext())
        val schedule = settings.schedule
        val existing = arguments?.getString(ARG_ENTRY_ID)?.let { id -> schedule.entries.firstOrNull { it.id == id } }
        val typedName = dialog.findViewById<TextInputEditText>(R.id.entry_edit_name)?.text?.toString()?.trim().orEmpty()
        val entry = ScheduleEntry(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = typedName.ifBlank { cycle.name },
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
        private const val STATE_SELECTION = "selection"

        private const val SLOT_CYCLE = 0
        private const val SLOT_START_MONTH = 1
        private const val SLOT_START_DAY = 2
        private const val SLOT_END_MONTH = 3
        private const val SLOT_END_DAY = 4
        private const val SLOT_COUNT = 5

        /** [entryId] null = a new entry. */
        fun forEntry(entryId: String?): ScheduleEntryDialog =
            ScheduleEntryDialog().apply { arguments = bundleOf(ARG_ENTRY_ID to entryId) }
    }
}
