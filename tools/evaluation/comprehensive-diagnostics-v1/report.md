# Comprehensive saved-corpus diagnostic suite

**Exploratory development-set analysis—not held-out accuracy or an Android implementation of a hybrid.** No OCR, baseline, v2 or v3 extractor was rerun. All selection policies operate on saved outputs only; production unchanged.

## Existing workflow components

| Saved system | Correct / wrong / abstained | Complete / eligible |
|---|---:|---:|
| production | 480 / 141 / 271 | 6 / 101 |
| lab_baseline | 597 / 100 / 195 | 23 / 101 |
| experimental_v2 | 566 / 121 / 205 | 8 / 101 |
| experimental_v3 | 599 / 84 / 209 | 19 / 101 |
| production_60s | 486 / 138 / 268 | 6 / 101 |

## Opportunity bounds (use reference answers; NOT runtime policies)

- Either complete entry already correct: **28/101**.
- Every field correct in at least one selected output: **32/101**.
- Every field correct somewhere in selected outputs / baseline alternatives / v3 hypotheses: **47/101**.
The latter two can require incompatible choices. They locate potential opportunity, not an achievable guarantee. Candidate traces are limited to what was saved.

## Stage triage

| Category | Fields |
|---|---:|
| baseline_selected_correct_v3_candidate_missing | 19 |
| correct_baseline_alternative_not_selected | 13 |
| correct_v3_hypothesis_not_selected | 107 |
| geography_needs_semantic_origin_review | 67 |
| literal_text_present_candidate_missing | 26 |
| no_literal_match_not_proof_of_ocr_failure | 61 |
| unscored_reference | 116 |
| v3_selected_correct | 599 |

Literal-text checks are proxies, not proof of OCR correctness/failure. Geography is not classified from literal absence because it can require appellation inference. A correct generated hypothesis not selected does not distinguish rule rejection, search pruning, ranking and ambiguity; inspect Android decision traces before claiming causation.

## 107 reference-free selection policies

16 coherent producer/name/geography/vintage source assignments; 64 independent-field source assignments (stress tests); 8 preferred-source/fallback/disagreement policies; 18 OCR-evidence score/margin policies; strict agreement. Raw OCR support is not independent model consensus: both extractors share evidence. No probabilities are manufactured.

Top 12 **in-sample** policies below, ranked by full entries then wrong fills. These are hypotheses to validate, not recommendations to deploy. Full grid, per-field results, gained/lost complete entries, duplicate sensitivity, structural flags and deterministic identity-group slices are in summary.json. Group slices are robustness diagnostics, NOT a held-out test: the corpus has already informed development.

| Policy | Complete / eligible | Correct / wrong / abstained | Complete gained / lost vs baseline | Mixed geography sources |
|---|---:|---:|---:|---:|
| evidence-0-0-0 | 28 / 101 | 648 / 118 / 126 | 7 / 2 | 0 |
| evidence-1-0-0 | 26 / 101 | 643 / 118 / 131 | 7 / 4 | 0 |
| hybrid-0-1-prefer | 26 / 101 | 630 / 129 / 133 | 3 / 0 | 0 |
| field-011100 | 25 / 101 | 630 / 89 / 173 | 6 / 4 | 96 |
| field-011000 | 25 / 101 | 627 / 90 / 175 | 6 / 4 | 118 |
| field-010100 | 25 / 101 | 620 / 90 / 182 | 6 / 4 | 116 |
| field-010000 | 25 / 101 | 617 / 91 / 184 | 6 / 4 | 0 |
| group-0100 | 25 / 101 | 617 / 91 / 184 | 6 / 4 | 0 |
| evidence-0-2-0 | 25 / 101 | 633 / 99 / 160 | 7 / 5 | 0 |
| evidence-1-2-0 | 25 / 101 | 633 / 99 / 160 | 7 / 5 | 0 |
| hybrid-1-1-prefer | 25 / 101 | 634 / 113 / 145 | 8 / 6 | 0 |
| field-011110 | 24 / 101 | 624 / 88 / 180 | 5 / 4 | 0 |

## Near-complete entries

Number of unresolved known fields on the 101 six-known photos (wrong fills and blanks both count):

- baseline: 0 fields remaining: 23 photos, 1 fields remaining: 33 photos, 2 fields remaining: 17 photos, 3 fields remaining: 4 photos, 4 fields remaining: 15 photos, 5 fields remaining: 9 photos
- v3: 0 fields remaining: 19 photos, 1 fields remaining: 30 photos, 2 fields remaining: 24 photos, 3 fields remaining: 8 photos, 4 fields remaining: 14 photos, 5 fields remaining: 6 photos

## Lower-error shortlist (not automatically safe to deploy)

These group-preserving policies have no more total wrong fills than the saved baseline. Complete-entry gains and losses still need inspection; a lower error total does not mean zero new errors.

- `group-0100`: 25/101 complete; 617 correct, 91 wrong, 184 abstained; 6 complete entries gained and 4 lost.
- `evidence-0-2-0`: 25/101 complete; 633 correct, 99 wrong, 160 abstained; 7 complete entries gained and 5 lost.
- `evidence-1-2-0`: 25/101 complete; 633 correct, 99 wrong, 160 abstained; 7 complete entries gained and 5 lost.
- `group-0110`: 24/101 complete; 624 correct, 88 wrong, 180 abstained; 5 complete entries gained and 4 lost.
- `group-0000`: 23/101 complete; 597 correct, 100 wrong, 195 abstained; 0 complete entries gained and 0 lost.

## Review and reproduction

- `case-diagnostics.csv`: eligible near-complete entries, blocking fields and oracle opportunity flags.
- `field-diagnostics.csv`: all 1008 targets, outputs, provenance, saved alternatives and stage triage.
- `policy-outputs.json.gz`: every policy output, allowing independent rescoring without any extractor rerun.
- `policy-decisions.json.gz`: selected source per field and source evidence scores; -1 means abstention, not a newly generated value.
- `summary.json`: complete grid, hashes, per-field scores, unknowns, complete-entry regressions, group slices and duplicate sensitivity.
- Existing Android search ablation: `tools/evaluation/search-sweep-39814991460/`. Its exhaustive run supplies independent evidence that widening search alone recovered no correct fields on this corpus.
- Missing-region/duplicate-name/mixed-geography flags do not establish full semantic coherence. No KB hierarchy is rerun or guessed here.
- Compare production and lab paths cautiously: image processing and budgets differ. Saved evidence does not isolate each preprocessing stage.
- Freeze references; adjudicate disputed roles separately and apply any revised scoring equally. Validate promising hybrid policies in actual Kotlin and on fresh labels before promotion.

```sh
python3 tools/corpus/comprehensive_diagnostics.py
python3 -m unittest discover -s tools/tests
```
