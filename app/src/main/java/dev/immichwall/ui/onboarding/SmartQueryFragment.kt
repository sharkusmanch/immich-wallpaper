package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.textfield.TextInputEditText
import dev.immichwall.R
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel
import dev.immichwall.ui.afterTextChanged

/**
 * CLIP smart-search source: free-text query plus an optional person filter
 * (reuses PeoplePickerFragment in selection-only mode; the selection lands in
 * the shared wizard state and the summary refreshes in onResume).
 */
class SmartQueryFragment : Fragment(R.layout.fragment_smart_query) {

    private var peopleSummary: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        val nav = requireActivity() as MainActivity

        val queryField = view.findViewById<TextInputEditText>(R.id.smart_query)
        val selectPeopleButton = view.findViewById<Button>(R.id.smart_select_people)
        val continueButton = view.findViewById<Button>(R.id.smart_continue)
        peopleSummary = view.findViewById(R.id.smart_people_summary)

        if (savedInstanceState == null) {
            queryField.setText(vm.query)
        }
        continueButton.isEnabled = vm.query.isNotBlank()

        queryField.afterTextChanged { text ->
            vm.query = text.trim()
            continueButton.isEnabled = vm.query.isNotBlank()
        }

        selectPeopleButton.setOnClickListener {
            nav.navigateTo(PeoplePickerFragment.newInstance(selectionOnly = true))
        }

        continueButton.setOnClickListener {
            if (vm.query.isBlank()) return@setOnClickListener
            vm.mode = WizardViewModel.Mode.SMART
            nav.navigateTo(SourcePreviewFragment())
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh after returning from the selection-only people picker.
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        peopleSummary?.text =
            if (vm.smartPersonNames.isEmpty()) getString(R.string.smart_people_none)
            else getString(R.string.smart_people_selected, vm.smartPersonNames.joinToString(", "))
    }

    override fun onDestroyView() {
        peopleSummary = null
        super.onDestroyView()
    }
}
