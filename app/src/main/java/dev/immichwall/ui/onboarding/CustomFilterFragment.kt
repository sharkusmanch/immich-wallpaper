package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import dev.immichwall.R
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * The general form every other mode is a special case of: combine a CLIP text query,
 * people (any/all), an album, a city, favorites-only and a taken-date range into one
 * filter. Empty fields are unused; Continue requires at least one constraint.
 */
class CustomFilterFragment : Fragment(R.layout.fragment_custom_filter) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]

        val query = view.findViewById<TextInputEditText>(R.id.custom_query)
        val peopleButton = view.findViewById<Button>(R.id.custom_people)
        val peopleSummary = view.findViewById<TextView>(R.id.custom_people_summary)
        val requireAllSwitch = view.findViewById<SwitchMaterial>(R.id.custom_require_all)
        val albumButton = view.findViewById<Button>(R.id.custom_album)
        val cityButton = view.findViewById<Button>(R.id.custom_city)
        val favoritesSwitch = view.findViewById<SwitchMaterial>(R.id.custom_favorites)
        val afterButton = view.findViewById<Button>(R.id.custom_after)
        val beforeButton = view.findViewById<Button>(R.id.custom_before)
        val continueButton = view.findViewById<Button>(R.id.custom_continue)

        query.setText(vm.customQuery)
        query.doAfterTextChanged { vm.customQuery = it?.toString() ?: "" }
        favoritesSwitch.isChecked = vm.customFavoritesOnly
        favoritesSwitch.setOnCheckedChangeListener { _, checked -> vm.customFavoritesOnly = checked }
        requireAllSwitch.isChecked = vm.customRequireAll
        requireAllSwitch.setOnCheckedChangeListener { _, checked -> vm.customRequireAll = checked }

        fun renderState() {
            peopleSummary.text = when {
                vm.customPersonNames.isEmpty() -> getString(R.string.custom_people_none)
                else -> vm.customPersonNames.joinToString(", ")
            }
            requireAllSwitch.visibility = if (vm.customPersonIds.size >= 2) View.VISIBLE else View.GONE
            albumButton.text =
                if (vm.customAlbumName.isBlank()) getString(R.string.custom_album_button)
                else getString(R.string.custom_album_set, vm.customAlbumName)
            cityButton.text =
                if (vm.customCity.isBlank()) getString(R.string.custom_city_button)
                else getString(R.string.custom_city_set, vm.customCity)
            afterButton.text =
                if (vm.customTakenAfter.isBlank()) getString(R.string.custom_after_button)
                else getString(R.string.custom_after_set, vm.customTakenAfter)
            beforeButton.text =
                if (vm.customTakenBefore.isBlank()) getString(R.string.custom_before_button)
                else getString(R.string.custom_before_set, vm.customTakenBefore)
        }
        renderState()

        peopleButton.setOnClickListener {
            (requireActivity() as MainActivity)
                .navigateTo(PeoplePickerFragment.forTarget(PeoplePickerFragment.TARGET_CUSTOM))
        }

        albumButton.setOnClickListener {
            pickFromList(
                titleRes = R.string.custom_album_button,
                loader = { wizardApiClient().getAlbums().sortedBy { it.albumName.lowercase() } },
                labeler = { "${it.albumName} (${it.assetCount})" },
                onCleared = { vm.customAlbumId = ""; vm.customAlbumName = ""; renderState() },
            ) { album ->
                vm.customAlbumId = album.id
                vm.customAlbumName = album.albumName
                renderState()
            }
        }

        cityButton.setOnClickListener {
            pickFromList(
                titleRes = R.string.custom_city_button,
                loader = {
                    wizardApiClient().getCities()
                        .mapNotNull { it.exifInfo?.city }
                        .distinct()
                        .sorted()
                },
                labeler = { it },
                onCleared = { vm.customCity = ""; renderState() },
            ) { city ->
                vm.customCity = city
                renderState()
            }
        }

        afterButton.setOnClickListener {
            pickDate(vm.customTakenAfter) { vm.customTakenAfter = it; renderState() }
        }
        beforeButton.setOnClickListener {
            pickDate(vm.customTakenBefore) { vm.customTakenBefore = it; renderState() }
        }

        continueButton.setOnClickListener {
            vm.mode = WizardViewModel.Mode.CUSTOM
            if (vm.buildSourceSpec() == null) {
                Toast.makeText(requireContext(), R.string.custom_needs_filter, Toast.LENGTH_SHORT).show()
            } else {
                (requireActivity() as MainActivity).navigateTo(SourcePreviewFragment())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the people picker: refresh the summary line.
        view?.let { v ->
            val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
            v.findViewById<TextView>(R.id.custom_people_summary).text = when {
                vm.customPersonNames.isEmpty() -> getString(R.string.custom_people_none)
                else -> vm.customPersonNames.joinToString(", ")
            }
            v.findViewById<SwitchMaterial>(R.id.custom_require_all).visibility =
                if (vm.customPersonIds.size >= 2) View.VISIBLE else View.GONE
        }
    }

    /** Loads a list off-main, then shows a single-choice dialog with a Clear option. */
    private fun <T> pickFromList(
        titleRes: Int,
        loader: () -> List<T>,
        labeler: (T) -> String,
        onCleared: () -> Unit,
        onPicked: (T) -> Unit,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                try {
                    loader()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    null
                }
            }
            if (items == null) {
                Toast.makeText(requireContext(), R.string.custom_list_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = items.map(labeler).toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setItems(labels) { _, which -> onPicked(items[which]) }
                .setNeutralButton(R.string.custom_clear) { _, _ -> onCleared() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pickDate(current: String, onPicked: (String) -> Unit) {
        val picker = MaterialDatePicker.Builder.datePicker().build()
        picker.addOnPositiveButtonClickListener { millis ->
            val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
            onPicked(date)
        }
        picker.addOnNegativeButtonClickListener { if (current.isNotBlank()) onPicked("") }
        picker.show(parentFragmentManager, "customDate")
    }
}
