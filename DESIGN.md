# immich-wallpaper — Design (v1.1, 2026-07-16)

> **Fork note:** the ["Fork additions (v1.1)"](#fork-additions-v11) section at the end of this document supersedes the cache layout, rotation and signatures described here for upstream v1.0.

Android live-wallpaper app: every screen-on shows a **new** photo on **both** lock and home screen, drawn from a configurable Immich **source** — person(s), album, CLIP smart-search query, location, favorites, memories (on-this-day), or the whole library. Like iOS photo-shuffle, but self-hosted and far more capable.

Architecture selected by a 3-lens adversarial design review (reliability 8–4, UX/battery 9–5, simplicity 8–4 in favor of the live-wallpaper approach over a WallpaperManager setter service). Judge-mandated fixes are folded in below and marked **[FIX]**.

## Ground truth (verified live 2026-07-16)
- Development server: a self-hosted LAN Immich, **v3.0.3**. Auth: `x-api-key` header.
- Test person: id `<uuid>`, ~1,600 assets (do NOT hardcode; wizard picks person).
- `POST /api/search/random` body `{"personIds":["<id>"],"size":N,"type":"IMAGE"}` → **bare JSON array** of assets (fields: `id,type,width,height,thumbhash,originalFileName,fileCreatedAt,isFavorite,visibility,…`). `personIds` is AND-semantics across people — we only ever pass one.
- `GET /api/faces?id=<assetId>` → array of `{id, boundingBoxX1,Y1,X2,Y2, imageWidth, imageHeight, person:{id,name,…}|null, sourceType}`. Box coords are pixels in `imageWidth×imageHeight` space (the ML-input size, NOT necessarily the original size — always scale box → target bitmap space by ratio).
- `GET /api/assets/{id}/thumbnail?size=fullsize|preview|thumbnail` → JPEG/WebP. `fullsize` may 404/400 depending on server config → **fallback to `preview`** (1440px long edge, ~2× upscale on the 2856px panel — acceptable).
- `GET /api/people?page=&size=&withHidden=false` → `{people:[…], total, hidden, hasNextPage}`. Page until `!hasNextPage` (size=500).
- `GET /api/people/{id}/thumbnail` → image bytes.
- `GET /api/server/version` → `{major,minor,patch}` — **no auth required** (reachability probe). Credential check = any authed endpoint.
- **Source-mode contracts (all verified live 2026-07-16):**
  - `POST /api/search/smart` `{"query":"sunset over water","size":N,...}` → `{albums, assets:{total,count,items,facets,nextPage}}`. CLIP semantic search; composable with `personIds` (verified). Results are relevance-ranked → fetch top ~200 and shuffle client-side for variety.
  - `POST /api/search/metadata` `{...filters, size, page}` → same `{albums, assets:{items,…,nextPage}}` shape. Filters incl. `albumIds`, `city`, `personIds`, `takenAfter/Before`, `isFavorite` (verified `albumIds` works — this is the v3-correct way to get album assets).
  - `GET /api/search/cities` → array of representative assets, one per city (235 on this server) — powers the location picker.
  - `GET /api/memories?for=YYYY-MM-DD` (**bare date — ISO datetime is rejected with 400**) → array of `{type:"on_this_day", data:{year}, assets:[…]}` (verified: 5 memories, 25 assets today).
  - `GET /api/albums` → array with `id, albumName, assetCount` (23 on this server).
  - `POST /api/search/random` also accepts filter fields (`isFavorite` verified; implementation agent must verify `albumIds`/`city` presence in RandomSearchDto against the local spec and fall back to metadata-search+shuffle where absent).
- v3 breaking traps: album `assets` removed (use search/metadata `albumIds`); `GET /assets/random` removed (use search/random); asset `people` no longer carries faces (use `/faces`); `exifInfo` not present in search responses (top-level `width`/`height` are).

## Target & constraints
Pixel 10 Pro, GrapheneOS (Android 16), installed in a **secondary user profile**. No Play Services (WorkManager runs on platform JobScheduler — fine). FOSS deps only. minSdk 34, compileSdk 35, targetSdk 35. Package **`dev.immichwall`**. Single `:app` module, Kotlin, AGP 8.7.3, Kotlin 2.1.0 + kotlinx-serialization, OkHttp 4.12.0, androidx.work 2.10.0, androidx.exifinterface, Material Components (XML views, no Compose), androidx.security-crypto for the API key. JAVA_HOME=/opt/android-studio/jbr.

## Core mechanism (the part that must be exactly right)
A `WallpaperService` (`PhotoWallpaperService`) bound by system_server serves **one engine** for both home and lock (Android 14+ lock-capable). It draws exactly one **pre-cropped, panel-sized JPEG** from local cache. The photo **advances while the screen is dark**, so wake reveals an already-drawn new photo: zero jank, no FGS, no notification, no BOOT_COMPLETED — the OS rebinds the wallpaper at every profile start.

**[FIX] Trigger inversion (judge finding):** `onVisibilityChanged(false)` does NOT fire at screen-off when an app occludes the launcher (engine already invisible). Therefore the **primary trigger is a runtime-registered `ACTION_SCREEN_OFF` receiver** living in the wallpaper process (legal: this process is bound at visible priority, never cache-frozen while the wallpaper is set). `onVisibilityChanged(false)` + `PowerManager.isInteractive()==false` is the secondary path. Both funnel into one debounced, idempotent `advance()`.

**Advance state machine** (in `RotationController`, thread-confined to a dedicated `HandlerThread("wallpaper-render")`):
1. Trigger arrives (screen-off broadcast or visibility-loss) → if an advance for this off-cycle already ran, ignore (debounce keyed on screen-on resetting the latch).
2. **[FIX]** Gate: if `UserManager.isUserForeground()` is false (Owner/Work profile active), skip — don't burn photos on other profiles' screen events. (Guard with API-level check; if unavailable, proceed.)
3. **[FIX]** Acquire `PARTIAL_WAKE_LOCK` with 3s timeout (`WAKE_LOCK` permission) — CPU may otherwise suspend mid-decode.
4. **[FIX]** 300ms settle delay, **cancelled by `ACTION_SCREEN_ON`** — guards double-tap-to-wake races and stops 2-second AOD glances from burning queued photos.
5. `PhotoCacheManager.advanceCursor()` → next entry; `BitmapPipeline.decodeReady()`; on corrupt file: delete entry, advance again (max 3 attempts, then keep current bitmap).
6. Swap `currentBitmap`, draw to surface if surface valid (even if invisible — cheap, keeps surface warm), release wakelock.
7. **[FIX]** Cursor persists to a **tiny sidecar file** (`cursor.txt`), NOT by rewriting manifest.json per screen-off.

**WallpaperColors:** `StablePaletteProvider` returns a fixed palette by default so Material You doesn't re-theme the whole UI on every wake (`onComputeColors`); optional setting derives from current photo (accepting theme churn) — derived once per photo from a ~96px copy, cached by bitmap identity (Muzei's approach), never recomputed per binder ask.

