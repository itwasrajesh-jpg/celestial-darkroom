# Celestial Darkroom — Handoff

*Film camera for Android by Celestial (formerly **Latent**). State as of 4 Oct 2026, app step 47a.*
*Purpose: everything a fresh session needs. Paste this at the start of a new conversation.*
*Replaces `LATENT_HANDOFF.md` (12 Sep, step 8a).*

---

## 1. What it is

A film camera, not an editor. Shoot RAW; the photo develops through spektrafilm's physically
based simulation (spectral exposure → dye density → print → scan), then a darkroom lets you print
it as a printer would: test strips, a colour ring-around, dodge and burn, painted softening, fog
and light. Free and open source (GPLv3), and staying free — a give-back to open source.

- **Name:** Celestial Darkroom (renamed from Latent on 4 Oct 2026; two iPhone apps and an
  unreleased Android app already use "Latent").
- **Repo:** `github.com/itwasrajesh-jpg/celestial-darkroom` (public; the old `…/latent` address
  redirects — never create a new repo called `latent`, it would break the redirect).
- **Internal names are unchanged on purpose:** package `com.celestial.latent`, the `DCIM/Latent`
  folder, `LATENT_` file names, `latent_*` preferences, thread names, the log tag `Latent`.
  Changing the package would break updates; changing the folder or file names would drop every
  existing photo from the roll. The roll's "LATENT" filter is the film term (undeveloped), not the
  app's name.
- **Devices:** Xiaomi 15 Ultra (main, Android 16); Pixel 7 Pro (second).

Required attribution under GPLv3 §7(b), clickable, in the About screen and NOTICE.md:
- **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
- Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm
- **Celestial Darkroom** — https://github.com/itwasrajesh-jpg/celestial-darkroom

The first two are upstream notices and stay word for word.

## 2. Working method

- Development from the phone: GitHub web editor, Codespaces and Actions. A Windows PC (RTX 4070 Ti,
  64 GB) is available for heavier jobs.
- Code arrives as zip bundles → upload to the repo root → `drop.yml` unpacks and commits.
  Bundles can add or replace files, **not delete them** — deletions are done by hand.
- **Builds are manual:** Actions → "Build Celestial Darkroom APK" → Run workflow. Releases are
  titled "Celestial Darkroom 0.1.N" with `celestial-darkroom-vN.apk` (older ones say Latent).
  The in-app updater takes any `.apk` from the latest release and reads the `vN` tag.
- **Workflow files are hand-edited** in the GitHub editor (`drop.yml` excludes `.github/workflows/*`).
- **Never bundle** `app/build.gradle.kts`, `.github/workflows/build.yml` or any keystore unless
  deliberately changing them, and **always fetch the live file from main first**.
- **Signing:** private release key since v114 (alias "1"), kept off the repo; keystore and
  passwords live in GitHub secrets. Bitdefender flags the app as Android.Riskware.Repack — a false
  positive that began with the key change.
- Engine + LibRaw are fetched at build time from the user's mirror at a pinned commit
  (`ENGINE_REPO` / `ENGINE_REF` in build.yml), then every patch in `engine-patches/` is applied in
  order. arm64 only. JDK 21, Gradle 8.14.3, AGP 8.7.3, NDK 27.0.12077973, CMake 3.22.1.
- Build errors: the `e: file:///…` lines are what's needed. Crashes: the app shows the trace on
  next launch with a Share button. Runtime behaviour: only the phone can verify.
- **The user's preferences:** plain language and everyday words; aesthetics considered in the
  first version; read the code before planning; verify thoroughly; ask before big or sensitive
  changes; "reorganise but keep controls the same" means verbatim extraction, proven.

