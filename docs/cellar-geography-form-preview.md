# Cellar geography and combined OCR-lab preview

The lab now puts a six-field combined scanning-form preview above the independent engine comparison. CSV exports add one `production_combined` / `form_summary` row per image, including states, reasons and alternatives. This runs the production FillEngine and cellar presentation on the available saved ML Kit and PP-OCRv6 Medium readings—not a best-value-per-engine selection. Saved confidence values are retained; Paddle posteriors remain estimated. This is a saved-crop preview, not shutter/deadline/camera-localization replay, and suggestions require confirmation. It never edits a live form.

## Geography/name policy

- Brunello di Montalcino and Rosso di Montalcino remain appellations in the local hierarchy. Their cellar Subregion is the locality **Montalcino**, below **Tuscany**.
- A separate Brunello cue (including a bounded one-edit spelling) plus Montalcino can suggest Brunello di Montalcino, with confirmation. Rosso is supported separately; competing cues abstain. **Montalcino alone is not enough to infer Brunello.**
- A valley is presented in Region. An actual known valley ancestor can preserve a finer Subregion (Napa Valley / Oakville). No parent is invented.
- A remaining orphan locality is promoted to Region for confirmation, unless geography conflicts; Subregion cannot remain without Region. The manual form also blocks saving a nonempty Subregion with empty Region, without erasing user edits.
- Damaged legal text, alcohol-unit fragments, and locality/type fragments are excluded from name candidates. These filters do not rewrite OCR or raise its scores.

## Latest batch host replay

`form-review.json` retains only observed text, scores and boxes from batch `a2184fe2-abec-4172-a090-87781ed6c44d`. `generate-form-replay.py` exercises the real adapter using the host fixture/reference KB. `tools/run-logic-checks.sh` writes `.ocr-harness/checks/form-preview-replay.csv`.

| Image | Producer | Wine name | Country | Region | Subregion | Vintage |
|---|---|---|---|---|---|---|
| 1 | Kirkland Signature | — | California | Napa Valley | — | 2024 |
| 2 | Kirkland Signature | Brunello di Montalcino | Italy | Tuscany | Montalcino | 2021 |
| 3 | Marqués de Riscal | Verdejo | Spain | Castilla y Leon | Rueda | 2023 |
| 4 | Kirkland Signature | Cabernet Sauvignon | California | Alexander Valley | — | 2022 |
| 5 | Blackstone | Merlot | California | — | — | — |
| 6 | — | — | France | Rhône | Côtes du Rhône | 2022 |

These are observed replay outputs, not independently verified answers or an accuracy score. Image 1 still abstains on wine name; image 6 still lacks producer/name. The combined path can abstain even when an individual engine's table shows a name.

Executed: host extraction/presentation/adapter regressions, latest batch replay, Python tests and offline-boundary checks. Not executed: Android APK/Compose build, instrumentation/Robolectric, emulator or phone validation. No new OCR inference was run on the photos.
