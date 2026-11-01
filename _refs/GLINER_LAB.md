# GLiNER lane lab

The v6 decision-policy implementation, reference revision, matched saved-OCR no-hints ablation,
and device/holdout limitations are documented in `docs/gliner-v6-implementation-evaluation.md`.
V6 changes only this experimental workflow; fresh phone performance has not been verified.

Settings → Scanner testing → GLiNER lane lab is an experimental workflow, separate from the
production scanner and Lab v7. Its INT4 model is optional and is not committed to the repository.
It uses the same curated evaluation set as Lab v7 and the frozen Prod v6 results only for scoring
comparisons. The set currently contains 124 owner-reviewed workbook photos plus 69 internet
front-label photos (six from Costco Wine Blog and 63 from the X-Wines test set). The curated score
tables are directly below the run controls, before the personal audit and imported-photo cards; they
remain visible even before a run. Before GLiNER runs, Lab v7 and Prod v6 show their available saved
photo cohorts, explicitly marked as not paired. Once GLiNER has results, their rows score only the
matching photo identities, with matched counts shown. If baseline coverage is incomplete the displayed
GLiNER and baseline totals cover different cohorts and must not be treated as a paired comparison.
When the corpus changes, if the current Lab folder has no results, the app can read the most recent
nonempty saved Lab v7 run for byte-matching retained photos and rescore those predictions against the
current references; the source build and module configuration are shown. It neither modifies the old
run nor creates results for new photos. Old baselines cover new photos only after their first capture
on the phone. Curated GLiNER run results
are held in the current app process, not automatically restored after the process ends; export JSON
to retain them. This layout change does not restore lost runs.

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
label; and removal of an unsupported subregion that appears to be a fuzzy producer-word leak.
Policy v4 adds repairs for address or grape/place leakage into the producer, nested OCR particles,
qualified appellations, estate/appellation line concatenation, and title/producer collisions with a
nearby producer role. It also has narrower single-engine repairs using an observed harvest/year/title
line or a separately printed winery/company name matching the selected producer. Such single-engine
repairs are not independent OCR consensus and carry additional risk. Policy v5 cautiously completes
partial particle-ending producers from an adjacent, independently corroborated line; identifies a
standalone style between such a completed producer and its geographic fallback; joins adjacent
consensus grape lines for a blank blend; and completes an exact grape/style name with an adjacent,
corroborated title prefix. Prefixes may differ slightly between OCR engines or be split into
fragments. Completion requires a unique candidate and excludes known geography, legal terms and
other conflicting fields. The shared bundled origin names and aliases are a conservative veto, not
an inference source; a genuine title that is also a place may therefore be left incomplete.
All rules use generic terms, geometry, model hints, and observed text, never reference answers. Ambiguous or unsupported
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

#### Policy v5 replay (in-sample)

The current #154 Porto reference correction changes the curated v3 export to 96/121 and its v4
replay to 106/121. On the exact saved readings, lane hints and base fields, v5 also scores
**106/121**; all final fields across all 124 rows are identical to v4 (including excluded fields).
Thus v5 adds no measured curated gains or losses versus v4. This is not a fresh curated run.

For the latest exported set of 26 reviewed personal photos, scored against the corrected bundled
references with the app's Kotlin normalizer and aliases, paired saved-input replay gives:

| | v4 saved/replayed | v5 replay | Paired gains / losses |
|---|---:|---:|---:|
| Complete labels | 11/26 | 14/26 | +3 / −0 |
| Correct fields | 129/156 | 135/156 | +6 / −0 |

The field gains are one producer and five wine names; other fields have no gains or losses. An older
export of the same photos replays from 12/26 and 130/156 to 15/26 and 136/156, also +3/−0 labels and
+6/−0 fields. These are repeated observations of the same photos, not another independent dataset.
Both engines' saved OCR was used as-is; no OCR, base extraction or GLiNER model was run again. The
approved expanded producer index may change future base extraction, so these figures do not predict a
fresh phone run. None of the policy-v5 results is verified on Android, and no single-scan latency has
been measured for v5. Both sets informed development and cannot establish holdout performance.

### Temporary internet front-label additions

