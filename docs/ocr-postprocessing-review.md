# Post-processing review: improve identity assignment, not model count

## Scope and evidence

Production remains ML Kit Latin + PP-OCRv6 Medium detection/recognition, sequential
native execution, bounded working bitmaps, inverse coordinate mappings and a shared
15-second deadline. No extra model, cloud wine API, NER, confidence inflation or
label-specific correction is proposed. Model removal is implemented in this change;
the field-policy changes below are a staged design, **not yet implemented**.

Evidence: batch `24987ba8-df0d-4ba4-b3f6-a0ddd424cab9`, build `df45fbb5e57b`, six images.
The diagnostic table processes each engine separately with estimated scores, bypasses
the production singleton merge gate and caps suggestions to YELLOW. It is useful for
finding role-selection mistakes, but is not a measurement of combined production
accuracy. Six repeatedly tested labels are a development set, not a held-out test set.

`app/src/testDebug/resources/textlab/production-review.json` preserves only the two
retained engines' accepted OCR, normalized boxes, reported scores and observed table
outputs. These outputs are **not expected answers**. Original images/device telemetry
and alternative-engine readings are not bundled in this fixture.

### Concrete failures

| Observed text | Observed table mistake | Likely rule-level cause |
|---|---|---|
| Marqués de Riscal / Verdejo / Rueda-España | Rueda-España chosen as wine name | Named style penalized; geography footer not excluded as a complete semantic span |
| KIRKLAND / bignature / CABERNET SAUVIGNON | bignature chosen as name | Unrecognized fragment ranks above a valid varietal; adjacent brand fragment not resolved |
| KIRKLAND / BRUNELLO / DIMONTALCINO | BRUNELLO becomes producer | Split appellation/name interpretation and producer priority are not resolved jointly |
| KIRKLAND / SIGNATURE / Stags Leap Distrid | district chosen as producer | Tall/unknown fallback can win before an exact curated alias is evaluated |
| LACKSTONE / 13.58 BY TOL / CALIFORNIA | alcohol debris becomes wine name | Packaging parser relies on clean units/percent/ALC/BY VOL patterns |
| BLACKSTONE / WINERY / Merlot / CALIFORNIA | Correct Medium output | Preserve this positive control; do not globally suppress unknown or stylized names |

These are not arguments to fill an unreadable producer from a grape or appellation.
A missing value is preferable to a plausible but unsupported brand.

## Findings in the current code

1. **Producer priority is inconsistent.** `FieldExtractor` first tries KB matches,
   then calls `ProducerLineSelector.unknown()`, which ranks production cues, height,
   then vote. `BundledProducerNames.exactName()` is only applied to the chosen unknown
   line. Thus an exact curated alias on a different line can lose before being tested.
   The curated list already includes Kirkland; adding another alias alone will not
   fix this ordering. Also audit ambiguous aliases such as “Stags Leap”: a district
   reading must not automatically establish a similarly named winery.
2. **Wine-name fallback rewards the wrong property.** The candidate sort in
   `FieldExtractor` explicitly ranks non-wine-descriptions before wine-descriptions.
   This helps distinctive cuvées but also lets a geographic footer or OCR fragment
   outrank an explicitly printed varietal. `styleFallback` only runs when the candidate
   list is empty, not when it contains weak/inappropriate alternatives.
3. **Premature commitment hides disagreement.** `ProducerLineSelector.unknown()` and
   `LabelNamePolicy.joinStyle()` return one choice. The latter is wrapped in a singleton
   wine-name candidate list, so `Validator` cannot compare discarded wine-name options.
4. **Filtering occurs after destructive cleanup.** `MetadataRules.identityText()`
   strips recognized packaging/year tokens before identity validation. Reject a clearly
   administrative original span before stripping, rather than letting residual words
   become a name. Current packaging regex is precise but brittle to noisy OCR units.
5. **Geography has incompatible roles.** Place claims support origin, but a place can
   also be a legal wine name or appear in a producer. The current `placeLines` and phrase
   removal rules do not robustly classify mixed footers such as region-country strings.
   Appellation-name exceptions are necessary; blanket geographic-word removal is unsafe.
6. **Line grouping lacks horizontal separation.** `LineBuilder` tests vertical overlap
   and median height but not the horizontal gap or independent columns. Distant front/
   back-label fragments can become one line. Recognition-line boxes are not word boxes;
   do not pretend joined strings have precise word-level geometry.
7. **Confidence is not semantic correctness.** `TokenMerger`'s per-engine max prevents
   repeated-pass vote inflation, but its cross-engine formula is not calibrated for
   correlated OCR. A high vote says little about producer versus cuvée role. Do not
   lower production thresholds to compensate for role-selection mistakes.
8. **Some corrections are already sample-specific.** `LabelNamePolicy.joinStyle()` has
   a producer-scoped Reciente correction. Audit and isolate this in replay evaluation;
   do not generalize it into a broad edit-distance correction rule or add more such
   cases as the main strategy. Never supply a vintage or missing identity from a known
   bottle. Existing field policies are unchanged by this cleanup.

## Proposed bounded pipeline

### A. Preserve evidence and classify roles before assigning fields

Keep raw string, normalized comparison text, normalized label box, engine, confidence
kind and source IDs immutable. Add a small `RoleEvidence` contract with potentially
multiple roles: producer cue, known-producer match, distinctive name, varietal/style,
appellation, origin footer, vintage, founding year, packaging, importer and award.
Store the rule and matched span for each role. A line may be both appellation and wine
name; roles are not globally exclusive. Administrative blocks must not seed identities.

