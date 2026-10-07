package dev.immichwall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.immichwall.R
import dev.immichwall.api.CheckResult
import dev.immichwall.api.ConnectionValidator
import dev.immichwall.api.ImmichApiClient
import dev.immichwall.api.PersonDto
import dev.immichwall.settings.SettingsRepository
import dev.immichwall.source.SourceSpec
import dev.immichwall.ui.onboarding.LiveWallpaperLauncher
import dev.immichwall.ui.onboarding.LockReplaceConfirmDialog
import dev.immichwall.ui.onboarding.WelcomeFragment
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-activity host. Routes to the onboarding wizard when unconfigured,
 * otherwise to the status screen. Fragments navigate via [navigateTo]/[resetTo].
 */
class MainActivity : AppCompatActivity() {

    private var lastNavElapsed = 0L
    private var lastNavClass: KClass<*>? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Neither the theme nor Material Components asks for dark system-bar icons, so on
        // a light background they were white on white. The activity is recreated when
        // the night mode changes, so reading it here keeps the icons in step.
        val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
        // Android 15 enforces edge-to-edge: inset the fragment container so content
        // never renders under the status/navigation bars or the IME.
        val container = findViewById<android.view.View>(R.id.fragment_container)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        // Lock-replace confirmation result: the DialogFragment survives recreation,
        // so the listener is registered here (every onCreate) to stay armed after a
        // rotation mid-dialog. Routes the confirmed choice to the launcher.
        supportFragmentManager.setFragmentResultListener(
            LockReplaceConfirmDialog.REQUEST_KEY, this
        ) { _, _ -> LiveWallpaperLauncher.confirmAndOpen(this) }
        val settings = SettingsRepository.get(this)
        if (savedInstanceState == null) {
            val root: Fragment = if (settings.isConfigured) StatusFragment() else WelcomeFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, root)
                .commit()
            // Health notifications only matter once the wallpaper is set up, so the
            // permission is asked for on the status screen (or right after Apply).
            if (settings.isConfigured) maybeRequestNotificationPermission()
        }
    }

    /**
     * Requests POST_NOTIFICATIONS (needed for health notifications) unless already
     * granted. Called once the app is configured and again right after Apply; the
     * system itself stops re-prompting after repeated denials.
     */
    fun maybeRequestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Push [fragment] onto the back stack. */
    fun navigateTo(fragment: Fragment) {
        // Double-tap guard: a second click in the same input batch arrives before the
        // first (async) commit executes, so the current-fragment class check alone
        // can't catch it. Debounce per destination class — a repeat of the SAME
        // destination within the window is a double-tap, while a fast navigation to a
        // DIFFERENT destination is legitimate and proceeds. Stamped only after the
        // guards so skipped calls don't extend the dead window.
        val now = SystemClock.elapsedRealtime()
        if (fragment::class == lastNavClass && now - lastNavElapsed < NAV_DEBOUNCE_MS) return
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current != null && current::class == fragment::class) return
        if (supportFragmentManager.isStateSaved) return
        lastNavElapsed = now
        lastNavClass = fragment::class
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    /** Clear the whole back stack and make [fragment] the new root. */
    fun resetTo(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    companion object {
        private const val NAV_DEBOUNCE_MS = 500L
    }
}

/**
 * Shared, in-memory wizard state. Scoped to the activity so every onboarding
 * fragment reads/writes the same instance. Nothing here is persisted — the
 * final [buildSourceSpec] result is what gets stored in [SettingsRepository].
 */
class WizardViewModel : ViewModel() {

    enum class Mode { PEOPLE, ALBUM, SMART, LOCATION, FAVORITES, MEMORIES, EVERYTHING, CUSTOM }

    /** Connection-test state, kept here so it survives rotation mid-test. */
    sealed interface ServerTestState {
        data object Idle : ServerTestState
        data object Running : ServerTestState
        data class Done(val results: List<CheckResult>) : ServerTestState
    }

    var serverUrl: String = ""
    var awayUrl: String = ""
    var apiKey: String = ""

    var mode: Mode? = null

    /** Cycle id being edited (preview save reuses it); null = creating a new cycle. */
    var editingCycleId: String? = null

    /** Per-cycle people preference draft: "off" | "prefer" | "require". */
    var cyclePeoplePreference: String = "prefer"

    // Memories options (see SourceSpec.Memories).
    var memoriesWindowDays: Int = 3
    var memoriesYearsAgoMin: Int = 0
    var memoriesYearsAgoMax: Int = 0

    val selectedPersonIds: MutableList<String> = mutableListOf()
    val selectedPersonNames: MutableList<String> = mutableListOf()
    var requireAll: Boolean = false

    /** People mode's in-progress (uncommitted) grid selection; survives rotation. */
    val draftPersonIds: MutableList<String> = mutableListOf()

    /** Cached people list so a rotation doesn't re-download every page. */
    var peopleCache: List<PersonDto>? = null

    /** Smart mode's optional person filter — deliberately separate from People mode's. */
    val smartPersonIds: MutableList<String> = mutableListOf()
    val smartPersonNames: MutableList<String> = mutableListOf()

