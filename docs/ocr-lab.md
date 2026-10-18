# OCR Lab (debug builds only)

Build/run the **debug** variant in Android Studio, then open **Add New Wine →
OCR Lab — compare 30 treatments**. Release builds do not package the Lab activity,
recipes, filters, runner, UI or evaluation code. The normal scanner is unchanged;
the production scanner exposes internal serialization and base-image capture hooks
that are unused by ordinary scans. No benchmark suggestion is applied to the cellar.

## Run and share

1. In the debug build, open **Settings/Profile → OCR Lab — multi-image tests**
   (the Add New Wine shortcut still works).
2. Tap **Choose multiple label images**. Android's documents picker supports local
   storage; choose up to **10** photos. Larger selections are rejected explicitly,
   not silently truncated. Duplicate selected URIs are removed. Nothing runs yet.
3. Each image has its own thumbnail, editable sample nickname, and six actual-detail
   inputs: maker/producer, wine name, Country (state for U.S.), region, subregion/
   appellation, vintage. Mark each image reviewed. No preset answer is silently
   assigned to a new photo. Blank fields mean **unscored**, not “must be absent.”
4. Tap **Run N images × 30 treatments**. Images/cases are sequential, never parallel.
   Metadata is frozen per image when the batch starts. Edits after the batch apply
   only to the next run. Failed image setup is recorded, and the batch continues
   with the next image. Stop retains all completed CSV rows.
5. Inspect a label's cases or expand **cross-image summary**. Mean scores are shown
   with complete/scored image counts; missing/failed cases are not successes.
6. **Export all saved test data (CSV)** exports the retained history from every
   batch. The same action is directly available as **Settings/Profile → Export OCR
   test data (CSV)**, including after closing the Lab or restarting the app.
7. **Export current batch CSV + images ZIP** includes only the current in-memory
   batch's CSV, processed/base images and detailed diagnostics. Image previews are
   temporary; export this ZIP before closing. CSV rows persist independently.

### Structured data and privacy

The UTF-8 CSV has one row per attempted treatment, plus explicit rows for image
setup/run failures. Stable columns include batch/image IDs, user sample nickname,
input/base hashes, build/device metadata, order/seed, individual recipe parameters,
status, elapsed/preparation times, deadline flag, expected/actual values for all six
fields, field states/reasons, raw OCR text and confidence, engine pass timing,
image dimensions and original-to-base homography. Raw-name similarity and exact
field counts are separate. Empty expected values are unscored. Multiline text,
commas and quotes are RFC4180-escaped. Formula-like cells get a leading apostrophe
for spreadsheet safety; strip that escape if performing exact text analysis.

Completed rows are atomically saved in a private **no-backup** directory and survive
screen close, new batches and app restarts. History is capped at **64 MiB**; a storage
failure stops the batch visibly instead of silently dropping results. Nothing is
automatically deleted from CSV history. **Clear saved test history…** requires
confirmation; export first. It does not affect cellar data or original photos.
Uninstalling/clearing application data removes this private history.

CSV has typed expected answers and label text/device information but **no photos,
original filenames/URIs, EXIF/GPS, account data or manual cellar fields**. User-entered
sample nicknames are included. The optional ZIP also contains processed label
pixels. Both exports use Android's save-document picker and are explicitly initiated
by the user; the scanner performs no upload. A failed export may leave a partial
output document that needs deleting.

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

Per-image ground truth enters scoring **only after** OCR and production extraction. Ranking
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
- Stored PNG previews have a 64 MiB batch cap divided among selected images;
  later previews may be omitted without suppressing their OCR attempt. A base crop
  larger than its per-image share fails visibly (retry with fewer selected images).
  Image cache is private, removed on screen disposal/new run; process-death leftovers
  older than 24 hours expire on the next run. Durable CSV history is separate.

## Validation scope

Host checks cover the 30-case plan, shear bounds/inverses, and ground-truth scoring
without promoting low-confidence text into fields. Additional host tests cover
per-image CSV truth, multiline quoting/formula safety, atomic row replacement,
reopening history, multi-batch export and explicit deletion. Existing host scanner tests
remain in the same suite. Debug Robolectric wrappers are authored in `testDebug`.
A host type-check of the preprocessing code uses real OpenCV Java API declarations;
it does not execute native filters. Physical-phone accuracy, timing, heat and the
new Compose screen require running this debug build. No emulator acceptance is
claimed.
