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
