# Large image dataset test

> **Retired 2026-10-26:** the 168-photo `phone-corpus-v1` page (`PhoneCorpusActivity`), its
> ViewModel, its scored-CSV importer and its 31 MB debug asset bundle were removed. **Profile /
> Settings → Large image dataset test** now opens the 125-label `WineAppLabelsActivity`, and the
> Prod v1.0/v2.0/v3.0 rows on it are frozen comparators, not live contenders. Sections below
> describing the 168-photo run, the "Saved Production v2.0" comparison rows and **Import scored
> results CSV** describe the retired page and no longer exist in the app; the historical inputs are
> kept offline in `tools/evaluation/retired-phone-corpus-v1/`.

## Use on the phone (live 125-label benchmark)

Build/install the app, then open **Profile / Settings → Large image dataset test**. It is separate from both existing OCR Labs and uses `app/src/main/assets/wine-app-labels-v1/` (125 retained workbook photos).

Only Lab v4 opens the Lab-only expanded producer index (LWIN plus source-tagged X-Wines, TTB and OpenStreetMap candidates); supplemental candidates never reach production or the Prod versions, and every suggestion still requires confirmation. The page itself carries no prose about this — the session copy of the scored stats and this document hold the provenance, the screen shows only state and counts.

**Prod v1.0, Prod v2.0 and Prod v3.0 are frozen baselines.** Each photo runs through them once; the outcome (fields, status, engine flags and the compact pipeline funnel) is saved on the device under `noBackupFilesDir/wine-app-labels-v1/baselines-v1/<corpus signature>/<MODE>/` and every later pass scores that record instead of re-running the version. Only **Lab v4** runs fresh OCR on later passes, so a re-run measures the current model against a fixed baseline. Records are keyed by the corpus signature (inputs/evaluation-set/scoring hashes), so a revised reference set never mixes with an older capture, and they survive app updates (uninstall/clear data removes them). The first pass after upgrading freezes any Prod checkpoints an earlier build already produced instead of asking for a second run. **Retry failed / partial** re-runs a Prod version only for photos whose frozen record is a failure.

All 125 retained rows of the updated, owner-reviewed workbook are eligible and no photo is filtered out of OCR: Club des Sommeliers #14 and the marked-red rows were removed from both workbook tabs, IMG_2732.jpeg stays excluded, and the 168-photo bundle is no longer packaged. The legacy 168/106 selection, its 62 exclusions and its retired 160-label acceptance target therefore no longer apply to this screen.

1. **Lab v4** is the only selectable version (latest spatial-merging variant, 60 seconds per engine). The Prod radios are display-only and show each version's frozen photo count and capturing build. On a first pass the Prod versions run alongside Lab, once per photo.
2. Tap **Start / resume remaining photos**. No picker, image download, desktop OCR or saved-text replay is used.
3. Keep the screen open and the phone powered. The screen stays awake while working; runs can take hours and generate heat. Stop requests take effect after the current photo. Backgrounding requests a stop. Closing the Activity or process death may interrupt the current photo, which will be reattempted on resume.
4. Read the horizontally scrollable aggregate table and expand **Show per-photo results** for expected/recognized values and outcomes.
5. **Copy scored stats**, **Prepare scored CSV export** and **Prepare raw diagnostics CSV export** are in the **CSV export** section; each prepared file gets a **Save** button that opens Android's document picker. Both exports cover all four versions in one file: scored rows carry `build`/`mode`/`run_id` (frozen rows also carry `result_origin=frozen_baseline` and `captured_build`), and the raw file adds the Lab pass's tokens, engine timings, traces, pipelines, funnel rows and every retained attempt plus the frozen Prod aggregate `pipeline_step` rows — frozen records keep no OCR tokens, so the raw file never claims evidence that was not stored. No images or account information are exported.

Resume skips all checkpointed attempts, including failures. **Retry failed / partial** explicitly reattempts failed/incomplete/deadline-limited images; it does not retry images merely because their answers disagree with the reference. The latest attempt replaces that image's scored result; earlier attempts remain on disk. There is no automatic best-result selection. Exported attempt numbers distinguish retries.

New runs preserve old files. Runs are isolated by build SHA, APK install/update timestamp, mode, and UUID. An APK reinstall/update starts a new namespace, even for the same source SHA: outputs from changed binaries cannot silently mix. Old run files remain private app data, but this first UI does not browse old run archives; export before changing runs/builds. Uninstall/clear app data removes checkpoints.

## Exactly which photos? (retired 168-photo bundle)

