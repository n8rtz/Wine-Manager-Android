# Lab test modifiers

Checkbox source of truth: `LabPostOcrModules.selectableModuleIds` (16 active entries).
The full `ModuleId` enum additionally retains two retired keys for historical decoding. Selections are independent booleans, serialized by stable
key into the run signature/export metadata. Cached OCR is reusable; derived results
must be recomputed for a new combination. Frozen production comparators are unchanged.

## Existing modifiers

| Key | Checkbox | Operating principle |
| --- | --- | --- |
| `blank_subregion_fuzzy` | Blank subregion fuzzy search | Last-resort raw-OCR appellation span search only when subregion remains blank; compatible with selected geography. This is the old fallback, not early discovery. |
| `vintage_recovery` | Vintage recovery (OCR year) | Repair year-like OCR (I9xx/2Oxx), rescue moderate-confidence years, join split crest years and hybrid fill. |
| `stacked_appellations` | Stacked 2-line appellations | Join spatially compatible appellation and classification lines. |
| `final_producer_near_match` | Final producer near-match (d<=1) | Canonicalize a final producer to a unique catalog identity within one edit. |
| `producer_role_particle` | Producer role & 'de' rules | Particle omission, role/grape penalties and expanded producer shortlist. |
| `spatial_semantic_guards` | Spatial join & anchor guards | Apply semantic boundaries to spatial joins and producer evidence/anchor spans. |
| `vertical_wine_title_pairs` | **Retired — no checkbox/new tests** | Historical key retained for old results; rejected after estate-plus-grape regressions. |

## Early candidate discovery — experimental, default off

These five active checkboxes add alternatives **before label context collection and hypothesis
resolution**, regardless of whether a field already has a value. They do not rewrite
OCR, change confidence, or save fields. Each OCR observation is searched separately,
so one engine can supply a useful span without another engine agreeing.

| Key | Checkbox | Operating principle / acceptance |
| --- | --- | --- |
| `geography_spans` | Geography span discovery | Keep recognized geography spans and globally distinctive bounded spelling repairs. Existing hierarchy/context checks resolve country, region and subregion. The checkbox enables bounded repair for its spans independently of the older place-repair switch. |
| `producer_spans` | Producer span discovery | Query the local fuzzy producer shortlist for each span; retain spans with a candidate within one edit. Existing producer role, alias and ambiguity handling still decides the interpretation. |
| `grape_spans` | Grape span discovery | Keep exact normalized grape vocabulary spans, longest compounds first. Feed the existing varietal/wine-name interpretation; does not introduce a new saved grape field. |
| `classification_spans` | Classification span discovery | Keep recognized designations and classification-qualified phrases. Feed wine-name hypotheses; does not invent a new classification field or independently assign geography. |
| `vintage_spans` | Vintage span discovery | Keep spans accepted by the existing plausible-year normalizer; existing vintage ranking decides. OCR-year normalization is reused; broader vintage recovery remains a separate checkbox. |
| `wine_name_spans` | **Retired — no checkbox/new tests** | Open-ended title spans were tested without correctness gains and with regressions. Historical key only. |

### Shared boundaries and provenance

- Search contiguous spans inside each observation: first 24 lexical tokens, at most
  six tokens per span. Hyphens and apostrophes within words are preserved.
- This first implementation does **not** add arbitrary cross-line spans or glued-word
  splitting. Existing spatial and stacked-line modules remain independently selectable.
- Prefer longer spans in enumeration, but retain competing shorter candidates.
- Keep the observation's original box and observation ID. The box is line-level,
  not an invented word-level coordinate; alternatives are not extra OCR votes.
- Merge accepted field families on the same span. Restrict its normal field offers
  to those families to reduce cross-field leakage. Geography hierarchy may populate
  several geographic fields together.
- Existing confidence, administrative/fragment, ranking and abstention checks remain.
  Candidate discovery does not guarantee an output value.
- Existing graph/hypothesis/time budgets still apply. New spans are appended after
  baseline phrases; graph truncation is reported. Broad modules can compete for
  capacity, so combinations are not assumed to be interference-free.

## Presets and experiment protocol

All five active discovery methods are off in Baseline and Best build; All enables those five. Best build
remains the measured preset, not a claim that these new methods have been evaluated.

1. Run Baseline on the same cached OCR and corpus.
2. Run each modifier alone; record per-field gains, losses, blanks, elapsed time and
   truncation. Inspect raw evidence, not just total score.
