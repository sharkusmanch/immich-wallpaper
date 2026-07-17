package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import dev.immichwall.R
import dev.immichwall.api.BaseUrlSelector
import dev.immichwall.api.ImmichApiClient
import dev.immichwall.api.PersonDto
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.PersonPickerAdapter
import dev.immichwall.ui.WizardViewModel
import dev.immichwall.ui.afterTextChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 3-column thumbnail grid of all named people (paged size=500 until
 * !hasNextPage), with search filter, multi-select, and an ANY/ALL toggle that
 * appears once two or more people are selected.
 *
 * Two modes:
 *  - normal (wizard People mode): Continue → SourcePreviewFragment
 *  - selectionOnly (SmartQuery person filter): Done → writes the selection to
 *    the wizard state and pops back to SmartQueryFragment.
 */
class PeoplePickerFragment : Fragment(R.layout.fragment_people_picker) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val selectTarget = arguments?.getString(ARG_SELECT_TARGET)
        val selectionOnly = selectTarget != null || (arguments?.getBoolean(ARG_SELECTION_ONLY) ?: false)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        val client = wizardApiClient()

        if (savedInstanceState == null) {
            // Fresh entry: seed the draft from this mode's committed selection.
            // On rotation (savedInstanceState != null) the draft carries the
            // in-progress taps so they aren't lost.
            vm.draftPersonIds.clear()
            vm.draftPersonIds.addAll(
                when {
                    selectTarget == TARGET_CUSTOM -> vm.customPersonIds
                    selectionOnly -> vm.smartPersonIds
                    else -> vm.selectedPersonIds
                }
            )
        }

        val search = view.findViewById<TextInputEditText>(R.id.people_search)
        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.people_match_group)
        val progress = view.findViewById<ProgressBar>(R.id.people_progress)
        val errorText = view.findViewById<TextView>(R.id.people_error)
        val retryButton = view.findViewById<Button>(R.id.people_retry)
        val grid = view.findViewById<RecyclerView>(R.id.people_grid)
        val continueButton = view.findViewById<Button>(R.id.people_continue)

        continueButton.setText(if (selectionOnly) R.string.people_done else R.string.people_continue)
        toggleGroup.check(if (vm.requireAll) R.id.people_match_all else R.id.people_match_any)

        fun updateControls(count: Int) {
            toggleGroup.visibility =
                if (!selectionOnly && count >= 2) View.VISIBLE else View.GONE
            continueButton.isEnabled = selectionOnly || count >= 1
        }
        updateControls(0)

        // The callback needs the adapter to read the selection back, so wire it
        // up via a captured reference (assigned right after construction).
        var adapterRef: PersonPickerAdapter? = null
        val adapter = PersonPickerAdapter(client, viewLifecycleOwner.lifecycleScope) { count ->
            // Mirror every toggle into the ViewModel so rotation keeps the draft.
            adapterRef?.let { a ->
                vm.draftPersonIds.clear()
                vm.draftPersonIds.addAll(a.selectedPeople().map { it.id })
            }
            updateControls(count)
        }
        adapterRef = adapter
        grid.layoutManager = GridLayoutManager(requireContext(), 3)
        grid.adapter = adapter

        search.afterTextChanged { adapter.filter(it) }

        fun load() {
            // Served from the ViewModel cache after the first fetch (e.g. rotation).
            vm.peopleCache?.let { people ->
                progress.visibility = View.GONE
                retryButton.visibility = View.GONE
                if (people.isEmpty()) {
                    errorText.setText(R.string.people_empty)
                    errorText.visibility = View.VISIBLE
                }
                adapter.submit(people, vm.draftPersonIds)
                return
            }
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                val result: Result<List<PersonDto>> = withContext(Dispatchers.IO) {
                    try {
                        val out = mutableListOf<PersonDto>()
                        var page = 1
                        while (true) {
                            val resp = client.getPeople(page = page, size = 500, withHidden = false)
                            out.addAll(resp.people)
                            if (!resp.hasNextPage) break
                            page++
                        }
                        Result.success(
                            out.filter { it.name.isNotBlank() && !it.isHidden }
                                .sortedBy { it.name.lowercase() }
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                progress.visibility = View.GONE
                result.fold(
                    onSuccess = { people ->
                        vm.peopleCache = people
                        if (people.isEmpty()) {
                            errorText.setText(R.string.people_empty)
                            errorText.visibility = View.VISIBLE
                        }
                        adapter.submit(people, vm.draftPersonIds)
                    },
                    onFailure = { e ->
                        errorText.text =
                            getString(R.string.people_load_error, e.message ?: e.javaClass.simpleName)
                        errorText.visibility = View.VISIBLE
                        retryButton.visibility = View.VISIBLE
                    },
                )
            }
        }
        retryButton.setOnClickListener { load() }
        load()

        continueButton.setOnClickListener {
            val people = adapter.selectedPeople()
            if (selectionOnly) {
                // Each mode's person filter is separate state — an abandoned People-mode
                // selection must never leak into a smart query or custom filter.
                if (selectTarget == TARGET_CUSTOM) {
                    vm.setCustomPeople(people.map { it.id }, people.map { it.name })
                } else {
                    vm.setSmartPeople(people.map { it.id }, people.map { it.name })
                }
                parentFragmentManager.popBackStack()
            } else {
                vm.setSelectedPeople(people.map { it.id }, people.map { it.name })
                vm.requireAll =
                    toggleGroup.checkedButtonId == R.id.people_match_all && people.size >= 2
                // Re-assert the mode (matches the other pickers): after process death the
                // restored back stack has a fresh ViewModel with mode == null, and the
                // preview would otherwise dead-end on an "incomplete" toast.
                vm.mode = WizardViewModel.Mode.PEOPLE
                (requireActivity() as MainActivity).navigateTo(SourcePreviewFragment())
            }
        }
    }

    companion object {
        private const val ARG_SELECTION_ONLY = "selectionOnly"
        private const val ARG_SELECT_TARGET = "selectTarget"
        const val TARGET_CUSTOM = "custom"

        fun newInstance(selectionOnly: Boolean): PeoplePickerFragment =
            PeoplePickerFragment().apply {
                arguments = Bundle().apply { putBoolean(ARG_SELECTION_ONLY, selectionOnly) }
            }

        /** Selection-only picker writing into the given target's person list. */
        fun forTarget(target: String): PeoplePickerFragment =
            PeoplePickerFragment().apply {
                arguments = Bundle().apply { putString(ARG_SELECT_TARGET, target) }
            }
    }
}

/**
 * API client for onboarding/status screens: base URL comes from
 * [BaseUrlSelector] (last-good, else primary), key from [SettingsRepository].
 */
internal fun Fragment.wizardApiClient(): ImmichApiClient {
    val settings = SettingsRepository.get(requireContext())
    val selector = BaseUrlSelector(settings)
    return ImmichApiClient({ selector.currentBaseUrl() }, { settings.apiKey })
}
