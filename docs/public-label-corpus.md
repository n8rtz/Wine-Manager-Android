# Public label corpus acquisition — initial audit

## Acquisition completed

Source: `AntoninLeroy/wine_label_reader_toolkit`, pinned revision `eb2c14ba4f9a1b6efaa476696439754a9abde8ce`.

- 163 images from `img/train_and_valid/X` and seven from `img/to_read`: **170 candidates**.
- Excluded masks (`y`) and copied training/validation directories (`X_train`, `X_valid`).
- **168 exact-byte-distinct images**; this is NOT yet a count of distinct bottles.
- Three perceptual near-duplicate pairs flagged for review, not automatically classified as identical wines.
- Seven originals have a short edge below 400 pixels. Individual label readability still needs review.
- Local derivatives total **31,781,389 bytes**, EXIF-transposed, RGB, maximum edge 1600 without upscaling, JPEG quality 88. Source bytes were hashed before transformation, then discarded; metadata/EXIF is not preserved in derivatives.

Images and working annotations reside outside the repository at `/home/user/wine-evaluation/public-candidates`. Only reproducible tools, source URLs, revisions, hashes and dimensions are committed. No public or user label images enter the app, Git, or recognition KB.

## Important limits

The source has **no declared license**. The user has explicitly authorized private testing. Rights clearance is not a gate for this private experiment; no images enter app assets, Git, or exported diagnostics. This does not establish redistribution rights.

This is NOT a 170-example verified benchmark. Four photos received an initial agent visual review, stored as external **draft** annotations; none is independently verified. Some identities/field splits remain ambiguous. No bottle-level grouping or holdout is approved. Some reviewed photos are French labels; country/style diversity is not established for the full sample. No accuracy numbers are reported.

A second source, `qanastek/WinesLabelDataset`, was inspected and rejected as the primary OCR corpus: the reviewed annotated images are 300×226 whole-bottle thumbnails and the repository also lacks a declared license. Its images are not included in this inventory.

## Reproduction

Install Pillow in an external tool environment, and use the configured `gh` client:

```
python tools/corpus/acquire_public_labels.py \
  --output /home/user/wine-evaluation/public-candidates \
  --private-review-unlicensed
python tools/corpus/validate_corpus.py /home/user/wine-evaluation/public-candidates
```

The acquisition verifies Git blob hashes, records original SHA-256, strips EXIF and caches verified local derivatives. Four concurrent downloads; no credentials stored. Resuming is idempotent for complete verified images. `public-source-inventory.json` contains metadata only. Pillow is an acquisition dependency, not an Android dependency.

`validate_corpus.py --require-evaluation-ready` deliberately rejects this corpus today: unreviewed targets and unassigned bottle groups (use `--private-test` for the authorized private experiment; redistribution clearance remains a separate default gate). It also rejects source-image/bottle leakage across splits, missing groups, changed image bytes and duplicate record IDs. Near duplicates still need semantic review, not just hashing.

## OCR execution blocker

The exact production v6 Medium detector/recognizer asset IDs were resolved from `GreatV/oar-ocr` release v0.7.0. Download attempts failed at the redirected `release-assets.githubusercontent.com` host in this environment. Empty failed-download files are not treated as models. No genuine Medium inference was run, and ML Kit is Android-only. No substitute OCR engine was run or presented as production-equivalent results.

Host-only dependencies were installed externally for investigation; there is no new application OCR stack. This acquisition does not establish scanner improvement or complete the role/composition/trace refactor.

## Work still required

1. Run the authorized private experiment; no redistribution is planned.
2. Review readability, identity duplicates, category and geographic/style diversity; replace unsuitable samples to reach 100–200 usable distinct labels.
3. Independently annotate visible text and six-field references, marking inferred facts and unknowns. Group rescans by bottle before freezing development/validation/holdout splits. The four already inspected photos belong in development, not untouched holdout.
4. Execute the Android instrumentation workflow below with genuine model weights. No desktop OCR substitute.
5. Replay baseline and experimental extraction with fixed annotations, report wrong fills/abstentions/consistency, and only then iterate on development failures.

The user need not individually supply all those additional labels. However, acquisition, annotation and actual inference are separate milestones; none should be claimed merely because files were downloaded.

## Authorized Android execution

The user authorized GitHub-hosted Android emulation for private corpus testing, superseding the prior no-emulator restriction for this task. `.github/workflows/private-corpus.yml` first runs a three-image smoke test; manual dispatch accepts up to 168 distinct images. It builds the unchanged app plus an opt-in instrumentation test. Genuine Medium weights are fetched and checksum-checked by the existing Gradle task on the hosted runner, outside this sandbox's download restriction.

`PublicCorpusDeviceTest` runs **two separate real paths** for each image:

- `OfflineWineScanner.scan`: normal FILE-input rectification, engines, shared deadline, ensemble and fill; production reports saved as JSON.
- `TextLabRunner.recognize` plus `TextLabWineExtraction.append`: actual full-image OCR Lab, ML Kit reference and Medium detector/recognizer, normal lab budgets, production-combined and experimental evidence previews; standard CSV checkpoints.

It does not pretend the OCR Lab's diagnostic budget is the production deadline. It does not emulate camera capture or picker copy time. Images are staged into private app storage, never packaged into the APK. Expected answers are not supplied to recognition. Individual JSON/CSV checkpoints retain failures/partial results and bound retained rows. No text/accuracy assertion is made against unreviewed targets. Native failures, timeouts and failed/missing lab extraction summaries cannot pass the smoke test silently. Production deadline results are retained separately, not silently replaced by lab results.

The emulator uses the shipped arm64 libraries through the Google APIs native bridge; a preflight rejects an image that does not advertise arm64 support. Translation is a runtime difference, **not phone performance validation**. No production ABI, model or OpenCV build setting is changed. Artifact retention is seven days and excludes input photos. A small diagnostics check is also published so execution can be inspected through the GitHub API even when artifact downloads are unavailable in this sandbox.

Execution evidence: run `39707956584` successfully acquired the corpus and built the genuine-model Android app and instrumentation APK (91 tasks, 8m28s). The API 30 emulator then timed out during boot, before instrumentation/inference. The next attempt uses API 35, explicit KVM, no snapshots/Vulkan, retained boot logs and bounded instrumentation. Genuine inference is still unverified. Corpus annotation, bottle grouping and holdout remain unfinished.
