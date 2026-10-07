package dev.immichwall.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.immichwall.R
import dev.immichwall.backup.BackupPrompts
import dev.immichwall.backup.RestorePrompt
import dev.immichwall.backup.ServerPart

/**
 * Says what restoring the picked backup will replace, before anything changes. When the
 * backup holds a server address and key and the screen offers them as a choice, replacing
 * the current ones is a checkbox, off unless ticked; where the screen uses them (first-run
 * setup's server screen) the text says so instead, or says they are still to be entered.
 * Whenever confirming can store them, the backup's addresses are shown first: the API key
 * is sent to both, and a backup file can come from anywhere.
 *
 * Shown by [SettingsRestoreFlow] in the host's child fragment manager. The backup itself
 * waits in the host's [SettingsRestoreViewModel]; "Restore" is the only thing that applies
 * it, and any other way out drops it. If the process was killed in the meantime the backup
 * is gone, and the dialog goes with it.
 */
class RestoreConfirmDialog : DialogFragment() {

    private val model: SettingsRestoreViewModel
        get() = ViewModelProvider(requireParentFragment())[SettingsRestoreViewModel::class.java]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!model.hasPending) dismissAllowingStateLoss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val res = resources
        val cycles = args.getInt(ARG_CYCLES)
        val entries = args.getInt(ARG_ENTRIES)
        val server = ServerPart.valueOf(args.getString(ARG_SERVER)!!)

        // From the waiting backup, not the arguments: addresses do not go into saved state.
        val addresses = model.pendingServerAddresses

        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_restore_confirm, null)
        content.findViewById<TextView>(R.id.restore_confirm_message).text = listOf(
            getString(
                R.string.restore_confirm_replaces,
                res.getQuantityString(R.plurals.restore_confirm_cycles, cycles, cycles),
                res.getQuantityString(R.plurals.restore_confirm_entries, entries, entries),
            ),
            getString(
                if (args.getBoolean(ARG_OPTIONS)) R.string.restore_confirm_options_replaced
                else R.string.restore_confirm_options_kept
            ),
            getString(
                when (server) {
                    ServerPart.NOT_IN_FILE -> R.string.restore_confirm_server_none
                    ServerPart.IGNORED -> R.string.restore_confirm_server_ignored
                    ServerPart.OPTIONAL ->
                        if (addresses != null) R.string.restore_confirm_server_optional_shown
                        else R.string.restore_confirm_server_optional
                    ServerPart.USED -> R.string.restore_confirm_server_used
                    ServerPart.TO_ENTER -> R.string.restore_confirm_server_to_enter
                    ServerPart.INVALID_TO_ENTER -> R.string.restore_confirm_server_invalid_to_enter
                }
            ),
        ).joinToString("\n\n")
        if (addresses != null) {
            content.findViewById<TextView>(R.id.restore_confirm_server_addresses).apply {
                text = listOfNotNull(
                    getString(R.string.restore_confirm_server_address, addresses.primary),
                    addresses.away.takeIf { it.isNotEmpty() }?.let { getString(R.string.restore_confirm_away_address, it) },
                ).joinToString("\n")
                visibility = View.VISIBLE
            }
        }
        // Keeps its own state across recreation (it has an id, and the dialog saves its views).
        val replaceServer = content.findViewById<MaterialCheckBox>(R.id.restore_confirm_replace_server)
        if (server == ServerPart.OPTIONAL) replaceServer.visibility = View.VISIBLE

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setView(content)
            .setPositiveButton(R.string.restore_confirm_confirm) { _, _ ->
                model.confirm(replaceServer = BackupPrompts.appliesServer(server, replaceServer.isChecked))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.dropPending() }
            .create()
    }

    /** Back, or a tap outside. Not onDismiss: that also runs when a recreation tears the dialog down. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        model.dropPending()
    }

    companion object {
        const val TAG = "RestoreConfirmDialog"
        private const val ARG_CYCLES = "cycles"
        private const val ARG_ENTRIES = "entries"
        private const val ARG_OPTIONS = "options"
        private const val ARG_SERVER = "server"

        fun forPrompt(prompt: RestorePrompt.Confirm): RestoreConfirmDialog = RestoreConfirmDialog().apply {
            arguments = bundleOf(
                ARG_CYCLES to prompt.cycleCount,
                ARG_ENTRIES to prompt.scheduleEntryCount,
                ARG_OPTIONS to prompt.replacesOptions,
                ARG_SERVER to prompt.server.name,
            )
        }
    }
}