*The bundle described in this section is no longer packaged; it lives in `tools/evaluation/retired-phone-corpus-v1/` and the commit history. The live corpus is `app/src/main/assets/wine-app-labels-v1/` (125 photos).*

`app/src/debug/assets/phone-corpus-v1/images/` contained **168 JPEGs, 31,163,745 bytes (29.72 MiB)**. Their bytes match the previously evaluated, EXIF-oriented, maximum-1600-edge JPEGs—not newly substituted labels. All transformed SHA-256 hashes match the frozen reference. The builder additionally verified each original source Git blob SHA-1.

- Source: `AntoninLeroy/wine_label_reader_toolkit`, commit `eb2c14ba4f9a1b6efaa476696439754a9abde8ce`.
- `inputs.json`: identities/hashes for all 168 bundled images, used for integrity checks.
- `evaluation-set.json`: user-reviewed included/excluded image IDs, source workbook URL/hash and decision provenance. Only the 106 included IDs are passed to OCR.
- `scoring.json`: frozen provisional references, blank adjudications, accepted normalized aliases and saved baseline predictions. It is read by the scoreboard, not the OCR runner. One user-confirmed visual correction is applied through `reference-adjudications-v1.json`: review #63 wine name is scored as **Volcanic Rosé**, while the imported source transcription **Volcanie Rosé** remains preserved for provenance. The corpus bundler verifies the imported value/aliases before applying that explicit overlay.
- `manifest.json`: provenance, hashes and private-distribution warning.

The upstream images have no declared redistribution license. The user expressly requested private test embedding. **Do not publish this debug APK as a public release.** Release APKs are not inflated by this corpus. Genuine Medium model assets are still supplied by the existing `preparePaddleMedium` build task; no new OCR models were introduced.

Reproduction of that retired bundle requires the pinned upstream archive and Pillow 13.0.0 (`python tools/corpus/bundle_phone_corpus.py /path/to/pinned-source.zip`). It rebuilds `app/src/debug/assets/phone-corpus-v1/`, which **nothing in the app reads any more** — the live corpus is already committed under `app/src/main/assets/wine-app-labels-v1/` and is regenerated by `tools/evaluation/wine-app-labels-review-v1/build_phone_test_assets.py`. Neither builder fetches anything or changes frozen reference hashes, and the large upstream ZIP is not checked into Git.

## Execution, isolation and scoring

Production v1.0 calls the real FILE scanner with its ordinary 15-second budget, localization, confidence gates and automatic form presentation. The per-image clock starts before copying/verifying the packaged photo. Shared scanner preflight initializes services before that clock; these are not cold-start timing measurements.

Production v2.0 and Lab decode fresh pixels and run the same full-label crop OCR path used by the prior corpus harness: ML Kit and Medium, each with the existing 60-second experimental budget. V2.0 selects the former `lab-guarded-producer` implementation without spatial merging. Lab selects only the latest raw-preserving guarded extraction plus primary-LWIN linked producer/wine resolver; it does not also run v2.0, trigram or residual contenders. Per-engine field comparison work is skipped for these benchmark modes. The original form extraction remains an internal anchor required by the guarded algorithm, not an additional scored contender.

Each mode has exactly one current scored pipeline: `production-v1.0-15s`, `production-v2.0-guarded`, `production-v3.0-lwin-only`, or `lab-v4-expanded-producers` (the current Lab generation, formerly reported as `lab-linked-wine-v1`). Scored CSVs saved under retired mode pipeline IDs (`lab-v4-lwin-only`, `production-v3.0-spatial-merged`) restore and import read-only under their owning mode via `Mode.fromPipelineId` / `legacyPipelineIds`; the file's own ID is what remains displayed, and future renames must be appended to that alias map. Prod v3.0 and Lab v4 run the identical raw-preserving spatial pipeline; they differ only in producer data — Prod v3.0 resolves against the verified LWIN producer index only, Lab v4 against the Lab expanded producer index (same LWIN subset plus source-tagged X-Wines/TTB/OpenStreetMap winery candidates). Saved historical-baseline rows remain and are not rerun. V2.0 and Lab are **not** 60-second production-camera scans. Phone timings and emulator timings are not interchangeable. The normal Add Wine scanner is unchanged (v1.0).

Both expected engines and Medium assets are required at preflight. Execution errors/missing summaries do not receive correct-blank credit. Deadline-limited or truncated executions can retain real partial predictions and are flagged separately. Failed eligible forms conservatively count as multi-error as well as appearing in the explicit failure column. No manual cellar entries are read. Raw tokens/provenance stay in private per-attempt files; lab traces also retain their detailed CSV. Both user-facing exports are text only, not photo uploads. Raw export streams one attempt at a time and uses the same formula-safe CSV encoder.

