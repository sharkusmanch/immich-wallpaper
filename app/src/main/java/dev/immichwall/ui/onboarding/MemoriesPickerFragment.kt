package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.immichwall.R
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Options for the Memories mode: how wide the "this day" window is (exact day / ±3 days /
 * ±1 week) and which years count (all / only last year / two-plus years ago). Also shows a
 * live line of what today's memories actually contain, so the choice isn't blind.
 */
class MemoriesPickerFragment : Fragment(R.layout.fragment_memories_picker) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]

        val windowGroup = view.findViewById<RadioGroup>(R.id.memories_window_group)
        val yearsGroup = view.findViewById<RadioGroup>(R.id.memories_years_group)
        val availability = view.findViewById<TextView>(R.id.memories_availability)
        val continueButton = view.findViewById<Button>(R.id.memories_continue)

        windowGroup.check(
            when (vm.memoriesWindowDays) {
                0 -> R.id.memories_window_day
                7 -> R.id.memories_window_week
                else -> R.id.memories_window_3
            }
        )
        yearsGroup.check(
            when {
                vm.memoriesYearsAgoMin <= 1 && vm.memoriesYearsAgoMax == 1 -> R.id.memories_years_last
                vm.memoriesYearsAgoMin >= 2 -> R.id.memories_years_older
                else -> R.id.memories_years_all
            }
        )

        continueButton.setOnClickListener {
            vm.mode = WizardViewModel.Mode.MEMORIES
            vm.memoriesWindowDays = when (windowGroup.checkedRadioButtonId) {
                R.id.memories_window_day -> 0
                R.id.memories_window_week -> 7
                else -> 3
            }
            when (yearsGroup.checkedRadioButtonId) {
                R.id.memories_years_last -> {
                    vm.memoriesYearsAgoMin = 1
                    vm.memoriesYearsAgoMax = 1
                }
                R.id.memories_years_older -> {
                    vm.memoriesYearsAgoMin = 2
                    vm.memoriesYearsAgoMax = 0
                }
                else -> {
                    vm.memoriesYearsAgoMin = 0
                    vm.memoriesYearsAgoMax = 0
                }
            }
            (requireActivity() as MainActivity).navigateTo(SourcePreviewFragment())
        }

        loadAvailability(availability)
    }

    /** "On this day: 2019 (8), 2022 (12) — 20 photos" or a friendly empty/error line. */
    private fun loadAvailability(label: TextView) {
        val client = wizardApiClient()
        label.setText(R.string.memories_availability_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val memories = client.getMemories(LocalDate.now().toString())
                    val perYear = memories
                        .filter { (it.data?.year ?: 0) > 0 }
                        .sortedBy { it.data!!.year }
                        .map { m -> m.data!!.year to m.assets.count { it.type == "IMAGE" } }
                        .filter { it.second > 0 }
                    val total = perYear.sumOf { it.second }
                    if (total == 0) {
                        getString(R.string.memories_availability_none)
                    } else {
                        val years = perYear.joinToString(", ") { (y, c) -> "$y ($c)" }
                        getString(R.string.memories_availability_line, years, total)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    getString(R.string.memories_availability_error)
                }
            }
            label.text = text
        }
    }
}
