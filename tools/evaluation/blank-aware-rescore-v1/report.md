# Blank-aware rescoring of frozen outputs

Expected values retain original aliases and geographic inference. Expected blanks earn credit; unsupported fills are errors. Unresolved remains unscored.

Provisional adjudication from existing visual-review notes, not a new image audit or independent review. No predictions rerun. No frozen v1 reference edited.

Reference slots: 894 expected values, 97 expected blanks, 17 unresolved. Fully scorable forms: 160/168.

| System | Complete forms | Correct values | Correct blanks | Wrong values | Unsupported fills | Missing required values |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 26/160 | 597 | 77 | 100 | 20 | 197 |
| previous_v3 | 24/160 | 599 | 77 | 84 | 20 | 211 |
| guarded_hybrid | 32/160 | 627 | 77 | 82 | 20 | 185 |

## Correct slots per fully scorable form (includes correct blanks)

| Correct slots | Baseline | Previous v3 | Guarded hybrid |
|---|---:|---:|---:|
| 6 | 26 | 24 | 32 |
| 5 | 49 | 48 | 46 |
| 4 | 38 | 43 | 43 |
| 3 | 15 | 18 | 15 |
| 2 | 22 | 17 | 18 |
| 1 | 10 | 10 | 6 |
| 0 | 0 | 0 | 0 |

## Interpretation

A correctly blank region/subregion now contributes toward a completely correct form. Geographic inference from supported appellations is still required. Do not compare these counts directly with the old 103-photo denominator as if model quality improved: only the scoring changed.
No-value producer references for reviews 9/38/109, unreadable/uncertain fields in 4/8/19/101, and the wine-name policy ambiguity in 125 remain unresolved. See reference-overlay.csv for every decision, including the original review notes.
Unknown is not automatically converted to expected blank. The inferred St. Helena subregion in review 150 remains an expected value, not a blank, under the unchanged geographic policy.
This is a development-set scoring correction, not held-out validation or a new production promotion. Frozen baseline/v3/hybrid outputs are unchanged.