A process mutex prevents concurrent corpus workers. Native OCR remains under the existing scanner/native gates. Only one image is processed at a time; full traces are removed from the small checkpoint projection before loading the result set. Checkpoints use same-directory temporary files and atomic rename, with replace fallback. An incomplete temporary checkpoint is ignored after restart. References never enter the recognition/extraction adapter.

Scoring uses normalized exact accepted aliases, not fuzzy target matching. It distinguishes correct values, wrong values, missing values, correct blanks, unsupported fills, unresolved slots and execution failures. In the selected 106-label set, six-field completion has 105 eligible forms and one partly unresolved form. No acceptance threshold is set yet for this newly curated subset; the former ≥128/160 and ≤7/160 target is retired. Partial runs do not establish full-set acceptance.

The saved baseline is **not rerun**. The scoreboard shows same-attempted-photo comparisons and a baseline row filtered to the curated 106-label set. This is a provisional development benchmark, not independently adjudicated held-out accuracy. Baseline source: `tools/evaluation/blank-aware-rescore-v1/paired-fields.csv`, `baseline_output`.

Frozen parity oracle:

| Metric | Saved baseline |
|---|---:|
| Fully correct eligible forms | 26/158 |
| Correct values | 597 |
| Correct blanks | 77 |
| Wrong values | 100 |
| Unsupported fills | 20 |
| Missing values | 195 |
| Reference slots: values / blanks / unresolved | 892 / 97 / 19 |

## Validation performed here

- **205 host tests pass**: `python -m unittest discover -s tools/tests`, including bundled-image hashes, corrected reference dispositions and aliases, the 106/62 selection, saved-score parity, and reproducible derived reports.
- `git diff --check` passes.
- **Android APK compilation/device execution remain unverified.** `./gradlew :app:assembleDebug` could not start because this sandbox has no Java executable or `JAVA_HOME`; no APK was produced and no phone run was performed. The attached latest OCR export is still historical for the prior 107-image selection.

Re-run JVM checks with Java 21 and Kotlin 2.2.10:

```sh
bash tools/run-phone-corpus-checks.sh
bash tools/run-choice-checks.sh
bash tools/run-host-checks.sh
```

Build on the normal Android/Code on the Go setup with `./gradlew :app:assembleDebug`. Required device smoke checks: compile/install; Settings launch; one-photo checkpoint; Stop/Resume; background/rotation/process-death recovery; failed-engine reporting; CSV opening; then the full 106-photo curated pass. The excluded 62 are not run. No accuracy improvement is claimed before that run.

## Upload-size export update

Benchmark exports now use **Prepare scored CSV export** / **Prepare raw diagnostics CSV export**, followed by a **Save** button for each prepared file. Files over **25,000,000 bytes** are split at CSV record boundaries, with the exact header repeated in every numbered part. A typical 35 MB file produces two parts; larger exports can produce more so every file stays below the upload cap. Quoted newlines, escaped quotes and UTF-8 bytes are preserved. A single record too large to fit is an explicit error, never silently truncated. The original checkpoint data is unchanged.

**Split an existing CSV for upload** (live page) opens Android's document picker and can split a previously saved 35 MB export from an older build, without rerunning OCR or accessing that build's run namespace. Save and upload all resulting parts. No broad storage permission or uploads are added. Both preparation and file copying run off the UI thread. Prepared parts are temporary cache files; if the app is killed or cache cleared, prepare/select the source again.

Validation: 77 actual Kotlin JVM split checks pass, including a ~35 MB file → two parts, exact byte boundaries, multiline/escaped/unicode fields, header repetition, multiple parts and byte-for-byte reconstruction. Android SAF/Compose integration still requires a device smoke test; this sandbox cannot establish APK build success.

## Three-mode update validation

The selected implementation is identified in the run metadata and CSV pipeline ID. Mode/build/install isolation prevents mixing old LAB checkpoints with the new spatial variant. Export earlier runs before updating. Mode labels and scoreboard routing have host wiring checks; 160 host tests, 85 actual Kotlin spatial-merger checks, 12 actual Kotlin spatial-to-guarded integration checks and 2,030 scorer/stat/CSV checks pass. Android Activity/ViewModel wiring and a full APK build still require verification in Android Studio. No phone accuracy improvement is claimed before testing.

## Saved Production v2.0 comparison (retired with the 168-photo page)