3. Run pairs, then promising combinations. In particular compare geography +
   classifications, producer + grape, and vintage discovery + vintage recovery.
4. Compare combined changes against each single-method run. Check that one discovery
   family did not steal another's field, multiply engine votes, or exhaust budgets.
5. Regression label #34 / 0064.jpg: `AINT-EMILION O GRAND CRU` must expose the isolated
   `AINT-EMILION` span for bounded repair. Check geography separately from wine name;
   this implementation does not bridge over the stray `O` to invent a qualified name.
6. Do not promote a new combination into Best build until the full corpus is measured.

## Recent builds export

Use **Prepare recent builds CSV export**, then the existing Save button(s). It
exports the same recent runs shown in the table, with run/configuration metadata,
logged aggregate and per-parameter scores, and six final field-value rows for every
dataset wine per run. Field rows include reference values and outcomes, not OCR
observations, images, traces, candidate lists or bounding boxes. Unattempted wines
and unavailable saved run results are explicitly marked. Field outcomes are scored
against the currently packaged references; logged summary scores retain their saved
values. Files use the existing 25 MB CSV splitting/document-picker save workflow.

## One-click automated experiments

The Lab page now offers control + individual toggles (17 configurations), or control
+ toggles + every two-toggle combination (137 configurations). Control is the measured
four-module preset plus blank-subregion fallback, not the currently checked UI selection.
Each experiment flips a flag relative to control: enabled flags are tested by removal,
disabled flags by addition. These are ablations, not only additive experiments.

The suite requires 124 verified cached OCR records and all frozen Prod baselines. Each
configuration gets a fresh result directory and executes sequentially with cached OCR.
Progress shows the configuration index and keys. Stop ends after the current photo;
completed runs are retained. The original checkbox/cache selection is restored afterward.
A compact suite CSV is prepared automatically after normal completion or a user stop,
including every completed configuration, not just the recent ten. Use Save to download.
On unexpected errors the suite stops and completed runs remain on disk; automatic
restart/resume of a suite after process death is not implemented. A long pairwise sweep
can take substantial time and storage. No result is automatically promoted to Best build.

Automated field-by-field analysis (one CSV or all split parts):

```sh
python3 tools/analyze-lab-runs.py suite.csv --control 1 --output comparison.md
```

For manually collected recent-build exports choose the appropriate control rank. The
report lists correct totals, gains, losses, net change, output changes, and every changed
wine/field with before/after values. Mixed reference corpora or incomplete results are
rejected. Saved totals that differ from freshly scored field outcomes are flagged.

## General extraction foundations (ordered experiments)

These modifiers contain no evaluation label IDs or reference-derived name exceptions.
They default off in the measured preset and are enabled by All on. Use **Run ordered
foundation checks (6 configs)** to compare control, then each cumulative stage below.
The ordinary single-toggle suite now uses 17 configurations; the full pairwise suite
uses 137. New single-toggle tests are not substitutes for the ordered cumulative suite.

1. `candidate_isolation`: collect label context before substring alternatives; use
   same-engine/pass physical observations rather than alternative-phrase counts for
   paragraph density; enforce a discovered candidate's field eligibility on low-score
   rescue paths as well as ordinary offers. Original observations remain unchanged.
2. `exact_producer_protection`: retain an already exact catalog answer; prefer a unique
   exact observed identity within one producer edit of the final guess, using supported
   OCR lines and the bounded database shortlist. Abstain on equally close fuzzy
   identities instead of using FTS ordering. This is not unrestricted substring matching
   or a global catalog scan; candidates absent from the shortlist cannot be protected.
3. `administrative_filtering`: use a shared syntax-based administrative role check for
   whole identity phrases and source observations of extracted spans. Legal frames may
   still provide geography, but packaging, founding/operator/importer text must not
   become an identity merely by substring extraction. Conservative: extracting a
   producer embedded in a rejected legal line is not newly implemented here.
4. `role_aware_joins`: require positive catalog/producer-heading/cuvee support for joins,
   reject producer-to-description joins, and apply this to horizontal, vertical and
   connector continuations. This is intentionally conservative; unknown title-plus-grape
   joins are not automatically accepted. Administrative join filtering also applies when
   its modifier is enabled.