### Checks run on every bundle
1. Brackets balanced in every file.
2. Nothing called that isn't visible across files (including constants).
3. Named constructor arguments match the data class.
4. No C-style `for` loops.
5. Nothing used before it is declared inside composables and `run {}`.
6. **Lane trap:** a helper that takes the engine lane is never called while the lane is held.
7. No full-width child inside a horizontal `Row`.
8. No suspending calls inside `awaitEachGesture` (only pointer-await calls).
9. Each painting step writes its own mask slot in `Masks`.
10. Row widths estimated against the phone's ~357 dp.
11. **Memory at full size** for anything new (a 12.5 MP float plane is 48 MB; the app gets ~512 MB).
12. When renaming or searching text, judge the string itself — never filter on file names
    (a `Log.` filter once skipped `CrashLog.kt`).

## 3. Device facts (Xiaomi 15 Ultra, Android 16)

- Camera2 LEVEL_3. Logical camera 0 with physical 2 (main 23 mm, 1-inch, f/1.63), 3 (UW 14 mm),
  4 (70 mm, ISO max 1119), 5 (100 mm). Extra logical cameras 6 and 7 exist.
- Third-party RAW: 12.5 MP, **10-bit** (white 1023, black 64) on every lens; no 50 MP for
  outside apps. Colour matrices, noise profile and shading map all present and matching
  Xiaomi's own Pro-mode RAW.
- RAW burst runs at a true 30 fps with no drops.
- Camera Extensions available: AUTOMATIC, BOKEH, NIGHT (front camera too).
- `JPEG_R` (Ultra HDR) is offered but **cannot share a session with RAW** — the driver throws a
  fatal error. Ultra HDR was removed.
- **Measured dead ends**: DCG, staggered HDR, multi-frame HDR, snapshot HDR and MFNR vendor keys
  are accepted but have *zero* measurable effect on the RAW (A/B tested at ISO 390 and 3112,
  ±0.01). The driver publishes no vendor result keys, so "echo=1" means "not rejected", nothing
  more. Ideal RAW times out in every stream layout. All those toggles were removed.
- **What is real**: in-sensor zoom on the JPEG path (main lens), `sensor_meta_data.current_mode`
  as a route to sensor-crop RAW on the telephotos (MotionCam uses mode 38 on lens 5), the
  Camera Extensions, and our own aligned stacking.
- Vendor codes are per-lens in the app; a mode valid on one sensor errors on another.
- Camera opens are numbered (step 46a): an open that finishes after the app was minimised closes
  itself, so a vanished viewfinder surface no longer crashes the app.

## 4. Engine facts

**The port ships `.claude/skills`, including `spectrafilm-dev` — read it before touching the
C++.** Its hard laws: parity with the Python oracle is the prime directive (max_abs ≤ 1e-4,
rms ≤ 1e-5, byte-identical across thread counts); any change under
`engine/spektra-core/src/main/cpp/**` must keep the host-parity suite green before it is done;
non-parity behaviour must default OFF; NDK r27 / CMake 3.22.1 / JDK 21 are hard pins;
`-fno-finite-math-only` must never be stripped (the scan stage relies on NaN propagation);
stochastic stages need fixed seeds; thread-invariance is mandatory; GPU never routes export or
parity; and never claim the parity gate passed without running it.

- **Pinned** at `3c80804` on the user's mirror `itwasrajesh-jpg/Spektrafilm-android`.
- **Local engine changes** live as patches in this repo's `engine-patches/`, applied at build:
  `0001-dodge-burn-light-map.patch` adds `print_exposure_map` to `spk_params` (the dodge-and-burn
  light map, tested).
- `engine:spektra-core` (C++ + Kotlin facade) and `lib:libraw`, fetched from the mirror.
- API used: `simulate`, `simulatePreview` (the only path honouring the GPU preview flag),
  `bakeCubeLut`, `meterExposureEv`, `listProfiles`.
- Input must be **linear ProPhoto**: the engine ignores the colour-space label and always
  interprets input as ProPhoto. RAW arrives that way from LibRaw; JPEG sources are converted
  in `Develop.openImage` (sRGB curve removed, then sRGB→ProPhoto primaries).
- Output: the scan stage converts to the chosen space with correct matrices, CAT02 adaptation
  and per-space encoding. sRGB is correct for viewing; wide spaces are for onward grading and
  can band in an 8-bit JPEG.
