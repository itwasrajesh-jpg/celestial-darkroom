# Celestial Darkroom

*Formerly Latent.*

A film camera for Android. Pick a film, shoot RAW, and the photo develops through a
physically based film simulation — spectral exposure, dye density, a print under an
enlarger, and a scan.

Built and tested on a Xiaomi 15 Ultra; developed entirely from a phone via the GitHub
web editor and GitHub Actions.

## Community

Questions, feedback, shots: https://discord.gg/HfCeunz8Cs

## Attribution

> **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm

Both lines are required under GPLv3 §7(b) and are shown in the app's About screen with
clickable links. See NOTICE.md for the full terms and LICENSE for GPLv3.

## What works

**Camera** — Camera2 at LEVEL_3, all four rear lenses (14/23/70/100 mm equivalent) plus a
×2 in-sensor crop on the main lens. 12.5 MP DNG capture with the sensor's colour matrices,
noise profile and lens-shading map, matching the phone's own Pro-mode RAW. Optional JPEG
alongside. Xiaomi-style control strip (EV, shutter, ISO, Kelvin white balance, focus) with
tap-to-focus, an exposure slider on the focus point, and long-press AE/AF lock. Anti-banding,
gridlines, volume-button shutter, timer, haptics.

**Burst and stacking** — a 16-frame RAW burst at the sensor's full 30 fps, aligned
(coarse-to-fine shift search with per-tile rejection for movement) and merged into one DNG.
Measured about 2.6× less shadow noise at ISO 800; little benefit at base ISO, where
quantisation dominates.

**Xiaomi's own processing** — Portrait, Night and Auto via Android's Camera Extensions,
with whatever controls each mode actually accepts (the app asks and shows only those).
JPEG only; that is the API's limit.

**Develop** — every single shot is developed in the background with the selected film.
Twenty film stocks, each automatically paired with the print stock it was designed for;
slide films are scanned directly with no print stage.

**Darkroom** — a draggable sheet with tabs for film, halation, grain, diffusion, camera,
enlarger, scanner, glare, colour and engine settings, covering the full parameter surface
the engine exposes. Live preview (coarse while dragging, fine when it settles), hold to
compare against the original, save named recipes, cancel a long develop.

**Roll** — a contact sheet of the session, filters for developed and undeveloped, develop
all, and import of any RAW or JPEG on the phone.

## Engine

The film engine (`engine:spektra-core`) and the RAW decoder (`lib:libraw`) are not committed
here. The build workflow fetches them from a pinned commit of a mirror of the Android port,
so builds are reproducible and the upstream code is never vendored into this repository.
The pinned commit is recorded in `.github/workflows/build.yml` and shown in the app's About
screen. Any local change to the engine is documented in that mirror's NOTICE.

## How it's built

Builds are manual: **Actions → Build Celestial Darkroom APK → Run workflow**. The result is an APK
attached to a GitHub Release; install it over a previous release-signed build; an older debug-signed build must be uninstalled once first (
the signing key is private and stored as a GitHub Actions secret). The first build after an engine change compiles
C++ via the NDK and takes 10–15 minutes; later builds are cached.

Code arrives as zip bundles: upload one to the repository root or `drop/`, and
`.github/workflows/drop.yml` unpacks it, commits, and deletes the zip. Nothing builds
automatically. Workflow files are edited by hand — GitHub does not let a workflow modify
other workflows.

## Licence

GPLv3. The film profiles and LUTs from spektrafilm are CC BY-SA 4.0. RAW decoding uses
LibRaw (LGPL-2.1 / CDDL-1.0).

Camera, develop flow and darkroom by Celestial. Written with Anthropic's Claude, in
conversation with the author.
