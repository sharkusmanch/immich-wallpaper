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
import dev.immichwall.backup.BackupRestore
import dev.immichwall.backup.RestoreOutcome
import dev.immichwall.backup.RestorePrompt
import dev.immichwall.backup.ServerAddresses
import dev.immichwall.backup.ServerUse
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
 * what every cycle change starts → say what happened. Before setup is finished nothing is
 * started: the wizard's last step does that, from what is stored by then.
 *
 * Create it as a property of the host fragment (the picker must be registered before the
 * fragment is created) and call [start] from a button. Nothing is applied without the
 * confirmation: a backup that was read waits in [SettingsRestoreViewModel], which survives
 * the activity being recreated and is lost, unapplied, if the process is killed. One
 * restore at a time: [start] does nothing until the one before it is over, so the
 * confirmation on screen always describes the backup that "Restore" applies.
 *
 * @param serverUse what this screen does with a server address and API key the backup
 *   holds: offers them as the user's choice, never applies them, or uses them.
 * @param onBusy true while a restore is being applied, false once it is over: the host
 *   should not let its own settings be saved in between.
 * @param onRestored the stored settings were replaced; redraw anything that shows them.
 *   The argument says whether the server address and API key were replaced too. Called
 *   once per restore, with the host at least started.
 * @param onUnfinished applying failed part-way, so the stored settings may or may not have
 *   changed; redraw anything that shows them. Called with the host at least started.
 */
class SettingsRestoreFlow(
    private val fragment: Fragment,
    private val serverUse: ServerUse,
    private val onBusy: (Boolean) -> Unit = {},
    private val onRestored: (serverApplied: Boolean) -> Unit,
    private val onUnfinished: () -> Unit = {},
) {

    private val model: SettingsRestoreViewModel by fragment.viewModels()

    private val openDocument = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> model.picked(uri, serverUse) }

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
        // A second tap while the picker is opening, a file is being read, a confirmation is
        // up or a restore is being applied.
        if (!model.beginPick()) return
        try {
            openDocument.launch(MIME_TYPES)
        } catch (e: ActivityNotFoundException) {
            model.picked(null, serverUse)
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
                // Before setup is finished nothing is fetched yet (BackupRestore.startsSyncing).
                RestoreOutcome.RESTORED -> toast(
                    if (SettingsRepository.get(fragment.requireContext()).isConfigured) R.string.restore_done
                    else R.string.restore_done_setup
                )
                RestoreOutcome.RESTORED_WITH_SERVER -> toast(
                    if (SettingsRepository.get(fragment.requireContext()).isConfigured) R.string.restore_done_with_server
                    else R.string.restore_done_with_server_setup
                )
                RestoreOutcome.RESTORED_SERVER_KEPT -> BackupMessageDialog.show(fragment, R.string.restore_done_server_kept)
                RestoreOutcome.RESTORED_SERVER_TO_ENTER -> toast(R.string.restore_done_server_to_enter)
                RestoreOutcome.UNFINISHED -> BackupMessageDialog.show(fragment, R.string.restore_unfinished)
            }
            if (shown) {
                when (event.outcome) {
                    RestoreOutcome.NOTHING_RESTORED -> Unit
                    RestoreOutcome.UNFINISHED -> onUnfinished()
                    else -> onRestored(event.outcome == RestoreOutcome.RESTORED_WITH_SERVER)
                }
            }
            shown
        }
    }

    private fun toast(message: Int): Boolean {
        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
        return true
    }

    companion object {
        internal const val TAG = "SettingsRestore"

        /** Not JSON alone: file providers often report a `.json` file as text or as unknown. */
        private val MIME_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

        /**
         * Applies [backup] and then, when [BackupRestore.startsSyncing] says so, starts what
         * every cycle change starts. Blocks (many preference commits, possibly the Keystore,
         * possibly a wait for a sync to stop): not for the main thread. May throw (a Keystore
         * write, the wait for the cancellations); syncing that was stopped is started again
         * even then.
         *
         * The apply and the schedule's follow-up run under [ScheduleApplier]'s monitor, so no
         * [ScheduleApplier.applyIfDue] elsewhere can plan against the old cycles and activate
         * one of them afterwards. When a server block is going in, syncing is stopped first
         * (before setup is finished there is none to stop, and none is started after).
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
            val stopSyncs = replaceServer && backup.hasServer
            // Stays null when applying throws.
            var applied: BackupApplied? = null
            try {
                applied = if (stopSyncs) SyncScheduler.withSyncsStopped(appCtx, apply) else apply()
                return applied
            } finally {
                val startSyncing = BackupRestore.startsSyncing(settings.isConfigured, applied?.applied, stopSyncs)
                if (startSyncing) {
                    // Also what restarts syncing after withSyncsStopped.
                    SyncScheduler.ensurePeriodic(appCtx, forceReplace = true)
                    SyncScheduler.kickInitialFill(appCtx)
                    RotationController.onActiveCycleChanged(appCtx)
                }
                Logg.d(
                    TAG,
                    "restore: applied=${applied?.applied} serverApplied=${applied?.serverApplied} " +
                        "syncingStarted=$startSyncing",
                )
            }
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

    /** What the screen the waiting backup was picked on does with a server block. */
    private var pendingServerUse = ServerUse.IGNORED
    val hasPending: Boolean get() = pending != null

    /**
     * The addresses confirming the waiting backup can store, for the confirmation to show.
     * Read from here each time the dialog is built: they are not put in its arguments.
     */
    val pendingServerAddresses: ServerAddresses?
        get() = pending?.let { BackupPrompts.serverAddressesToShow(it, pendingServerUse) }

    private var picking = false
    private var reading = false

    /**
     * Claims the restore for a new file. False while one is under way: the picker is open,
     * its file is being read, its backup waits for the confirmation, or it is being applied.
     */
    fun beginPick(): Boolean {
        if (picking || reading || pending != null || applying.value) return false
        picking = true
        return true
    }

    /** The picker closed, with [uri] or (null) without a file. */
    fun picked(uri: Uri?, serverUse: ServerUse) {
        picking = false
        if (uri != null) read(uri, serverUse)
    }

    private fun read(uri: Uri, serverUse: ServerUse) {
        reading = true
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
            pendingServerUse = serverUse
            reading = false
            event.value =
                if (result == null) Event.Unreadable else Event.Read(BackupPrompts.restorePrompt(result, serverUse))
        }
    }

    /** The user confirmed: applies the waiting backup, once. */
    fun confirm(replaceServer: Boolean) {
        val backup = pending ?: return
        val serverUse = pendingServerUse
        pending = null
        applying.value = true
        viewModelScope.launch {
            val applied = withContext(Dispatchers.IO) {
                try {
                    SettingsRestoreFlow.apply(getApplication(), backup, replaceServer)
                } catch (e: Exception) {
                    // The class only: the message could quote the address or the file.
                    Logg.w(SettingsRestoreFlow.TAG, "restore did not finish: ${e.javaClass.simpleName}")
                    null
                }
            }
            applying.value = false
            event.value = Event.Applied(
                BackupPrompts.restoreOutcome(
                    backup, serverUse, replaceServer, applied?.applied, applied?.serverApplied == true,
                )
            )
        }
    }

    /** The user backed out of the confirmation. */
    fun dropPending() {
        pending = null
    }
}