- 20 filming profiles, 8 printing profiles. Each film declares its `target_print`: Kodak
  negatives → Portra Endura, Fuji → Crystal Archive II, Vision3/Verita → Kodak 2383. Slide
  films (Provia, Velvia, Ektachrome, Kodachrome) have none and need `scanFilm = true`.
  Using a printing profile as a film gives "internal error".
- **The film look is not tied to any colour space.** The simulation works in 81 spectral bands
  and dye density; only the final scan stage converts to RGB.
- **Exposure looks like it does nothing on the print route** — by design.
  `print_exposure_compensation` and `normalize_print_exposure` recompute the enlarger exposure
  from the film exposure, exactly as a printer would. Brightness on the print route is
  **Print exposure** (`PRINT_EXPOSURE_MIN` 0.15 … `MAX` 3.0).
- **Diffusion:** the engine's own filter is a direct 2D convolution — minutes at 12.5 MP. Latent
  has its own FFT path (`FilmDiffusion`, JTransforms), used by default ("fast diffusion") and for
  previews. The engine honours the **lens** filter family; it has **no family setting for the
  enlarger**, so with fast diffusion off at full size the enlarger always uses Black Pro-Mist.
  Latent's own families (fog) always take Latent's path (`engineHas()`).
- **Diffusion memory (step 42a):** the transform never exceeds `MAX_FFT` 2048 (two ~34 MB
  buffers); the wide glow goes to a quarter-size copy whenever it is much wider than the middle
  glow *or* too wide for the limit. Fog at full size dropped from ~576 MB (crash) to 64 MB.
- GPU (Vulkan) covers the **scan stage only** and is **preview-only by the engine's own rule**.
  The app exposes a GPU *preview* switch only; `Develop.render` forces it off for full renders.
  Do not re-add a GPU export option.

## 5. App structure

```
app/src/main/java/com/celestial/latent/
  MainActivity.kt        navigation, permission, crash screen, volume keys
  LaunchScreen.kt        opening animation (CELESTIAL / DARKROOM / A FILM CAMERA)
  CameraScreen.kt        viewfinder, lenses, controls, cinema mode, double exposure, auto-rotate,
                         frame lines, quick-settings drawer
  RollScreen.kt          contact sheet, filters, develop all, import
  DarkroomScreen.kt      SIMPLE / FULL / PRINT modes; zoom; framing; full develop (shows failures)
  PrintMode.kt           the six PRINT steps, shared PaintStep (handles, tabs), Masks
  FrameMode.kt           FRAME panel: turn, flip, straighten, widescreen, bars
  Zoom.kt                ZoomState, fitRect, ZOOM_MAX 4
  Cinema.kt              cinema definitions; PhotoRecipes (per-photo recipes)
  LookScreen.kt          Film Builder
  FilmStrip.kt           28 looks + your films
  SettingsScreen.kt, Settings.kt, AboutScreen.kt, LogScreen.kt, CrashLog.kt, Updater.kt
  develop/
    Develop.kt           decode, cache, render, save; fog and rays applied before the lens
    DevelopQueue.kt      single engine lane; full develop takes the lane too (42a)
    Recipe.kt, DarkroomPrefs.kt
    ExposureMap.kt       painted maps: dodge & burn, soften, fog, rays
    Fog.kt               FogLook (v2), physics, colour tools (shared with lights)
    Rays.kt              RaysLook (v3): point/sun/spot/area × add light / through gaps
    Framing.kt, DoubleExposure.kt, FilmDiffusion.kt
    Fingerprint.kt, Reconstruct.kt, Texture.kt, LookSession.kt, Emulsion.kt, LookBaker.kt
  camera/                Camera2 controller, lens table, alignment, DNG writer, report, probe
  gl/FilmPreviewView.kt  viewfinder shader (film LUT, 85B tint)
```

## 6. Where things stand

