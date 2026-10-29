# Lab test modifiers

Checkbox source of truth: `LabPostOcrModules.ModuleId`. The Wine App Labels Lab
page renders every entry. Selections are independent booleans, serialized by stable
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
| `vertical_wine_title_pairs` | Vertical wine-title pairs | Offer bounded two-line title interpretations. Known to regress estate-plus-grape labels; not in Best build. |

## Early candidate discovery — experimental, default off

These six checkboxes add alternatives **before label context collection and hypothesis
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
| `wine_name_spans` | Wine / cuvée span discovery | Open-ended alphabetic spans of at least two tokens and five letters, without digits. Existing title/cuvée/role rules decide. Deliberately broad and likely to over-segment; measure carefully. |

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

All six new methods are off in Baseline and Best build; All enables them. Best build
remains the measured preset, not a claim that these new methods have been evaluated.

1. Run Baseline on the same cached OCR and corpus.
2. Run each modifier alone; record per-field gains, losses, blanks, elapsed time and
   truncation. Inspect raw evidence, not just total score.
3. Run pairs, then promising combinations. In particular compare geography +
   classifications, producer + wine-name, and vintage discovery + vintage recovery.
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

The Lab page now offers control + individual toggles (14 configurations), or control
+ toggles + every two-toggle combination (92 configurations). Control is the measured
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
The ordinary single-toggle suite now uses 19 configurations; the full pairwise suite
uses 172. New single-toggle tests are not substitutes for the ordered cumulative suite.

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
