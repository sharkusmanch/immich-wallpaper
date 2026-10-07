package dev.immichwall.ui.onboarding

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import dev.immichwall.R
import dev.immichwall.settings.OptionChoices
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.ui.MainActivity
import kotlin.math.roundToInt

/**
 * Cache size (50–300 photos with a rough MB estimate), refresh interval
 * (3/6/12/24 h) and stable-vs-photo-derived theme colors.
 */
class OptionsFragment : Fragment(R.layout.fragment_options) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository.get(requireContext())

        val cacheValue = view.findViewById<TextView>(R.id.options_cache_value)
        val cacheSlider = view.findViewById<Slider>(R.id.options_cache_slider)
        val rotationGroup = view.findViewById<RadioGroup>(R.id.options_rotation_group)
        val intervalGroup = view.findViewById<RadioGroup>(R.id.options_interval_group)
        val themeSwitch = view.findViewById<SwitchMaterial>(R.id.options_theme_switch)
        val qualitySwitch = view.findViewById<SwitchMaterial>(R.id.options_quality_switch)
        val cellularSwitch = view.findViewById<SwitchMaterial>(R.id.options_cellular_switch)
        val continueButton = view.findViewById<Button>(R.id.options_continue)

        fun updateCacheLabel(count: Int) {
            val mb = (count * MB_PER_PHOTO).roundToInt()
            cacheValue.text = getString(R.string.options_cache_label, count, mb)
        }

        // Snap to the slider's step grid so setValue never rejects it. The slider's
        // from/to/step in the layout mirror OptionChoices.CACHE_COUNT_*.
        val initial = settings.targetCacheCount
            .coerceIn(OptionChoices.CACHE_COUNT_MIN, OptionChoices.CACHE_COUNT_MAX)
            .let { it / OptionChoices.CACHE_COUNT_STEP * OptionChoices.CACHE_COUNT_STEP }
        cacheSlider.value = initial.toFloat()
        updateCacheLabel(initial)
        cacheSlider.addOnChangeListener { _, value, _ -> updateCacheLabel(value.toInt()) }

        // The radio ids below must cover OptionChoices.ROTATION_MINUTES and REFRESH_HOURS
        // (restoring a backup coerces to those lists).
        rotationGroup.check(
            when (settings.rotationMinIntervalMinutes) {
                5 -> R.id.options_rotation_5m
                60 -> R.id.options_rotation_1h
                360 -> R.id.options_rotation_6h
                1440 -> R.id.options_rotation_1d
                else -> R.id.options_rotation_wake
            }
        )

        intervalGroup.check(
            when (settings.refreshIntervalHours) {
                3 -> R.id.options_interval_3
                12 -> R.id.options_interval_12
                24 -> R.id.options_interval_24
                else -> R.id.options_interval_6
            }
        )

        themeSwitch.isChecked = settings.deriveThemeFromPhoto
        qualitySwitch.isChecked = settings.qualityFilterEnabled
        cellularSwitch.isChecked = settings.syncOverCellular

        val standalone = arguments?.getBoolean(ARG_STANDALONE) == true
        if (standalone) continueButton.setText(R.string.options_save)

        continueButton.setOnClickListener {
            val intervalBefore = settings.refreshIntervalHours
            val cellularBefore = settings.syncOverCellular
            val qualityBefore = settings.qualityFilterEnabled
            settings.syncOverCellular = cellularSwitch.isChecked
            settings.rotationMinIntervalMinutes = when (rotationGroup.checkedRadioButtonId) {
                R.id.options_rotation_5m -> 5
                R.id.options_rotation_1h -> 60
                R.id.options_rotation_6h -> 360
                R.id.options_rotation_1d -> 1440
                else -> 0
            }
            settings.targetCacheCount = cacheSlider.value.toInt()
            settings.refreshIntervalHours = when (intervalGroup.checkedRadioButtonId) {
                R.id.options_interval_3 -> 3
                R.id.options_interval_12 -> 12
                R.id.options_interval_24 -> 24
                else -> 6
            }
            settings.deriveThemeFromPhoto = themeSwitch.isChecked
            settings.qualityFilterEnabled = qualitySwitch.isChecked
            if (standalone) {
                // Settings visit from the status screen: apply interval/network changes
                // in place (UPDATE keeps the period phase) and go back.
                if (settings.refreshIntervalHours != intervalBefore ||
                    settings.syncOverCellular != cellularBefore
                ) {
                    SyncScheduler.ensurePeriodic(requireContext().applicationContext, forceReplace = true)
                }
                // The quality switch is part of every cycle's cache key, so the active
                // cycle has nothing cached under its new key until a sync lands a photo:
                // start one now instead of waiting for the next periodic run.
                if (settings.isConfigured && settings.qualityFilterEnabled != qualityBefore) {
                    SyncScheduler.kickManualRefresh(requireContext().applicationContext)
                }
                parentFragmentManager.popBackStack()
            } else {
                (requireActivity() as MainActivity).navigateTo(ApplyFragment())
            }
        }
    }

    companion object {
        /** DESIGN.md: finished wallpapers run ~0.8–1.5 MB → ~1.2 MB average. */
        private const val MB_PER_PHOTO = 1.2f
        private const val ARG_STANDALONE = "standalone"

        /** Settings-page mode: saves and pops back instead of continuing to Apply. */
        fun standalone(): OptionsFragment = OptionsFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_STANDALONE, true) }
        }
    }
}
