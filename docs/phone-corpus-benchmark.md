# Bundled 168-photo phone benchmark (debug only)

## Use on the phone

Build/install **debug** from this branch, then open **Profile / Settings → Run bundled 168-photo benchmark**. It is separate from both existing OCR Labs. Normal release source sets do not contain these photos or this Activity.

1. Choose **Current production scanner** (shared 15-second budget) or **Lab comparison** (60 seconds per OCR engine, current experimental extraction).
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

Production mode calls the real FILE scanner with its ordinary 15-second budget, localization, confidence gates and automatic form presentation. The per-image clock starts before copying/verifying the packaged photo. Shared scanner preflight initializes services before that clock; these are not cold-start timing measurements.

Lab mode decodes fresh pixels and runs the same full-label crop path used by the prior corpus harness: ML Kit and Medium, each with the existing 60-second experimental budget. It reports combined form, guarded producer and trigram summaries separately. Residual diagnostics stay in raw traces and are not promoted into invented filled fields. Lab mode is **not** a 60-second production scan. Phone timings and emulator timings are not interchangeable.

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