The combined 193-photo set contains 124 retained workbook photos, six Costco Wine Blog photos
(#191–196), and 63 more selected X-Wines test-set photos (#197–259). Each addition was visually checked to show one wine bottle and a whole, unobscured front label;
the owner chose to retain the original 124 unchanged even when background bottles appear. Many X-Wines samples are catalog-style, tightly framed bottle
photos rather than handheld scans; they do not replace diverse real-world photos. The X-Wines images
are 480×640, and its catalog is also a source for the expanded producer database, so this is not an
independent database holdout. Source archive/member, catalog ID, hash, and scorer-side references
are recorded in `tools/evaluation/wine-app-labels-review-v1/internet-labels-v1.json`; source titles
are not supplied to extraction.

Costco Wine Blog's FAQ permits use of its label photos elsewhere online. The X-Wines repository
provides a CC0 dataset license but its paper describes collecting data on the open web; we have not
verified the rights of the individual underlying photos. **Testing only**; do not redistribute those
photos without resolving rights. Both sets are included in normal app assets temporarily at the
owner's direction. Remove all additions, their records and any associated cache/baseline entries
when model testing is finalized, then regenerate the corpus, coverage and manifests. The original
124 owner-reviewed photos and their historical records must be retained unless separately directed.
Credit and citation: R. X. de Azambuja, A. J. Morais, V. Filipe, “X-Wines: A Wine Dataset for
Recommender Systems and Machine Learning,” 2023, https://doi.org/10.3390/bdcc7010020.

These are candidate references, not owner adjudications. At the owner's direction, a vintage or
subregion absent from the photographed front can be **expected blank** and eligible for whole-label
scoring. A correct blank is scored; an unsupported fill is an error. This is a photo-level target,
not a claim that the wine itself is non-vintage or has no geographic origin. Printed and supported
geographic designations are populated; establishment years are not vintage years. No catalog list
of available vintages is treated as the vintage of a particular photo. #259 shows 2019. #238 remains
vintage-unresolved because faint marks beneath “Vendemmia” cannot be reliably distinguished from a
year. #226 has Delle Venezie DOC as its subregion, but its region remains unresolved because the
DOC spans multiple regions. #196 geography below California also awaits review. Of 63 X-Wines
photos, 61 are now eligible for all-six scoring; 188 of the combined 193 are eligible.

These reference changes alter the scoring signature and denominators. Retained photo matches can be
rescored using the current references; do not silently combine old aggregates with the new corpus.
Saved Lab/Prod coverage still reports only photos actually run, and these new eligibility counts are
not model accuracy results.

The 69 internet photos have no packaged OCR seed or frozen comparison records. On a new Lab v7
run, retained matching frozen per-photo Prod records migrate across the changed corpus signature;
new photos need fresh capture. The scoreboard identifies missing baselines rather than treating
them as zero or claiming they were already run. Historic 121-eligible scores above refer to the
prior 124-photo set. Neither these added photos nor their references have been run or validated on a
phone; after tuning, they are not an independent model holdout.

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

### Repeatable personal audit

The 26-photo corrected reference set is bundled with the GLiNER module and automatically copied to
app-private storage the first time the audit is opened. No JSON download is needed. Existing saved
references are never overwritten automatically; **Load built-in audit** explicitly restores the
bundled set after confirmation, retaining previous runs. The bundle contains image hashes, reviewed
values and prior predictions, but not photos, original filenames or raw OCR. As packaged app assets,
those reference values and hashes are also visible in the source repository and the installed app.

Under **Personal audit**, you can alternatively import a reviewed GLiNER JSON export or use
**Save reviewed fields** after reviewing photos on this screen. Bundled or imported prior predictions
are rescored against the saved references as a baseline, not a new inference run. Private reference
copies and run checkpoints live in app-private, non-backup storage. References can be inspected and
edited per field; blank and unreviewed remain separate. Saving current reviews updates only reviewed
fields. Removing a photo does not delete its
reference: re-select the same original bytes to restore it. If any referenced image is missing, the
full audit will not start. Imports match private images by SHA-256, and each file is checked again
before inference. JSON contains references and diagnostics, not image bytes.

**Run audit test** runs the same pipeline on the saved set without clearing the separate personal
review or changing the curated scoreboard. References are frozen for that run and are compared only
*after* inference, using normalized exact (including saved aliases) and expected-blank matching. Failed
runs and exact bundled-image matches are reported separately and excluded from accuracy totals;
unreviewed fields are excluded. The audit displays the same summary and per-field tables as the
curated GLiNER view, and uses the same expected/output grid for failed parameters. Its table is
personal-audit-only: Lab v7 and Prod scores for different photos are not comparable. The attempted
count includes exact matches even though they are excluded from the scored totals. Raw OCR is
collapsed; the bundled historical baseline intentionally has no raw OCR. A run is checkpointed
after every photo and prior runs are retained.
**Save audit JSON** exports the history, including each run's reference snapshot and per-field
outcomes. App data clearing/uninstall removes the private photos, references and history, so export
important results. Exact hashes cannot detect re-encodings or different photos of the same label;
repeated evaluation or tuning on these wines is not an independent holdout.

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
