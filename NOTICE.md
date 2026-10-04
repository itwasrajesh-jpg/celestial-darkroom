# Celestial Darkroom — notices

*Formerly Latent.*

Copyright © 2026 Celestial (itwasrajesh-jpg).

Celestial Darkroom is free software, released under the GNU General Public License version 3
or (at your option) any later version. See LICENSE for the full terms.

## Required attribution

Under GPLv3 §7(b), the following notices must be preserved in any distribution of
this software or of a work derived from it, and shown to users in a place they
can reasonably find:

> **Spektrafilm for Android** by Akshay Sharma — https://github.com/thetechgeekko/Spektrafilm-android
>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — https://github.com/andreavolpato/spektrafilm
>
> **Celestial Darkroom** — https://github.com/itwasrajesh-jpg/celestial-darkroom

All three appear in the app's About screen with clickable links.

## What is whose

**spektrafilm** (Andrea Volpato) is the research and the film science: the spectral
model, the measured film and paper profiles, the density curves, and the
negative → enlarger → print → scan chain. CC BY-SA 4.0 for the profile and LUT data.

**Spektrafilm for Android** (Akshay Sharma) is the C++/NDK port of that engine to
Android, checked bit-for-bit against the original. GPLv3.

**Celestial Darkroom** (Celestial; formerly Latent) is this application: the camera (viewfinder, all lenses, DNG
capture, burst alignment and stacking, exposure and focus controls), the develop
flow and the darkroom, the live film preview in the viewfinder, the colour-noise
cleanup, the Display P3 and Rec.709 output conversions, the film-to-paper pairing,
and the FFT implementation of the diffusion filter. GPLv3.

## Engine

The engine and RAW decoder are fetched at build time from a pinned commit of a
mirror of the Android port; they are not vendored into this repository. The pinned
commit is recorded in `.github/workflows/build.yml` and shown in the About screen.
Local changes to the engine are kept as patch files in this repository's
`engine-patches/` folder and applied at build time, so every change is public here.

## Other components

- **LibRaw** — RAW decoding. LGPL-2.1 / CDDL-1.0.
- **JTransforms** — the FFT used by the diffusion filter. BSD 2-clause.
- Film profiles and LUTs from spektrafilm — CC BY-SA 4.0. Any profile derived from
  them (for example a blended emulsion) is also CC BY-SA 4.0 and must not be named
  as a product of Kodak, Fujifilm, Leica or any other manufacturer.

"Celestial Darkroom" and "Celestial" are names, not licensed code: GPLv3 covers the software,
not the naming of it.
