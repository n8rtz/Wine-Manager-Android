# Producer-only Android iteration

Saved OCR and all controls reused. Only the new producer stage executed. Other five fields are checked unchanged. Blank-aware references are provisional. No production promotion.

| System | Producer correct / wrong / missing | Complete forms | Two or more errors | Complete gained / lost vs hybrid | Meets 80% / <5% target |
|---|---:|---:|---:|---:|---|
| baseline | 71 / 38 / 56 | 26/160 | 85 | 5 / 11 | False |
| previous_v3 | 55 / 34 / 76 | 24/160 | 88 | 3 / 11 | False |
| guarded_hybrid | 71 / 34 / 60 | 32/160 | 82 | 0 / 0 | False |
| producer-direct-2 | 71 / 35 / 59 | 32/160 | 82 | 0 / 0 | False |
| producer-direct-1 | 73 / 36 / 56 | 32/160 | 80 | 0 / 0 | False |
| producer-compose-1 | 76 / 39 / 50 | 31/160 | 78 | 0 / 1 | False |
| producer-consensus-2 | 76 / 35 / 54 | 33/160 | 80 | 1 / 0 | False |

Each output includes candidate text, source observation IDs, construction, engine support and a rejection reason, plus the final decision reason. Engine support is ordinal evidence, not a probability. Complete gains/losses and producer transitions are in scores.json.

Only selector execution time is measured; frozen OCR/extractor costs are excluded. These are emulator, not phone timings.