*This section documents the removed debug page. The live page has no saved-v2/baseline comparison rows; its Prod v1.0/v2.0/v3.0 rows are that device's own frozen capture. The offline data is retained in `tools/evaluation/retired-phone-corpus-v1/saved-production-v2.json` for the historical analyses below.*

The retired page displayed two additional rows from the user's completed 168-photo phone run on build `28bce9e81cc3`, whose original pipeline ID was `lab-guarded-producer`:

- **Saved Production v2.0 · same attempted photos** — scored only on the image IDs checkpointed in the current run.
- **Saved Production v2.0 · same curated set** — the historical 168-photo predictions are filtered to the same 106 included image IDs. This is the valid matched reference for current runs; the original all-168 historical totals below are context only and must not be compared directly to the curated denominator.

The saved predictions are no longer bundled in app assets; the offline copy is `tools/evaluation/retired-phone-corpus-v1/saved-production-v2.json`, reproduced from the compact full-run analysis in `tools/evaluation/phone-full-run-28bce9e/` (source CSV/build/pipeline and hashes retained). They are loaded only by the scoreboard, never supplied to recognition/extraction, and never rerun. No new photos or models are included.

These rows appear in the on-screen table, copied statistics, and scored CSV (including per-field expected/recognized values). Exported saved-v2 rows include `result_origin`, `source_build`, `source_pipeline` and `source_csv_sha256`, distinct from the current export build. Existing historical frozen-baseline rows remain labeled historical; **they are not Production v1.0**. Running v1.0 or Lab therefore provides a matched-photo comparison against saved v2.0, without running the other contenders. This does not invent a full saved v1.0 result.

205 host tests pass, including all 1,008 saved prediction values against the reproducible bundle, full-run scoring parity, first-19-photo parity (4/14 complete), and empty-subset handling. Android/Compose rendering has not been executed in this sandbox.


## Pipeline bottleneck table

The benchmark screen shows three **requirement tables** below scored results: ML Kit OCR, Paddle OCR, then post-OCR. The OCR tables represent distinct outputs, not concurrent execution: this benchmark runner executes ML Kit before its Paddle candidate loop. Their counts preserve the latest-attempt state categories (passed, blocked, not reached, not recorded, not applicable). Post-OCR shows the guarded assignment and true dependent resolver gates; evidence observation/hypothesis counts are not presented as requirements because hypotheses are jointly assigned across fields. Linked-Wine gates are conditioned on the Producer resolver having run; they are not forced into a universal field-by-field chain. Producer and Wine name resolver phases are combined where semantically equivalent, with separate field counts. Field correctness comes from benchmark scoring only, including correct expected blanks; unresolved references remain N/A. CSV schema and recognition behavior are unchanged.

Every row defaults to one line with its field counts visible. Its own **+** expands only that row's description; **−** collapses it. Expanded descriptions wrap to the screen width. Both benchmark screens size the matrix columns to the screen width — the step-title column takes a fixed share and the six field columns split the remainder — so the whole table is visible without horizontal scrolling, with theme-colored grid lines between every row and column. Cells show `passed/failed` distinct-label counts from latest attempts, with passed counts in green and failed counts in red (using light/dark appropriate colors). Non-applicable, not-reached and not-recorded observations are omitted rather than counted as failures, and a blank means there was no measured pass/fail count for that field/step. Recognition and CSV columns are unchanged. **Copy scored stats** uses the grouped matrix; CSV exports retain the existing scalar `pipeline_step` schema and per-field scoring records.

- Input verification, services, decoding/preparation and production-only native/quality/localization steps.
- **ML Kit branch:** started, completed, nonblank text, usable mapped observations. ML Kit exposes a combined recognition API; the table does not invent internal detector/recognizer counters.
- **Paddle Medium branch:** started, detector completion, regions, line-recognition attempts, completion, text and usable mapped observations.
- Engine rejoin, extraction KB, raw form anchor, evidence observations/hypotheses and guarded assignment.
- Primary manifest, producer database, wine database and supporting-layer validation.
- **Producer and linked-wine branches, separately for each OCR engine:** eligibility, lookup completion, returned candidates, distance, plus wine/producer observation separation.
- Two-engine/disjoint support, identity ambiguity, rival separation, existing-value protection, confirmed/suggested names and actual field changes.
- Final selected pipeline/field coverage and two explicitly **scoring-only** outcomes (all six correct; at most one error).

### What the matrix counts mean

