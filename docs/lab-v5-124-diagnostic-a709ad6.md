# First compact-JSON diagnostic — 124-label raw export (`a709ad6`)

> Analysed 2026-10-29 from the owner's first attached diagnostic test.
> This is a **host-side reading of a phone export only**: no code, OCR,
> scoring rule or reference value was changed, and nothing here is a
> fresh-OCR claim. The next step is a decision, not an implementation
> (see §6).

## 1. What was supplied, and whether it is intact

| File | Bytes | SHA-256 (host-recomputed) | Verdict |
|---|---:|---|---|
| `wine-app-labels-124-raw-all-versions-a709ad645c9f-part-1.json` | 15,010,712 | `c0ed3aa7580f96b5921a771c22155f577cc8ba0793d2cc0996b284204188a961` | Matches its manifest entry exactly |
| `wine-app-labels-124-raw-all-versions-a709ad645c9f-manifest.json` | 1,178 | — (manifest itself) | `wine-lab-suite-json-manifest` v1, package `f6f3c6ab-6e8d-4a07-a928-7d8c69f40810`, 1 part |
| `wine-app-labels-automated-suite-manifest.json` | 1,353 | — | **Manifest only — see §5** |

The part decodes cleanly through the repo's own reader
(`tools/lab_suite_archive.py: read_many`), which verifies the manifest
hash/size per file and rejects duplicate dictionary IDs and missing
parts. Record counts agree with the manifest: 3 runs, 124 images,
1,234 candidates, 76 common, 20,251 objects, **35,612 events**
(25,222 `trace`, 9,920 `pipeline_funnel`, 222 `pipeline_step`,
124 `image_summary`, 124 `pipelines`). The `-scores.csv` listed in the
manifest (224 bytes — header only) was not uploaded; it is optional by
design, and §2 shows the host rescore reproduces the phone's own
scoring rows, so nothing is blocked by its absence.

**Provenance caveat that bounds every claim below:** all 124 Lab rows
carry `ocr_source=cached_ocr`, `build=4e0c2124a4fa` on the image
summaries, and run
`4e0c2124a4fa-1793286210214-LAB-68d12796-da4d-49c6-a004-6c81ea34b7f6`
with modules
`blank_subregion_fuzzy+vintage_recovery+final_producer_near_match+producer_role_particle+spatial_semantic_guards`.
The `pipeline_step` aggregates are stamped `a709ad645c9f`. In other
words this run **re-ran the current Lab post-OCR/scoring on OCR cached
by an earlier build** — it validates the new compact-JSON export and
the current Lab extraction/resolver logic, not fresh OCR on `a709ad6`.

## 2. Headline scores (host rescore, frozen references)

