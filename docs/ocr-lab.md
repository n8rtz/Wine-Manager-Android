# OCR Lab (debug builds only)

Build/run the **debug** variant in Android Studio, then open **Add New Wine →
OCR Lab — compare 30 treatments**. Release builds do not package the Lab activity,
recipes, filters, runner, UI or evaluation code. The normal scanner is unchanged;
the production scanner exposes internal serialization and base-image capture hooks
that are unused by ordinary scans. No benchmark suggestion is applied to the cellar.

## Run and share

1. Check the editable expected producer, name, Country (state for U.S. wines), and
   optional vintage. Blackstone and Olarra are convenience presets, not OCR inputs.
   Blank expectations mean **unscored**, not “must be absent.”
2. Choose the same saved photo used for the original diagnostics. No camera or
   storage permission is required; the existing bounded photo importer is reused.
3. Allow several minutes. The screen stays awake while open. Stop cancels the
   batch and preserves completed cases. Closing cancels work and removes its cache.
4. Inspect the processed image, raw readings/scores, and extracted field decisions.
   Baseline has up to three separately inspectable pass images. Previews are hidden
   during a batch so UI decodes cannot overlap the working-image allocation budget.
5. **Export results + images ZIP** uses Android's document picker. It exports the
   base/processed label pixels, expected answers, raw OCR diagnostics, build/device
   information, recipe descriptions, timings and scores. It does **not** export the
   original file, URI, EXIF/GPS, account data or manual cellar fields. Share the ZIP
   only intentionally. A failed/cancelled export may leave a partial destination.

## The 30 cases

- Current V0/V1/conditional V2 pipeline on the fixed crop (baseline).
- Raw; CLAHE/highlight compression; unsharp; unsharp + half-height aspect rescue.
- Gaussian illumination division (sigma 25); illumination + sharpening.
- Sauvola: windows 25/51, k 0.20/0.35, with/without illumination correction (8).
- Dark-stroke thickening (2×2 ellipse) on raw/CLAHE/illumination (3).
- Sauvola window 25 with thickening, k 0.20/0.35 (2).
- Fixed shear slopes −0.24/−0.12/+0.12/+0.24 (4).
- CLAHE plus shear −0.12/+0.12 (2).
- Sauvola window 25 plus shear −0.12/+0.12 (2).
- Zhang–Suen thinning on Sauvola output, with/without subsequent thickening (2).

These are bounded comparisons, not every possible combination. Shear candidates
are explicitly fixed experimental slopes, **not** inferred deskew/slant estimates.
No Wolf/Niblack implementation or heuristic ligature cutting is claimed. Thinning
is cancellable and limited to 64 iterations; non-convergence is an explicit failed
case rather than a silently accepted partial skeleton. No ximgproc/native module,
extra model, external wine lookup or downloaded binary is added.

## Fair comparison and scoring

One normal scan localizes/rectifies the label and warms the engines. Its base image
is saved losslessly before preprocessing. Setup is unranked; if no label quad/base
can be obtained the batch stops rather than testing unrelated full-scene crops.
All 30 cases decode **that same PNG**, with its SHA-256 and original-to-base
homography recorded. The baseline is run first; remaining cases are shuffled with
a recorded seed to reduce systematic order bias. Repeat runs/multiple labels are
still necessary: warmup, heat and handwriting can change which treatment wins.

Each case uses the same available engines. Baseline has up to three variants;
other recipes have one. The ensemble never combines results across cases. Repeated
ML Kit variants remain one engine, not independent confidence votes. ML Kit still
receives its normal byte-owned grayscale NV21 input; previews show the prepared
pixels *before* that adapter.

Ground truth enters scoring **only after** OCR and production extraction. Ranking
uses complete cases only, by raw-name best-span normalized edit similarity, then
exact matches among supplied expected fields. Best-span similarity searches one
to three adjacent raw lines within a single engine/pass; it is **not** full-label
CER, confidence, or proof of whole-label accuracy. Field matches may include the
normal dictionary corrections, so raw-name scores are reported separately. Other
unexpected output and missing unscored fields remain visible in the diagnostics.
There is no automatic “install this winning filter” action.

## Budgets and ownership

- Target 8 seconds per case, reserving 300 ms for field extraction. Each includes
  decode, treatment, preview encoding, OCR and extraction; scoring is separate.
  Synchronous native operations cannot be preempted midway, so overruns are
  explicitly recorded and excluded from ranking, not presented as successes.
- One app-scoped scan permit, shared engine gate, no parallel case execution.
- At most two working Bitmaps; base ≤1280 wide/1920 long, no third Bitmap for resize.
- Shear includes horizontal compression/padding to retain all label corners.
  Each polygon is inverse-mapped to base pixels before original-capture mapping.
- Temporary Mats/Bitmaps close in finally; the selected original is never modified.
  Late ML Kit tasks retain only their independently owned bytes and the native gate.
- Stored PNG previews have a 64 MiB cap; later previews may be omitted without
  suppressing their OCR attempt. Cache is private, removed on screen disposal/new
  run; process-death leftovers older than 24 hours are expired on the next run.

## Validation scope

Host checks cover the 30-case plan, shear bounds/inverses, and ground-truth scoring
without promoting low-confidence text into fields. Existing host scanner tests
remain in the same suite. Debug Robolectric wrappers are authored in `testDebug`.
A host type-check of the preprocessing code uses real OpenCV Java API declarations;
it does not execute native filters. Physical-phone accuracy, timing, heat and the
new Compose screen require running this debug build. No emulator acceptance is
claimed.