    // Custom-filter mode's state — separate from every other mode's.
    val customPersonIds: MutableList<String> = mutableListOf()
    val customPersonNames: MutableList<String> = mutableListOf()
    var customRequireAll: Boolean = false
    var customAlbumId: String = ""
    var customAlbumName: String = ""
    var customQuery: String = ""
    var customCity: String = ""
    var customFavoritesOnly: Boolean = false
    var customTakenAfter: String = ""
    var customTakenBefore: String = ""

    var query: String = ""
    var city: String = ""
    var albumId: String = ""
    var albumName: String = ""

    val serverTest = MutableStateFlow<ServerTestState>(ServerTestState.Idle)

    /** Field values (normalized) the last test was started with; edits to other values invalidate. */
    var testedUrl: String = ""
    var testedAwayUrl: String = ""
    var testedKey: String = ""

    private var serverTestToken = 0

    /**
     * Runs [ConnectionValidator] against [url]/[key] in [viewModelScope] so an
     * in-flight test survives rotation. [connectionCheckName] labels the single
     * catch-all failure row (resolved by the fragment — no Context here).
     */
    fun runServerTest(url: String, key: String, connectionCheckName: String) {
        val token = ++serverTestToken
        viewModelScope.launch {
            serverTest.value = ServerTestState.Running
            val results = withContext(Dispatchers.IO) {
                try {
                    val client = ImmichApiClient({ url }, { key })
                    ConnectionValidator(client).validate()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    listOf(
                        CheckResult(
                            name = connectionCheckName,
                            ok = false,
                            detail = e.message ?: e.javaClass.simpleName,
                        )
                    )
                }
            }
            // Only the latest run publishes, and not if a field edit reset us to Idle.
            if (token == serverTestToken && serverTest.value == ServerTestState.Running) {
                serverTest.value = ServerTestState.Done(results)
            }
        }
    }

    fun setSelectedPeople(ids: List<String>, names: List<String>) {
        selectedPersonIds.clear()
        selectedPersonIds.addAll(ids)
        selectedPersonNames.clear()
        selectedPersonNames.addAll(names)
    }

    fun setSmartPeople(ids: List<String>, names: List<String>) {
        smartPersonIds.clear()
        smartPersonIds.addAll(ids)
        smartPersonNames.clear()
        smartPersonNames.addAll(names)
    }

    fun setCustomPeople(ids: List<String>, names: List<String>) {
        customPersonIds.clear()
        customPersonIds.addAll(ids)
        customPersonNames.clear()
        customPersonNames.addAll(names)
    }

    /** Prefills the custom-filter state from an existing spec (cycle editing). */
    fun loadCustom(spec: SourceSpec.Custom) {
        setCustomPeople(spec.personIds, spec.personNames)
        customRequireAll = spec.requireAll
        customAlbumId = spec.albumId
        customAlbumName = spec.albumName
        customQuery = spec.query
        customCity = spec.city
        customFavoritesOnly = spec.favoritesOnly
        customTakenAfter = spec.takenAfter
        customTakenBefore = spec.takenBefore
    }

    fun buildSourceSpec(): SourceSpec? = when (mode) {
        Mode.PEOPLE ->
            if (selectedPersonIds.isEmpty()) null
            else SourceSpec.People(
                ids = selectedPersonIds.toList(),
                names = selectedPersonNames.toList(),
                requireAll = requireAll && selectedPersonIds.size >= 2,
            )
        Mode.ALBUM ->
            if (albumId.isBlank()) null else SourceSpec.Album(albumId, albumName)
        Mode.SMART ->
            if (query.isBlank()) null
            else SourceSpec.SmartQuery(
                query = query.trim(),
                personIds = smartPersonIds.toList(),
                personNames = smartPersonNames.toList(),
            )
        Mode.LOCATION ->
            if (city.isBlank()) null else SourceSpec.Location(city)
        Mode.FAVORITES -> SourceSpec.Favorites
        Mode.MEMORIES -> SourceSpec.Memories(
            windowDays = memoriesWindowDays,
            yearsAgoMin = memoriesYearsAgoMin,
            yearsAgoMax = memoriesYearsAgoMax,
        )
        Mode.EVERYTHING -> SourceSpec.EverythingRandom
        Mode.CUSTOM -> {
            val spec = SourceSpec.Custom(
                personIds = customPersonIds.toList(),
                personNames = customPersonNames.toList(),
                requireAll = customRequireAll && customPersonIds.size >= 2,
                albumId = customAlbumId,
                albumName = customAlbumName,
                query = customQuery.trim(),
                city = customCity,
                favoritesOnly = customFavoritesOnly,
                takenAfter = customTakenAfter,
                takenBefore = customTakenBefore,
            )
            // At least one constraint, or it's just "Everything" pretending.
            val constrained = spec.personIds.isNotEmpty() || spec.albumId.isNotBlank() ||
                spec.query.isNotBlank() || spec.city.isNotBlank() || spec.favoritesOnly ||
                spec.takenAfter.isNotBlank() || spec.takenBefore.isNotBlank()
            if (constrained) spec else null
        }
        null -> null
    }
}

/** Minimal dependency-free replacement for core-ktx doAfterTextChanged. */
internal fun EditText.afterTextChanged(action: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            action(s?.toString().orEmpty())
        }
    })
}
