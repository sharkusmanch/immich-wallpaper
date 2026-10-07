package dev.immichwall.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.immichwall.R
import dev.immichwall.backup.BackupCodec
import dev.immichwall.backup.BackupFile
import dev.immichwall.backup.BackupPrompts
import dev.immichwall.backup.BackupSaved
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.util.Logg
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Back up settings": ask whether to include the server address and key
 * ([BackupOptionsDialog]) → the system's "save as" picker → write the SAVED settings to the
 * chosen file → say how it went.
 *
 * Create it as a property of the host fragment (the picker must be registered before the
 * fragment is created) and call [start] from a button. The include-server choice is kept in
 * the fragment's saved state, because the activity can be recreated, or the process killed,
 * while the picker is open.
 */
class SettingsBackupFlow(private val fragment: Fragment) {

    private var includeServer = false
    private val model: SettingsBackupViewModel by fragment.viewModels()

    private val createDocument = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) model.write(uri, includeServer) }

    init {
        fragment.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val registry = fragment.savedStateRegistry
                includeServer = registry.consumeRestoredStateForKey(STATE_KEY)?.getBoolean(STATE_INCLUDE_SERVER) == true
                registry.registerSavedStateProvider(STATE_KEY) { bundleOf(STATE_INCLUDE_SERVER to includeServer) }

                fragment.childFragmentManager.setFragmentResultListener(
                    BackupOptionsDialog.REQUEST_KEY, fragment,
                ) { _, result -> pickFile(result) }

                fragment.lifecycleScope.launch {
                    fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        model.message.collect { message -> if (message != null && show(message)) model.message.value = null }
                    }
                }
            }
        })
    }

    fun start() {
        val fm = fragment.childFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(BackupOptionsDialog.TAG) != null) return
        BackupOptionsDialog().show(fm, BackupOptionsDialog.TAG)
    }

    /** False when it could not be shown now and should be offered again. */
    private fun show(@StringRes message: Int): Boolean {
        // Success is a toast; a backup that lacks what was asked for needs reading.
        if (message == R.string.backup_saved_server_unset) return BackupMessageDialog.show(fragment, message)
        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
        return true
    }

    private fun pickFile(choice: Bundle) {
        includeServer = choice.getBoolean(BackupOptionsDialog.KEY_INCLUDE_SERVER)
        try {
            createDocument.launch(BackupFile.suggestedName(LocalDate.now()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(fragment.requireContext(), R.string.backup_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val STATE_KEY = "settings_backup"
        const val STATE_INCLUDE_SERVER = "includeServer"
    }
}

/** Writes the backup off the main thread; outlives a recreation of the screen that asked. */
class SettingsBackupViewModel(app: Application) : AndroidViewModel(app) {

    /** What to tell the user about the last write; the screen clears it once shown. */
    val message = MutableStateFlow<Int?>(null)

    fun write(uri: Uri, includeServer: Boolean) {
        viewModelScope.launch {
            message.value = withContext(Dispatchers.IO) { writeNow(uri, includeServer) }
        }
    }

    @StringRes
    private fun writeNow(uri: Uri, includeServer: Boolean): Int {
        val resolver = getApplication<Application>().contentResolver
        return try {
            val backup = SettingsRepository.get(getApplication()).buildBackup(includeServer)
            val bytes = BackupCodec.encode(backup).toByteArray(Charsets.UTF_8)
            // "wt": the picker may hand back an existing file the user chose to overwrite.
            (resolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")).use { it.write(bytes) }
            when (BackupPrompts.backupSaved(backup, includeServer)) {
                BackupSaved.SAVED -> R.string.backup_saved
                BackupSaved.SAVED_WITH_SERVER -> R.string.backup_saved_with_server
                BackupSaved.SAVED_SERVER_UNSET -> R.string.backup_saved_server_unset
            }
        } catch (e: Exception) {
            // The class only: a message could quote the file.
            Logg.w(TAG, "backup not written: ${e.javaClass.simpleName}")
            // The picker has already created the file; do not leave an empty or half one.
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            R.string.backup_failed
        }
    }

    private companion object {
        const val TAG = "SettingsBackup"
    }
}
