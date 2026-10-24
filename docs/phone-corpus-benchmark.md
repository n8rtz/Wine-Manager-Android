# Bundled 168-photo phone benchmark (debug only)

## Use on the phone

Build/install **debug** from this branch, then open **Profile / Settings → Run bundled 168-photo benchmark**. It is separate from both existing OCR Labs. Normal release source sets do not contain these photos or this Activity.

1. Choose **Production v1.0** (shared 15-second scanner), **Production v2.0** (guarded producer, 60 seconds per OCR engine), or **Lab** (latest spatial-merging variant, 60 seconds per engine). Each option runs only its selected implementation.
2. Tap **Start / resume remaining photos**. No picker, image download, desktop OCR or saved-text replay is used.
3. Keep the screen open and the phone powered. The screen stays awake while working; runs can take hours and generate heat. Stop requests take effect after the current photo. Backgrounding requests a stop. Closing the Activity or process death may interrupt the current photo, which will be reattempted on resume.
4. Read the horizontally scrollable aggregate table and expand **Show per-photo results** for expected/recognized values and outcomes.
5. **Export scored CSV** uses Android's document picker. Copy stats and a separate **Export raw diagnostics CSV** (tokens, boxes, scores, engine traces and retained earlier attempts) are also available. Export before switching to a new run or installing another APK.

Resume skips all checkpointed attempts, including failures. **Retry failed / partial** explicitly reattempts failed/incomplete/deadline-limited images; it does not retry images merely because their answers disagree with the reference. The latest attempt replaces that image's scored result; earlier attempts remain on disk. There is no automatic best-result selection. Exported attempt numbers distinguish retries.

New runs preserve old files. Runs are isolated by build SHA, APK install/update timestamp, mode, and UUID. An APK reinstall/update starts a new namespace, even for the same source SHA: outputs from changed binaries cannot silently mix. Old run files remain private app data, but this first UI does not browse old run archives; export before changing runs/builds. Uninstall/clear app data removes checkpoints.

## Exactly which photos?

`app/src/debug/assets/phone-corpus-v1/images/` contains **168 JPEGs, 31,163,745 bytes (29.72 MiB)**. Their bytes match the previously evaluated, EXIF-oriented, maximum-1600-edge JPEGs—not newly substituted labels. All transformed SHA-256 hashes match the frozen reference. The builder additionally verified each original source Git blob SHA-1.

- Source: `AntoninLeroy/wine_label_reader_toolkit`, commit `eb2c14ba4f9a1b6efaa476696439754a9abde8ce`.
- `inputs.json`: image identities and hashes only; the runner receives only these.
- `scoring.json`: frozen provisional references, blank adjudications, accepted normalized aliases and saved baseline predictions. It is read by the scoreboard, not the OCR runner.
- `manifest.json`: provenance, hashes and private-distribution warning.

The upstream images have no declared redistribution license. The user expressly requested private test embedding. **Do not publish this debug APK as a public release.** Release APKs are not inflated by this corpus. Genuine Medium model assets are still supplied by the existing `preparePaddleMedium` build task; no new OCR models were introduced.

Reproduction requires the pinned upstream archive and Pillow 13.0.0:

```sh
python tools/corpus/bundle_phone_corpus.py /path/to/pinned-source.zip
```

The builder does not fetch anything or change frozen reference hashes. The large upstream ZIP is not checked into Git.

## Execution, isolation and scoring

Production v1.0 calls the real FILE scanner with its ordinary 15-second budget, localization, confidence gates and automatic form presentation. The per-image clock starts before copying/verifying the packaged photo. Shared scanner preflight initializes services before that clock; these are not cold-start timing measurements.

Production v2.0 and Lab decode fresh pixels and run the same full-label crop OCR path used by the prior corpus harness: ML Kit and Medium, each with the existing 60-second experimental budget. V2.0 selects the former `lab-guarded-producer` implementation without spatial merging. Lab selects only the latest spatial-merging + guarded extraction implementation; it does not also run v2.0, trigram or residual contenders. Per-engine field comparison work is skipped for these benchmark modes. The original form extraction remains an internal anchor required by the guarded algorithm, not an additional scored contender.

Each mode has exactly one current scored pipeline: `production-v1.0-15s`, `production-v2.0-guarded`, or `lab-spatial-v1`. Saved historical-baseline rows remain and are not rerun. V2.0 and Lab are **not** 60-second production-camera scans. Phone timings and emulator timings are not interchangeable. The normal Add Wine scanner is unchanged (v1.0).

Both expected engines and Medium assets are required at preflight. Execution errors/missing summaries do not receive correct-blank credit. Deadline-limited or truncated executions can retain real partial predictions and are flagged separately. Failed eligible forms conservatively count as multi-error as well as appearing in the explicit failure column. No manual cellar entries are read. Raw tokens/provenance stay in private per-attempt files; lab traces also retain their detailed CSV. Both user-facing exports are text only, not photo uploads. Raw export streams one attempt at a time and uses the same formula-safe CSV encoder.

A process mutex prevents concurrent corpus workers. Native OCR remains under the existing scanner/native gates. Only one image is processed at a time; full traces are removed from the small checkpoint projection before loading the result set. Checkpoints use same-directory temporary files and atomic rename, with replace fallback. An incomplete temporary checkpoint is ignored after restart. References never enter the recognition/extraction adapter.

Scoring uses normalized exact accepted aliases, not fuzzy target matching. It distinguishes correct values, wrong values, missing values, correct blanks, unsupported fills, unresolved slots and execution failures. Six-field completion excludes the eight partly unresolved forms. The full-corpus target remains ≥128/160 complete and ≤7/160 multi-error; partial runs do not establish that target.

