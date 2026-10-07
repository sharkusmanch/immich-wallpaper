package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.immichwall.R
import dev.immichwall.api.AssetDto
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.AssetSourceResolver
import dev.immichwall.source.SourceSpec
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.PreviewGridAdapter
import dev.immichwall.ui.WizardViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live preview: 12 sample candidates from [AssetSourceResolver] for the
 * SourceSpec built from the wizard state, so the user sees what the wallpaper
 * will draw from before committing. Confirm persists the spec; when the app is
 * already configured (change-source flow) it also kicks a re-fill and returns
 * straight to the status screen instead of re-running Options/Apply.
 */
class SourcePreviewFragment : Fragment(R.layout.fragment_source_preview) {

    private var previewAdapter: PreviewGridAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        val settings = SettingsRepository.get(requireContext())

        val spec: SourceSpec? = vm.buildSourceSpec()
        if (spec == null) {
            Toast.makeText(requireContext(), R.string.preview_incomplete, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            return
        }

        val summary = view.findViewById<TextView>(R.id.preview_summary)
        val progress = view.findViewById<ProgressBar>(R.id.preview_progress)
        val emptyText = view.findViewById<TextView>(R.id.preview_empty)
        val retryButton = view.findViewById<Button>(R.id.preview_retry)
        val grid = view.findViewById<RecyclerView>(R.id.preview_grid)
        val confirmButton = view.findViewById<Button>(R.id.preview_confirm)
        val saveButton = view.findViewById<Button>(R.id.preview_save)
        val peopleGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.preview_people_group)

        peopleGroup.check(
            when (vm.cyclePeoplePreference) {
                "off" -> R.id.preview_people_off
                "require" -> R.id.preview_people_require
                else -> R.id.preview_people_prefer
            }
        )
        peopleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                vm.cyclePeoplePreference = when (checkedId) {
                    R.id.preview_people_off -> "off"
                    R.id.preview_people_require -> "require"
                    else -> "prefer"
                }
            }
        }

        summary.text = spec.summaryLabel()
        if (settings.isConfigured) {
            // Steady state: building a cycle must not disturb the running one — offer
            // "Save" (keep current cycle running) alongside "Save & activate".
            saveButton.visibility = View.VISIBLE
            confirmButton.setText(R.string.preview_save_activate)
        }

        val client = wizardApiClient()
        val adapter = PreviewGridAdapter(
            client,
            viewLifecycleOwner.lifecycleScope,
            requireContext().cacheDir,
        )
        previewAdapter = adapter
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter

        fun setActionsEnabled(enabled: Boolean) {
            confirmButton.isEnabled = enabled
            saveButton.isEnabled = enabled
        }

        fun load() {
            progress.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            retryButton.visibility = View.GONE
            setActionsEnabled(false)
            viewLifecycleOwner.lifecycleScope.launch {
                val result: Result<List<AssetDto>> = withContext(Dispatchers.IO) {
                    try {
                        Result.success(AssetSourceResolver(client).candidates(spec, PREVIEW_COUNT))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = { candidates ->
                        adapter.submit(candidates)
                        if (candidates.isEmpty()) {
                            emptyText.setText(R.string.preview_empty)
                            emptyText.visibility = View.VISIBLE
                            setActionsEnabled(false)
                        } else {
                            setActionsEnabled(true)
                        }
                    },
                    onFailure = { e ->
                        emptyText.text =
                            getString(R.string.preview_error, e.message ?: e.javaClass.simpleName)
                        emptyText.visibility = View.VISIBLE
                        retryButton.visibility = View.VISIBLE
                        setActionsEnabled(false)
                    },
                )
            }
        }
        retryButton.setOnClickListener { load() }
        load()

        fun persistAsCycle(): dev.immichwall.source.SavedCycle {
            // Editing an existing cycle keeps its id (and thus its slot + active status).
            val id = vm.editingCycleId ?: java.util.UUID.randomUUID().toString()
            val cycle = dev.immichwall.source.SavedCycle(
                id, spec.summaryLabel(), spec, vm.cyclePeoplePreference)
            settings.upsertCycle(cycle)
            // An edit of the ACTIVE cycle must propagate to the running spec.
            if (settings.activeCycleId == id) settings.activateCycle(id)
            vm.editingCycleId = null
            return cycle
        }

        // Save only: the cycle lands in the list; the running cycle is untouched.
        saveButton.setOnClickListener {
            persistAsCycle()
            Toast.makeText(requireContext(), R.string.preview_cycle_saved, Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        confirmButton.setOnClickListener {
            val cycle = persistAsCycle()
            // A manual pick: with the schedule on it holds until the schedule next changes.
            dev.immichwall.schedule.ScheduleApplier.activateManually(requireContext().applicationContext, cycle.id)
            if (settings.isConfigured) {
                // Activate flow: re-fill for the new source and go home; the wallpaper
                // crossfades to the new cycle's first photo when it lands.
                SyncScheduler.kickInitialFill(requireContext().applicationContext)
                Toast.makeText(requireContext(), R.string.preview_source_updated, Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            } else {
                (requireActivity() as MainActivity).navigateTo(OptionsFragment())
            }
        }
    }

    override fun onDestroyView() {
        // Downloaded thumbs are only useful while this grid is on screen.
        previewAdapter?.clear()
        previewAdapter = null
        super.onDestroyView()
    }

    companion object {
        private const val PREVIEW_COUNT = 12
    }
}