`X/Y` means **X passed / Y failed** among distinct wine-label images with an observed pass/fail result for that step and field; only the latest attempt contributes. It never counts lines, variants, joined phrases, database hits or reference rows. Shared stages have a single shared count across the six columns. Producer and linked-wine resolver steps are attributed to Producer and Wine name respectively; the wine-link checks specifically about producer compatibility stay under Producer. Other field cells remain blank unless that field had a genuine per-field measurement. Per-field correctness is attached only by the benchmark scorer; reference answers never reach recognition or resolver telemetry.

Not-reached, not-recorded and N/A statuses are intentionally omitted from matrix cells, not reclassified as failures. Thus X+Y may be below attempted labels. Unattempted labels are excluded. The bottom all-fields-correct shared row has the same passed count as the complete total in scored results; unresolved forms are not counted as passed or failed there.

Counts across engine branches are not a single decreasing chain. Engine completion and useful text are different facts: successful no-text OCR completes but supplies no useful text; deadline-limited OCR may still supply real usable partial readings. Confirmation of an already-correct name is not a new fill. Final populated-field rows measure coverage, not correctness; a correctly expected blank is not a coverage success. Tap a row's **+** to read its full step definition.

New debug runs record per-resolver gates without adding queries, OCR passes, reference-name inference, extra votes, or relaxing acceptance safeguards. Asset setup records partial success, so a producer DB that validates before the wine DB fails is visible. Compact stage snapshots remain in current checkpoints after large raw trace arrays are stripped. Aggregate and per-field correctness results are attached only by the scoreboard; reference answers never reach recognition or resolver telemetry.

### CSV schema

Existing summary and field rows, saved-v2 comparisons, split-at-record-boundary behavior and formula-safe encoding are preserved.

- Both exports include aggregate `row_type=pipeline_step` rows with `step_id`, `step_branch`, `step`, `step_definition`, `labels_attempted`, `labels_passed`, `labels_reached`, `labels_blocked`, `labels_not_reached`, `labels_not_recorded`, `labels_not_applicable`, `step_reasons`, and `funnel_version`.
- New raw exports additionally include per-attempt `row_type=pipeline_funnel` rows (`step_id`, `step_status`, `step_reason`). Fine resolver gates are also flattened into final diagnostic summary fields beginning `funnel.`.
- Raw CSV retains all attempts; its aggregate table still uses only current/latest attempts, matching the screen/scored CSV. Never sum per-attempt rows to reconstruct a latest-attempt denominator without selecting attempts first.
- Older checkpoints/exports do not contain the new fine-grained gates. Recover only provable facts from their traces and terminal reasons; show other steps as **not recorded**. Never assume that the absence of a candidate means the database lookup failed. An explicitly recorded early abstention can establish downstream **not reached** states.

### Checks and reuse of existing OCR

`tools/run-pipeline-funnel-checks.sh` compiles actual Kotlin funnel, resolver telemetry, checkpoint JSON, scoreboard and CSV logic with host asset/mode stand-ins. It needs Java 21, Kotlin 2.2.10 and a genuine JSON-java jar/classes directory in `JSON_JAVA_CLASSPATH` (the checks here used upstream JSON-java tag `20250517`). These checks are **not** an Android/Compose/native-runtime build.

```sh
# Default: bundled saved-v2 prediction fixture, with unrecorded stages correctly unknown.
bash tools/run-pipeline-funnel-checks.sh

# Optional: validate all raw parts against a saved scored export, then generate a scored CSV + table.
# No OCR, matching or baseline execution; original field values are preserved.
bash tools/run-pipeline-funnel-checks.sh \
  --scored /path/to/wine-168-lab-f9a8891dc192.csv \
  --raw /path/to/wine-168-raw-lab-f9a8891dc192-part-01.csv \
  --raw /path/to/wine-168-raw-lab-f9a8891dc192-part-02.csv \
  --output /private/path/scored-with-pipeline-table.csv
```

Validation on this branch: 194 Python host tests pass, including grouped-matrix/source-layout contracts (not Android rendering); 75 actual Kotlin funnel, scorer and matrix checks pass on the bundled default fixture. Earlier saved-export validation passed 52 checks on all three attached f9 CSVs before the matrix-only changes. These are not native-device results.

The added `PipelineFunnelDeviceTest` exercises actual packaged native SQLite through spatial preview, checkpoint, scoreboard and CSV on a **disposable emulator**, without OCR. It is added, **not executed in this workspace**. Full Android build/UI/SAF/stop-resume checks remain required; see this session's section in `_refs/THINGS_TO_CHECK.md`. No recognition or latency improvement is claimed for adding diagnostics.
