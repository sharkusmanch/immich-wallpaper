package dev.immichwall.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.immichwall.R

/**
 * Asks, before a backup is written, whether it should hold the server address and API key
 * (off unless ticked; the warning about the unencrypted key is always on show). The choice
 * goes to the host as the fragment result [REQUEST_KEY]; cancelling does nothing.
 */
class BackupOptionsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // The checkbox keeps its own state across recreation (it has an id, and the dialog
        // saves its view state).
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_backup_options, null)
        val includeServer = content.findViewById<MaterialCheckBox>(R.id.backup_include_server)
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_dialog_title)
            .setView(content)
            .setPositiveButton(R.string.backup_dialog_confirm) { _, _ ->
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(KEY_INCLUDE_SERVER to includeServer.isChecked),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    companion object {
        const val REQUEST_KEY = "backup_options"
        const val KEY_INCLUDE_SERVER = "includeServer"
        const val TAG = "BackupOptionsDialog"
    }
}