### Done (selected, newest first)
- **47a** Fog and Rays controls in tabs (COLOUR | BRUSH; LIGHT | BEAM | BRUSH); fixed control
  height so the photo never shrinks; painting only on the BRUSH tab.
- **46a** Minimise-and-return camera crash fixed (numbered opens + surface check + safety net).
- **45a/45b** Renamed to Celestial Darkroom; repo renamed; NOTICE corrected (engine changes live
  in `engine-patches/`).
- **44a** Rays: *add light* (the light emits its own beam) and *through gaps* (the photo's own
  light); light colour (temperature + vivid hues, ceiling 0.9), dust, "only in fog".
- **43a** Rays light types — point, sun (directional), spot (cone), area (line) — with handles.
- **42a** Fog diffusion out-of-memory fixed; failures shown on screen.
- Earlier: PRINT mode (test strip, colour ring-around, dodge & burn, soften, fog, rays), framing,
  zoom with sharp tiles, cinema mode (Vision3 + 2383/2393, aspect, bars, 85B), auto-rotate,
  double exposure, Film Builder, the fog diffusion filter.

### Open
1. Test auto-rotate direction on a sideways shot; test double exposure (ghost alignment,
   500T + 85B in daylight).
2. Memory readings at 1.5× and 2× print size (to decide on 2.5× and a warn-before-starting check).
3. Pixel 7 Pro lens discovery test.
4. Log screen keeping earlier sessions.
5. Enlarger diffusion family: a small `engine-patches` bundle (see §4).
6. Double-exposure darkroom: balance the two frames, nudge the second.
7. Expired film; negative reveal.
8. The ad video's end card still says Latent.

### Ideas discussed
- **Depth from an on-device model** — Depth Anything V2 Small (Apache-2.0; the larger V2 models
  are non-commercial) or Depth Anything 3 Small (official card says Apache-2.0; one catalogue
  says otherwise — check the licence file first). Relative depth; ~20–95 MB, downloaded on first
  use. Would enable fog by distance (first), beams passing behind subjects, depth of field, and
  rough relighting.
- **More physical rays:** forward scattering (brighter facing the light), sun rays converging in
  perspective.
- **Give back upstream:** offer the dodge-and-burn patch to the Android port; F-Droid listing
  (would need checking against model downloads).
- Akshay's Hugging Face repo `thetechgeekko/latent-android-models` is for a separate, unreleased
  LATENT Android camera app of his — worth a friendly conversation.

### Decided against, with reasons
- **A GPU port of the engine / GPU compute during export** — the port already ships Vulkan for
  the scan stage and has the rest on its roadmap; export must stay CPU by the engine's rules.
- **Diffusion micro-optimisation in C++** — float32 would break bit-exactness with the oracle.
- **Ultra HDR**, **DCG / staggered HDR / MFHDR / snapshot HDR / MFNR** — measured dead, removed.

## 7. Testing habits that work

- Settings → Logs → "Showing: this app only" → Clear → do the thing → Refresh → Share.
- Measure, don't assume: the A/B test and the sensor-mode sweep settled questions that
  metadata could not; prove new maths on a test scene before writing app code.
- Nothing heavy runs twice at once: developing is single-lane. A hot phone throttles hard and
  makes every timing meaningless — measure cool.
- Rare timing bugs (like the minimise crash) need many quick repeats to test.

## 8. Key numbers

- Lens IDs (15 Ultra): 2 main 1×, 3 UW 0.6×, 4 70 mm 3×, 5 100 mm 4.3×.
- Print sizes 1×, 1.25×, 1.5×, 2×. Zoom: `ZOOM_MAX` 4, `DETAIL_EDGE` 2400.
- Super 35: 25 mm. 85B gains `[1.2044, 1, 0.4923]` on linear ProPhoto.
- Fog: saturation ceiling 0.25, thickness 0–3. Rays: grid 512, 64 steps; dust at full ≈ 27%
  variation; lights' saturation ceiling 0.9.
- Diffusion: `MAX_FFT` 2048. The app's memory limit is ~512 MB.
- Tabbed controls height: 196 dp.
