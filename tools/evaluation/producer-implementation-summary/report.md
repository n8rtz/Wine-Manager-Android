# Producer implementation: two measured iterations

**Selected for optional lab testing:** `producer-refined-2`. No production/default promotion.

| Measure | Previous guarded hybrid | Conservative producer repair |
|---|---:|---:|
| Correct producers | 71 | 76 |
| Wrong producers | 34 | 33 |
| Missing producers | 60 | 56 |
| Complete forms | 32/160 | 33/160 |
| Forms with two or more errors | 82/160 | 80/160 |

Four missing producers and one wrong producer became correct. All 71 previously correct producers were preserved; no new wrong producer fills were introduced. One complete form gained (review 144), none lost. Other five fields were checked unchanged. There are still 89 scorable producer failures (33 wrong, 56 missing).

## What changed

- Full-name candidate construction from observed readings and bounded, spatially adjacent same-engine fragments.
- Producer-role and legal-context checks; generic headings such as “Estate Winery” cannot stand in for a name.
- Two independent engine families must corroborate a co-located reading or composition. Repeated passes of one engine never count twice.
- A reliable existing name extent is protected from appended fragments. Competing names preserve the original instead of an arbitrary winner.
- Every candidate carries text, source observation IDs, construction, engine support and rejection reasons. No reference names or bottle-specific corrections enter extraction.
- Exact-text per-call caching removes repeated role checks. The cached result matched all 168 preceding predictions without old OCR/extractor execution.
- Added a default-off producer-repair checkbox beneath the guarded hybrid lab option. Baseline rows are retained, output is labeled, suggestions stay YELLOW/RED and no intake fields are changed.

## Corrected producer cases

| Review | Before | After | Transition |
|---:|---|---|---|
| 48 | — | CHÁTEAU PEYROUQUET | missing_value → correct_value |
| 68 | — | CHÂTEAU DU BOUSQUET | missing_value → correct_value |
| 94 | — | CHATEAU AMELISSE | missing_value → correct_value |
| 144 | MISE EN BOUTEILLE À LA PROPRIÉTÉ DOMAINE DU MANAY | DOMAINE DU MANAY | wrong_value → correct_value |
| 164 | — | CHẢTEAU DU BOUSQUET | missing_value → correct_value |

## All eight experiments

| Variant | Producer correct / wrong / missing | Complete | Multi-error |
|---|---:|---:|---:|
| baseline | 71 / 38 / 56 | 26/160 | 85 |
| previous_v3 | 55 / 34 / 76 | 24/160 | 88 |
| guarded_hybrid | 71 / 34 / 60 | 32/160 | 82 |
| producer-direct-2 | 71 / 35 / 59 | 32/160 | 82 |
| producer-direct-1 | 73 / 36 / 56 | 32/160 | 80 |
| producer-compose-1 | 76 / 39 / 50 | 31/160 | 78 |
| producer-consensus-2 | 76 / 35 / 54 | 33/160 | 80 |
| producer-refined-2 | 76 / 33 / 56 | 33/160 | 80 |
| producer-refined-1 | 77 / 35 / 53 | 32/160 | 78 |
| producer-title-2 | 77 / 39 / 49 | 32/160 | 77 |
| producer-title-1 | 77 / 46 / 42 | 33/160 | 79 |

The broad title-role hypotheses were rejected: they filled more boxes but introduced too many wrong identities. No variant meets the user target (at least 128 complete and at most 7 multi-error forms on this reference set). This is a small, measured component improvement—not a solution to producer recognition or the overall completion goal.

## Validation

- Actual Android first batch: 39854457095, source 45c3bbe; 672 executions and synthetic checks passed. Publishing expected a missing decision artifact; recovery 39856497283 scored the existing artifact without rerunning the experiment.
- Actual Android second batch: 39857230621, source 17c6f06; 672 revised executions and synthetic checks passed.
- Lab-routing/cache parity validation: Android checks passed in 39861083810, source fcb9c27. All 168 cached predictions matched the preceding outputs, not reference answers. Synthetic lab routing passed. Final archive upload failed due to GitHub artifact storage quota, after results were successfully committed as 8be476c.
- Cached selector-only emulator time: median 290 ms, maximum 1,932 ms, total 63,204 ms; preceding uncached run median 585 ms, maximum 6,940 ms. Different emulator runs, not a controlled phone performance benchmark.
- 127 host tooling tests pass. All current measurements use provisional development references, not independent held-out labels.
- A three-photo visual audit confirms residual saved name-reading errors despite high raw recognizer scores. It does not establish a crop/decoder fix. See docs/producer-reading-audit.md.