The saved baseline is **not rerun**. Both a same-attempted-photo baseline and full-corpus baseline are displayed. This is a provisional development benchmark, not independently adjudicated held-out accuracy. Baseline source: `tools/evaluation/blank-aware-rescore-v1/paired-fields.csv`, `baseline_output`.

Frozen parity oracle:

| Metric | Saved baseline |
|---|---:|
| Fully correct eligible forms | 26/160 |
| Correct values | 597 |
| Correct blanks | 77 |
| Wrong values | 100 |
| Unsupported fills | 20 |
| Missing values | 197 |
| Reference slots: values / blanks / unresolved | 894 / 97 / 17 |

## Validation performed here

- **149 host tests pass**, including all 168 bundled image hashes, manifest hashes, reference disposition/alias parity and saved-baseline counts; recognition-network boundary and SQLite import checks pass.
- **2,030 actual Kotlin JVM checks pass**: all 1,008 baseline field outcomes and normalization parity, complete-form denominator/result, failure/blank/unresolved cases, real lab statistics and CSV formula escaping. These compile the actual math, journal, lab stats and CSV encoder, not Android UI.
- **43 actual Kotlin assignment/policy/state/scan-stat checks pass**, with the existing host geometry fixtures.
- `git diff --check` passes.
- **Android/Compose/runner/ViewModel compilation and device execution remain unverified.** `./gradlew :app:compileDebugKotlin --offline` failed before compilation because the Gradle wrapper distribution could not be downloaded in this sandbox (`SSLHandshakeException` at services.gradle.org). No APK was produced here. The earlier authorized GitHub workflow dispatch also returned HTTP 403; no emulator run started.

Re-run JVM checks with Java 21 and Kotlin 2.2.10:

```sh
bash tools/run-phone-corpus-checks.sh
bash tools/run-choice-checks.sh
bash tools/run-host-checks.sh
```

Build on the normal Android/Code on the Go setup with `./gradlew :app:assembleDebug`. Required device smoke checks: compile/install; Settings launch; one-photo checkpoint; Stop/Resume; background/rotation/process-death recovery; failed-engine reporting; CSV opening; then the full 168-photo pass. No accuracy improvement is claimed before that run.

## Upload-size export update

Benchmark exports now use **Prepare scored CSV export** / **Prepare raw diagnostics CSV export**, followed by a **Save** button for each prepared file. Files over **25,000,000 bytes** are split at CSV record boundaries, with the exact header repeated in every numbered part. A typical 35 MB file produces two parts; larger exports can produce more so every file stays below the upload cap. Quoted newlines, escaped quotes and UTF-8 bytes are preserved. A single record too large to fit is an explicit error, never silently truncated. The original checkpoint data is unchanged.

**Split an existing CSV for upload** opens Android's document picker and can split a previously saved 35 MB export from an older build, without rerunning OCR or accessing that build's run namespace. Save and upload all resulting parts. No broad storage permission or uploads are added. Both preparation and file copying run off the UI thread. Prepared parts are temporary cache files; if the app is killed or cache cleared, prepare/select the source again.

Validation: 77 actual Kotlin JVM split checks pass, including a ~35 MB file → two parts, exact byte boundaries, multiline/escaped/unicode fields, header repetition, multiple parts and byte-for-byte reconstruction. Android SAF/Compose integration still requires a device smoke test; this sandbox cannot establish APK build success.

## Three-mode update validation

The selected implementation is identified in the run metadata and CSV pipeline ID. Mode/build/install isolation prevents mixing old LAB checkpoints with the new spatial variant. Export earlier runs before updating. Mode labels and scoreboard routing have host wiring checks; 160 host tests, 85 actual Kotlin spatial-merger checks, 12 actual Kotlin spatial-to-guarded integration checks and 2,030 scorer/stat/CSV checks pass. Android Activity/ViewModel wiring and a full APK build still require verification in Android Studio. No phone accuracy improvement is claimed before testing.

## Saved Production v2.0 comparison

Every benchmark mode now displays two additional rows from the user's completed 168-photo phone run on build `28bce9e81cc3`, whose original pipeline ID was `lab-guarded-producer`:

- **Saved Production v2.0 · same attempted photos** — scored only on the image IDs checkpointed in the current run.
- **Saved Production v2.0 · full corpus** — all 168 photos, 160 fully scorable forms: **34 complete / 43 one-error / 83 multi-error**, 630 correct values, 80 wrong values, 184 missing values, 77 correctly blank and 20 unsupported fills.

The saved predictions are bundled in debug assets as `phone-corpus-v1/saved-production-v2.json`. `tools/corpus/bundle_saved_phone_v2.py` reproduces them from the compact full-run analysis, retaining the source CSV/build/pipeline and hashes. They are loaded only by the scoreboard, never supplied to recognition/extraction, and never rerun. No new photos or models are included.

These rows appear in the on-screen table, copied statistics, and scored CSV (including per-field expected/recognized values). Exported saved-v2 rows include `result_origin`, `source_build`, `source_pipeline` and `source_csv_sha256`, distinct from the current export build. Existing historical frozen-baseline rows remain labeled historical; **they are not Production v1.0**. Running v1.0 or Lab therefore provides a matched-photo comparison against saved v2.0, without running the other contenders. This does not invent a full saved v1.0 result.

163 host tests pass, including all 1,008 saved prediction values against the reproducible bundle, full-run scoring parity, first-19-photo parity (4/15 complete), and empty-subset handling. Android/Compose rendering has not been executed in this sandbox.
