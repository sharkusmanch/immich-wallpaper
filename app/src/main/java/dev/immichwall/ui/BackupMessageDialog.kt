package dev.immichwall.ui

import android.app.Dialog
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A backup or restore result that is not plain success: a refused file, a restore that left
 * something out. A dialog because a toast shows two lines and then is gone.
 */
class BackupMessageDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(requireArguments().getCharSequence(ARG_MESSAGE))
            .setPositiveButton(android.R.string.ok, null)
            .create()

    companion object {
        private const val TAG = "BackupMessageDialog"
        private const val ARG_MESSAGE = "message"

        /** False when [host] cannot show a dialog right now (its state is already saved). */
        fun show(host: Fragment, @StringRes message: Int): Boolean {
            val fm = host.childFragmentManager
            if (fm.isStateSaved) return false
            // The text, not its id: ids are not stable across builds, and the arguments are saved.
            BackupMessageDialog().apply { arguments = bundleOf(ARG_MESSAGE to host.getText(message)) }.show(fm, TAG)
            return true
        }
    }
}
