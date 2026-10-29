# Lab v6 Remaining-Failure & Architecture Gap Analysis (2026-10-29)

Baseline: phone-verified Lab v6 floor **72/121 complete, 115/121 ≤1 error**
(producer 100/123, wine_name 91/123, country/region 124/124, subregion
122/124, vintage 123/123). 49 eligible labels are incomplete; 57 field
errors remain: wine_name 32 (27 wrong + 5 missing), producer 23 (22 wrong
+ 1 missing), subregion 2 unsupported fills.

Method: regenerated the host floor + debug journal
(`tools/host-lab/out/lab6-floor/`), listed every wrong field, and for each
producer/wine error located where the correct answer dies in the pipeline:
OCR line → graph phrase → extractor hypothesis → solver selection →
repair/resolver stages.

## 1. Where correct answers die (producer + wine_name, 53 errors)

| Stage | Count | Meaning |
|---|---:|---|
| Correct text was a hypothesis, lost selection | 14 | Ranking/arbitration problem |
| Correct text was an OCR line, never became an exact hypothesis | 9 | Span/hypothesis generation problem |
| Words present but split across lines/records | 1 | Composition problem |
| Exact text absent from all lines | 29 | Of these, most are ≤3 edits from *some* line (garbled near-miss); only ~13 have no near text anywhere (OCR ceiling: script, curved, rotated, blown-out text) |

Conclusion: the majority of the remaining pool is **selection and
canonicalization**, not OCR and not hypothesis generation. The roadmap
(plan) is heavily weighted toward hypothesis *generation* (Items 4a–4c,
5d); the larger residual mass sits in *arbitration between existing
hypotheses*, which the plan covers only narrowly (Item 2b ranking, 2c
blank-fill fallback).

## 2. Dominant failure clusters

1. **Geography/appellation occupies the wine slot while a cuvée/title
   exists** (~14 wine errors): #10, #30, #43w, #63, #71w, #74, #92,
   #103, #106, #116, #128w, #129, #139w, #163, #171, #173, #190.
   Either the appellation offer (rank 65) beats the title offer (43), or
   the solver/fallback abstains among several appellations.
2. **Garbage, legal, or classification text wins a field** (~8):
   #56 `CRUCLASS`, #62 `PREMIER`, #69, #68, #71p, #119p, #139p,
   #182 (a 10-word back-label sentence as wine_name), #34, #58
   (varietal+region text as producer). Identity offers carry no
   linguistic plausibility or length discipline; `MetadataRules`
   boilerplate lists only exact legal phrases, which garbled OCR escapes.
3. **Producer near-miss / fragment / overlong** (~12): #12/#117
   `Trancette`→`La Francette`, #114 truncated `Le Pas du`, #36
   `HATEAU`, #45, #140, #128p, #96 (extra word), #86 (overlong),
   #54/#104 (span exists, later stage keeps fragment), #42/#61.
4. **Wine stack residuals** (~8): #60 (producer-headed pairs compete to
   abstention), #70 (missing `Les Cailloux` head), #135 `Orange Muscat`
   blank, #174 (word-split varietal), #178 (needs safe triple), #182,
   #188 (`DECOY Pinot Noir` — producer brand prepended to the varietal).

## 3. Gaps NOT in the current improvement plan (ranked)

