# Full Android corpus run — completed

**Subsequent scoring:** [all 168 photos visually annotated and compared](public-label-corpus-accuracy-v1.md). The execution-only accuracy limitations below describe this original run audit; the linked report supplies provisional, explicitly denominated correctness results.

**Status: successful; 168 of 168 byte-distinct candidate photos processed and audited.**

- [OCR run 39716483937](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39716483937): success.
- Scanned source commit: `80d5d8eb43ace0d24752acb5ebdfb89cb6973d0e`.
- Job: 2026-10-22 03:22:39–05:04:13 UTC (1h41m34s including setup/build).
- Instrumentation: 5,596.246 seconds (1h33m16s); `OK (1 test)`.
- [Result preservation run 39726037954](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39726037954): success.

## Verified execution

| Check | Result |
|---|---:|
| Selected photos with matching host/device SHA-256 | 168/168 |
| Production scanner JSON reports | 168/168 |
| OCR Lab CSV image results | 168/168 |
| Medium lab runs completed | 168/168 |
| ML Kit lab runs with text | 167/168 |
| ML Kit lab runs reporting no text | 1/168 |
| Medium recognized lines | 1,422 |
| ML Kit recognized lines | 1,267 |
| Missing or duplicate final comparison summaries | 0 |
| Lab error rows | 0 |
| Reported production bitmap-lifetime violations | 0 |
| Production deadline-cancelled reports | **95/168** |

Every exported image name/hash was also checked against the committed acquisition inventory. Exported source-build IDs and Medium detector/recognizer hashes match the pinned run. This is independent of the JUnit pass: the export verifies exact image coverage, not just counts.

The single ML Kit no-text image is `c4c006c5404752ebfecaf1f4364e91dcb9c30b18.jpg`; its Medium lab run completed. No image was silently dropped to obtain a passing result.

## What ran

Each verified input ran through the real `OfflineWineScanner.scan` FILE-input entrypoint, then the real OCR Lab (`TextLabRunner` and `TextLabWineExtraction`). The lab used ML Kit and the genuine v6 Medium detector/recognizer, with baseline combined-form and experimental evidence outputs on the same lab readings.

Runtime: API 35 Android emulator, native x86_64 CI-only debug configuration. The code, models and deadlines were not relaxed. CI uses same-version upstream OpenCV 4.12.0 JNI for x86; normal phone builds still use the existing minimal arm64 prebuilt. Production scanner and OCR Lab have different budgets and orchestration; they are not interchangeable measurements. Camera/picker UI is not exercised.

**The 95 production deadlines are observed emulator results, not phone latency measurements and not hidden by the successful lab runs.** The test succeeds when all diagnostic lab runs/extractions complete and bitmap-lifetime checks pass; it does not require every production scan to beat its deadline.

## Field output coverage — not accuracy

Nonblank field counts below describe output/abstention only. They do not establish correctness, wrong-fill rate, or an improvement.

| Field | Normal production scanner | Combined lab baseline | Experimental lab |
|---|---:|---:|---:|
| Producer | 108 | 111 | 96 |
| Wine name | 117 | 116 | 124 |
| Country | 116 | 137 | 146 |
| Region | 96 | 118 | 120 |
| Subregion | 77 | 96 | 90 |
| Vintage | 125 | 146 | 137 |
| **Total nonblank, out of 1,008 fields** | **639** | **724** | **713** |

The baseline lab generated some suggestions for 167 images and none for one; one suggestions result was marked partial. The experimental lab generated suggestions for 166 images and none for two. More or fewer filled fields is not inherently better.

This source still has no fully reviewed field annotations or bottle-level holdout. The 168 inputs are byte-distinct photos, not a proven count of distinct wines. No reference answers were supplied to recognition. Accuracy remains explicitly null; this run completes acquisition-to-inference execution, not benchmark annotation or the planned role/composition refactor.

## Saved results — no ZIP required

All are committed under `tools/evaluation/public-corpus-run-39716483937/`:

- `ocr-comparison.csv`: 3,898 data rows, approximately 3.3 MiB. Compact plain CSV with raw OCR, engine scores, coordinates, model hashes, isolated field summaries, baseline/experimental fields, reasons, alternatives and statuses. Graph observation/hypothesis trace rows are excluded from this compact export.
- `production.json`: all 168 normal scanner reports, including fields, tokens, timings, deadlines and diagnostics (approximately 1.9 MiB).
- `audit.json`: exact coverage validation, run/build identity, status counts and original per-image CSV hashes.

The original full CSV traces and CI logs remain in the source run's `private-corpus-results` artifact (seven-day retention). The committed exports do not depend on that artifact remaining available. No input photos or model binaries were added to Git or app assets.
