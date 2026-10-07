<p align="center">
  <img src="docs/icon.png" width="128" alt="Immich Wallpaper icon">
</p>

<h1 align="center">Immich Wallpaper</h1>

<p align="center">
  <img alt="Android 14+" src="https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Immich v3.0+" src="https://img.shields.io/badge/Immich-v3.0%2B-4250AF?logo=immich&logoColor=white">
  <img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-blue">
</p>

> [!NOTE]
> This is a fork of [SONDLecT/immich-wallpaper](https://github.com/SONDLecT/immich-wallpaper).
> It adds a date-of-year **schedule** that switches cycles automatically, keeps each
> cycle's photos apart so a switch is clean, crops for foldables, and accepts HTTPS
> servers only. It installs beside the original (application ID `io.github.sharkusmanch.immichwall`).

An Android **live wallpaper** for self-hosted photo libraries: pick people, albums,
searches or date ranges from your library, and your lock and home screen show a
different photo every time you wake the phone.

> [!IMPORTANT]
> This app is a companion for **[Immich](https://github.com/immich-app/immich)** — the
> wonderful self-hosted photo and video management platform. It requires a running
> Immich server; if you aren't self-hosting your photos yet,
> [start there](https://immich.app). Your library will thank you.

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fadd%2Fhttps%3A%2F%2Fgithub.com%2Fsharkusmanch%2Fimmich-wallpaper"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="56"></a>
  <a href="https://github.com/sharkusmanch/immich-wallpaper/releases/latest"><img src="https://img.shields.io/github/v/release/sharkusmanch/immich-wallpaper?label=Latest%20release&style=for-the-badge" alt="Latest release" height="56"></a>
</p>

## How it works

The app talks to your Immich server's HTTP API with an API key you create. A background
sync asks the server for photos matching your configuration (people, album, smart
search, location, favorites, memories, or any combination), downloads the full
resolution images, crops them to your screen — centered on the faces that matter, using
Immich's own face detection — and keeps a rotating cache of finished wallpapers on the
device, kept apart for each cycle. A small wallpaper engine then serves those photos, advancing to the next one
each time the screen turns off.

Syncs run a few times a day (configurable), on Wi-Fi only by default or also over
mobile data if you enable it. Between syncs the wallpaper rotates entirely from the
on-device cache, so it keeps working with no connection at all.

## Features

- **Schedule** — give saved cycles date ranges (`Nov 26 – Dec 31`, a single birthday, a
  season) and the wallpaper switches by itself at the first wake of the day. Entries are
  checked top to bottom and the first match wins; a default cycle covers every other day.
  The next cycle's photos are fetched two days ahead, so a switch needs no connection.
  Picking a cycle by hand holds until the schedule next changes.
- **Wallpaper cycles** — save any number of photo configurations and switch between
  them with one tap; build and preview new ones while the current one keeps running.
  Each cycle has its own cache, so a switch never mixes in the previous cycle's photos.
- **Photo sources** — people (one or several, any-of or all-together), albums,
  free-text CLIP smart search ("at the beach"), location, favorites, on-this-day
  memories (with day-window and years-back options), the entire library, or a **custom
  filter** combining people + album + search phrase + place + favorites + a taken-date
  range.
- **Quality filtering** — screenshots, documents, blurry and badly exposed shots are
  skipped, and a per-cycle "People in photos" preference (**Any**, **Prefer** or **Require**) can keep
  face-less photos (menus, signs, receipts) off your wallpaper. All heuristics are local — EXIF, face geometry,
  sharpness — no ML downloads.
- **Face-aware cropping** — portrait crops are anchored on the faces of the people the
  cycle is about. On a foldable, photos are cropped to suit every screen shape the
  wallpaper has been shown on and slid to keep the faces in view on whichever is active.
- **Full resolution images** — originals (HEIC/JPEG) are downloaded and processed
  on-device.
- **No repeats** — least-recently-shown rotation across the cache.
- **Rotation cadence** — a new photo at every screen wake (default), or at most every
  5 minutes / hour / 6 hours / day.
- **Back up and restore** — **Back up settings** and **Restore settings** on the settings
  screen (the tool icon on the status screen) save your cycles, schedule and options to a
  file and restore them from one; the first-run wizard's **Choose a photo source** step
  offers **Restore from a backup**. The server address and API key go in the file only if you tick that box when saving, and
  they are stored unencrypted; restoring replaces yours only if you tick that choice, and
  the wizard never does. Photos are not included.
- **Wi-Fi or mobile data** — downloads wait for Wi-Fi unless you opt into cellular;
  a separate away-URL (e.g. a VPN/Tailscale address) covers syncing when you're not on
  your home network. Both addresses must be `https://` — plain HTTP is refused, so a LAN server needs a
  TLS-terminating reverse proxy in front.
- No Google services required; no foreground service, no alarms, no analytics.

## Requirements

- **Android 14 or newer**
- **An Immich server, v3.0 or newer** — see below

### Immich server (v3.0+)

Developed and tested against Immich **v3.0.3**. Older servers (1.x/2.x) will not work:
the v3 API moved face data to its own endpoint (`/api/faces`), removed the old
random-asset and album-contents routes in favor of the unified search API, and changed
several response shapes this app depends on. If your server is older, update it first.

You'll also need an **API key** (Immich → Account settings → API keys) with read access
to assets, people, faces, albums, memories and downloads. The setup wizard probes each
scope and names exactly which one is missing if validation fails.

## Install

- **Obtainium:** tap the badge at the top of this page on your phone to add Immich Wallpaper, and Obtainium will keep it updated.
- **Manually:** download the APK from the [latest release](https://github.com/sharkusmanch/immich-wallpaper/releases/latest) and open it on your phone (or `adb install immich-wallpaper-x.y.z.apk`). Android will warn about unknown sources — that's normal for sideloaded apps; allow your browser or file manager to install them.

### Verify a download

Each release is built and signed by GitHub Actions from a tagged commit on `main`, and
the build is attested. To check a download came from this repository's workflow (the first command) and arrived intact (the second):

```bash
gh attestation verify immich-wallpaper-x.y.z.apk -R sharkusmanch/immich-wallpaper
sha256sum --check immich-wallpaper-x.y.z.apk.sha256   # both files in the same folder
```

Releases are signed with a certificate whose SHA-256 fingerprint is
`7e1c7db5ef4ce92d33b060b6b2bbe2a62358784d1926fb92ef1d17d544898e35`.

## Building from source

```bash
git clone https://github.com/sharkusmanch/immich-wallpaper.git
cd immich-wallpaper
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Optional developer convenience: put your server in `local.properties` (never
committed) to pre-fill the setup wizard on debug builds —

```properties
immich.server.url=https://your-immich-host
immich.api.key=<your api key>
```

A build you make yourself is signed with your own debug key, so it cannot be installed over a release APK or the other way round.

The app installs as `io.github.sharkusmanch.immichwall`, so it can sit alongside the original app.

> ⚠️ **Don't share debug APKs built with a populated `local.properties`** — the server
> URL and API key are baked into the binary. Release builds never include them.

## Setup on the phone

1. Install the APK and open the app.
2. Enter your server URL (+ an optional away/VPN URL, both `https://`) and API key.
3. Pick a photo source, preview it live, and save it as your first cycle — or choose
   **Restore from a backup** to bring back cycles, schedule and options from a file.
4. Apply the wallpaper (the system picker opens; choose *Home and lock screen*).

Day to day you never open the app — photos just change. When you do, the main page
shows the current photo, cache and sync status, and your saved cycles; the tool icon
opens the settings screen (options, back up and restore); the schedule has its own **Edit schedule** button here.

## Privacy

Your photos never leave your devices: the app talks only to the Immich server you
configure, and cached wallpapers live in the app's private, credential-encrypted
storage. No analytics, no telemetry, no third-party services.

## Architecture notes

Single-module Kotlin app, XML views, deliberately dependency-light (OkHttp,
kotlinx-serialization, WorkManager, Material). The interesting parts:

- `wallpaper/` — the engine and the screen-off advance state machine
- `cache/` — atomic manifest + rotation state that survives process death
- `crop/` — EXIF-aware decode → face-anchored crop → exact panel-size output
- `source/` — `SourceSpec` (sealed, serialized) maps each cycle type onto Immich search
  endpoints; `PhotoScorer` implements the quality heuristics
- `DESIGN.md` / `CONTRACTS.md` — the full design rationale and cross-module contracts

The crossfade approach is modeled on [Muzei](https://github.com/muzei/muzei)
(Apache-2.0); see `NOTICE`.

## About this project

This is a personal project, built for my own phone and shared because it might be
useful to someone else. It was coded with
[Claude Code](https://claude.com/claude-code) — if that's not your thing, no hard
feelings: fork it, rewrite it, or build your own. Comments, issues and contributions
are all welcome.

## License

[Apache License 2.0](LICENSE)
