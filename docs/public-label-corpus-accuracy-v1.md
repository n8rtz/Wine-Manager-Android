# Public-label corpus: visual-reference scoring

**Provisional, single-agent visual review—not human-adjudicated ground truth.**

All 168 photos were visually reviewed. Targets and accepted aliases were frozen in commit `3c48c98` before loading detailed saved predictions for this comparison. No scanner code or recognition KB was changed.

## Overall results

| Pipeline | Correct | Wrong fills | Abstained | Scored | Unscored | Correct / scored | Correct / scored fills |
|---|---:|---:|---:|---:|---:|---:|---:|
| production | 480 | 143 | 271 | 894 | 114 | 53.69% | 77.05% |
| lab_baseline | 597 | 100 | 197 | 894 | 114 | 66.78% | 85.65% |
| experimental | 566 | 121 | 207 | 894 | 114 | 63.31% | 82.39% |

Wrong means a nonblank output fails the predeclared label-supported aliases. Abstained means blank despite a known target. The 114 unknown targets are excluded from correctness denominators: their filled values are **unverified**, not automatically wrong or correct. GREEN/YELLOW suggestions are both evaluated; this is field correctness, not automatic-acceptance accuracy.

## Per-field results

| Field | Known / 168 | Production C / W / A | Lab baseline C / W / A | Experimental C / W / A |
|---|---:|---:|---:|---:|
| producer | 165 | 35 / 71 / 59 | 71 / 38 / 56 | 24 / 70 / 71 |
| wine_name | 157 | 49 / 63 / 45 | 54 / 56 / 47 | 72 / 48 / 37 |
| country | 157 | 114 / 1 / 42 | 132 / 1 / 24 | 142 / 0 / 15 |
| region | 153 | 94 / 1 / 58 | 113 / 1 / 39 | 116 / 0 / 37 |
| subregion | 112 | 65 / 5 / 42 | 81 / 4 / 27 | 75 / 3 / 34 |
| vintage | 150 | 123 / 2 / 25 | 146 / 0 / 4 | 137 / 0 / 13 |

C = correct; W = wrong fill; A = abstained. Unscorable per field is 168 minus Known.

## Whole-image correctness

| Pipeline | All six correct / six-known eligible | Ineligible images | All known fields correct (includes partial references) | Images with ≥1 scored wrong fill |
|---|---:|---:|---:|---:|
| production | 6 / 103 | 65 | 15 / 168 | 108 |
| lab_baseline | 23 / 103 | 65 | 34 / 168 | 76 |
| experimental | 8 / 103 | 65 | 10 / 168 | 97 |

**Full correctness requires all six reference fields known and all six outputs correct.** Partial-reference successes do not certify a complete bottle entry, because unscored fields could contain errors. Empty unknowns earn no credit.

## Unknown fields and fill counts

| Pipeline | Filled unknowns (unverified) | Blank unknowns | All filled fields / 1008 |
|---|---:|---:|---:|
| production | 16 | 98 | 639 |
| lab_baseline | 27 | 87 | 724 |
| experimental | 26 | 88 | 713 |

## Duplicate sensitivity

The 168 byte-distinct photos form **143 reviewed visible identity groups**, including **23 repeated groups**. These are not proven physical bottle IDs. Different vintages are separate. Repeated undated labels cannot prove vintage equivalence.

One representative per identity is selected by lowest review number (source-hash order), independently of output quality:

| Pipeline | Correct / wrong / abstained | Scored | Correct / scored | All six correct / eligible |
|---|---:|---:|---:|---:|
| production | 417 / 114 / 231 | 762 | 54.72% | 6 / 87 |
| lab_baseline | 515 / 84 / 163 | 762 | 67.59% | 21 / 87 |
| experimental | 480 / 99 / 183 | 762 | 62.99% | 6 / 87 |

This is a duplicate-sensitivity check, not an independent bottle-level benchmark or train/test split. `scores.json` also includes repeated-group output stability (which may be consistently wrong), confidence-state strata, and strata defined by the production deadline flag.

## Main finding

The lab baseline leads this comparison. Relative to it, experimental produces **31 fewer correct fields and 21 more wrong fills**. Its wine-name results improve (54 → 72 correct), but producer results regress sharply (71 → 24 correct); vintage recall also drops (146 → 137). This does not support promoting experimental over the baseline. The one-photo-per-identity check preserves the same ordering.

Producer and wine-name assignment are the dominant remaining weaknesses, not country recognition. The next engineering step should preserve these frozen outputs, add decision-neutral role/composition traces, and address assignment failures without changing production or using these references as a recognition lookup.

## Interpretation and limitations

- Geography may be inferred from a visible appellation; it is not all literal label transcription. Country stores the US state per the app convention. Origin is not inferred merely from estate reputation or bottler addresses.
- Producer versus brand/cuvée roles, generic range wording, and geographic display levels involve judgment. The frozen reference lists accepted aliases and review notes. Matching is exact after case/accent/punctuation normalization, not fuzzy or substring matching. Alternative assignments not listed may deserve adjudication; these results are diagnostic, not final expert-certified truth.
- The same agent built the tooling and reviewed the photos. Detailed saved predictions were not consulted during annotation, but aggregate fill counts were already known. This is not an independently blinded expert study.
- The corpus is heavily French/Bordeaux-weighted, repeats estates and labels, and is not a representative or held-out worldwide benchmark. All 168 images are retained, including one with uncertain product category.
- Production used its normal 15-second budget; the lab has its separate 60-second diagnostic budget. 95 production scans reached the deadline. Comparisons therefore conflate available OCR evidence/budget with post-processing behavior. Emulator timings are not phone performance.
- The previous 639 / 724 / 713 fill counts are reproduced, but fills are not correctness. No new OCR run, model, or production change was needed.

## Reproduction and review files

```sh
python3 tools/corpus/score_public_corpus.py
python3 -m unittest discover -s tools/tests
```

- Reference: `tools/evaluation/public-corpus-reference-v1/reference.json` plus SHA-256 freeze; `readings.tsv` is the reviewable transcription with notes and pinned source-photo links in JSON.
- Per-output decisions: `tools/evaluation/public-corpus-scoring-v1/field-results.csv` (3,024 rows).
- Counts, duplicate groups, stability, and input hashes: `tools/evaluation/public-corpus-scoring-v1/scores.json`.
- Frozen Android evidence: `tools/evaluation/public-corpus-run-39716483937/`.
- Photos remain outside Git. Any future reference correction must be versioned and reported; do not tune targets to predictions.