5. `broader_discovery`: after the safeguards, enable existing bounded catalog-appellation
   stacking independently of its older checkbox, and admit a classification-framed
   numeric title (reserve/reserva/riserva with an optional gran/grand prefix and a one-
   to-three-digit identifier). Years, bottle numbers and packaging are not accepted by
   that numeric rule. No arbitrary numeric-brand completion is performed.

The suite is a development experiment, not evidence of an accuracy improvement. Confirm
field gains/losses and run a held-out dataset before moving any flags into normal scanning.

## Retained suite decisions and shared OCR (future suites)

Audit: attempts/ already retained each run's full trace, while current/ deliberately
strips it. Existing spatial trace rows contain observations, phrases, ranked hypotheses,
solver pruning/finalists, role filters, gazetteer replacements and final-field reasons.
The previous compact suite export omitted all of these; this was an export/association
gap as well as a few missing instrumentation fields.

Every new suite now retains a manifest under `suites/<id>/`, including started run IDs,
configuration plan, saved run summaries, reference/evaluation snapshots and one shared
OCR snapshot per image. The OCR payload receives a SHA-256 `ocr_ref`. Cache contents are
checked against the snapshot before each configuration; a changed cache stops the suite.
Suite attempts store configuration decision rows, not another raw recognition/token
block. Original observation text is replaced by a hash; on export it links to matching
shared OCR row IDs. Multiple matching readings remain explicit, with engine/box data
available to disambiguate. Derived phrase/name strings may repeat: these are configuration
interpretations, not repeated copies of the recognition dataset. No image bytes are exported.

The automatic suite CSV now adds:
- `suite_manifest`: suite identity, plan, status and archived run metadata;
- `shared_ocr`: OCR once per image, identified by `ocr_ref` and `ocr_row_id`;
- `run_decision`: retained per-configuration evidence/solver/gazetteer records;
- `field_decision`: final value and final-stage selection reason per parameter;
- `diagnostic_status`: explicit availability per run/image.

Instrumentation additions only: generated hypotheses before ranking filters, original
base and adjusted ranks/deltas, explicit field-scope/administrative/limit rejections,
exact-geography filtering, final evidence-field selections, joint geography penalties,
and exact trace-row drop counts. Existing solver finalists, ownership conflicts and
pruning records are reused. Diagnostics are bounded and say when rows were dropped;
absence of a trace event is not proof a candidate was never considered. Joint assignment
is not a simple per-field rank contest: finalists and ownership records explain competing
assignments, and later gazetteer/fallback rows explain replacements after evidence selection.

Use **Re-export a saved suite** in CSV export to select any retained suite and prepare
all its runs again, independently of the recent-ten table. Reference snapshots prevent
later dataset edits from silently changing historical scoring. Manifests are updated as
runs start/finish so stopped suites retain their association too. An interrupted suite
may have missing results, which are marked rather than fabricated. Existing suites from
older builds have no manifests or pinned OCR snapshots and are not retroactively inferred.
The shared OCR can contain label addresses/operator text; treat the export as private.

## Focused diagnostics suite (6 active configurations)

Use **Run focused diagnostics suite (6 configs)** for the targeted follow-up to the
foundation results. Control remains the four measured modifiers plus blank-subregion
fallback; the current manual checkbox selection does not alter it.

1. Control.
2. Control + candidate isolation.
3. Control + candidate isolation + vintage-span discovery.
4. Control + role-aware joins only.
5. Control + broader discovery only.
6. Control + all five foundation modifiers (isolation, exact producer protection,
   administrative filtering, role-aware joins, broader discovery).

These are explicit independent comparisons, not the six-stage cumulative foundation
suite. The suite manifest records `focused_diagnostics` and the complete ordered module
plan. Existing fresh-run execution, pinned shared OCR, decision instrumentation, stop
controls, automatic suite CSV preparation and saved-suite re-export apply unchanged.

## Compressed normalized suite archives (superseded for phone exports)

**Phone exports now use the uploadable JSON package described below. ZIP remains
readable by host tools for older saved files but is not used by the phone.**

Automatic and saved-suite exports now produce **one `.zip`**, not many uncompressed
CSV parts. Tap **Save ZIP archive**. The document picker uses `application/zip`;
ordinary compact recent-build/scored CSV exports remain available and unchanged.
Archives are not split into invalid ZIP fragments. A large archive can be re-exported
without rerunning extraction; platform upload limits still apply.

