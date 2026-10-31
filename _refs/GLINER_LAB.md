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
recover a blank/legal-text-contaminated producer from corroborated estate-name evidence. It uses
the app's existing bundled grape vocabulary, not reference answers. Ambiguous or unsupported
candidates are rejected. Every altered field is YELLOW and logged. These heuristics are experimental
and were tuned on the available corpus; the phone test and a held-out corpus are needed to assess
real improvements and regressions.

JSON exports preserve final fields, `base_fields`, `base_outcomes`, `gliner_decisions`, exact
`decision_readings`, `raw_readings` (with source engine, estimated flag, confidence and box), and
`lane_hints` with full scores. `same_ocr_comparison` reports paired eligible-label and per-field
gains/losses versus the base on exactly the same readings; it is not a Lab v7 baseline. The first
result wall time includes model loading and cold setup; batch per-photo averages exclude that work.
The host tool `tools/host-lab/replay_gliner_decisions.py` evaluates the actual Kotlin policy from a
phone export using reference targets only for scoring. Older exports lack full hint scores and exact
decision input text, so their replay is explicitly approximate.
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
