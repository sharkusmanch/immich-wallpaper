package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import dev.immichwall.R
import dev.immichwall.ui.MainActivity
import dev.immichwall.ui.WizardViewModel

/**
 * Mode list: People / Album / Smart search / Location / Favorites / Memories /
 * Everything. Picker-less modes go straight to the live preview.
 */
class SourcePickerFragment : Fragment(R.layout.fragment_source_picker) {

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
    }
}