Bound fuzzy recognition to **closed administrative vocabularies** with corroborating
syntax. For example, a plausible alcohol percentage number PLUS “BY” PLUS a near-match
to VOL is evidence of packaging; a decimal number alone is not. Never blacklist arbitrary
text containing “vol”, “alc”, or a digit: brands and cuvées can legitimately contain them.
Retain exact alternatives and show why each was suppressed.

### B. Build a small candidate set, including “unknown”

For producer, consider all full-span exact KB/curated matches before unknown fallback.
Then consider explicit maker-marker + adjacent name, followed by conservative unknown
name candidates. Require a useful margin over incompatible alternatives; a tall line
alone is insufficient. Geographic-only/appellation-only/style-only candidates cannot
win producer without independent maker evidence. Curated aliases must still be screened
for conflicting geographic roles; locality and winery names can overlap.

For wine name, rank evidence categories rather than “not a style”: verified cuvée for
a supported producer; distinctive non-administrative name with adjacent designation;
then printed varietal/appellation/style. Include an empty candidate. Do not let an
unrecognized fragment win merely because it is taller or not in the style dictionary.
Do not require an identified producer to preserve an otherwise credible wine name.

### C. Resolve producer and name together, with bounded search

Initially cap each field to 4 candidates plus unknown (at most 25 producer/name pairs).
Score interpretable positive/negative features and preserve the runner-up. Penalize
using the same complete evidence as both producer and wine name unless a verified,
explicitly supported case allows it. Penalize packaging/administrative role conflicts.
Do not treat KB origin as proof of bottle origin. Joining adjacent name/designation
must retain existing inverse mappings and avoid crossing distant blocks.

Start in debug replay/shadow reporting. Ranking weights and margins are proposals,
not probabilities or validated thresholds. Use abstention when the top assignments
are close; do not silently choose based on candidate order.

### D. Geography and vintage remain separately constrained

Maintain explicit versus inherited origin evidence and current hierarchy-conflict
warnings. Geography may support spelling/role interpretation only within bounded,
recorded rules; it must not manufacture producer/cuvée. Preserve the U.S. state-as-Country
presentation. Missing state stays unknown rather than showing “US” or assuming California.

Vintage must remain an observed valid year, with founding dates and alcohol/volume
excluded by context. Split-digit reconstruction, if introduced, needs spatially adjacent
observed digits and its own YELLOW reason. Never infer a vintage from a product catalog,
producer, filename or a prior scan. Keep the user-confirmed Olarra 2019 as evaluation
annotation only, not an app correction.

### E. Separate suggestion, validation and UI policy

Shared role/assignment logic should serve both production and diagnostic paths.
Production still applies genuine evidence gates, deadline/quality downgrades and manual
edit protection. Lab suggestions remain YELLOW. Report field status, alternatives,
source IDs and rejection reasons independently of OCR success. Keep country inheritance
and normalization visible so users do not mistake inferred text for literal OCR.

## Evaluation plan before changing production behavior

1. **Replay first:** use saved accepted text + geometry with a pinned KB snapshot.
   Measure role/assignment changes without rerunning models. Run both a lab-like path
   and production merge/validation path; do not copy lab estimated scores into measured
   evidence. Keep old/new outputs side by side with rule version and reasons.
2. **Annotate independently:** transcribe the image and label producer/name/origin/year
   separately from the app's output. Distinguish “not printed”, “unreadable”, “ambiguous”
   and known value. User confirmation is needed where the photograph does not establish
   a field. The saved observed-output fixture must never become ground truth by default.
3. **Split by bottle/producer family:** keep all crops/lighting/rotations of one bottle
   together. Reserve unseen producers, multilingual labels and layouts for hold-out;
   do not randomly split near-duplicate photos or count 12 passes as 12 independent wines.
4. **Measure per-field precision and coverage:** exact and normalized value accuracy,
   wrong nonblank suggestion rate, abstention rate, ambiguity rate and fraction requiring
   correction. Report producer/name swap rate separately. “More fields filled” is not
   success. Do not count one inferred geographic chain as three independent OCR wins.
5. **Negative controls:** founding years, alcohol with damaged units, importer text,
   wine names containing digits/places, unknown boutique producers, identical brand and
   district names, multiple producers, cropped labels, empty OCR, partial deadlines and
   contradictory vintages. Add distant same-height lines and adjacent split appellations.
6. **Ablations:** role masking, producer-match priority, wine-name ranking, bounded pair
   selection and geometry grouping each get separate on/off replay comparisons. This
   identifies what helped, not just whether a large rewrite changed these six examples.
7. **Device gates:** enforce the existing 15-second total deadline; check post-processing
   latency separately (initial target <100ms for typical bounded line counts, unmeasured),
   memory/cancellation and manual-edit behavior. No additional working bitmap or model.

## Recommended implementation order

| Phase | Deliverable | Promotion gate |
|---|---|---|
| 1 | Replay corpus + explicit raw-role/rejection diagnostics | Baseline reproduced; independent annotations and negative controls established |
| 2 | Exact producer matches before fallback; syntax-supported packaging mask | No new false identities on held-out cases; unknown producers preserved |
| 3 | Geography/footer roles + printed-style fallback; preserve alternatives | Fewer name/producer swaps without lower precision; no fabricated geography |
| 4 | Bounded joint assignment and spatial block limits | Ablation benefit over phases 2–3; bounded latency and useful abstention |
| 5 | Production rollout | Phone regression suite, manual-edit safety, calibrated acceptance policy |

Do not add another OCR/NER model as the first response to these failures. The strongest
next step is phase 1–2: the text is often already present, and the current deterministic
selection rules explain several wrong assignments. A rule tweak that fixes only these
six bottles is not sufficient evidence for a robust improvement.