### Gap A — Wine-slot arbitration: appellation vs existing title/cuvée
The plan's Item 2c only *fills blanks* and picks the deepest descendant;
nothing arbitrates when an appellation hypothesis already occupies
WINE_NAME while a clean non-producer, non-geography title hypothesis
exists on the same label. Candidate rule (Lab-gated): demote the
appellation-as-wine offer when such a competing title exists, or boost
the title when it sits between the producer and appellation lines
(layout order). Addressable: most of cluster 1. Risk: appellation-named
wines with a producer title line (e.g. #180) — needs the no-loss gate.

### Gap B — Cross-field producer↔wine joint check
Fields are solved independently; no stage asks whether a wine pair's
upper line *is* the selected producer. #188 proves the failure
(`DECOY Pinot Noir` vs expected `Pinot Noir`), and the same guard is
what a safe #178 triple and #60 pair filtering need. Mechanism: suppress
or demote pair/stack wine hypotheses whose head line equals a strong
producer hypothesis's text in the same run. Not in the plan.

### Gap C — Identity text-quality gate
Long legal/back-label/garbled lines can win producer or wine slots.
A bounded plausibility rule for *winning identity values* (word count,
dictionary/gazetteer token hit-rate, classification-only content) could
force abstention to the next candidate instead of a garbage winner.
Targets: #182, #68, #119p, #34, #71p. Not in the plan. Must be a gate on
selection, not generation, to avoid deleting true long estate names.

### Gap D — Producer completion canonicalization (span stage)
Item 3a was closed after final-value variants (d=2, containment,
preservation lift) measured ≤0. A different, stricter mechanism remains
untested: at *span* stage, complete a producer value only when it is a
proper token subsequence of exactly one catalog producer (article
insertion `Trancette`→`La Francette`, truncation `Le Pas du`→`Le Pas
du Templier`) with independent-engine support. ~6 labels addressable,
but prior negative evidence demands the strictest gating and named
gain/loss measurement.

### Gap E — Fallback disambiguation among multiple printed appellations; style appellations missing from the place gazetteer
#92 wine is blank although `Pessac-Léognan` is printed: two appellation
groups (Haut-Brion, Pessac-Léognan) make `AppellationFallback` abstain
— Item 2c's "deepest descendant" logic applied to *printed* competing
appellations would resolve it. #190 `Rosso di Montalcino` and #74
`Médoc Supérieur` are wine-style appellations that are not `Place`
entries at all, so no geography/fallback route can ever produce them;
they need a WineDesignations-level style-appellation route. Partly
extends 2c/4a; the gazetteer-coverage half is new.

### Gap F — Graph composition over horizontal phrases
The graph builds vertical pairs only from single observations. When the
spatial merger leaves a varietal as word-level records (#174
`CABERNET`/`SAUVIGNON`), no pair can use it. Bounded extension: allow a
bounded pair's lower/upper to be an *aligned horizontal phrase*. One
current label, but a general composition hole between the reading layer
and the graph.

### Gap G — Classification phrase as the wine name
#90's expected wine_name *is* `Premier Grand Cru Classé`; bare
classifications are suppressed by design. A narrow exception (unique
Premier Grand Cru Classé phrase + matching appellation context) is not
in the plan. Small (1–2 labels).

### Gap H — Appellation double-use as SUBREGION
The only two subregion errors (#72 `Moulis`, #154 `Porto`) are
appellation places also filling SUBREGION against an expected blank,
while appellation subregion fills are correct on many other labels.
#154 even uses Porto as both wine and subregion. Needs an
owner-convention check first (when is an appellation a subregion?),
then possibly a same-place two-field demotion. Not in the plan.

### Gap I — Solver abstention policy (missing vs wrong)
Five missing wines and #43's missing producer are deliberate
abstentions under the fixed 3.0 ambiguity margin. For the *benchmark*,
a best-guess would strictly dominate (missing and wrong score alike);
for the *product*, a blank may be preferable to a wrong suggestion.
This is a policy decision for the owner, not a bug — but it should be
made explicitly, because it caps measurable accuracy.

### Gap J — Host/phone evidence-KB divergence (process)
Host replay feeds the evidence stage a `FixtureKnowledgeBase` base
(~11 producers), while the phone's evidence stage wraps the full
SQLite base in `SupplementedWineKnowledgeBase`. Catalog-gated evidence
rules (e.g. the producer-fragment guard) therefore behave *stronger*
on device than on host: host can both understate gains and miss
phone-only losses. Mitigation options: serve the evidence KB through
the host SQLite bridge as well, or keep a mandatory phone spot-check
list for any catalog-gated rule (current practice).

### Gap K — Corpus validation (process)
124 photos / 121 eligible, with duplicate wines (#92/#136, #42/#61,
#12/#117, #10/#63, #32/#89). Rule specificity has grown with each
integration; without fresh labels there is no holdout signal and
overfitting risk rises. Highest-value process step before further
rules: a small set of new, unseen labels scored as validation only.
Also worth an owner check: #86 expects producer `De Luze` while its
wine_name is `Baron de Luze` — plausible per catalog, but it is the
kind of adjudication that silently caps two fields at once.

## 4. Plan items to close or re-scope

- **2a**: targets all correct via another route — close as covered.
- **5e, 6a–6d**: no headroom on the current corpus (geography and
  vintage saturated) — close as moot, reopen with new labels.
- **3a**: keep closed as designed; any revival should be Gap D's
  stricter span-stage form, measured fresh.
- **3b**: worth exactly one retest on top of integrated 4c spans.
- **4a/5d**: retain, but note they overlap Gap E on #103.

## 5. Suggested order

1. Gap A (wine-slot arbitration) — largest addressable wine pool.
2. Gap B (producer↔wine joint check) — unblocks #188, #60, safe #178.
3. Gap E (fallback disambiguation + style appellations) — three
   missing wines.
4. 4c residuals + Gap D (producer completion, strict gates).
5. Gap C (text-quality gate) — garbage winners.
6. Gap F, Gap G, Gap H (small, bounded).
7. Gap K (validation labels) before any further rule batch; Gap J if
   catalog-gated rules multiply.