**Visible changes crossfade (v1.2):** screen-off advances stay instant hard swaps (nothing is watching), but a photo change the user can see — initial fill landing, source switch (`jumpToNewest` targets the new source's freshest photo) — runs as a 700ms cosine crossfade on the render thread (approach modeled on Muzei, Apache-2.0). Study of Muzei/Paperize/WallFlow/Peristyle/WallYou (2026-07-17) validated the screen-off-swap core (Paperize does the same) and found no AOD or picker techniques we lack; adoptable leftovers: BitmapRegionDecoder ingest (memory), TFLite EfficientDet-Lite0 crop for face-less sources (WallFlow pattern, re-implement).

**Surface sizing:** the engine's `onSurfaceChanged` dimensions are the source of truth for crop size (persisted; a mismatch marks cache entries stale for re-crop on next refresh). **[FIX]** Never use `WindowManager.getCurrentWindowMetrics()` from service context.

## Cache
`filesDir/wallpaper-cache/` (credential-encrypted app storage — never `cacheDir`; photos of a child stay profile-private):
```
manifest.json      (atomic tmp+rename; written by refresh worker only)
cursor.txt         (single int, written per advance)
staging/           (downloads + crops in progress)
ready/<assetId>.jpg  (finished wallpapers: face-cropped, upright, exactly panel-sized, JPEG q90, ~0.8–1.5MB)
```
- Every ready file is a **finished wallpaper** — wake path does decode only, never crop/scale.
- Default `targetCacheCount=150` (~150–220MB), user 50–300.
- **[STOLEN from design B]** staging → **verification re-decode** → fsync → atomic rename into `ready/` (corrupt downloads die at ingest, not at wake).
- **[STOLEN]** Eviction hard floor: never drop below **20 ready photos** unless directly replaced.
- Refill selection: `AssetSourceResolver.candidates(spec, need×2)`, filter out assetIds already in manifest, take `need`. Random-with-dedup gives variety without exhaustion tracking.
- **Quality scoring (v1.2):** `PhotoScorer` gates refills when `qualityFilterEnabled` (default on). Pre-download (per candidate: 1 asset-detail GET + the faces call the crop needs anyway): hard-reject short edge <1000px and missing camera EXIF (screenshot/meme signal); score portrait aspect, favorite/rating, aperture ≤f/2, high-ISO penalty, front-cam penalty, face-size band 6–55%, crop-clips-faces penalty. Post-prepare on the panel bitmap (~1ms at 128px): reject hopeless blur (Laplacian variance) and too-dark/blown exposure. Deferred candidates re-ingest in a relaxation pass (best score first) whenever a small pool (Favorites/Memories) would otherwise starve — the cache always fills.
- **Rotation is least-recently-shown (v1.2),** not a fixed ring: `CacheEntry.lastShownAt/shownCount`; advance = peek LRU entry → decode → re-check screen still off → commit (cursor.txt + `shown.log` append; marks fold into manifest.json at the next worker persist, replayed at load after process death). Eviction is most-shown-first. No photo repeats until everything cached has had its turn.
- **Download quality ladder (v1.2):** `/original` → `fullsize` → `preview` — this server 302-redirects `fullsize` to the 1440px preview, so originals (full-res HEIC/JPEG, decoded natively, downscaled into the crop) are the only sharp source; `CacheEntry.sourceSize` records the tier that actually served.
- `manifest.sourceKey` = stable hash of the active `SourceSpec` (+ current date for Memories). Key mismatch at refresh time ⇒ full re-sync (source changed or memories date rolled over); ready photos from the old source are drained gradually (evicted as new ones arrive) so the wallpaper never goes blank during a source switch.

## Source layer (the generalization)
```kotlin
@Serializable sealed class SourceSpec {          // persisted as JSON in prefs
  data class People(val ids: List<String>, val names: List<String>, val requireAll: Boolean) : SourceSpec()
  data class Album(val albumId: String, val albumName: String) : SourceSpec()
  data class SmartQuery(val query: String, val personIds: List<String> = emptyList()) : SourceSpec()
  data class Location(val city: String) : SourceSpec()
  object Favorites : SourceSpec()
  object Memories : SourceSpec()                 // on-this-day; date-sensitive
  object EverythingRandom : SourceSpec()
}
```
`AssetSourceResolver.candidates(spec, n): List<AssetDto>` maps each spec to endpoints:
- **People, requireAll=false (ANY)**: one `search/random {personIds:[id], size:n/k}` per person, merge + dedup (v3 `personIds` is AND-semantics — the sibling project learned this the hard way). **requireAll=true (ALL)**: single call with all ids.
- **Album**: `search/random {albumIds:[id]}` if the DTO supports it, else `search/metadata {albumIds}` paged + client shuffle.
- **SmartQuery**: `search/smart {query, personIds?}` top ~200 → client shuffle.
- **Location**: `search/random {city}` if supported, else metadata+shuffle.
- **Favorites**: `search/random {isFavorite:true}`. **EverythingRandom**: `search/random {}`.
- **Memories**: `GET /memories?for=<today>` → flatten all years' assets (typically small; supplement: if <15 assets, widen ±3 days).
All modes filter `type=="IMAGE"` and dedup against manifest.

## Refresh (WorkManager, no GMS)
- Unique periodic `cache-refresh` every 6h (user: 3/6/12/24), constraints CONNECTED + battery-not-low.
- Steps: pick base URL (`BaseUrlSelector`: primary LAN URL, 3s connect timeout, fallback to optional Tailscale URL, remember last-good) → `searchRandom` → for each new asset: download `fullsize` (fallback `preview`) to staging → `getFaces(assetId)` → `FaceCropCalculator` → `BitmapPipeline.prepareWallpaper` → promote → `evictToTarget` → manifest update (lastSyncAt/result) → `HealthChecker.check()`.
- **[FIX]** Initial fill: expedited one-shot for the first **15** photos (instant gratification, inside expedited quota), then a regular chained worker tops up to target.
- **[STOLEN]** Health worker duties ride along in every refresh: wallpaper component still set? periodic work still scheduled (re-enqueue if lost)? cache above floor? Surface problems via the status screen + a `health` notification channel (only for "wallpaper was replaced/cleared" and "cache empty" — actionable states).
- Failure policy: any network/API failure → keep serving cache, exponential backoff retry; cache never emptied by failures.

## Face-centered cropping (all source modes)
`FaceCropCalculator.cropRect(faces, priorityPersonIds, srcW, srcH, targetAspect) -> Rect`:
- Filter boxes to priority people (People-mode ids / SmartQuery personIds) when present; fallback: union of ALL detected faces (verified `/faces` works per-asset regardless of how the asset was found); fallback: center crop.
- Union multiple boxes; add headroom (extend top by 35% of box height); expand to target aspect around the union center; clamp to image; scale coords from `imageWidth×imageHeight` (face space) to actual bitmap space.
`BitmapPipeline` (port from sibling `ImmichDreamService` L119–162): EXIF-rotate → `inJustDecodeBounds` probe → power-of-2 sample ≥ target → decode → crop → bilinear scale to exact panel px → JPEG q90 tmp+rename. `decodeReady()` returns null on corruption.

## Settings / onboarding (single `MainActivity`, XML fragments)
Wizard: **Welcome** (privacy note: photos cached only in this profile's encrypted storage) → **Server** (URL + optional away-URL + API key; validate: version probe, then **[STOLEN]** per-scope probes — people list, one asset thumbnail, one faces call — naming the exact missing API-key scope on 403) → **SourcePicker**: mode list (People / Album / Smart search / Location / Favorites / Memories / Everything) → per-mode picker:
- *People*: thumbnail grid (`people/{id}/thumbnail`, pages size=500 until `!hasNextPage`, search filter), multi-select + ANY/ALL toggle when ≥2 selected.
- *Album*: list with names + asset counts. *Location*: city list from `/search/cities` with representative thumbnails. *Smart search*: free-text query + optional person filter. *Favorites/Memories/Everything*: no picker.
- Every picker ends with a **live preview grid** (12 samples via `AssetSourceResolver`) so the user sees what the wallpaper will draw from before committing.
→ **Options** (cache count, refresh interval, stable-vs-photo theme) → **Apply** (kick initial fill; then **[FIX]** intent ladder: `ACTION_CHANGE_LIVE_WALLPAPER` in try/catch → `ACTION_LIVE_WALLPAPER_CHOOSER` → manual instructions dialog — GrapheneOS has no Google wallpaper picker and the secondary-profile picker may be minimal).
Steady state: **Status screen** — current photo thumb, cache stats, last sync, active source summary, buttons (refresh now / change source / re-apply wallpaper / battery-optimization exemption hint).

## Failure modes
| Case | Behavior |
|---|---|
| Server unreachable (away, LAN down) | Serve cache indefinitely; retry per schedule; status shows staleness |
| Force-stop of app | Wallpaper cleared by system → health notification can't fire (WorkManager also dead); status banner on next app open explains re-apply. Accepted limitation. |
| Reboot / profile restart | system_server rebinds wallpaper; WorkManager persists. Nothing to do. **Top risk:** GrapheneOS secondary-profile live-wallpaper persistence — spike-test first (Phase 0). |
| Cache corrupt entry | Deleted + skipped at advance; re-verified at ingest |
| Panel size change (OS update) | onSurfaceChanged mismatch → mark stale → re-crop on next refresh; meanwhile letterbox-scale |
| Person has no new photos | Random re-serves old ones; fine |

## File plan (~28 files)
```
app/src/main/java/dev/immichwall/
  App.kt
  api/ImmichModels.kt  api/ImmichApiClient.kt  api/BaseUrlSelector.kt  api/ConnectionValidator.kt
  source/SourceSpec.kt  source/AssetSourceResolver.kt
  cache/CacheManifest.kt  cache/PhotoCacheManager.kt
  crop/FaceCropCalculator.kt  crop/BitmapPipeline.kt
  sync/CacheRefreshWorker.kt  sync/InitialFillWorker.kt  sync/SyncScheduler.kt
  wallpaper/PhotoWallpaperService.kt  wallpaper/RotationController.kt
  wallpaper/ScreenOffReceiver.kt  wallpaper/StablePaletteProvider.kt
  settings/SettingsRepository.kt
  util/HealthChecker.kt  util/Logg.kt
  ui/MainActivity.kt  ui/StatusFragment.kt
  ui/onboarding/{WelcomeFragment,ServerSetupFragment,SourcePickerFragment,PeoplePickerFragment,
                 AlbumPickerFragment,LocationPickerFragment,SmartQueryFragment,SourcePreviewFragment,
                 OptionsFragment,ApplyFragment}.kt
  ui/PersonPickerAdapter.kt  ui/PreviewGridAdapter.kt
```
Contracts (exact signatures) live in `CONTRACTS.kt` notes inside each agent brief; integration owner resolves drift.

## Phase 0 spike (before polishing anything)
Minimal APK: hardcoded-photo engine + screen-off advance → sideload → verify on the Pixel: (a) lock+home both render engine, (b) advance at wake works from inside-app screen-off, (c) survives reboot in secondary profile, (d) AOD interaction. This de-risks the one open platform question before the full build.

## Fork additions (v1.1)

- **Module split.** Pure logic lives in `:core` (plain Kotlin/JVM, unit tested): the Immich
  API client and models, `schedule/`, `cache/CachePolicy`, `crop/CropTarget`. `:app` is the
  Android shell around it.
- **Schedule.** `ScheduleResolver` maps a date to a cycle (ordered `MM-DD` ranges, inclusive,
  first match wins, year wrap, default). `SchedulePlan` adds the manual override and the
  retention window (today plus two days). `ScheduleApplier` turns the answer into
  `activateCycle`; it runs at the first screen-off and the first screen-on of each day,
  before the first load after a restart, at the start of every sync, when the app is
  opened, and on every edit. No alarms.
- **Cache partitioned by cycle.** An entry is identified by cycle key and asset id; files
  live in `ready/<key prefix>/`. Rotation draws only from the active cycle (while that cycle
  is empty, from the cycle already on screen, never from one that is only prefetched). Each
  sync fills the active cycle and prefetches the ones in the retention window, then deletes
  photos of cycles outside it once the active cycle has a photo.
- **Foldables.** Photos are cropped to the union box of every surface shape the engine has
  seen, and each cache entry records where its faces are; the engine slides the photo so
  they stay in view on whichever panel is active.
- **Transport.** HTTPS only; redirects are followed only within the same origin; downloads
  and JSON bodies are size-capped; asset ids must be UUIDs. Sync runs are serialized and
  stop when WorkManager replaces them.
- **Backup.** `:core` `dev.immichwall.backup`: one JSON file (marker, `format` 1, cycles,
  active cycle, schedule, six options, optional server block). The server block (address,
  away address, API key; unencrypted) is opt-in at export, off by default. Never in the file:
  photos, the manual schedule override, debug date, crop sizes, sync bookkeeping. Decode never
  throws: a non-backup, a file over 1 MB or not UTF-8 is refused, a higher `format` is refused
  as newer, and nothing changes. Restored options are coerced to what the options screen
  offers; the server block is applied only if it validates, and then when the user ticks it
  on the settings screen or, without asking further, by the restore on the first-run
  wizard's server screen (which then runs the connection test); the restore on the wizard's
  source step never applies it. Cycles, schedule and options are applied in one commit under
  the cycles lock.
- **Low-cache warning.** `CachePolicy.warnsLowCache` stays quiet when the last good sync
  proved the source holds fewer photos than the floor (`knownSourceSize`; unknown for
  any-of-people and smart-search sources, which fall back to warning).
