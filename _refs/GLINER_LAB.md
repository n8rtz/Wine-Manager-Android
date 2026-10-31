# GLiNER lane lab

Settings → Scanner testing → GLiNER lane lab is an experimental workflow, separate from the
production scanner and Lab v7. Its INT4 model is optional and is not committed to the repository.
It uses the same curated 124-photo evaluation set and the Lab v7 / frozen Prod v6 results only for
scoring comparisons. The score tables compare the actual photos completed by this run.

## Module boundary

`:gliner-workflow` owns the model bundle, tokenizer, ONNX graph, document/lanes and the GLiNER field
decision policy. Android adapters for the app's existing OCR clients, dataset, comparison UI,
foreground service and reusable evidence extractor live in the module's `src/appIntegration/java`
source directory and are compiled into `:app` to avoid a circular app dependency. Lab v7 and Prod
remain separate; they do not invoke the new decision policy. The shared evidence extractor is used
as a base result through the adapter, not modified by this workflow.

The workflow reads both ML Kit and PP-OCRv6. Fusion produces the sequence used by GLiNER only;
the app adapter sends the actual original per-engine OCR lines (with their engine, score, estimated
flag and box) into the base evidence extractor. GLiNER never removes words from that input or
pretends a synthesized span was independently read by both engines.

After the base extraction, `GlinerFieldDecisionPolicy` considers typed lane hints and bounded OCR
candidates. It may repair an identity collision or geographic fallback with a unique exact-line
wine hint; recover a blank or producer-prefixed grape line only when two independent engines read
the same overlapping line; complete a grape title from an adjacent two-engine-consensus prefix; or
recover a blank/legal-text-contaminated producer from corroborated estate-name evidence. Policy v3
also considers: two-engine quoted/prominent cuvées over a geographic fallback; an adjacent title
anchored to the selected estate; a corroborated wine style and descriptor for a blank name;
classification-contaminated estate names supported by both OCR engines and a place read on the
label; and removal of an unsupported subregion that appears to be a fuzzy producer-word leak. These
rules use the app's existing grape and style vocabularies and observed text, never reference answers.
Ambiguous or unsupported candidates are rejected. Nonblank altered fields are YELLOW and a cleared
field is RED; each decision is logged. These are experimental heuristics tuned on the available
corpus, and need a phone run and held-out labels to assess real improvements and regressions.

JSON exports preserve final fields, `base_fields`, `base_outcomes`, `gliner_decisions`, exact
`decision_readings`, `raw_readings` (with source engine, estimated flag, confidence and box), and
`lane_hints` with full scores. `same_ocr_comparison` reports paired eligible-label and per-field
gains/losses versus the base on exactly the same readings; it is not a Lab v7 baseline. The first
result wall time includes model loading and cold setup; batch per-photo averages exclude that work.
The host tool `tools/host-lab/replay_gliner_decisions.py` evaluates the actual Kotlin policy and
bundled vocabularies from a phone export, using reference targets only for scoring. It reports paired
eligible and excluded-field changes against both the base and exported final fields. Older exports
lack full hint scores and exact decision input text, so their replay is explicitly approximate.

### Latest same-OCR experiment

The v2 phone export from build `1d81827` scored 86/121 eligible complete labels (124 attempted).
Replay of the same v2 decision inputs reproduced 86/121 and every exported final field. Replaying
the v3 policy on those exact inputs gives 95/121: nine additional complete labels, no complete-label
losses, and four producer, four wine and one subregion field gains with no field losses. Four of the
producer gains are repeat photos of the same estate; they are not four independent producer examples.
Excluded resolved fields also had no gains or losses. V3 has **not yet been verified on Android**:
the replay doesn't rerun OCR or inference. This dataset was used to develop these rules; these
numbers are in-sample results, not a holdout accuracy claim. More than 90% would require 109/121.
The remaining replay errors comprise 15 producer, 14 wine and one subregion field. The partial
module boundary described above remains unfinished.
The screen supports copying results, JSON/CSV export, and foreground-service execution while
navigating away or locking the phone.

## Model contract

The existing GLiNER-Small INT4 ONNX export uses marker IDs `<<ENT>>` = 128002 and `<<SEP>>` =
128003 with its shipped tokenizer/config. The labels and text are wrapped once with `[CLS]` and
`[SEP]`; `words_mask` is one-based on text-word starts. The rectangular span grid has
`words * max_width` entries with invalid ends masked. The graph returns
`[1, words, max_width, 3]`. The decoder uses the configured score threshold, defaults to 0.50,
and reports each lane's best pre-threshold probe. The output shape, prompt ids and unknown-token
count are included in the result diagnostics.

A new model still needs its own compatible exported INT4 graph, tokenizer and configuration. The
app can import the model bundle from device storage. Optional build-time provisioning is pin-gated;
see [GLINER_MODEL_UPLOAD.md](GLINER_MODEL_UPLOAD.md). No model or APK build artifact belongs in Git.

Historical notes from the earlier implementation are retained in [GLINER_LAB_HISTORY.md](GLINER_LAB_HISTORY.md).
