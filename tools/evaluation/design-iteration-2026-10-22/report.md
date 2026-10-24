# Design iteration: measured hybrid, not wholesale replacement

## Decision and six-field result

**Advance the guarded hybrid to further lab testing, not production.** Seventeen candidates were executed and scored on Android across successive implementation batches. The first nine failed the advancement gate; their losses informed the next eight. The selected architecture preserves baseline producer/vintage, uses revised wine/geography selection, and abstains on incomplete producer headings. It does not use the additional appellation fallback.

| Architecture | Six-field complete | Correct fields | Wrong fills | Abstained | Complete gained / lost vs baseline |
|---|---:|---:|---:|---:|---:|
| Saved lab baseline | 23 / 103 | 597 | 100 | 197 | — |
| Saved evidence v3 | 19 / 103 | 599 | 84 | 211 | 5 / 9 |
| **Guarded hybrid** | **24 / 103** | **627** | **82** | **185** | **5 / 4** |

Against the baseline: **30 additional correct fields, 18 fewer wrong fills, and one additional complete entry net**. This is not uniform improvement: 62 fields become correct but 32 formerly correct fields become wrong or blank. Against v3: 28 additional correct fields, two fewer wrong fills, eight complete gains and three losses. One-photo-per-identity complete entries are 22/87 versus baseline 21/87.

## What is implemented

- The guarded hybrid is an explicit **default-off** option in OCR Text Lab, below “Compare experimental evidence pipeline.” The prior experimental option remains available. Normal production is unchanged.
- The real lab path reuses its already-produced baseline summary, runs the revised cuvée-role extractor, then applies the same guarded selector evaluated on Android. It does not run baseline twice.
- Baseline output remains visible; the extra row is labeled “Guarded hybrid” and CSV version `anchor-hybrid-v1`. Nonblank hybrid fields are YELLOW; absent fields are RED. No intake edits or GREEN promotion.
- Missing/failed baseline fails explicitly rather than rerunning it or silently substituting another architecture.
- Android lab-routing integration validation: **PASS**, [run 39836157004](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39836157004), source `fc033f6`. The app and Android test compiled; synthetic preview assertions passed. This is targeted routing validation, not a full scanner regression suite or fresh-label benchmark.

## Gains and regressions—not just the net score

Complete entries gained versus baseline (review IDs): 2, 33, 47, 51, 165.
Complete entries lost versus baseline: 102, 115, 123, 142. All four involve Saint-Émilion Grand Cru; this is a concentrated weakness to validate, not something hidden by the aggregate.

| Lost baseline-complete case | Field | Provisional target | Hybrid output | Outcome |
|---:|---|---|---|---|
| 102 | wine_name | Saint-Émilion Grand Cru | — | abstained |
| 102 | region | Bordeaux | — | abstained |
| 102 | subregion | Saint-Émilion | — | abstained |
| 115 | wine_name | Saint-Émilion Grand Cru | — | abstained |
| 115 | subregion | Saint-Émilion | — | abstained |
| 123 | wine_name | Saint-Émilion Grand Cru | — | abstained |
| 123 | region | Bordeaux | — | abstained |
| 123 | subregion | Saint-Émilion | — | abstained |
| 142 | wine_name | Saint-Émilion Grand Cru | INT-É M Grand Cru | wrong |

The three complete-entry losses versus v3 are producer regressions in reviews 96, 144 and 155. Preserving a baseline value is not proof it is right; legal-line contamination and name-role ambiguity remain open.

| Paired field transition vs baseline | Count |
|---|---:|
| wrong -> correct | 21 |
| abstained -> correct | 41 |
| correct -> wrong | 8 |
| correct -> abstained | 24 |
| wrong -> abstained | 21 |
| abstained -> wrong | 16 |

## All 17 Android candidates

| Candidate | Complete / 103 | Correct / wrong / abstained | Complete gained / lost vs baseline |
|---|---:|---:|---:|
| rules-001 | 19 | 602 / 82 / 210 | 5 / 9 |
| rules-010 | 19 | 604 / 94 / 196 | 5 / 9 |
| rules-011 | 20 | 608 / 89 / 197 | 6 / 9 |
| rules-100 | 19 | 603 / 86 / 205 | 5 / 9 |
| rules-101 | 19 | 606 / 84 / 204 | 5 / 9 |
| rules-110 | 19 | 609 / 97 / 188 | 5 / 9 |
| rules-111 | 20 | 613 / 93 / 188 | 6 / 9 |
| baseline-repair | 23 | 601 / 100 / 193 | 0 / 0 |
| baseline-cuvee-repair | 23 | 601 / 100 / 193 | 0 / 0 |
| appellation-1-blank | 24 | 600 / 103 / 191 | 1 / 0 |
| appellation-2-blank | 24 | 600 / 102 / 192 | 1 / 0 |
| appellation-1-metadata | 24 | 600 / 103 / 191 | 1 / 0 |
| appellation-2-metadata | 24 | 600 / 102 / 192 | 1 / 0 |
| anchors-only | 24 | 627 / 86 / 181 | 5 / 4 |
| anchors-role-guard | 24 | 627 / 82 / 185 | 5 / 4 |
| anchors-role-guard-appellation-1 | 24 | 628 / 83 / 183 | 5 / 4 |
| anchors-role-guard-appellation-2 | 24 | 627 / 83 / 184 | 5 / 4 |

Rules bits are typography equivalence / independent-engine agreement bonus / explicit cuvée role. The all-off v3 control was reused, not rerun. Full-reading engine agreement helped some fields but also strengthened wrong readings. The first targeted baseline repairs added four correct wine-name fields without completing another entry.

The standalone appellation fallback added one complete entry but also two or three wrong fills. Added to the guarded hybrid, it completed no extra entries and introduced another wrong fill. Three anchor variants passed the unchanged advancement gate; the simpler guarded variant was selected for fewer wrong fills and fewer added rules, rather than chasing one extra correct field at the cost of another error.

## Validation and limits

- Actual Android corpus runs: [39828977215](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39828977215), source `35ddeda`; [39834118734](https://github.com/n8rtz/Wine-Manager-Android/actions/runs/39834118734), source `fc02b11`. All cases completed, no reported corpus failures.
- Attempt 39831650464 compiled but its emulator did not boot; no corpus cases executed. Its retry was combined with the hybrid batch in one emulator session.
- **113 host tooling tests pass.** Actual Android synthetic guard and origin-preservation assertions passed before the corpus batches. Separate real lab-routing validation is tracked above.
- All 168 photos are development data with provisional references; 894 fields are scored, 114 unknown fields excluded. Only 103 photos have all six fields known. Identity deduplication is a sensitivity check, not held-out validation.
- “Baseline” here means the saved generous-budget full-photo FillEngine comparison, not normal app/shutter performance. Same saved ML Kit/Medium readings and frozen component outputs were reused; no baseline, old extractor or OCR execution was repeated.
- Timing in the anchor/appellation batches measures only the new selection/repair step on an emulator, excluding frozen components. It is not end-to-end phone timing.
- Production promotion is **false**. Next validation must use fresh identity-grouped labels and cover designation-rich appellations and producer/legal-line ambiguity before considering any default change.

## Reproducible evidence

- `../rule-iteration-39828977215/`: all nine first-batch outputs, field transitions and decision.
- `../appellation-iteration-39834118734/`: four fallback policies, paired scores and rejected advancement.
- `../anchor-iteration-39834118734/`: four anchor policies, raw Android outputs, source hashes, paired scores and gate decision.
- `docs/corpus-iteration-operating-plan.md`: implementation decisions, no-rerun constraints, safeguards and remaining validation.
