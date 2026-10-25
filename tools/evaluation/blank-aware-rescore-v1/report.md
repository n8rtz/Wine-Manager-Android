# Blank-aware rescoring of frozen outputs

Expected values retain original aliases and geographic inference. Expected blanks earn credit; unsupported fills are errors. Unresolved remains unscored.

Provisional visual-reference correction at the owner's request: Club des Sommeliers is treated as branding, not as the producer; actual producers are left unresolved when not established. Saved predictions were not rerun.

Reference slots: 892 expected values, 97 expected blanks, 19 unresolved. Fully scorable forms: 158/168.

| System | Complete forms | Correct values | Correct blanks | Wrong values | Unsupported fills | Missing required values |
|---|---:|---:|---:|---:|---:|---:|
| baseline | 26/158 | 597 | 77 | 100 | 20 | 195 |
| previous_v3 | 24/158 | 599 | 77 | 84 | 20 | 209 |
| guarded_hybrid | 32/158 | 627 | 77 | 82 | 20 | 183 |

## Correct slots per fully scorable form (includes correct blanks)

| Correct slots | Baseline | Previous v3 | Guarded hybrid |
|---|---:|---:|---:|
| 6 | 26 | 24 | 32 |
| 5 | 47 | 47 | 45 |
| 4 | 38 | 43 | 43 |
| 3 | 15 | 17 | 14 |
| 2 | 22 | 17 | 18 |
| 1 | 10 | 10 | 6 |
| 0 | 0 | 0 | 0 |

## Interpretation

A correctly blank region/subregion now contributes toward a completely correct form. Geographic inference from supported appellations is still required. Do not compare these counts directly with the old 103-photo denominator as if model quality improved: only the scoring changed.
Producer identity is now unresolved for reviews 14 and 108: Club des Sommeliers is label branding, while the named Cave de Tain and Maison Delor are bottlers; no actual producer is established. Existing no-value producer references for reviews 9/38/109, unreadable/uncertain fields in 4/8/19/101, and the wine-name policy ambiguity in 125 remain unresolved. See reference-overlay.csv for every decision and original review notes.
Unknown is not automatically converted to expected blank. The inferred St. Helena subregion in review 150 remains an expected value, not a blank, under the unchanged geographic policy.
This is a development-set scoring correction, not held-out validation or a new production promotion. Frozen baseline/v3/hybrid outputs are unchanged.
