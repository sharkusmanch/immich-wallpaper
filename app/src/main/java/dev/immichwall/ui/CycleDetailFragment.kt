package dev.immichwall.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.immichwall.R
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.CycleLabels
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.ui.onboarding.AlbumPickerFragment
import dev.immichwall.ui.onboarding.CustomFilterFragment
import dev.immichwall.ui.onboarding.LocationPickerFragment
import dev.immichwall.ui.onboarding.MemoriesPickerFragment
import dev.immichwall.ui.onboarding.PeoplePickerFragment
import dev.immichwall.ui.onboarding.SmartQueryFragment
import dev.immichwall.ui.onboarding.SourcePreviewFragment

/**
 * Detail page for one saved cycle: what it is, its per-cycle settings, and the actions —
 * activate, re-edit the photo source (re-enters the wizard prefilled, preview save keeps
 * the cycle's id), delete.
 */
class CycleDetailFragment : Fragment(R.layout.fragment_cycle_detail) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository.get(requireContext())
        val cycleId = requireArguments().getString(ARG_CYCLE_ID) ?: ""

        fun currentCycle(): SavedCycle? = settings.savedCycles.firstOrNull { it.id == cycleId }
        val cycle = currentCycle()
        if (cycle == null) {
            parentFragmentManager.popBackStack()
            return
        }
        val isActive = settings.activeCycleId == cycle.id

        // Titled as the row that led here: the label, which tells same-named cycles apart.
        view.findViewById<TextView>(R.id.detail_name).text =
            CycleLabels.of(settings.cyclesConsistentWithActiveSpec()).firstOrNull { it.cycle.id == cycleId }?.label ?: cycle.name
        view.findViewById<TextView>(R.id.detail_state).setText(
            if (isActive) R.string.cycle_active_label else R.string.detail_inactive
        )

        val peopleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.detail_people_group)
        peopleGroup.check(
            when (cycle.peoplePreference) {
                "off" -> R.id.detail_people_off
                "require" -> R.id.detail_people_require
                else -> R.id.detail_people_prefer
            }
        )
        peopleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val pref = when (checkedId) {
                R.id.detail_people_off -> "off"
                R.id.detail_people_require -> "require"
                else -> "prefer"
            }
            settings.setCyclePeoplePreference(cycleId, pref)
            // Active cycle: the changed quality tag re-rolls the cache on the next refresh.
            if (settings.activeCycleId == cycleId) {
                SyncScheduler.kickManualRefresh(requireContext().applicationContext)
            }
        }

        val activateButton = view.findViewById<Button>(R.id.detail_activate)
        activateButton.visibility = if (isActive) View.GONE else View.VISIBLE
        activateButton.setOnClickListener {
            val appCtx = requireContext().applicationContext
            dev.immichwall.schedule.ScheduleApplier.activateManually(appCtx, cycleId)
            dev.immichwall.wallpaper.RotationController.onActiveCycleChanged(appCtx)
            SyncScheduler.kickInitialFill(appCtx)
            Toast.makeText(
                requireContext(),
                getString(R.string.cycle_activated, cycle.name),
                Toast.LENGTH_SHORT,
            ).show()
            parentFragmentManager.popBackStack()
        }

        view.findViewById<Button>(R.id.detail_edit).setOnClickListener {
            val c = currentCycle() ?: return@setOnClickListener
            val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
            vm.editingCycleId = c.id
            vm.cyclePeoplePreference = c.peoplePreference
            (requireActivity() as MainActivity).navigateTo(loadSpecIntoWizard(vm, c.spec))
        }

        val deleteButton = view.findViewById<Button>(R.id.detail_delete)
        deleteButton.setOnClickListener {
            if (settings.activeCycleId == cycleId) {
                Toast.makeText(requireContext(), R.string.cycle_delete_active, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (settings.isCycleScheduled(cycleId)) {
                Toast.makeText(requireContext(), R.string.cycle_delete_scheduled, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.cycle_delete_title)
                .setMessage(getString(R.string.cycle_delete_message, cycle.name))
                .setPositiveButton(R.string.cycle_delete_confirm) { _, _ ->
                    settings.deleteCycle(cycleId)
                    parentFragmentManager.popBackStack()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Prefills the wizard state from [spec] and returns the screen to edit it on. */
    private fun loadSpecIntoWizard(vm: WizardViewModel, spec: SourceSpec): Fragment = when (spec) {
        is SourceSpec.People -> {
            vm.mode = WizardViewModel.Mode.PEOPLE
            vm.setSelectedPeople(spec.ids, spec.names)
            vm.requireAll = spec.requireAll
            vm.draftPersonIds.clear()
            vm.draftPersonIds.addAll(spec.ids)
            PeoplePickerFragment()
        }
        is SourceSpec.Album -> {
            vm.mode = WizardViewModel.Mode.ALBUM
            vm.albumId = spec.albumId
            vm.albumName = spec.albumName
            AlbumPickerFragment()
        }
        is SourceSpec.SmartQuery -> {
            vm.mode = WizardViewModel.Mode.SMART
            vm.query = spec.query
            vm.setSmartPeople(spec.personIds, spec.personNames)
            SmartQueryFragment()
        }
        is SourceSpec.Location -> {
            vm.mode = WizardViewModel.Mode.LOCATION
            vm.city = spec.city
            LocationPickerFragment()
        }
        is SourceSpec.Memories -> {
            vm.mode = WizardViewModel.Mode.MEMORIES
            vm.memoriesWindowDays = spec.windowDays
            vm.memoriesYearsAgoMin = spec.yearsAgoMin
            vm.memoriesYearsAgoMax = spec.yearsAgoMax
            MemoriesPickerFragment()
        }
        is SourceSpec.Custom -> {
            vm.mode = WizardViewModel.Mode.CUSTOM
            vm.loadCustom(spec)
            CustomFilterFragment()
        }
        SourceSpec.Favorites -> {
            vm.mode = WizardViewModel.Mode.FAVORITES
            SourcePreviewFragment()
        }
        SourceSpec.EverythingRandom -> {
            vm.mode = WizardViewModel.Mode.EVERYTHING
            SourcePreviewFragment()
        }
    }

    companion object {
        private const val ARG_CYCLE_ID = "cycleId"

        fun forCycle(cycleId: String): CycleDetailFragment = CycleDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_CYCLE_ID, cycleId) }
        }
    }
}
