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
import dev.immichwall.api.ServerUrl
import dev.immichwall.backup.BackupRestore
import dev.immichwall.backup.ServerUse
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.SettingsRestoreFlow
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
 *
 * In first-run setup the screen also offers "Restore from a backup". A backup that holds a
 * usable server address and API key fills the fields with them and is tested like typed
 * ones; when every check passes, setup goes on by itself. Once cycles are stored (restored
 * here or on the next step), Continue goes to the options step: there is no photo source
 * left to choose.
 */
class ServerSetupFragment : Fragment(R.layout.fragment_server_setup) {

    private val vm: WizardViewModel
        get() = ViewModelProvider(requireActivity())[WizardViewModel::class.java]

    /** True from the view's creation until its saved state is back in the fields. */
    private var settingUpFields = false

    /** Offered in first-run setup only, in place of typing the address and key. */
    private val restore = SettingsRestoreFlow(
        this,
        serverUse = ServerUse.USED,
        // Nothing here may store or test the fields while a backup is being applied.
        onBusy = { view?.let(::enableButtons) },
        // Without a server block applied the address and key are still to be entered: the
        // fields stay as they are, and what was restored is picked up by Continue.
        onRestored = { serverApplied -> if (serverApplied) view?.let(::testRestoredServer) },
        // The key or an address may have been stored before it failed: show what is there
        // now, untested, rather than fields that Continue would store over it unseen.
        onUnfinished = {
            val settings = SettingsRepository.get(requireContext())
            if (settings.serverUrl.isNotBlank() || settings.apiKey.isNotBlank()) view?.let(::showStoredServer)
        },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository.get(requireContext())
        val vm = vm

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

        // Real edits only. Until onViewStateRestored the text changes are Android putting
        // saved text back, one field at a time: judging the first against a test of all
        // three would wipe a green result on every rotation.
        settingUpFields = true
        val edited: (String) -> Unit = { if (!settingUpFields) resetTestIfEdited(view) }
        urlField.afterTextChanged(edited)
        awayField.afterTextChanged(edited)
        keyField.afterTextChanged(edited)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.serverTest.collect { state ->
                    when (state) {
                        WizardViewModel.ServerTestState.Idle -> {
                            progress.visibility = View.GONE
                            checksContainer.removeAllViews()
                        }
                        WizardViewModel.ServerTestState.Running -> {
                            progress.visibility = View.VISIBLE
                            checksContainer.removeAllViews()
                        }
                        is WizardViewModel.ServerTestState.Done -> {
                            progress.visibility = View.GONE
                            renderResults(checksContainer, state.results)
                        }
                    }
                    enableButtons(view)
                    if (state is WizardViewModel.ServerTestState.Done) {
                        // Once per restore: the flag lives with the test, so a
                        // recreation neither loses it mid-test nor finds it again after.
                        if (vm.continueWhenTestPasses) {
                            vm.continueWhenTestPasses = false
                            if (state.passed) continueSetup(view)
                        }
                    }
                }
            }
        }

        testButton.setOnClickListener { startTest(view) }
        continueButton.setOnClickListener { continueSetup(view) }

        // The same test MainActivity uses to choose between the wizard and the status screen.
        if (!settings.isConfigured) {
            view.findViewById<View>(R.id.server_restore_section).visibility = View.VISIBLE
            view.findViewById<Button>(R.id.server_restore).setOnClickListener {
                // Going on by itself is over once the user starts something else here.
                vm.continueWhenTestPasses = false
                restore.start()
            }
        }
    }

    override fun onDestroyView() {
        // Leaving the screen (not a recreation) while a restored server is being tested:
        // coming back must not jump ahead on a result the user did not wait for.
        if (!requireActivity().isChangingConfigurations) vm.continueWhenTestPasses = false
        super.onDestroyView()
    }

    private val WizardViewModel.ServerTestState.passed: Boolean
        get() = this is WizardViewModel.ServerTestState.Done && results.isNotEmpty() && results.all { it.ok }

    /** Test unless one is running, Continue only on an all-green result, and none of the three while a backup is being applied. */
    private fun enableButtons(view: View) {
        val state = vm.serverTest.value
        val idle = !restore.isApplying
        view.findViewById<Button>(R.id.server_test).isEnabled = idle && state != WizardViewModel.ServerTestState.Running
        view.findViewById<Button>(R.id.server_continue).isEnabled = idle && state.passed
        view.findViewById<Button>(R.id.server_restore).isEnabled = idle
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        settingUpFields = false
        // One pass over the fields as they now are, prefilled or restored: a green result
        // surviving in the ViewModel (e.g. after back-and-forth) must not authorize values
        // that were never tested.
        view?.let(::resetTestIfEdited)
    }

    /**
     * Back to Idle only when the (normalized) values actually differ from what was tested:
     * setting identical text must not wipe a green result.
     */
    private fun resetTestIfEdited(view: View) {
        val url = normalizeUrl(fieldText(view, R.id.server_url))
        val away = normalizeUrl(fieldText(view, R.id.server_away))
        val key = fieldText(view, R.id.server_key).trim()
        if (url != vm.testedUrl || away != vm.testedAwayUrl || key != vm.testedKey) {
            vm.continueWhenTestPasses = false
            if (vm.serverTest.value != WizardViewModel.ServerTestState.Idle) {
                vm.serverTest.value = WizardViewModel.ServerTestState.Idle
            }
        }
    }

    private fun fieldText(view: View, id: Int): String =
        view.findViewById<TextInputEditText>(id).text?.toString().orEmpty()

    /** "Test connection". False when the fields cannot be tested as they are. */
    private fun startTest(view: View): Boolean {
        if (restore.isApplying) return false
        val url = normalizeUrl(fieldText(view, R.id.server_url))
        val away = normalizeUrl(fieldText(view, R.id.server_away))
        val key = fieldText(view, R.id.server_key).trim()
        if (ServerUrl.normalize(fieldText(view, R.id.server_url)) == null ||
            ServerUrl.normalize(fieldText(view, R.id.server_away)) == null
        ) {
            Toast.makeText(requireContext(), R.string.server_https_only, Toast.LENGTH_LONG).show()
            return false
        }
        if (url.isBlank() || key.isBlank()) {
            Toast.makeText(requireContext(), R.string.server_missing_fields, Toast.LENGTH_SHORT).show()
            return false
        }
        vm.testedUrl = url
        vm.testedAwayUrl = away
        vm.testedKey = key
        vm.runServerTest(url, key, getString(R.string.server_check_connection))
        return true
    }

    /**
     * A restore stored the backup's server address and key: shows them, read back from the
     * settings as when the screen is opened, and tests them as "Test connection" does.
     */
    private fun testRestoredServer(view: View) {
        showStoredServer(view)
        vm.continueWhenTestPasses = startTest(view)
    }

    /** Puts the stored address, away address and key in the fields; the edit resets any test result. */
    private fun showStoredServer(view: View) {
        val settings = SettingsRepository.get(requireContext())
        view.findViewById<TextInputEditText>(R.id.server_url).setText(settings.serverUrl)
        view.findViewById<TextInputEditText>(R.id.server_away).setText(settings.awayUrl)
        view.findViewById<TextInputEditText>(R.id.server_key).setText(settings.apiKey)
    }

    /** "Continue": stores the tested fields and goes to the next step. */
    private fun continueSetup(view: View) {
        if (restore.isApplying) return
        val settings = SettingsRepository.get(requireContext())
        val vm = vm
        val url = normalizeUrl(fieldText(view, R.id.server_url))
        val away = normalizeUrl(fieldText(view, R.id.server_away))
        val key = fieldText(view, R.id.server_key).trim()
        // Defense in depth: besides an all-green result, the current field values
        // must be exactly what that result was produced for.
        val state = vm.serverTest.value
        val validated = state is WizardViewModel.ServerTestState.Done &&
            state.results.isNotEmpty() && state.results.all { it.ok } &&
            url == vm.testedUrl && away == vm.testedAwayUrl && key == vm.testedKey
        if (!validated) return

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

        // From what is stored, not from what happened on this visit: the cycles may have
        // been restored before the app was closed, or on the next step before coming back.
        val skipSource = BackupRestore.setupSkipsSourceStep(settings.isConfigured, settings.savedCycles.size)
        (requireActivity() as MainActivity).navigateTo(if (skipSource) OptionsFragment() else SourcePickerFragment())
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

    /** "" for blank or rejected input; see [ServerUrl.normalize] for the rules. */
    private fun normalizeUrl(raw: String): String = ServerUrl.normalize(raw) ?: ""
}