Archive v1 contents:
- `scores.csv`: small convenience table with run/image IDs; resolve IDs using the files below.
- `runs.jsonl`, `images.jsonl`: long provenance strings stored once per distinct record.
- `candidates.jsonl`: shared candidate identity (phrase, values, source observations, base rank).
- `common.jsonl`: shared pipeline/version/spatial metadata.
- `objects.jsonl`: content-addressed payload definitions, deduplicated across configurations.
- `events.jsonl`: ordered occurrences linking run/image, payload, candidate and metadata IDs;
  volatile sequence/timestamps remain on occurrences rather than defeating deduplication.
- `manifest.json`: schema version, dictionary/record counts, uncompressed bytes and SHA-256
  hashes for the data files; `README.txt` explains reconstruction.

Every original export row can be reconstructed, including unknown future diagnostic
fields. `diagnostic_json` becomes native JSON objects rather than quoted JSON inside CSV.
Generation/ranking event differences are preserved even when candidate identity is shared.
Repeated final results and identical decision payloads are deduplicated across runs.
No new sampling or trace limits are introduced. Existing upstream trace limits and their
counts remain explicit. Maximum standard ZIP deflate compression is used; writing streams
to temporary files rather than constructing the entire archive in memory, and temporary
files are cleaned afterward. Content-hash registries grow with unique dictionary entries.

The analysis command reads ZIP directly and expands only scores for analysis (after
integrity verification), not all diagnostic dictionaries:

```sh
python3 tools/analyze-lab-runs.py suite.zip --control 1 --output comparison.md
python3 tools/lab_suite_archive.py verify suite.zip
```

Existing large suite CSVs (include every split part in order) can be converted without
rerunning the phone tests. Conversion keeps all row types and fields:

```sh
python3 tools/lab_suite_archive.py pack part-01.csv part-02.csv --output suite.zip
python3 tools/lab_suite_archive.py unpack suite.zip --output reconstructed.csv
```

Unpacking restores the verbose representation and is optional; keep the archive as the
source artifact. The SHA-256 manifest detects damaged files; it is an integrity check,
not authentication or encryption. Archives may contain private label/operator text.


## Arena-uploadable normalized JSON packages — current phone format

Arena permits JSON and CSV but not ZIP. All automatic and retained-suite exports now
save plain UTF-8 `.json` (application/json), never binary disguised as text, base64 or
compressed archives. The same lossless dictionaries and event links are preserved.

Save and upload the **manifest JSON and every numbered part JSON**. Each part is valid
JSON and at most 20,000,000 bytes, with short numeric table codes and archive-local IDs.
The small scores CSV is optional: all results are already represented in the JSON events.
Its IDs resolve through the package. Shared dictionary entries may be in an earlier part;
parts are not individually complete diagnostic packages. The manifest states the exact
part count, schema/table mapping, dictionary/record counts, file sizes and SHA-256 hashes.
The reader rejects missing parts or checksum mismatches rather than silently analyzing
an incomplete package. Diagnostic record data and unknown future fields are retained.

```sh
python3 tools/analyze-lab-runs.py suite-manifest.json --control 1 --output analysis.md
# If an upload renamed parts, supply all JSON files explicitly so hashes resolve them:
python3 tools/analyze-lab-runs.py suite-manifest.json suite-part-1.json suite-part-2.json --output analysis.md
# Convert previously saved verbose CSV parts to uploadable JSON without rerunning OCR:
python3 tools/lab_suite_archive.py pack-json old-part-01.csv old-part-02.csv --output suite.json
```

Plain JSON is not compressed; savings come from normalization and deduplication, not the
file extension. A real results-only 3,026,888-byte fixture becomes a roughly 330 KB JSON
payload plus a small manifest when the optional scores CSV is omitted. Full diagnostic
savings depend on repetition and must be measured on the actual export. A single record
larger than the limit fails explicitly rather than truncating or producing invalid JSON.

## Unscored device-photo sanity checks

On **Large image dataset test**, choose the desired Lab modifier configuration (for
example **Best build**), then tap **Test Lab v5 on device photos**. This snapshots the
current modifier keys; they are displayed on the dedicated screen and cannot change
mid-run. Select multiple images in the system document picker (image MIME types).
Each is processed sequentially from its original bytes through **the same Lab v5
pipeline**, always with fresh OCR. The normal selected-photo importer applies its
32 MiB per-image bound. The picker can be cancelled without clearing current results.

