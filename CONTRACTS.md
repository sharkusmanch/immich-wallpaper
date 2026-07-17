# Cross-module contracts (v1.1) — implement EXACTLY these public signatures

All code: package `dev.immichwall.*`, Kotlin, no dependencies beyond: kotlinx-serialization-json, okhttp 4.12.0, androidx.core/appcompat/fragment/recyclerview/work-runtime-ktx 2.10.0/exifinterface/security-crypto, material, kotlinx-coroutines-android. JSON: single shared `Json { ignoreUnknownKeys = true; encodeDefaults = true }` exposed as `dev.immichwall.api.ApiJson.json`.

```kotlin
// ===== api/ImmichModels.kt (owner: api agent) =====
@Serializable data class ServerVersionDto(val major: Int, val minor: Int, val patch: Int)
@Serializable data class PersonDto(val id: String, val name: String = "", val thumbnailPath: String? = null, val isHidden: Boolean = false, val birthDate: String? = null)
@Serializable data class PeopleResponseDto(val people: List<PersonDto>, val total: Int = 0, val hidden: Int = 0, val hasNextPage: Boolean = false)
@Serializable data class AssetDto(val id: String, val type: String, val width: Int? = null, val height: Int? = null, val thumbhash: String? = null, val originalFileName: String? = null, val fileCreatedAt: String? = null, val isFavorite: Boolean = false, val exifInfo: ExifLiteDto? = null)
@Serializable data class ExifLiteDto(val city: String? = null, val country: String? = null)
@Serializable data class AssetFaceDto(val id: String, val boundingBoxX1: Int, val boundingBoxY1: Int, val boundingBoxX2: Int, val boundingBoxY2: Int, val imageWidth: Int, val imageHeight: Int, val person: PersonDto? = null, val sourceType: String? = null)
@Serializable data class AlbumDto(val id: String, val albumName: String, val assetCount: Int = 0)
@Serializable data class SearchAssetsPage(val items: List<AssetDto> = emptyList(), val total: Int = 0, val count: Int = 0, val nextPage: String? = null)
@Serializable data class SearchResponseDto(val assets: SearchAssetsPage = SearchAssetsPage())
@Serializable data class MemoryDto(val id: String = "", val type: String = "", val assets: List<AssetDto> = emptyList())
class ApiException(val code: Int, message: String, val scopeHint: String? = null) : Exception(message)

// ===== api/ImmichApiClient.kt (api agent) — synchronous; non-2xx → ApiException; transport → IOException
class ImmichApiClient(private val baseUrl: () -> String, private val apiKey: () -> String) {
    fun getServerVersion(): ServerVersionDto                    // GET /api/server/version, NO auth header needed
    fun getPeople(page: Int, size: Int = 500, withHidden: Boolean = false): PeopleResponseDto
    fun getPersonThumbnail(personId: String): ByteArray
    fun getAlbums(): List<AlbumDto>
    fun getCities(): List<AssetDto>                             // GET /api/search/cities (one representative asset per city; city in exifInfo.city)
    fun getMemories(forDate: String): List<MemoryDto>           // GET /api/memories?for=YYYY-MM-DD (bare date!)
    fun searchRandom(filters: JsonObject, size: Int): List<AssetDto>          // POST /api/search/random → BARE ARRAY
    fun searchMetadata(filters: JsonObject, page: Int, size: Int): SearchResponseDto
    fun searchSmart(query: String, personIds: List<String>, size: Int): SearchResponseDto
    fun getFaces(assetId: String): List<AssetFaceDto>           // GET /api/faces?id=
    fun downloadAssetImage(assetId: String, dest: File): DownloadedImage // quality ladder original → fullsize → preview (fallback on 400/403/404); atomic tmp+rename; returns Content-Type + tier served
    data class DownloadedImage(val contentType: String, val tier: String)   // tier: "original" | "fullsize" | "preview"
    fun downloadAssetThumbnail(assetId: String, dest: File): String // grid-sized: thumbnail?size=thumbnail → fallback preview; returns Content-Type
}

// ===== api/BaseUrlSelector.kt (api agent) =====
class BaseUrlSelector(private val settings: SettingsRepository) {
    fun currentBaseUrl(): String            // last-good, else primary
    fun probeAndSelect(): String?           // 3s-timeout probe primary → away; persists lastGoodBaseUrl; null if both dead
}
// ===== api/ConnectionValidator.kt (api agent) =====
data class CheckResult(val name: String, val ok: Boolean, val detail: String)
class ConnectionValidator(private val client: ImmichApiClient) { fun validate(): List<CheckResult> } // version, people-list, thumbnail-download, faces — name missing API-key scope on 403

// ===== source/SourceSpec.kt (source agent) — @Serializable sealed class, classDiscriminator "mode"
@Serializable sealed class SourceSpec {
    @Serializable @SerialName("people") data class People(val ids: List<String>, val names: List<String>, val requireAll: Boolean = false) : SourceSpec()
    @Serializable @SerialName("album") data class Album(val albumId: String, val albumName: String) : SourceSpec()
    @Serializable @SerialName("smart") data class SmartQuery(val query: String, val personIds: List<String> = emptyList(), val personNames: List<String> = emptyList()) : SourceSpec()
    @Serializable @SerialName("location") data class Location(val city: String) : SourceSpec()
    @Serializable @SerialName("favorites") data object Favorites : SourceSpec()
    @Serializable @SerialName("memories") data class Memories(val windowDays: Int = 3, val yearsAgoMin: Int = 0, val yearsAgoMax: Int = 0) : SourceSpec() // ±days window; years-ago bounds (0 = unbounded); legacy {"mode":"memories"} decodes to defaults
    @Serializable @SerialName("everything") data object EverythingRandom : SourceSpec()
    @Serializable @SerialName("custom") data class Custom(val personIds: List<String> = emptyList(), val personNames: List<String> = emptyList(), val requireAll: Boolean = false, val albumId: String = "", val albumName: String = "", val query: String = "", val city: String = "", val favoritesOnly: Boolean = false, val takenAfter: String = "", val takenBefore: String = "") : SourceSpec()
    // Custom resolver: no query → search/random (metadata fallback) with combined filters, per-person merge for ANY-of people;
    // with query → search/smart + supported filters; album constraint intersected client-side (SmartSearchDto lacks albumIds).
    // SavedCycle(id, name, spec, peoplePreference="prefer") — peoplePreference is per-cycle ("off"|"prefer"|"require"), set in the preview step / cycle detail page.
    fun stableKey(today: String): String    // hash of serialized form; Memories additionally keyed by `today`
    fun priorityPersonIds(): List<String>   // People.ids / SmartQuery.personIds / else empty
    fun summaryLabel(): String              // human-readable for status screen
}
// ===== source/AssetSourceResolver.kt (source agent) =====
class AssetSourceResolver(private val client: ImmichApiClient) {
    fun candidates(spec: SourceSpec, n: Int): List<AssetDto>    // per DESIGN.md mode mapping; IMAGE-only; deduped; shuffled
}

// ===== cache/CacheManifest.kt (cache agent) =====
@Serializable data class CacheEntry(val assetId: String, val fileName: String, val addedAt: Long, val width: Int, val height: Int, val sourceSize: String = "fullsize", val sourceKey: String = "")
// sourceSize records the tier that ACTUALLY served ("original"|"fullsize"|"preview"); sourceKey enables per-entry staleness (""+dims-match migrates from manifest.sourceKey at load)
@Serializable data class CacheManifest(val schemaVersion: Int = 1, val sourceKey: String = "", val cropWidth: Int = 0, val cropHeight: Int = 0, val entries: List<CacheEntry> = emptyList(), val lastSyncAt: Long = 0, val lastSyncResult: String = "")
// ===== cache/PhotoCacheManager.kt (cache agent) — thread-safe (internal lock), process singleton
class PhotoCacheManager private constructor(private val ctx: Context) {
    companion object { fun get(ctx: Context): PhotoCacheManager; const val HARD_FLOOR = 20 }
    fun manifest(): CacheManifest
    fun updateManifest(mutator: (CacheManifest) -> CacheManifest)   // atomic tmp+rename persist
    fun stagingDir(): File; fun readyDir(): File
    fun readyFile(entry: CacheEntry): File
    fun currentEntry(): CacheEntry?          // entry at cursor, null if empty
    fun peekNextShown(): CacheEntry?         // LRU candidate (least-recently-shown, excl. current); pure read
    fun commitShown(assetId: String)         // mark shown + cursor -> entry; persists cursor.txt + shown.log ONLY
    fun jumpToNewest(): CacheEntry?          // cursor -> max(addedAt) entry, marked shown (post-source-change fade target)
    fun entryFor(assetId: String): CacheEntry?
    fun promote(entry: CacheEntry, staged: File): Boolean  // verifyDecodable → fsync → rename into ready/ → manifest append
    fun removeEntry(assetId: String)         // manifest remove + file delete + cursor clamp
    fun evictToTarget(target: Int)           // oldest-first; never below max(HARD_FLOOR, ...) unless replaced
    fun containsAsset(assetId: String): Boolean
    fun readyCount(): Int
}

// ===== crop/FaceCropCalculator.kt (cache agent) =====
object FaceCropCalculator {
    fun cropRect(faces: List<AssetFaceDto>, priorityPersonIds: List<String>, srcW: Int, srcH: Int, targetAspectWOverH: Float): android.graphics.Rect
}
// ===== crop/BitmapPipeline.kt (cache agent) =====
object BitmapPipeline {
    fun prepareWallpaper(src: File, faces: List<AssetFaceDto>, priorityPersonIds: List<String>, outW: Int, outH: Int, dest: File): Boolean
    fun decodeReady(file: File): android.graphics.Bitmap?
    fun verifyDecodable(file: File): Boolean
}

// ===== sync/ (source agent) =====
object SyncScheduler {
    fun ensurePeriodic(ctx: Context)         // unique periodic cache-refresh, KEEP
    fun kickInitialFill(ctx: Context)        // expedited one-shot (15 photos) + chained top-up
    fun kickManualRefresh(ctx: Context)
}
class InitialFillWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker
class CacheRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker
class RefreshEngine(private val ctx: Context) { fun refresh(maxNew: Int): String }  // shared logic; in CacheRefreshWorker.kt

// ===== settings/SettingsRepository.kt (ui agent) — singleton; API key in EncryptedSharedPreferences, rest plain
class SettingsRepository private constructor(ctx: Context) {
    companion object { fun get(ctx: Context): SettingsRepository }
    var serverUrl: String; var awayUrl: String; var apiKey: String
    var sourceSpec: SourceSpec?              // via ApiJson.json
    var targetCacheCount: Int                // default 150 (50..300)
    var refreshIntervalHours: Int            // default 6 (3/6/12/24)
    var deriveThemeFromPhoto: Boolean        // default false
    var isConfigured: Boolean                // wizard completed
    var cropWidth: Int; var cropHeight: Int  // engine-reported; 0 = unknown
    var lastGoodBaseUrl: String
}

// ===== wallpaper/ (wallpaper agent) =====
object RotationController {
    fun ensureLoaded(ctx: Context)                       // load cursor entry into currentBitmap (no advance)
    fun currentBitmap(): android.graphics.Bitmap?
    fun awaitInitialLoad(ctx: Context, timeoutMs: Long): Boolean // bounded sync wait for first decode (first-frame path)
    fun onScreenOff(ctx: Context)                        // PRIMARY: wakelock(3s) → 300ms settle → advance+decode+redraw
    fun onScreenOn()                                     // cancels settle, resets per-off-cycle latch
    fun onVisibilityLost(ctx: Context)                   // secondary: only acts if !PowerManager.isInteractive
    fun addRedrawListener(cb: () -> Unit)                // one per engine (real + preview); invoked on render thread
    fun removeRedrawListener(cb: () -> Unit)
    fun refreshFromCacheHead(ctx: Context)               // after fill/source-change
}
class PhotoWallpaperService : WallpaperService            // engine: full-bleed draw of currentBitmap ?: branded placeholder; onSurfaceChanged persists cropWidth/Height via SettingsRepository; process-refcounted ScreenOffReceiver register/unregister; onComputeColors via StablePaletteProvider; UserManager.isUserForeground() gate lives in RotationController
class ScreenOffReceiver : BroadcastReceiver               // SCREEN_OFF→onScreenOff, SCREEN_ON→onScreenOn
object StablePaletteProvider { fun colors(settings: SettingsRepository, bitmap: android.graphics.Bitmap?): android.app.WallpaperColors }
// util/HealthChecker.kt (wallpaper agent)
data class HealthIssue(val id: String, val severity: Int, val message: String)
object HealthChecker { fun check(ctx: Context): List<HealthIssue> }
// util/Logg.kt (scaffold agent): object Logg { fun d(tag: String, msg: String); fun w(...); fun e(..., t: Throwable? = null) }
```

Manifest declarations (scaffold agent owns AndroidManifest.xml; names above are final): `MainActivity` (launcher, exported), `PhotoWallpaperService` (exported, `android.permission.BIND_WALLPAPER`, intent-filter `android.service.wallpaper.WallpaperService`, meta-data `android.service.wallpaper` → `@xml/photo_wallpaper`). Permissions: INTERNET, WAKE_LOCK, POST_NOTIFICATIONS. Debug prefill: BuildConfig fields `DEV_SERVER_URL`/`DEV_API_KEY` read from local.properties keys `immich.server.url`/`immich.api.key` (empty strings in release).

Threading: RotationController owns `HandlerThread("wallpaper-render")`; all its mutations confined there. PhotoCacheManager internal-locked, callable anywhere. Workers use Dispatchers.IO. UI never calls network on main (coroutines `lifecycleScope` + `Dispatchers.IO`).
