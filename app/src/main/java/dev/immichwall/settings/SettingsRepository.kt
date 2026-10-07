package dev.immichwall.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.immichwall.api.ApiJson
import dev.immichwall.backup.Backup
import dev.immichwall.backup.BackupOptions
import dev.immichwall.backup.BackupRestore
import dev.immichwall.backup.BackupServer
import dev.immichwall.crop.CropTarget
import dev.immichwall.schedule.Schedule
import dev.immichwall.schedule.ScheduleOverride
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import dev.immichwall.util.Logg
import javax.crypto.AEADBadTagException

/**
 * Outcome of [SettingsRepository.applyBackup]. [applied] false = the backup held no cycles
 * and NOTHING was changed; [serverApplied] is then false too.
 */
data class BackupApplied(val applied: Boolean, val serverApplied: Boolean)

/**
 * Process-wide settings store. The API key lives in EncryptedSharedPreferences;
 * everything else is plain SharedPreferences. [SourceSpec] is persisted as JSON
 * via [ApiJson.json]. Writes use commit() — they are rare and several are read
 * immediately afterwards by workers/services.
 */
class SettingsRepository private constructor(ctx: Context) {

    private val appCtx: Context = ctx.applicationContext

    private val plain: SharedPreferences =
        appCtx.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)

    private val secureLock = Any()

    @Volatile
    private var secureStore: SharedPreferences? = null

    /** Served while the Keystore is unavailable; in-memory only, see [InMemoryPrefs]. */
    private val memoryFallback: SharedPreferences by lazy { InMemoryPrefs() }

    /** True once [memoryFallback] has been served — its values may need migrating. */
    @Volatile
    private var fallbackServed = false

    /** elapsedRealtime of the last failed secure-store open; 0 = never failed. */
    @Volatile
    private var lastSecureFailureAt = 0L

    /**
     * Lazy on purpose: EncryptedSharedPreferences setup does Keystore + disk work
     * (100-300ms, more on first master-key generation) and the wallpaper process never
     * reads the API key, so process start must not pay for it. A transient Keystore
     * failure serves [memoryFallback] WITHOUT caching it, so a later access retries the
     * real store (e.g. once the user has unlocked) — but only after a short cooldown,
     * so per-request readers (the API-key header lambda) don't re-pay the full
     * attempt-sleep-retry on every read while the Keystore stays down. Anything written
     * to the fallback in the meantime is migrated into the real store the moment it
     * opens, so a key saved during an outage isn't silently shadowed by an empty store.
     */
    private val secure: SharedPreferences
        get() {
            secureStore?.let { return it }
            synchronized(secureLock) {
                secureStore?.let { return it }
                val now = SystemClock.elapsedRealtime()
                if (lastSecureFailureAt != 0L && now - lastSecureFailureAt < SECURE_FAILURE_COOLDOWN_MS) {
                    fallbackServed = true
                    return memoryFallback
                }
                val real = createSecurePrefsOrNull(appCtx)
                if (real != null) {
                    if (fallbackServed) migrateFallbackInto(real)
                    secureStore = real
                    return real
                }
                lastSecureFailureAt = SystemClock.elapsedRealtime()
                fallbackServed = true
                return memoryFallback
            }
        }

    /**
     * Forces the lazy secure-store open on the caller's thread so first UI reads
     * (e.g. the onboarding API-key prefill on main) hit the cached instance instead
     * of paying the Keystore + disk cost. Call from a background thread only.
     */
    fun warmUp() {
        secure
    }

    /**
     * Copies values written to [memoryFallback] during a Keystore outage into the
     * freshly opened real store (only Strings are ever stored there), then clears
     * the fallback. Called under [secureLock] before the real store is cached.
     */
    private fun migrateFallbackInto(real: SharedPreferences) {
        val pending = memoryFallback.all
        if (pending.isEmpty()) return
        Logg.w(TAG, "Migrating ${pending.size} value(s) from in-memory fallback into secure store")
        val editor = real.edit()
        for ((key, value) in pending) if (value is String) editor.putString(key, value)
        editor.commit()
        memoryFallback.edit().clear().commit()
    }

    var serverUrl: String
        get() = plain.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) { plain.edit().putString(KEY_SERVER_URL, value).commit() }

    var awayUrl: String
        get() = plain.getString(KEY_AWAY_URL, "").orEmpty()
        set(value) { plain.edit().putString(KEY_AWAY_URL, value).commit() }

    var apiKey: String
        get() = secure.getString(KEY_API_KEY, "").orEmpty()
        set(value) { secure.edit().putString(KEY_API_KEY, value).commit() }

    var sourceSpec: SourceSpec?
        get() {
            val raw = plain.getString(KEY_SOURCE_SPEC, null) ?: return null
            return try {
                ApiJson.json.decodeFromString(SourceSpec.serializer(), raw)
            } catch (t: Throwable) {
                Logg.e(TAG, "Failed to decode persisted SourceSpec", t)
                null
            }
        }
        set(value) {
            if (value == null) {
                plain.edit().remove(KEY_SOURCE_SPEC).commit()
            } else {
                val raw = ApiJson.json.encodeToString(SourceSpec.serializer(), value)
                plain.edit().putString(KEY_SOURCE_SPEC, raw).commit()
            }
        }

    var targetCacheCount: Int
        get() = plain.getInt(KEY_TARGET_CACHE_COUNT, DEFAULT_TARGET_CACHE_COUNT)
        set(value) { plain.edit().putInt(KEY_TARGET_CACHE_COUNT, value.coerceIn(OptionChoices.CACHE_COUNT_MIN, OptionChoices.CACHE_COUNT_MAX)).commit() }

    /**
     * How many photos the source of the cycle with [cycleKey] held at its last good sync,
     * or null when unknown. Sync bookkeeping, not a setting: it lets the status screen tell
     * a small source from a cache that is behind.
     */
    fun sourceSize(cycleKey: String): Int? =
        plain.getInt(KEY_SOURCE_SIZE_PREFIX + cycleKey, -1).takeIf { it >= 0 }

    fun setSourceSize(cycleKey: String, size: Int?) {
        val editor = plain.edit()
        if (size == null) editor.remove(KEY_SOURCE_SIZE_PREFIX + cycleKey)
        else editor.putInt(KEY_SOURCE_SIZE_PREFIX + cycleKey, size)
        editor.commit()
    }

    /** Forgets the sizes of cycles outside [cycleKeys] (a Memories key changes every day). */
    fun retainSourceSizes(cycleKeys: Set<String>) {
        val stale = plain.all.keys.filter {
            it.startsWith(KEY_SOURCE_SIZE_PREFIX) && it.removePrefix(KEY_SOURCE_SIZE_PREFIX) !in cycleKeys
        }
        if (stale.isEmpty()) return
        val editor = plain.edit()
        stale.forEach { editor.remove(it) }
        editor.commit()
    }

    var refreshIntervalHours: Int
        get() = plain.getInt(KEY_REFRESH_INTERVAL_HOURS, DEFAULT_REFRESH_INTERVAL_HOURS)
        set(value) { plain.edit().putInt(KEY_REFRESH_INTERVAL_HOURS, value).commit() }

    var deriveThemeFromPhoto: Boolean
        get() = plain.getBoolean(KEY_DERIVE_THEME, false)
        set(value) { plain.edit().putBoolean(KEY_DERIVE_THEME, value).commit() }

    /** When false (default), photo downloads wait for an unmetered (Wi-Fi) connection. */
    var syncOverCellular: Boolean
        get() = plain.getBoolean(KEY_SYNC_OVER_CELLULAR, false)
        set(value) { plain.edit().putBoolean(KEY_SYNC_OVER_CELLULAR, value).commit() }

    /** Prefer real, sharp, well-framed photos (skip screenshots/blurry/crowd shots). */
    var qualityFilterEnabled: Boolean
        get() = plain.getBoolean(KEY_QUALITY_FILTER, true)
        set(value) { plain.edit().putBoolean(KEY_QUALITY_FILTER, value).commit() }

    /**
     * How much face presence matters when the quality filter scores candidates:
     * [PEOPLE_PREF_OFF] not at all, [PEOPLE_PREF_PREFER] photos with people outrank
     * face-less ones (menus, signs, receipts sink), [PEOPLE_PREF_REQUIRE] face-less
     * photos are excluded outright (relaxation still refills starving pools).
     */
    var peoplePreference: String
        get() = plain.getString(KEY_PEOPLE_PREFERENCE, PEOPLE_PREF_PREFER) ?: PEOPLE_PREF_PREFER
        set(value) { plain.edit().putString(KEY_PEOPLE_PREFERENCE, value).commit() }

    /**
     * Minimum minutes between photo changes; 0 = change at every screen wake (default).
     * The swap still only ever happens while the screen is dark — an interval simply
     * lets wakes inside it reveal the same photo again.
     */
    var rotationMinIntervalMinutes: Int
        get() = plain.getInt(KEY_ROTATION_MIN_INTERVAL, 0)
        set(value) { plain.edit().putInt(KEY_ROTATION_MIN_INTERVAL, value.coerceAtLeast(0)).commit() }

    /** Millis of the last committed photo advance; written by the rotation controller. */
    var lastAdvanceAt: Long
        get() = plain.getLong(KEY_LAST_ADVANCE_AT, 0L)
        set(value) { plain.edit().putLong(KEY_LAST_ADVANCE_AT, value).commit() }

    /**
     * Saved wallpaper cycles. The ACTIVE cycle's spec is mirrored into [sourceSpec]
     * (which the sync pipeline reads) by [activateCycle]; the rest are inert drafts.
     * Reading migrates a pre-cycles install: the existing [sourceSpec] becomes the
     * first (active) saved cycle.
     */
    private val cyclesLock = Any()

    var savedCycles: List<SavedCycle>
        get() = synchronized(cyclesLock) { savedCyclesLocked() }
        set(value) = synchronized(cyclesLock) { persistCyclesLocked(value) }

    private fun savedCyclesLocked(): List<SavedCycle> {
        val raw = plain.getString(KEY_SAVED_CYCLES, null)
        if (raw != null) {
            return try {
                ApiJson.json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(SavedCycle.serializer()), raw)
            } catch (t: Throwable) {
                // Never let one bad entry wipe the list via a read-modify-write cycle.
                Logg.e(TAG, "savedCycles undecodable; treating as empty for THIS read", t)
                emptyList()
            }
        }
        // Migration: wrap the active spec (if any) as the first saved cycle.
        val spec = sourceSpec ?: return emptyList()
        val cycle = SavedCycle(java.util.UUID.randomUUID().toString(), spec.summaryLabel(), spec)
        Logg.d(TAG, "cycles: migrated active spec into first cycle '${cycle.name}'")
        persistCyclesLocked(listOf(cycle))
        activeCycleId = cycle.id
        return listOf(cycle)
    }

    private fun persistCyclesLocked(value: List<SavedCycle>) {
        val raw = ApiJson.json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SavedCycle.serializer()), value)
        plain.edit().putString(KEY_SAVED_CYCLES, raw).commit()
    }

    var activeCycleId: String
        get() = plain.getString(KEY_ACTIVE_CYCLE_ID, "") ?: ""
        set(value) { plain.edit().putString(KEY_ACTIVE_CYCLE_ID, value).commit() }

    /** Saves (or replaces by id) a cycle without touching the active configuration. */
    fun upsertCycle(cycle: SavedCycle) {
        synchronized(cyclesLock) {
            Logg.d(TAG, "cycles: upsert '${cycle.name}'")
            persistCyclesLocked(savedCyclesLocked().filter { it.id != cycle.id } + cycle)
        }
    }

    /** Removes a cycle; refuses to remove the active one or one the schedule refers to. */
    fun deleteCycle(cycleId: String): Boolean {
        synchronized(cyclesLock) {
            if (cycleId == activeCycleId || isCycleScheduled(cycleId)) return false
            val cycles = savedCyclesLocked()
            val victim = cycles.firstOrNull { it.id == cycleId } ?: return false
            Logg.d(TAG, "cycles: delete '${victim.name}'")
            persistCyclesLocked(cycles.filter { it.id != cycleId })
            return true
        }
    }

    /** Makes [cycleId] the active configuration; the sync pipeline picks it up next run. */
    fun activateCycle(cycleId: String): SavedCycle? {
        synchronized(cyclesLock) {
            val cycle = savedCyclesLocked().firstOrNull { it.id == cycleId } ?: return null
            Logg.d(TAG, "cycles: activate '${cycle.name}'")
            sourceSpec = cycle.spec
            activeCycleId = cycle.id
            return cycle
        }
    }

    /**
     * Repairs activeCycleId <-> sourceSpec divergence (belt-and-braces; the two are only
     * ever written together, but the running spec is the ground truth the sync pipeline
     * uses, so the cycle list must agree with it). Returns the list, healed if needed.
     */
    fun cyclesConsistentWithActiveSpec(): List<SavedCycle> {
        synchronized(cyclesLock) {
            val cycles = savedCyclesLocked()
            val spec = sourceSpec ?: return cycles
            val activeMatches = cycles.firstOrNull { it.id == activeCycleId }?.spec == spec
            if (activeMatches) return cycles
            val bySpec = cycles.firstOrNull { it.spec == spec }
            if (bySpec != null) {
                Logg.w(TAG, "cycles: healing activeCycleId -> '${bySpec.name}' (was inconsistent)")
                activeCycleId = bySpec.id
                return cycles
            }
            // The running spec has no cycle at all — re-wrap it so it's visible and owned.
            val wrapped = SavedCycle(java.util.UUID.randomUUID().toString(), spec.summaryLabel(), spec)
            Logg.w(TAG, "cycles: running spec had no cycle; re-wrapped as '${wrapped.name}'")
            persistCyclesLocked(cycles + wrapped)
            activeCycleId = wrapped.id
            return cycles + wrapped
        }
    }

    /**
     * A backup of the SAVED settings: cycles, active cycle, schedule and the six options,
     * plus the connection details when [includeServer] and both an address and a key are
     * set. The manual schedule override and everything that is bookkeeping are left out.
     */
    fun buildBackup(includeServer: Boolean): Backup {
        val (cycles, activeId) = synchronized(cyclesLock) {
            val list = cyclesConsistentWithActiveSpec()
            val active = activeCycleId.takeIf { id -> list.any { it.id == id } }
                ?: list.firstOrNull()?.id.orEmpty()
            list to active
        }
        val server = if (includeServer && serverUrl.isNotBlank() && apiKey.isNotBlank()) {
            BackupServer(serverUrl, awayUrl, apiKey)
        } else null
        return Backup(
            exportedAt = java.time.Instant.now().toString(),
            cycles = cycles,
            activeCycleId = activeId,
            schedule = schedule,
            options = BackupOptions(
                targetCacheCount = targetCacheCount,
                refreshIntervalHours = refreshIntervalHours,
                rotationMinIntervalMinutes = rotationMinIntervalMinutes,
                qualityFilterEnabled = qualityFilterEnabled,
                syncOverCellular = syncOverCellular,
                deriveThemeFromPhoto = deriveThemeFromPhoto,
            ),
            server = server,
        )
    }

    /**
     * Replaces the cycles, active cycle, schedule and (when present) options with [backup]'s,
     * clears the manual schedule override, and applies its server block only when
     * [applyServer] and [BackupRestore.serverToApply] accepts it. Does not kick syncs.
     * A backup with no cycles applies nothing ([BackupApplied.applied] false).
     *
     * Cycles, active id, mirrored source, schedule and the override removal go out in ONE
     * plain-prefs commit under [cyclesLock], so schedule readers (which take the lock via
     * [cyclesConsistentWithActiveSpec]) never see new cycles with the old schedule. The
     * secure store (its own lock) is written after that block exits, so the locks are never
     * nested here; the key goes first so a reader never sees the new address with the old key.
     */
    fun applyBackup(backup: Backup, applyServer: Boolean): BackupApplied {
        val active = backup.cycles.firstOrNull { it.id == backup.activeCycleId }
            ?: backup.cycles.firstOrNull()
            ?: return BackupApplied(applied = false, serverApplied = false)
        val server = if (applyServer) backup.server?.let(BackupRestore::serverToApply) else null
        val options = backup.options?.let(BackupRestore::sanitizeOptions)
        val json = ApiJson.json
        synchronized(cyclesLock) {
            val editor = plain.edit()
                .putString(KEY_SAVED_CYCLES, json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(SavedCycle.serializer()), backup.cycles))
                .putString(KEY_SOURCE_SPEC, json.encodeToString(SourceSpec.serializer(), active.spec))
                .putString(KEY_ACTIVE_CYCLE_ID, active.id)
                .putString(KEY_SCHEDULE, json.encodeToString(Schedule.serializer(), backup.schedule))
                .remove(KEY_SCHEDULE_OVERRIDE)
            if (options != null) {
                editor.putInt(KEY_TARGET_CACHE_COUNT, options.targetCacheCount)
                    .putInt(KEY_REFRESH_INTERVAL_HOURS, options.refreshIntervalHours)
                    .putInt(KEY_ROTATION_MIN_INTERVAL, options.rotationMinIntervalMinutes)
                    .putBoolean(KEY_QUALITY_FILTER, options.qualityFilterEnabled)
                    .putBoolean(KEY_SYNC_OVER_CELLULAR, options.syncOverCellular)
                    .putBoolean(KEY_DERIVE_THEME, options.deriveThemeFromPhoto)
            }
            editor.commit()
        }
        if (server != null) {
            apiKey = server.apiKey
            plain.edit()
                .putString(KEY_SERVER_URL, server.serverUrl)
                .putString(KEY_AWAY_URL, server.awayUrl)
                .putString(KEY_LAST_GOOD_BASE_URL, "")
                .commit()
        }
        Logg.d(TAG, "backup applied: ${backup.cycleCount} cycles, serverApplied=${server != null}")
        return BackupApplied(applied = true, serverApplied = server != null)
    }

    var isConfigured: Boolean
        get() = plain.getBoolean(KEY_IS_CONFIGURED, false)
        set(value) { plain.edit().putBoolean(KEY_IS_CONFIGURED, value).commit() }

    var cropWidth: Int
        get() = plain.getInt(KEY_CROP_WIDTH, 0)
        set(value) { plain.edit().putInt(KEY_CROP_WIDTH, value).commit() }

    var cropHeight: Int
        get() = plain.getInt(KEY_CROP_HEIGHT, 0)
        set(value) { plain.edit().putInt(KEY_CROP_HEIGHT, value).commit() }

    /** Distinct surface sizes the wallpaper engine has been given; [cropWidth]×[cropHeight] is their union box. */
    var seenSurfaces: List<CropTarget.Size>
        get() = CropTarget.decode(plain.getString(KEY_SEEN_SURFACES, "").orEmpty())
        set(value) { plain.edit().putString(KEY_SEEN_SURFACES, CropTarget.encode(value)).commit() }

    var lastGoodBaseUrl: String
        get() = plain.getString(KEY_LAST_GOOD_BASE_URL, "").orEmpty()
        set(value) { plain.edit().putString(KEY_LAST_GOOD_BASE_URL, value).commit() }

    /**
     * The date-of-year schedule. Undecodable JSON reads as "off and empty" rather than
     * throwing: this is read on the wake path, which must never crash the wallpaper.
     */
    var schedule: Schedule
        get() {
            val raw = plain.getString(KEY_SCHEDULE, null) ?: return Schedule()
            return try {
                ApiJson.json.decodeFromString(Schedule.serializer(), raw)
            } catch (t: Throwable) {
                Logg.e(TAG, "schedule undecodable; treating as off", t)
                Schedule()
            }
        }
        set(value) {
            plain.edit().putString(KEY_SCHEDULE, ApiJson.json.encodeToString(Schedule.serializer(), value)).commit()
        }

    /** A manual cycle pick that is holding against the schedule; null = none. */
    var scheduleOverride: ScheduleOverride?
        get() {
            val raw = plain.getString(KEY_SCHEDULE_OVERRIDE, null) ?: return null
            return try {
                ApiJson.json.decodeFromString(ScheduleOverride.serializer(), raw)
            } catch (t: Throwable) {
                null
            }
        }
        set(value) {
            val editor = plain.edit()
            if (value == null) editor.remove(KEY_SCHEDULE_OVERRIDE)
            else editor.putString(KEY_SCHEDULE_OVERRIDE, ApiJson.json.encodeToString(ScheduleOverride.serializer(), value))
            editor.commit()
        }

    /** Debug builds only: ISO date the schedule pretends it is; "" = the real date. */
    var debugToday: String
        get() = plain.getString(KEY_DEBUG_TODAY, "").orEmpty()
        set(value) { plain.edit().putString(KEY_DEBUG_TODAY, value).commit() }

    /** True when the schedule refers to [cycleId] (as an entry's cycle or as the default). */
    fun isCycleScheduled(cycleId: String): Boolean {
        val s = schedule
        return s.defaultCycleId == cycleId || s.entries.any { it.cycleId == cycleId }
    }

    /**
     * Opens the encrypted store, or returns null when it is temporarily unusable.
     *
     * Deleting the prefs file destroys the stored API key, so it is reserved for PROVEN
     * corruption ([isProvenCorruption]). Transient/unknown failures — Keystore not ready
     * before first unlock, keystore daemon hiccups under load — are retried once after a
     * short pause and otherwise surfaced as null so the caller can serve [memoryFallback]
     * for now and retry the real store later. Never crashes, never silently wipes.
     */
    private fun createSecurePrefsOrNull(ctx: Context): SharedPreferences? {
        val first = try {
            return buildSecurePrefs(ctx)
        } catch (t: Throwable) {
            t
        }
        if (isProvenCorruption(first)) return resetSecurePrefs(ctx, first)
        // Never sleep on the main thread: fall back immediately and let a later
        // (background or post-cooldown) access retry the real store.
        if (Looper.getMainLooper().isCurrentThread) {
            Logg.w(TAG, "Secure store unavailable on main thread — falling back without retry: $first")
            return null
        }
        Logg.w(TAG, "Secure store unavailable (transient Keystore error?); retrying once: $first")
        SystemClock.sleep(SECURE_RETRY_DELAY_MS)
        val second = try {
            return buildSecurePrefs(ctx)
        } catch (t: Throwable) {
            t
        }
        if (isProvenCorruption(second)) return resetSecurePrefs(ctx, second)
        Logg.e(TAG, "Secure store still unavailable — serving in-memory fallback (file kept)", second)
        return null
    }

    /**
     * Corrupted keyset or prefs file (e.g. restored from another device's backup): the
     * stored API key is unrecoverable, so reset the file — and flip isConfigured off so
     * the app re-onboards instead of silently sending empty credentials forever.
     */
    private fun resetSecurePrefs(ctx: Context, cause: Throwable): SharedPreferences? {
        Logg.e(TAG, "Encrypted prefs corrupted — resetting secure store", cause)
        ctx.deleteSharedPreferences(SECURE_PREFS)
        plain.edit().putBoolean(KEY_IS_CONFIGURED, false).commit()
        return try {
            buildSecurePrefs(ctx)
        } catch (t: Throwable) {
            Logg.e(TAG, "Secure store rebuild failed after reset", t)
            null
        }
    }

    /**
     * True only when the failure chain proves the on-disk keyset/prefs are unreadable
     * garbage (bad AEAD tag, unparseable keyset proto, permanently invalidated master
     * key). A transient Keystore marker anywhere in the chain vetoes corruption: the file
     * is likely fine, we just could not talk to the Keystore. Unknown errors are NOT
     * corruption — the default must never destroy the stored API key.
     */
    private fun isProvenCorruption(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (isTransientKeystoreError(cause)) return false
            cause = cause.cause
        }
        cause = t
        while (cause != null) {
            if (cause is AEADBadTagException) return true
            val name = cause.javaClass.name
            // Tink's (shaded) protobuf parse failure: the keyset file itself is garbage.
            if (name.endsWith(".InvalidProtocolBufferException")) return true
            // Master key gone for good (e.g. lock screen credentials wiped): unrecoverable.
            if (name == "android.security.keystore.KeyPermanentlyInvalidatedException") return true
            cause = cause.cause
        }
        return false
    }

    /**
     * Keystore-not-ready style failures (before first unlock, daemon restart, under
     * load). Matched by class name — android.security.KeyStoreException only became
     * public API in 33 and must not be referenced directly.
     */
    private fun isTransientKeystoreError(t: Throwable): Boolean {
        if (t is IllegalStateException) return true
        val name = t.javaClass.name
        return name == "android.security.KeyStoreException" ||
            name == "android.security.keystore.KeyStoreConnectException" ||
            name == "android.security.keystore.UserNotAuthenticatedException"
    }

    private fun buildSecurePrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Non-persistent [SharedPreferences] stand-in served while the Keystore is
     * unavailable (e.g. before first unlock on GrapheneOS). Reads behave like an empty
     * store; writes live in memory only and are lost on process death — a far better
     * failure mode than deleting the real encrypted store and the API key with it.
     */
    private class InMemoryPrefs : SharedPreferences {

        private val values = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(this) { HashMap(values) }

        override fun getString(key: String?, defValue: String?): String? =
            get(key) as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return get(key) as? MutableSet<String> ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = get(key) as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = get(key) as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = get(key) as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = get(key) as? Boolean ?: defValue

        override fun contains(key: String?): Boolean =
            key != null && synchronized(this) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        // Nothing registers listeners on the secure store; the fallback does not dispatch them.
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private fun get(key: String?): Any? =
            if (key == null) null else synchronized(this) { values[key] }

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) =
                put(key, values?.toMutableSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, REMOVE)

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                synchronized(this@InMemoryPrefs) {
                    if (clearAll) values.clear()
                    for ((k, v) in pending) {
                        if (v === REMOVE) values.remove(k) else values[k] = v
                    }
                }
                clearAll = false
                pending.clear()
                return true
            }

            override fun apply() {
                commit()
            }

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
        }

        private companion object {
            /** Sentinel marking a pending [SharedPreferences.Editor.remove]. */
            val REMOVE = Any()
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"

        private const val PLAIN_PREFS = "immichwall_settings"
        private const val SECURE_PREFS = "immichwall_secure"

        /** Pause before the single retry of a transiently failing secure-store open. */
        private const val SECURE_RETRY_DELAY_MS = 150L

        /** After a failed open, serve the fallback without retrying for this long. */
        private const val SECURE_FAILURE_COOLDOWN_MS = 5_000L

        private const val KEY_SERVER_URL = "serverUrl"
        private const val KEY_AWAY_URL = "awayUrl"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_SOURCE_SPEC = "sourceSpec"
        private const val KEY_TARGET_CACHE_COUNT = "targetCacheCount"
        private const val KEY_REFRESH_INTERVAL_HOURS = "refreshIntervalHours"
        private const val KEY_DERIVE_THEME = "deriveThemeFromPhoto"
        private const val KEY_SYNC_OVER_CELLULAR = "syncOverCellular"
        private const val KEY_QUALITY_FILTER = "qualityFilterEnabled"
        private const val KEY_PEOPLE_PREFERENCE = "peoplePreference"
        const val PEOPLE_PREF_OFF = "off"
        const val PEOPLE_PREF_PREFER = "prefer"
        const val PEOPLE_PREF_REQUIRE = "require"
        private const val KEY_ROTATION_MIN_INTERVAL = "rotationMinIntervalMinutes"
        private const val KEY_LAST_ADVANCE_AT = "lastAdvanceAt"
        private const val KEY_SAVED_CYCLES = "savedCycles"
        private const val KEY_ACTIVE_CYCLE_ID = "activeCycleId"
        private const val KEY_IS_CONFIGURED = "isConfigured"
        private const val KEY_CROP_WIDTH = "cropWidth"
        private const val KEY_CROP_HEIGHT = "cropHeight"
        private const val KEY_SEEN_SURFACES = "seenSurfaces"
        private const val KEY_LAST_GOOD_BASE_URL = "lastGoodBaseUrl"
        private const val KEY_SCHEDULE = "schedule"
        private const val KEY_SCHEDULE_OVERRIDE = "scheduleOverride"
        private const val KEY_DEBUG_TODAY = "debugToday"
        private const val KEY_SOURCE_SIZE_PREFIX = "sourceSize|"

        const val DEFAULT_TARGET_CACHE_COUNT = 150
        const val DEFAULT_REFRESH_INTERVAL_HOURS = OptionChoices.DEFAULT_REFRESH_HOURS

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(ctx: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(ctx.applicationContext).also { instance = it }
            }
    }
}
