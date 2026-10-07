package dev.immichwall.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.immichwall.backup.Backup
import dev.immichwall.backup.BackupDecodeResult
import dev.immichwall.backup.BackupFile
import dev.immichwall.backup.BackupPrompts
import dev.immichwall.backup.RestoreOutcome
import dev.immichwall.backup.RestorePrompt
import dev.immichwall.schedule.ScheduleApplier
import dev.immichwall.settings.BackupApplied
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.sync.SyncScheduler
import dev.immichwall.util.Logg
import dev.immichwall.wallpaper.RotationController
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Restore settings", start to finish: the system's file picker → read and decode off the
 * main thread → refuse what is not a backup, or ask ([RestoreConfirmDialog]) → apply → start
 * what every cycle change starts → say what happened.
 *
 * Create it as a property of the host fragment (the picker must be registered before the
 * fragment is created) and call [start] from a button. Nothing is applied without the
 * confirmation: a backup that was read waits in [SettingsRestoreViewModel], which survives
 * the activity being recreated and is lost, unapplied, if the process is killed.
 *
 * @param offerServer whether the confirmation may offer to replace the server address and
 *   API key when the backup holds them (the user's choice, off by default). False = they
 *   are never applied from this screen.
 * @param onBusy true while a restore is being applied, false once it is over: the host
 *   should not let its own settings be saved in between.
 * @param onRestored the stored settings were replaced; redraw anything that shows them.
 *   Called with the host at least started.
 */
class SettingsRestoreFlow(
    private val fragment: Fragment,
    private val offerServer: Boolean,
    private val onBusy: (Boolean) -> Unit = {},
    private val onRestored: () -> Unit,
) {

    private val model: SettingsRestoreViewModel by fragment.viewModels()

    private val openDocument = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) model.read(uri, offerServer) }

    init {
        fragment.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                fragment.lifecycleScope.launch {
                    fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch { model.applying.collect { onBusy(it) } }
                        model.event.collect { event -> if (event != null && show(event)) model.event.value = null }
                    }
                }
            }
        })
    }

    fun start() {
        try {
            openDocument.launch(MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.backup_no_picker)
        }
    }

    /** False when it could not be shown now and should be offered again. */
    private fun show(event: SettingsRestoreViewModel.Event): Boolean = when (event) {
        SettingsRestoreViewModel.Event.Unreadable -> BackupMessageDialog.show(fragment, R.string.restore_unreadable)
        is SettingsRestoreViewModel.Event.Read -> when (val prompt = event.prompt) {
            RestorePrompt.NotABackup -> BackupMessageDialog.show(fragment, R.string.restore_not_a_backup)
            RestorePrompt.NewerFormat -> BackupMessageDialog.show(fragment, R.string.restore_newer_format)
            is RestorePrompt.Confirm -> {
                val fm = fragment.childFragmentManager
                if (fm.isStateSaved) {
                    false
                } else {
                    if (fm.findFragmentByTag(RestoreConfirmDialog.TAG) == null) {
                        RestoreConfirmDialog.forPrompt(prompt).show(fm, RestoreConfirmDialog.TAG)
                    }
                    true
                }
            }
        }
        is SettingsRestoreViewModel.Event.Applied -> {
            val shown = when (event.outcome) {
                RestoreOutcome.NOTHING_RESTORED -> BackupMessageDialog.show(fragment, R.string.restore_nothing)
                RestoreOutcome.RESTORED -> toast(R.string.restore_done)
                RestoreOutcome.RESTORED_WITH_SERVER -> toast(R.string.restore_done_with_server)
                RestoreOutcome.RESTORED_SERVER_KEPT -> BackupMessageDialog.show(fragment, R.string.restore_done_server_kept)
            }
            if (shown && event.outcome != RestoreOutcome.NOTHING_RESTORED) onRestored()
            shown
        }
    }

    private fun toast(message: Int): Boolean {
        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
        return true
    }

    companion object {
        private const val TAG = "SettingsRestore"

        /** Not JSON alone: file providers often report a `.json` file as text or as unknown. */
        private val MIME_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

        /**
         * Applies [backup] and starts what every cycle change starts. Blocks (many preference
         * commits, possibly the Keystore, possibly a wait for a sync to stop): not for the
         * main thread.
         *
         * The apply and the schedule's follow-up run under [ScheduleApplier]'s monitor, so no
         * [ScheduleApplier.applyIfDue] elsewhere can plan against the old cycles and activate
         * one of them afterwards. When a server block is going in, syncing is stopped first.
         * Locks, outermost first: the sync run lock, [ScheduleApplier], then the settings'
         * own; the same order a sync run takes them in.
         */
        internal fun apply(appCtx: Context, backup: Backup, replaceServer: Boolean): BackupApplied {
            val settings = SettingsRepository.get(appCtx)
            val apply = {
                ScheduleApplier.exclusively {
                    settings.applyBackup(backup, replaceServer).also { if (it.applied) ScheduleApplier.applyIfDue(appCtx) }
                }
            }
            val applied =
                if (replaceServer && backup.hasServer) SyncScheduler.withSyncsStopped(appCtx, apply) else apply()
            // Also what restarts syncing after withSyncsStopped, so not conditional on the result.
            SyncScheduler.ensurePeriodic(appCtx, forceReplace = true)
            SyncScheduler.kickInitialFill(appCtx)
            RotationController.onActiveCycleChanged(appCtx)
            Logg.d(TAG, "restore: applied=${applied.applied} serverApplied=${applied.serverApplied}")
            return applied
        }
    }
}

/**
 * Holds a decoded backup between "picked" and "confirmed", and does the reading and applying
 * off the main thread. In memory only: a backup can hold the API key, so neither it nor the
 * file's text goes into saved state or a log.
 */
class SettingsRestoreViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Event {
        /** The file could not be opened or read. */
        data object Unreadable : Event

        data class Read(val prompt: RestorePrompt) : Event

        data class Applied(val outcome: RestoreOutcome) : Event
    }

    /** Something to show; the screen clears it once shown. */
    val event = MutableStateFlow<Event?>(null)

    val applying = MutableStateFlow(false)

    private var pending: Backup? = null
    val hasPending: Boolean get() = pending != null

    fun read(uri: Uri, offerServer: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(BackupFile::read)
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
            }
            pending = (result as? BackupDecodeResult.Ok)?.backup
            event.value =
                if (result == null) Event.Unreadable else Event.Read(BackupPrompts.restorePrompt(result, offerServer))
        }
    }

    /** The user confirmed: applies the waiting backup, once. */
    fun confirm(replaceServer: Boolean) {
        val backup = pending ?: return
        pending = null
        applying.value = true
        viewModelScope.launch {
            val applied = withContext(Dispatchers.IO) {
                SettingsRestoreFlow.apply(getApplication(), backup, replaceServer)
            }
            applying.value = false
            event.value = Event.Applied(
                BackupPrompts.restoreOutcome(backup, replaceServer, applied.applied, applied.serverApplied)
            )
        }
    }

    /** The user backed out of the confirmation. */
    fun dropPending() {
        pending = null
    }
}
