# Producer-only Android iteration

Saved OCR and all controls reused. Only the new producer stage executed. Other five fields are checked unchanged. Blank-aware references are provisional. No production promotion.

| System | Producer correct / wrong / missing | Complete forms | Two or more errors | Complete gained / lost vs hybrid | Meets 80% / <5% target |
|---|---:|---:|---:|---:|---|
| baseline | 71 / 38 / 56 | 26/160 | 85 | 5 / 11 | False |
| previous_v3 | 55 / 34 / 76 | 24/160 | 88 | 3 / 11 | False |
| guarded_hybrid | 71 / 34 / 60 | 32/160 | 82 | 0 / 0 | False |
| producer-refined-2 | 76 / 33 / 56 | 33/160 | 80 | 1 / 0 | False |
| producer-refined-1 | 77 / 35 / 53 | 32/160 | 78 | 0 / 0 | False |
| producer-title-2 | 77 / 39 / 49 | 32/160 | 77 | 0 / 0 | False |
| producer-title-1 | 77 / 46 / 42 | 33/160 | 79 | 1 / 0 | False |

Each output includes candidate text, source observation IDs, construction, engine support and a rejection reason, plus the final decision reason. Engine support is ordinal evidence, not a probability. Complete gains/losses and producer transitions are in scores.json.

Only selector execution time is measured; frozen OCR/extractor costs are excluded. These are emulator, not phone timings.
