package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputEditText
import dev.immichwall.BuildConfig
import dev.immichwall.R
import dev.immichwall.api.CheckResult
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel
import dev.immichwall.ui.afterTextChanged
import kotlinx.coroutines.launch

/**
 * Server URL / away URL / API key entry with a "Test connection" button that
 * runs [ConnectionValidator] on IO and renders a per-check pass/fail list
 * (including the missing-API-key-scope hint on 403s). Continue unlocks only
 * after an all-green validation of the current field values.
 *
 * Test state/results live in [WizardViewModel.serverTest] so green checks (and
 * an in-flight test) survive rotation; real edits to any field reset to Idle.
 */
class ServerSetupFragment : Fragment(R.layout.fragment_server_setup) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository.get(requireContext())
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]

        val urlField = view.findViewById<TextInputEditText>(R.id.server_url)
        val awayField = view.findViewById<TextInputEditText>(R.id.server_away)
        val keyField = view.findViewById<TextInputEditText>(R.id.server_key)
        val testButton = view.findViewById<Button>(R.id.server_test)
        val continueButton = view.findViewById<Button>(R.id.server_continue)
        val progress = view.findViewById<ProgressBar>(R.id.server_progress)
        val checksContainer = view.findViewById<LinearLayout>(R.id.server_checks)

        if (savedInstanceState == null) {
            // Prefill: persisted settings → wizard state → debug BuildConfig values.
            urlField.setText(
                settings.serverUrl.ifBlank { vm.serverUrl }.ifBlank { BuildConfig.DEV_SERVER_URL }
            )
            awayField.setText(settings.awayUrl.ifBlank { vm.awayUrl })
            keyField.setText(
                settings.apiKey.ifBlank { vm.apiKey }.ifBlank { BuildConfig.DEV_API_KEY }
            )
        }

        // Reset to Idle only when the (normalized) values actually differ from what
        // was tested: Android's view-state restoration re-sets identical text after
        // rotation, and that must not wipe a green result.
        val invalidate: (String) -> Unit = {
            val url = normalizeUrl(urlField.text?.toString().orEmpty())
            val away = normalizeUrl(awayField.text?.toString().orEmpty())
            val key = keyField.text?.toString()?.trim().orEmpty()
            if (url != vm.testedUrl || away != vm.testedAwayUrl || key != vm.testedKey) {
                if (vm.serverTest.value != WizardViewModel.ServerTestState.Idle) {
                    vm.serverTest.value = WizardViewModel.ServerTestState.Idle
                }
            }
        }
        urlField.afterTextChanged(invalidate)
        awayField.afterTextChanged(invalidate)
        keyField.afterTextChanged(invalidate)
        // The prefill above ran before the watchers were attached, so a fresh view
        // must run one invalidate pass itself: otherwise a green result surviving in
        // the ViewModel (e.g. after back-and-forth) would authorize prefilled values
        // that were never tested. Not on rotation — the fields are still empty here
        // (view-state restoration happens later) and would wipe a legitimate result.
        if (savedInstanceState == null) invalidate("")

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.serverTest.collect { state ->
                    when (state) {
                        WizardViewModel.ServerTestState.Idle -> {
                            progress.visibility = View.GONE
                            testButton.isEnabled = true
                            checksContainer.removeAllViews()
                            continueButton.isEnabled = false
                        }
                        WizardViewModel.ServerTestState.Running -> {
                            progress.visibility = View.VISIBLE
                            testButton.isEnabled = false
                            checksContainer.removeAllViews()
                            continueButton.isEnabled = false
                        }
                        is WizardViewModel.ServerTestState.Done -> {
                            progress.visibility = View.GONE
                            testButton.isEnabled = true
                            renderResults(checksContainer, state.results)
                            continueButton.isEnabled =
                                state.results.isNotEmpty() && state.results.all { it.ok }
                        }
                    }
                }
            }
        }

        testButton.setOnClickListener {
            val url = normalizeUrl(urlField.text?.toString().orEmpty())
            val away = normalizeUrl(awayField.text?.toString().orEmpty())
            val key = keyField.text?.toString()?.trim().orEmpty()
            if (url.isBlank() || key.isBlank()) {
                Toast.makeText(requireContext(), R.string.server_missing_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.testedUrl = url
            vm.testedAwayUrl = away
            vm.testedKey = key
            vm.runServerTest(url, key, getString(R.string.server_check_connection))
        }

        continueButton.setOnClickListener {
            val url = normalizeUrl(urlField.text?.toString().orEmpty())
            val away = normalizeUrl(awayField.text?.toString().orEmpty())
            val key = keyField.text?.toString()?.trim().orEmpty()
            // Defense in depth: besides an all-green result, the current field values
            // must be exactly what that result was produced for.
            val state = vm.serverTest.value
            val validated = state is WizardViewModel.ServerTestState.Done &&
                state.results.isNotEmpty() && state.results.all { it.ok } &&
                url == vm.testedUrl && away == vm.testedAwayUrl && key == vm.testedKey
            if (!validated) return@setOnClickListener

            // Server-derived wizard state (people list, person/album IDs) belongs to
            // the previously confirmed server; a config change invalidates all of it.
            if (url != vm.serverUrl || key != vm.apiKey) {
                vm.peopleCache = null
                vm.selectedPersonIds.clear()
                vm.selectedPersonNames.clear()
                vm.draftPersonIds.clear()
                vm.smartPersonIds.clear()
                vm.smartPersonNames.clear()
                vm.albumId = ""
                vm.albumName = ""
            }

            settings.serverUrl = url
            settings.awayUrl = away
            settings.apiKey = key
            settings.lastGoodBaseUrl = url

            vm.serverUrl = url
            vm.awayUrl = away
            vm.apiKey = key

            (requireActivity() as MainActivity).navigateTo(SourcePickerFragment())
        }
    }

    private fun renderResults(container: LinearLayout, results: List<CheckResult>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (result in results) {
            val row = inflater.inflate(R.layout.item_check_result, container, false)
            val glyph = row.findViewById<TextView>(R.id.check_glyph)
            val name = row.findViewById<TextView>(R.id.check_name)
            val detail = row.findViewById<TextView>(R.id.check_detail)
            glyph.text = getString(if (result.ok) R.string.check_ok_glyph else R.string.check_fail_glyph)
            glyph.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (result.ok) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
                )
            )
            name.text = result.name
            if (result.detail.isBlank()) {
                detail.visibility = View.GONE
            } else {
                detail.visibility = View.VISIBLE
                detail.text = result.detail
            }
            container.addView(row)
        }
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url.trimEnd('/')
    }
}
