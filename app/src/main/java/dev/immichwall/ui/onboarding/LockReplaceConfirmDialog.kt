package dev.immichwall.ui.onboarding

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.immichwall.R

/**
 * Confirmation for clearing a lock-specific wallpaper before opening the live
 * wallpaper picker (see [LiveWallpaperLauncher.launch]). A DialogFragment rather
 * than a raw dialog so a configuration change mid-confirmation re-shows it
 * instead of leaking the Activity window and silently dropping the choice.
 *
 * The positive result is published as a fragment result under [REQUEST_KEY];
 * MainActivity registers the listener in onCreate so it is re-armed after
 * recreation and routes it to [LiveWallpaperLauncher.confirmAndOpen]. Declining
 * (or dismissing) simply does nothing — nothing is cleared, no picker opens.
 */
class LockReplaceConfirmDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apply_lock_replace_title)
            .setMessage(R.string.apply_lock_replace_message)
            .setPositiveButton(R.string.apply_lock_replace_confirm) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(KEY_CONFIRMED to true),
                )
            }
            .setNegativeButton(R.string.apply_lock_replace_cancel, null)
            .create()

    companion object {
        const val REQUEST_KEY = "lock_replace"
        const val KEY_CONFIRMED = "confirmed"
        const val TAG = "LockReplaceConfirmDialog"
    }
}
