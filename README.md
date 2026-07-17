# Immich Wallpaper

An Android **live wallpaper** for self-hosted [Immich](https://immich.app) libraries:
pick people, albums, searches or date ranges from your library, and your lock and home
screen show a different photo every time you wake the phone.

## How it works

The app talks to your Immich server's HTTP API with an API key you create. A background
sync asks the server for photos matching your configuration (people, album, smart
search, location, favorites, memories, or any combination), downloads the full
resolution images, crops them to your screen — centered on the faces that matter, using
Immich's own face detection — and keeps a rotating cache of finished wallpapers on the
device. A small wallpaper engine then serves those photos, advancing to the next one
each time the screen turns off.

Syncs run a few times a day (configurable), on Wi-Fi only by default or also over
mobile data if you enable it. Between syncs the wallpaper rotates entirely from the
on-device cache, so it keeps working with no connection at all.

## Server requirement: Immich v3.0+

**This app requires an Immich server running v3.0 or newer** (developed and tested
against v3.0.3). It will not work with older servers: the v3 API moved face data to its
own endpoint (`/api/faces`), removed the old random-asset and album-contents routes in
favor of the unified search API, and changed several response shapes this app depends
on. If your server is on 1.x/2.x, update it first.

The API key needs read access to assets, people, faces, albums, memories and downloads.
The setup wizard probes each scope and names exactly which one is missing if
validation fails.

## Features

- **Wallpaper cycles** — save any number of photo configurations and switch between
  them with one tap; build and preview new ones while the current one keeps running.
- **Photo sources** — people (one or several, any-of or all-together), albums,
  free-text CLIP smart search ("at the beach"), location, favorites, on-this-day
  memories (with day-window and years-back options), the entire library, or a **custom
  filter** combining people + album + search phrase + place + favorites + a taken-date
  range.
- **Quality filtering** — screenshots, documents, blurry and badly exposed shots are
  skipped, and a per-cycle "people in photos" preference keeps face-less photos (menus,
  signs, receipts) off your wallpaper. All heuristics are local — EXIF, face geometry,
  sharpness — no ML downloads.
- **Face-aware cropping** — portrait crops are anchored on the faces of the people the
  cycle is about.
- **Full resolution images** — originals (HEIC/JPEG) are downloaded and processed
  on-device.
- **No repeats** — least-recently-shown rotation across the cache.
- **Rotation cadence** — a new photo at every screen wake (default), or at most every
  5 minutes / hour / 6 hours / day.
- **Wi-Fi or mobile data** — downloads wait for Wi-Fi unless you opt into cellular;
  a separate away-URL (e.g. a VPN/Tailscale address) covers syncing when you're not on
  your home network.
- No Google services required; no foreground service, no alarms, no analytics.

## Requirements

- Android 14 or newer
- Immich server v3.0+ with an API key (see above)

## Building

```bash
git clone <this repo>
cd immich-wallpaper
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

Optional developer convenience: put your server in `local.properties` (never
committed) to pre-fill the setup wizard on debug builds —

```properties
immich.server.url=http://your-immich-host:2283
immich.api.key=<your api key>
```

> ⚠️ **Don't share debug APKs built with a populated `local.properties`** — the server
> URL and API key are baked into the binary. Share the source, or build with those
> fields empty.

`./update-phone.sh` builds and installs onto a USB-connected phone in one step.

## Setup on the phone

1. Install the APK and open the app.
2. Enter your server URL (+ an optional away/VPN URL) and API key.
3. Pick a photo source, preview it live, and save it as your first cycle.
4. Apply the wallpaper (the system picker opens; choose *Home and lock screen*).

Day to day you never open the app — photos just change. When you do, the main page
shows the current photo, cache and sync status, and your saved cycles.

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
