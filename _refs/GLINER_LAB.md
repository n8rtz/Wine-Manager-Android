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
Policy v4 adds repairs for address or grape/place leakage into the producer, nested OCR particles,
qualified appellations, estate/appellation line concatenation, and title/producer collisions with a
nearby producer role. It also has narrower single-engine repairs using an observed harvest/year/title
line or a separately printed winery/company name matching the selected producer. Such single-engine
repairs are not independent OCR consensus and carry additional risk. All rules use generic terms,
geometry, model hints, and observed text, never reference answers. Ambiguous or unsupported
candidates are rejected. Nonblank altered fields are YELLOW and a cleared field is RED; each
decision is logged. These are experimental heuristics tuned on the available corpus, and need a
phone run and held-out labels to assess real improvements and regressions.

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

The v3 phone export from build `6685981` scored 95/121 eligible complete labels (124 attempted),
matching its prior replay. Its OCR lines, lane hints and base fields are identical to the preceding
v2 export; replaying v3 reproduced every final field. Replaying v4 on these same inputs gives
105/121: ten additional complete labels, no complete-label losses, and seven producer and six wine
field gains with no field losses. Excluded resolved fields had no gains or losses. The producer and
wine of one two-error label both improve; some other gains occur on repeated photos. V4 has **not
yet been verified on Android**: replay doesn't rerun OCR or inference. This dataset was used to
develop these rules; these are in-sample results, not a holdout accuracy claim. More than 90% would
require 109/121. The remaining replay errors comprise eight producer, eight wine and one subregion
field on 16 labels. The partial module boundary described above remains unfinished.
### Your own images and review

Choose **Your images → Choose images** to select multiple files, then **Run your images**. The picker
copies supported images (up to 64 MB and 100 million pixels each) into app-private, non-backup storage;
originals are never modified. Files survive app restarts until removed or app data is cleared. The
run uses the same two OCR engines, INT4 model and post-processing as the bundled run. After a run,
a review prompt opens the imported results: check each correct field; for an incorrect field enter
the correct value or explicitly mark it blank. An unchecked field with no correction remains
unreviewed. **All correct** is a per-photo shortcut. Rerunning an image clears its old judgements.

Imported results never change curated scores or receive reference values during inference. The
separate manual summary counts only completed, fully reviewed labels, and reports reviewed field
counts separately. Exact byte-for-byte matches to bundled photos are flagged and excluded from
manual summary totals; this cannot detect another photo, crop or re-encoding of the same label, or
tell whether a photo appeared in model training. Once the images or their corrections are used to
tune rules, they are no longer an independent holdout; reserve additional unseen labels for final
accuracy checks. JSON exports include `custom_images`, raw decision
inputs, predictions and a separate `manual_review` with each user verdict/corrected value, but not
image bytes. CSV includes corresponding manual-review columns. Imported files persist, but results
and reviews are held in the current app process; save/export before leaving if you need to preserve
them after process death. The foreground service keeps a run alive when navigating away or locking
the phone.

### Reference correction

The owner's later correction makes image #154's subregion **Porto**, rather than expected blank.
The scoring manifest hash was updated. Historical exports contain the older field outcome and are
not directly comparable; use `replay_gliner_decisions.py --rescore` to explicitly score old predictions
against the new reference. Under that changed reference, the earlier v3 phone export rescores from
95 to 96 complete labels and the v4 same-OCR replay from 105 to 106; neither is a new phone run.

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
