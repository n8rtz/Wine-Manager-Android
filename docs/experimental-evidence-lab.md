# Experimental evidence architecture — lab-only v2

## How to compare on the phone

Open the existing OCR Text Lab. Enable **Compare experimental evidence pipeline (keeps production baseline)** and keep **Detect text lines first** enabled. Run the batch normally. The combined form table now labels **Production** and **Experimental** rows for each image. The independent OCR-engine table remains below it. Export the same batch CSV.

The option defaults OFF. Both extractors consume the same saved ML Kit + Medium readings: selecting it does not run another OCR model, extend production deadlines, change intake values, or replace production behavior. Single-line mode produces an explicit experimental failure row rather than pretending full-frame coordinates describe a layout. The selected extraction mode is recorded in batch metadata.

## Architecture delivered

All implementation is in the debug source set. No production sources were modified.

1. **Observation layer:** original text, actual confidence, estimated flag, engine/variant and copied normalized boxes. No TokenMerger winner selection or rejection of weak observations at this layer.
2. **Phrase graph:** retained single readings plus bounded alternative case-boundary, horizontal, connector-linked and three-line segmentations. Each hypothesis references observation IDs; overlapping contained text is not automatically concatenated. The original interpretation remains available.
3. **Role and field hypotheses:** lexical administrative/style/producer/place catalogs plus relative geometry/paragraph cues. Producer/brand, title, vintage and geography interpretations compete. Exact observed geographic paths are bundles, not independently guessed parents. Producer catalog location is deliberately not used as wine origin. Shared reference facts are reused, not the production extractor or its bottle-driven repair helpers.
4. **Joint solver:** bounded beam over producer, name, coherent geography bundle and vintage; shared observation/overlapping-location conflicts prevent double identity use. One exact printed appellation may legitimately name the wine and supply geography. Distinct evidence paths remain separate during beam search. Near-tied final assignments yield field-level abstention. Ranking units are not probabilities; repeating an engine reading never increases confidence.
5. **Presentation:** all accepted experimental values are YELLOW. Only the existing cellar display adapter is shared, to keep state-as-country and Region/Subregion conventions comparable.

## Bounds and diagnostics

Per image: 128 observations, 384 phrases, 384 field hypotheses, 16 alternatives per assignment group, beam width 64, cooperative two-second extraction budget. Bounds/truncation are reported. Budget exhaustion is an explicit failure, never a hidden fallback to the baseline. Synchronous local KB calls cannot be preempted midway; this is not a hard phone latency guarantee. No bitmap or native-model ownership is added.

CSV rows:
- Existing `production_combined / form_summary` is preserved.
- `experimental_evidence / evidence_summary` carries six fields, states, reasons, alternatives, version, role diagnostics and `work_ms`.
- `evidence_observation`, `evidence_phrase`, `evidence_hypothesis` trace candidate values back to original readings and ranks.

Only bounded diagnostics are added. CSV uses the existing formula escaping/export path. There are no uploads, new permissions, network KB clients or model downloads.

## What the experiment does NOT establish

Version 2 passes a development-corpus improvement gate: **154 correct targets, zero wrong fills and six abstentions**, compared with the unchanged baseline’s 147, four and nine. See [the reproducible comparison](evidence-v2-comparison.md). This is **not a production replacement or a held-out accuracy claim**. Generic role ranking and phrase generation remain incomplete: damaged spellings, designation attachment, competing blocks and fragmented labels can lead to more abstentions than the baseline. Candidate/beam pruning can also lose a useful interpretation; truncation is visible, but search is not exhaustive. The underlying KB schema is unchanged; avoiding producer-location inference here is not a completed catalog migration.

No held-out-bottle evaluation or calibrated accuracy score has been completed. The current saved batches are development/regression evidence, not a holdout. Do not count every populated field as correct or every abstention as an error. Next evaluation should separately annotate verified answers, retain all rescans, split by bottle, and report wrong fills, abstentions and cross-scan consistency. Device timing remains necessary.

## Executed validation

Host Kotlin checks compile the graph, solver, lab adapter, CSV/batch/table helpers and execute invariant tests: unchanged raw observations/scores, retained segmentation alternatives, no GREEN, complete-year requirement, observation/phrase bounds, repeated-engine non-inflation, timeout, baseline invariance, idempotent comparison rows, single-line failure, default-off selection. Five saved batches (28 image instances) run both pipelines; existing baseline assertions remain in force. Newest input is `architecture-review.json` from `e1b6876b-b2f1-46e9-bd1a-c478c82dfe01`, not independently verified answers.

Host replay files are generated under ignored `.ocr-harness/checks/*-preview-replay.csv`. Python tests and the offline boundary check are also run. No Android APK/Compose compilation, instrumentation/Robolectric, emulator, fresh OCR inference or phone performance measurement was executed.

## Version 2 changes

- Alternative segmentations of the same overlapping text cannot concatenate into duplicated phrases. Horizontal heading fragments can participate in connector-linked titles; dangling connectors are not standalone identities.
- Joint assignment enforces observation ownership across engines and variants, not merely token IDs. A brand cannot reuse its final word as a competing wine name under another engine's observation ID.
- Normalized-equivalent values (case, accents, verified term spacing) no longer create false ambiguity. Complete phrases and designations outrank incomplete fragments, with explicit weak-content and fuller-overlapping-reading penalties. Scores remain original; these are ranking terms, not probabilities.
- Closed producer-role and exact-core appellation suffix alternatives stay alongside raw readings. Bounded label-local tail alternatives require supported producer context; producer corrections require multiple engines' standalone title observations. No bottle identities are encoded by these rules.
- Partially read geographic suffixes preserve supported country/region but withhold finer locality instead of silently substituting a coarser appellation. Recognized country suffixes and legal abbreviations are exempt.
- Export now uses `evidence_details` for graph provenance; the journal's `details` argument previously overwrote those diagnostic values. Tests cover the exported contents.
- `tools/evaluation/evidence-targets.json` separates declared development targets from observed OCR. The host suite runs the evaluator and rejects aggregate, per-bottle, and previously-correct-field regressions, as well as new wrong fills from former abstentions. Synthetic unrelated-name tests exercise the structural rules without using the target bottles.