Scored host-side against the packaged frozen references
(`app/src/main/assets/wine-app-labels-v1/scoring.json`: 124 labels,
742 scored cells, 2 unresolved cells — review #125 `wine_name`,
#176 `vintage`; 122 fully scorable forms), using the same
normalisation/outcome rules as `PhoneCorpusMath`. The rescore
**exactly reproduces** the export's own `pipeline_step` scoring rows
for Lab v5 (complete 53, ≤1 error 111, 2-error 11) — the compact-JSON
round-trip is lossless for scoring purposes.

| Version (pipeline) | Complete (6/6) | ≤1 error | 2+ errors | Source in this export |
|---|---:|---:|---:|---|
| **Lab v5** (`lab-v5-expanded-producers`) | **53/122 (43.4%)** | **111/122 (91.0%)** | **11/122 (9.0%)**, max 2 | Per-image fields + aggregates |
| Prod v4.0 (`production-v4.0-lwin-only`, frozen) | 43/122 (35.2%) | 92/122 (75.4%) | 30/122 (24.6%), max 5 | Aggregates only |
| Prod v2.0 (`production-v2.0-guarded`, frozen) | 43/122 (35.2%) | 91/122 (74.6%) | 31/122 (25.4%), max 5 | Aggregates only |
| Prod v1.0 (`production-v1.0-15s`, frozen) | 16/122 (13.1%) | 58/122 (47.5%) | 64/122 (52.5%), max 6 | Aggregates only |

Lab v5 per-field (correct / scored; `correct` includes correct blanks):

| Field | Correct | Wrong | Missing | Unsupported fill | Reading |
|---|---:|---:|---:|---:|---|
| Producer | 96/124 (77.4%) | 27 | 1 | 0 | Second-largest error pool |
| Wine name | 72/123 (58.5%) | 32 | 19 | 0 | **Largest error pool (51 cells)** |
| Country | 124/124 (100%) | 0 | 0 | 0 | Geography chain is not the current bottleneck |
| Region | 124/124 (100%) | 0 | 0 | 0 | (120 values + 4 correct blanks) |
| Subregion | 122/124 (98.4%) | 0 | 0 | 2 | Only errors: #72 `Moulis`, #154 `Porto` filled against an expected blank |
| Vintage | 123/123 (100%) | 0 | 0 | 0 | (108 values + 15 correct blanks; #176 unresolved) |

Execution health: 124/124 `image_summary` rows `complete`,
`engine_incomplete=false`, no errors. Lab elapsed per label:
median 1.50 s, mean 1.76 s, p90 2.53 s on cached OCR (not a fresh-OCR
latency figure); slowest #2 (18.6 s) and #186 (10.9 s).

Frozen-version caveat: this raw export carries **no per-image frozen
Prod fields** — frozen records keep no OCR tokens by design — so
per-field Prod accuracy and per-label Lab-vs-Prod gains/losses cannot
be computed from this file alone. That comparison needs the scored
export (or the automated-suite package, §5).

## 3. Where Lab v5 actually stops (funnel, 124 labels)

- **OCR is nearly never the wall.** Paddle text/mapping pass 124/124;
  ML Kit passes 123/124 — the one blank ML Kit read is review #128
  (Vega Sicilia), which still completed via Paddle and is a
  producer+wine double error, not an execution failure.
- **Producer resolver rarely gets to change anything.**
  `producer.independent` passes only 77/124 (42× *no independently
  supported producer*, 3× single-engine-fuzzy gate); `producer.changed`
  fires on just **28** labels (39 retained, 11 blocked by
  *different existing producer protected*, 38 unchanged for lack of
  independent support, 2 budget-blocked: #105, #118). Yet the final
  producer is correct on 96 — i.e. most correct producers come from
  the base extraction, and most of the 27 wrong producers are noisy
  base-extraction strings the resolver never replaced
  (`CRUCLASS`, `HATEAU`, `PREMIER`, `Trancette` for *La Francette*,
  `Bordeaux PPELLATI`, boilerplate like `MIS EN BOUTEILLE AU
  CHATEAU…`, `BODEGAS FUNDADAS EN 1890`).
- **Linked-wine resolver is almost inert.** `wine.independent` passes
  9/124, `wine.selected` 8, `wine.changed` **5** (5 more
  budget-blocked: #26, #105, #118, #165, #185). 54 labels never reach
  wine search for lack of an independently observed producer and 56
  more find no independently observed linked wine. Wine-name accuracy
  therefore stands or falls with base extraction, not the link step.
- **The remaining error mass is exactly two fields.** 58 of the 69
  non-complete eligible forms have exactly one error: **39 wine-name-
  only, 18 producer-only, 1 subregion-only**. If producer and wine
  name were both perfect, 120/122 eligible forms would be complete.
  10 labels fail on producer+wine together
  (#36, #43, #71, #86, #92, #119, #128, #139, #164, #169).
- **Dominant wine-name failure shape:** in 17 of the 32 wrong wine
  names the recognised string is simply the label's geography
  (`BORDEAUX`, `FRONSAC`, `LANGUEDOC`, `RIOJA`, `TORO`,
  `BARBARESCO`, `MINERVOIS`, `Ribera del Duero`…), i.e. the
  appellation/region line won the wine-name slot over the real cuvée
  (`Mouton Cadet`, `Léo by Léo`, `Gran Reserva 904`, `Triton`…).
  The other 19 wine errors are missing (blank) — concentrated in
  appellation-named wines (#46/#122/#167 *Montagne Saint-Émilion*,
  #94 *Saint-Émilion*, #99 *Bordeaux Supérieur*) and multi-line
  cuvées (#50/#152 *Naturae…*, #186 *Merlot*, #188 *Pinot Noir*).

## 4. What this proves about the new export itself

1. **Integrity/completeness:** manifest hash, byte counts, dictionary
   and event counts all verify host-side; no duplicate IDs, no missing
   parts, no truncated records in a 15 MB single-part package.
2. **Scoring fidelity:** host rescore from only the `pipelines` rows
   reproduces the device-computed `score.complete` /
   `score.at_most_one` aggregates exactly (53 / 111 / 11).
3. **Diagnostic depth retained:** full Lab trace + per-label funnel
   survive the compact encoding (the funnel figures in §3 come from
   this file, not from a CSV).
4. **Known limits, by design:** frozen Prod versions contribute only
   aggregate `pipeline_step` rows (34 steps for v1/v2, 77 for v4) —
   no per-image fields, no OCR tokens — so this file alone cannot
   produce a per-label version diff.

## 5. The second attachment cannot be read yet

`wine-app-labels-automated-suite-manifest.json` describes a different,
larger package (`c82305ab-…`, 7 runs, 248 images, 5,257 scored rows,
157,530 events) in **two JSON parts plus a scores CSV**, none of which
were attached. Per its own instructions (*"Upload all numbered JSON
parts and this manifest"*), please upload
`wine-app-labels-automated-suite-part-1.json` and
`wine-app-labels-automated-suite-part-2.json` (the scores CSV is
optional) if you want that suite analysed — the reader will verify
both parts against this manifest before scoring anything.

## 6. Recommended next step (one item, awaiting owner go-ahead)

Do **not** start with the producer gazetteer: geography is saturated
and the producer resolver's gates are not where the 51 wine-name
errors live. The single highest-leverage item is the **wine-name
slot**: 39 sole-error forms, led by the 17 appellation-as-wine-name
cases in §3. That maps to the already-open roadmap items on
appellation `WINE_NAME` handling and bounded cuvée+varietal joins
(`docs/lab-v5-accuracy-roadmap.md`, Items 2 and 4) rather than any new
mechanism. No code has been changed on the strength of this report;
say the word and that one item will be planned in detail first, per
repo Rule 8.

## 7. Update 2026-10-29 — host replay harness (owner-approved workflow)

Per the owner's direction, Lab iteration now happens **on the host**
and the phone is reserved for final verification. The harness
(`tools/host-lab/`, `tools/run-host-lab.sh`,
`tools/logic-checks/HostLabReplay.kt`) compiles the real Lab post-OCR
Kotlin (pinned JDK 21 / Kotlin 2.2.10) and replays the packaged
cached OCR for all 124 labels, with gazetteer lookups served from the
packaged SQLite assets. One full prepare + compile + replay + score
cycle takes ~90 seconds.

**Parity gate: PASS, exactly.** With the phone baseline module set
(`blank_subregion_fuzzy+vintage_recovery+final_producer_near_match+producer_role_particle+spatial_semantic_guards`)
the host reproduces §2 label-for-label: **0 differing fields out of
124×6** (complete 53/122, ≤1 error 111/122, identical per-field
counts). The phone is therefore no longer needed to *find* a better
Lab version — only to *confirm* one (fresh OCR, budgets, on-device
SQLite loading). Host runs deliberately pass no gazetteer lookup
deadline, so the phone's budget-blocked labels (producer #105/#118;
wine #26/#105/#118/#165/#185) are not reproduced as blocked; their
final fields nonetheless match.

First control iteration, proving the loop: module set `none`
(pre-improvement control) scores 49/122 complete, ≤1 error 106/122,
+5 gains / −18 losses (net −13) vs the baseline — correctly worse,
with every changed cell named in `tools/host-lab/out/control-none/report.md`.

Two things are unchanged by this update: the §6 recommendation
(wine-name slot first) still stands as the first real iteration, now
to be executed host-side; and the §5 automated-suite package still
cannot be read until its two JSON parts are uploaded. A pre-existing
host-check failure found en route (`run-spatial-preview-checks.sh`
check 29 at unmodified `a709ad6`) is logged as Bug-028, not fixed
here.
## 8. Host iteration result: wine-name slot (Items 2a/2c/4a)

Against the recreated baseline configuration (`blank_subregion_fuzzy+vintage_recovery+final_producer_near_match+producer_role_particle+spatial_semantic_guards` = 53/122 complete, 111/122 ≤1 error, parity PASS after all changes below):

| Variant | Complete | ≤1 error | Δ vs baseline |
|---|---:|---:|---|
| + `stacked_appellations` | 54 | 111 | +1 (#151) / −0 |
| + `broader_discovery` | 54 | 111 | +1 (#151) / −0 |
| + `classification_spans` | 53 | 110 | +0 / −1 (#140) |
| + `grape_spans` | 52 | 111 | +0 / −1 (#59) |
| + `appellation_wine_fallback` (after fix below) | 58 with stacked | 113 | **+7 / −0** |
| winner + `vertical_wine_title_pairs` | 54 | 106 | +5 / −10 (rejected) |

Root cause found for Item 2c: `AppellationFallback` built its gazetteer/hierarchy from the raw KB (27 fixture places) instead of `SupplementedWineKnowledgeBase`, so only fixture appellations (e.g. Saint-Emilion, #94) could ever fill a blank wine name; Montagne-Saint-Emilion (#46/#122/#167), Côtes de Bourg (#164) were unreachable. The fallback is now supplemented, plus a bounded one-edit repair (`PO MER ROL` → Pomerol, #36) that runs only when no exact printed appellation matches and still abstains on competing appellations. `stacked_appellations` adds #151 (Castillon Côtes de Bordeaux). Winning set: baseline + `appellation_wine_fallback` + `stacked_appellations` = **58/122 complete, 113/122 ≤1 error, wine_name 79/123**; per-label gains in `tools/host-lab/out/combo-fallback-stacked/report.md`. Phone verification checklist: `_refs/THINGS_TO_CHECK.md` (this branch).
