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
## 9. Item 2b + fallback completion (host-verified, phone pending)

Module `appellation_classification_precedence` (Lab-only, default off): classification-qualified appellation offers (`<Appellation> Grand Cru/Superieur`, framed or printed) rise 70/64 -> 96, above designation-linked title joins (90, e.g. `FLEURCARDINALE Grand Cru Classe` on #157); the explicit named-cuvee offer rises 75 -> 100 so an appellation cannot steal a named cuvee (#97 `Cuvee Garance` protected). Result on the Section 8 winner: **+1 (#157), zero losses**. The `AppellationFallback` one-edit path additionally strips legal framing (and drops a garbled head/tail token) and admits low-confidence lines (floor 0.20/0.35 in the fuzzy path only), gaining **#99** (`Appellation Bordeaus superieur coFoat`, ML Kit confidence 0.258).

**Current winner (host): baseline five + `appellation_wine_fallback` + `stacked_appellations` + `appellation_classification_precedence` = 60/122 complete, 113/122 at most one error, wine_name 81/123; +9 gains / -0 losses vs the phone baseline** (#36, #46, #94, #99, #122, #151, #157, #164, #167). Baseline parity re-verified PASS on the final tree. `broader_discovery` on top adds nothing and stays off. Remaining Item 2c target #105 (wrong free-text wine, fallback fills blanks only) is still open.
## 10. Item 3 producer round (host-verified, phone pending)

Module sweep on top of the Section 9 winner (each vs winner-v2): `candidate_isolation` net -3 (rejected), `administrative_filtering` 0, `producer_spans` 0, **`exact_producer_protection` +1 producer (#146 Citran), `role_aware_joins` +1 wine (#105, the last Item 2c target)**. New-code attempts for 3a (final-canonicalization distance 2, observed-preservation lift, containment) and 3b (hybrid anchor fallback) were implemented behind Lab gates, measured (+0 / negative), and reverted; the failure mode of the remaining 27 producer errors is span selection upstream (roadmap Item 4c), documented in the roadmap's Item 3 notes.

**Current winner (host): Section 9 set + `exact_producer_protection` + `role_aware_joins` = 62/122 complete, 113/122 at most one error; producer 97/124, wine_name 82/123; +2 net vs the phone-verified v2 (60/122), zero losses.** Baseline parity PASS on the final tree (the only tree change this round is a host-harness debug column).
## 11. Prod v5.0 checkpoint and Lab v6 (2026-10-29)

**Correction (owner, 2026-10-29): Prod v5.0 is frozen like every Prod version — it computes once per photo during its first capture pass and is replayed from `WineAppLabelsBaselines` afterwards (mode added to `BASELINE_MODES`); it is not a live contender on later runs.** The ten host/phone-proven modules (`blank_subregion_fuzzy`, `vintage_recovery`, `final_producer_near_match`, `producer_role_particle`, `spatial_semantic_guards`, `appellation_wine_fallback`, `stacked_appellations`, `appellation_classification_precedence`, `exact_producer_protection`, `role_aware_joins`) are now frozen as `LabPostOcrModules.prodV5Checkpoint` and ship as the new benchmark mode **Prod v5.0** (`production-v5.0-expanded-producers`, spatial contender `spatial_prod_v5`): the Lab spatial pipeline with the checkpoint set baked in, never following the Lab toggles. The experimental line is renamed **Lab v6** (`lab-v6-expanded-producers`; `lab-v5-expanded-producers` kept as a legacy ID alias) and starts from the checkpoint configuration as its default module set. Host regression after the change: baseline parity PASS (53/122), checkpoint configuration still 62/122; the checkpoint preset's signature was compile-probed to contain exactly the ten proven keys. Phone verification of the new Prod v5.0 row is checklist item 2d in `_refs/THINGS_TO_CHECK.md`.
## 12. Lab v6 integration (2026-10-29)

Per owner direction, the ten Prod v5.0 checkpoint modules are **integrated into Lab v6**: removed from the Lab checkbox list (`LabPostOcrModules.integratedModuleIds`; `selectableModuleIds` excludes them) and forced on at every Lab run boundary (`LabPostOcrModules.withIntegrated()` in `TextLabWineExtraction`, the ViewModel module switch, and the host replay). `prodV5Checkpoint` is now defined as `none.withIntegrated()` — one source of truth for both the frozen Prod v5.0 record and the Lab v6 floor. Host verification: Lab v6 with no experimental toggles scores **62/122 complete, 113/122 at most one error** (producer 97, wine_name 82), and the host parity gate now asserts this Lab v6 integrated floor instead of the historical 53/111 Lab v5 baseline (proven and phone-verified earlier; Sections 7-10). Future Lab v6 experiments toggle only the remaining experimental modules on top of this floor.
## 13. Item 1a — bounded wine-title pairs (2026-10-29)

The roadmap's Item 1a boundary caps (same engine pass, no vintage/framing/geography line crossings, height-ratio caps) already existed in `EvidenceGraph`/`TextLabSpatialPhrases`; measurement showed the residual failure was different: (i) the 1.45 height cap and the `role_aware_joins` positive-support veto killed every script-cuvée + small-caps-varietal pair (upper line 4–5× taller), and (ii) narrow varietal lines in dense label areas were `paragraph`-filtered out of identity offers entirely. New Lab module `bounded_wine_title_pairs` (Item 1a): bounded pairs are evaluated before the generic vertical caps with their own pair height bound (5.5×), are exempt from the role-aware veto, name the wine only over a **varietal** lower line (over an appellation lower line the appellation alone names the wine — pairs there only prepended producer fragments: `BOUSQUET COTES DE BOURG`, `GEORGES BERTRAND CORBIERES`), rank 88 for tall display uppers (≥2.5× the varietal; may beat the varietal alone at 75) and 70 for similar-height stacks (estate stacks stay below the varietal), never outrank classification-qualified appellations (96) or named cuvées (100), and subsume same-text single-line varietal offers from the other engine. Paragraph density no longer suppresses a line that is exactly a grape term (`Merlot`, #186) unless a pair covers that text. Result on the Lab v6 floor: **66/122 complete, 113/122 ≤1 error; +4/−0 vs the previous floor** (#170 `Inscription Pinot Noir`, #177 `Ultra Malbec`, #184 `Volcanic Pinot Noir`, #186 `Merlot`). The retired `vertical_wine_title_pairs` re-measured on this floor was +0/−1 and stays retired. Remaining 1a-listed targets need other mechanisms: #32/#89 (`LEO by LEO` three-token brand over an appellation), #50 (vintage line between cuvée and varietal — correctly blocked), #106 (`MOUTONCADET` concatenated token), #152 (garbled script reading), #174 (producer line merged into the pair's upper reading).

Per the Lab v6 integration pattern, the module is integrated into Lab v6 (always on, checkbox removed) and the host floor gate now asserts 66/113. **Freeze guard:** `prodV5Checkpoint` is now pinned as an explicit `copy(...)` of exactly the ten checkpoint modules — it must not be derived from `withIntegrated()`, which grows as Lab v6 integrates new winners (this corrects the Section 12 description). Phone verification: checklist item 2f. **Phone-verified 2026-10-29 (build `d1e44611074f`): exact match** — Lab v6 66/122 (47 one-error, 9 with 2+), wine_name 86 (+4 vs the prior run, all other fields identical), Prod v5.0 frozen at 62/122 with wine_name 82.
## 14. Items 4b + 4c — stacked wine titles and split producer spans (2026-10-29)

Two new Lab modules, each measured alone and combined on the 66/122 floor, then integrated into Lab v6. **Combined: 72/122 complete, 115/122 ≤1 error; +8 field gains / −0 losses vs the previous floor.**

**Item 4c `split_producer_spans` (+4/−0 alone: #92, #136 producer; #164 producer; #137 wine).** `EvidenceGraph` gains a bounded vertical producer-span join (estate heading / producer cue / particle line over a name line, same engine, height ratio ≤1.85, no geography/description/designation lower), exempt from the role-aware veto, plus a three-line extension (0.10 absolute gap cap — 0.08 missed #136 at 0.092, a relative 0.65× cap lost #92). Span phrases get joined producer rank (82) and flow into `ProducerResolver` via the existing evidence-span feed. Residual targets: #43 (rotated single-line reading `CASSAN DOMAINE DE`; needs a word-order rotation alternative), #54/#104 (span hypotheses exist but a later repair/resolver stage keeps a shorter fragment: `Les Chemins`, `Variere`). Dataset correction 2026-10-29: #42 and #61 expected producer is `La Petite Chapelle` (not `... de Bordeaux`); both are now winnable in principle via the two-line span, but the current build still selects garbled/fragment readings (`Bordeaux PPELLATI`, `Talent`) — corpus totals unchanged on rescore (72/122, producer still wrong on both).

**Item 4b `stacked_wine_titles` (+4/−0 alone: #50, #152, #154, #183 wine).** Bounded wine-title pairs gain: tier/designation uppers (`GOLDEN RESERVE`, `SIGNATURE SERIES`, `LATE BOTTLED VINTAGE`, `LBV`) that may head a pair even when description/cue-classified and even over an appellation lower (`LATE BOTTLED VINTAGE PORT`); a looser absolute-gap (0.08) pair vertical for small-caps tier lines; grape-term containment for grape+style lowers (`SYRAH ROSE`); vintage-skip pairs that join a cuvée to a varietal across exactly one intervening standalone year line (`Naturae`/`2020`/`MERLOT`); and `LEO / by / LEO`-style connector brand stacks. Tier pairs rank 90, below three-line stacks (92, kept only for the connector form) and classification offers (96). The general three-line extension (pair + title line above) was implemented and **removed**: it prepended producer lines (`TRIVENTO Golden Reserve Malbec`, #183) for one potential gain (#178). Residual targets: #32/#89 (second `LEO` line not read by either engine), #60 (producer-headed pairs `J.Kieffer Brut` compete into solver abstention), #174 (varietal split into word-level records upstream of the graph), #178 (needs the removed three-line extension or a producer-overlap guard).

Phone verification: checklist item 2g.
