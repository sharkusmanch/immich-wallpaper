package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import dev.immichwall.R
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.SettingsRestoreFlow
import dev.immichwall.ui.WizardViewModel

/**
 * Mode list: People / Album / Smart search / Location / Favorites / Memories /
 * Everything. Picker-less modes go straight to the live preview.
 *
 * In first-run setup the screen also offers "Restore from a backup": the backup's cycles,
 * schedule and options take the place of building a first cycle, and setup continues at
 * the options step. Opened from "+ New cycle" on a configured app it is the list alone.
 */
class SourcePickerFragment : Fragment(R.layout.fragment_source_picker) {

    /**
     * Offered in first-run setup only. Never applies a backup's server address and key: the
     * ones in use were entered on the step before this one.
     */
    private val restore = SettingsRestoreFlow(
        this,
        offerServer = false,
        onBusy = { busy ->
            // Choosing a source while a backup is being applied would add a cycle to a
            // list that is being replaced.
            view?.let { v ->
                v.findViewById<Button>(R.id.source_restore).isEnabled = !busy
                v.findViewById<LinearLayout>(R.id.source_modes_container).children.forEach { it.isEnabled = !busy }
            }
        },
        // The options step shows the stored options and "All set" the stored source, so
        // both show what was restored; nothing the wizard holds in memory is written later.
        onRestored = { (requireActivity() as MainActivity).navigateTo(OptionsFragment()) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[WizardViewModel::class.java]
        val nav = requireActivity() as MainActivity
        val container = view.findViewById<LinearLayout>(R.id.source_modes_container)
        val inflater = LayoutInflater.from(requireContext())

        fun addMode(@StringRes title: Int, @StringRes subtitle: Int, onClick: () -> Unit) {
            val item = inflater.inflate(R.layout.item_two_line, container, false)
            item.findViewById<TextView>(R.id.item_title).setText(title)
            item.findViewById<TextView>(R.id.item_subtitle).setText(subtitle)
            item.setOnClickListener { onClick() }
            container.addView(item)
        }

        addMode(R.string.source_people, R.string.source_people_desc) {
            vm.mode = WizardViewModel.Mode.PEOPLE
            nav.navigateTo(PeoplePickerFragment.newInstance(selectionOnly = false))
        }
        addMode(R.string.source_album, R.string.source_album_desc) {
            vm.mode = WizardViewModel.Mode.ALBUM
            nav.navigateTo(AlbumPickerFragment())
        }
        addMode(R.string.source_smart, R.string.source_smart_desc) {
            vm.mode = WizardViewModel.Mode.SMART
            nav.navigateTo(SmartQueryFragment())
        }
        addMode(R.string.source_location, R.string.source_location_desc) {
            vm.mode = WizardViewModel.Mode.LOCATION
            nav.navigateTo(LocationPickerFragment())
        }
        addMode(R.string.source_favorites, R.string.source_favorites_desc) {
            vm.mode = WizardViewModel.Mode.FAVORITES
            nav.navigateTo(SourcePreviewFragment())
        }
        addMode(R.string.source_memories, R.string.source_memories_desc) {
            vm.mode = WizardViewModel.Mode.MEMORIES
            nav.navigateTo(MemoriesPickerFragment())
        }
        addMode(R.string.source_everything, R.string.source_everything_desc) {
            vm.mode = WizardViewModel.Mode.EVERYTHING
            nav.navigateTo(SourcePreviewFragment())
        }
        addMode(R.string.source_custom, R.string.source_custom_desc) {
            vm.mode = WizardViewModel.Mode.CUSTOM
            nav.navigateTo(CustomFilterFragment())
        }

        // The same test MainActivity uses to choose between the wizard and the status screen.
        if (!SettingsRepository.get(requireContext()).isConfigured) {
            view.findViewById<View>(R.id.source_restore_section).visibility = View.VISIBLE
            view.findViewById<Button>(R.id.source_restore).setOnClickListener { restore.start() }
        }
    }
}