The finished screen pairs every photo with its six assigned parameters. Blank values
stay unassigned, failures stay visible per photo, and incomplete OCR has an explicit
warning. These photos have no reference answers, accuracy score, benchmark cohort or
best-build promotion. They do not read corpus cached OCR/predictions or write corpus
results/baselines/history. Normal packaged knowledge databases still support extraction.

Use **Copy all results**, or save the optional diagnostic manifest and all JSON parts.
Diagnostics include selected fields, per-photo status, build/configuration, linked raw
OCR and all existing extraction decision records, but not photo bytes. Scores CSV is
optional and empty of scored rows because there are no truth labels. The standard
accuracy-comparison analyzer does not score these unlabelled sessions. OCR and filenames
may be private; only share deliberately. Keep the screen open while running; rotation
retains the session. Stop finishes the current photo and marks the rest not run. Back
ends the activity session, cancels work and clears private temporary copies after work
stops; saved external exports and source photos are untouched. Process-death restoration
or a background-service guarantee is not provided by this separate sanity-check screen.

## General safety repairs after the focused-suite diagnostic review

These repairs are not new score-tuned modules and do not enable any checkbox automatically:
- Vintage subspans retain original observation context. Founding/since/establishment cues
  block a vintage interpretation at discovery and selection, including moderate-confidence
  recovery. Other field discovery still runs early; it is not gated on a final blank.
- `sharesVintageSource` records a legitimate embedded/harvest year interpretation. The
  joint solver uses this structured provenance rather than a reason-string prefix. Equivalent
  numeric span candidates cannot lose their shared-source compatibility solely because their
  display reason is “complete observed vintage”. Bare-year observations are still exclusive.
- Terminal producer near-match remains controlled by `finalProducerNearMatch`, but cannot
  promote a supplemental catalog neighbor from shortlist membership alone. It needs an exact
  eligible OCR match or two independent engine families with disjoint original observations.
  Verified-primary/LWIN candidates retain bounded comparison. Equally close producer identities
  abstain before unsupported candidates can be discarded to manufacture a unique alternative.
- `exactProducerProtection` additionally preserves an exact confident observed current producer
  even when it is not a catalog entry. Safety against unsupported supplemental fuzzy replacement
  and identity ties does not require that checkbox. Reasons include
  `insufficient_supplemental_support`, `identity_tie`, and `observed_producer_preserved`.

Generated/ranked hypothesis rows include `shares_vintage_source`; discovery reports
`vintage_context_rejected`. Terminal summaries record their support policy and any rejection.
The evidence version is `evidence-lab-v5-span-provenance-v2`, producer resolver version is
`producer-resolver-v8-safe-terminal`; UI model remains **Lab v5**. Historical results are not
rewritten. Repeat the same focused suite on the same OCR/cohort and then unseen device photos;
synthetic checks are not a measured improvement in corpus accuracy or proof against overfitting.


## Retired checkbox experiments (current selection policy)

The owner-approved conservative pruning removes **vertical wine-title pairs** and
**wine/cuvée span discovery** from the UI, All on, focused tests and generated single/pair
suites. Stored manual selections are sanitized for new runs, including device-photo checks.
Historical signatures/exports are not rewritten: the full enum and implementation remain
available internally to decode prior records and retain regression coverage.

The five current-control modifiers are unchanged: blank subregion fuzzy search, vintage
recovery, final producer near-match, producer role/particle rules and spatial semantic guards.
Keep the repaired/inconclusive experiments (candidate isolation, exact producer protection,
administrative filtering, role-aware joins, broader discovery, vintage spans) pending retesting.
Keep the not-yet-tested geography, producer, grape and classification span discovery plus
stacked appellations. That is 16 active checkboxes, 17 single-toggle configurations and
137 single/pair configurations. The focused suite is now six configurations; previously
saved seven-configuration suites still re-export their original complete plans.

## Manual raw diagnostic export — JSON

**Prepare raw diagnostics JSON export** now uses the same compact linked JSON package as
suite exports. Save/upload the manifest JSON and every numbered JSON part (20 MB maximum);
the convenience scores CSV is optional. All retained attempts and their tokens, engine timings,
traces, pipelines and funnels remain, alongside the current/frozen pipeline-step aggregates.
Nested values remain typed JSON and identical payloads are deduplicated. Frozen comparators
still have only the evidence retained by their store; no missing raw OCR is invented.
No extraction rerun or images are included. Scored and recent-build exports remain CSV;
**Split an existing CSV for upload** remains available for older CSVs only.
