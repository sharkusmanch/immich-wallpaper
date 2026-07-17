# Design System — "Immich ethos" (v1, 2026-07-16)

Derived from Immich's actual sources (`mobile/lib/theme/theme_data.dart`, `mobile/lib/constants/colors.dart`, `web/src/app.css`): Material 3, flat elevation-0 surfaces, indigo brand, primary-tinted accents, photo-first layouts. This doc is binding for all UI work.

## Color (Material 3 roles, both modes required everywhere)
Brand (verbatim from Immich): light primary **#4150AF**, dark primary **#ACCBFA**.

| Role | Light | Dark |
|---|---|---|
| primary | #4150AF | #ACCBFA |
| onPrimary | #FFFFFF | #10243E |
| primaryContainer | #DEE1FF | #3A4578 |
| onPrimaryContainer | #00105C | #DEE1FF |
| secondary | #575E71 | #C0C6DC |
| secondaryContainer | #DBE2F9 | #404659 |
| surface | #FAF9FF | #121318 |
| surfaceContainer | #EEEDF4 | #1E1F25 |
| surfaceContainerHigh | #E8E7EF | #282A2F |
| onSurface | #1A1B21 | #E3E2E9 |
| onSurfaceVariant | #45464F | #C5C6D0 |
| outline / outlineVariant | #757680 / #C5C6D0 | #8F909A / #45464F |
| error / errorContainer | #BA1A1A / #FFDAD6 | #FFB4AB / #93000A |

Ripple/splash: primary @ 10% alpha (Immich's `splashColor`). No dynamic color — brand identity wins.

## Typography
Bundle **Plus Jakarta Sans** (OFL — Immich ships GoogleSans, which is not redistributable; PJS is the accepted open equivalent) as `res/font/`: regular(400), medium(500), semibold(600), bold(700) + `plus_jakarta_sans.xml` font-family. Scale (mirrors Immich's TextTheme):
- App-bar title: 18sp semibold, **colorPrimary**, centered (their signature move)
- Headline (wizard pages): 26sp semibold, onSurface
- Title: 16–18sp semibold; Body: 14–16sp regular; Label/caption: 12sp medium, onSurfaceVariant

## Shape
Cards 16dp · hero/preview cards 24dp · dialogs & bottom sheets 28dp · popup menus 10dp (Immich exact) · photo thumbs 8dp · buttons + search fields: full pill · text fields: 12dp `immich-form-input` style — filled `surfaceContainer` bg, no box stroke until focus, then 1dp primary ring.

## Components
- **App bar**: surface bg, elevation 0 even scrolled-under (`scrolledUnderElevation 0`), centered primary semibold title, primary icons.
- **Buttons**: FilledButton primary/onPrimary for the single main action (bottom-pinned in wizard); TextButton for back/secondary; icons Material Symbols Outlined.
- **Wizard pages**: generous 24dp side padding, headline + one-line supporting body (onSurfaceVariant), content, bottom-pinned action row. Step dots (5dp, primary/outlineVariant) top-right.
- **Person grid**: 3-col circular avatars (aspect 1:1, circle crop), name below 12sp medium; selected = 3dp primary ring + small check badge (primary circle, onPrimary check).
- **Photo/preview grids**: 3-col, 2dp gutters, 8dp corners; loading = surfaceContainerHigh skeleton tiles.
- **Mode list (SourcePicker)**: cards 16dp, leading 40dp icon in primaryContainer circle (onPrimaryContainer glyph), title semibold + one-line description, trailing chevron.
- **Status screen**: hero card = current wallpaper at 9:19.5 aspect, 24dp corners, subtle 1dp outlineVariant border; stats as assist chips (surfaceContainer, 8dp icon-leading); health issues = errorContainer banner cards, 12dp; actions as list rows with primary icons.
- **Snackbars**: surfaceContainerHigh bg, **primary bold text** (Immich exact).
- **Slider (cache size)**: chunky 12dp track (Immich exact), value label above.
- **Empty/error states**: centered outline-style illustration glyph 48dp onSurfaceVariant, headline 16sp semibold, body 14sp, action button.

## System chrome & motion
Edge-to-edge; transparent status/nav bars, icons auto light/dark. Fragment transitions: M3 fade-through (MaterialFadeThrough from material lib). Content appear: 200ms fade+8dp rise, staggered 30ms in grids.

## Iconography
Material Symbols **Outlined** as VectorDrawables (bundle only used glyphs): person, group, photo_album, search/magic (smart), location_on, favorite, history (memories), shuffle, wallpaper, refresh, settings, check, arrow_back, chevron_right, error, cloud_off, schedule, palette, battery.

## App icon
Adaptive: indigo #4150AF background; foreground = white rounded photo-frame glyph with a small 4-color fold accent (nod to Immich's ribbon logo, NOT a copy of it — their logo is their trademark).
