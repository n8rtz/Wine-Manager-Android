# Wine-name audit — initial review queue

**Status:** prepared for human photo review; no new visual decisions have been made. The included targets remain provisional.

The queue joins all 168 photos to the frozen wine-name targets and accepted aliases, notes from the original reference review, saved Production v2.0 output, and frozen baseline output. Input hashes and the outcome totals are in `summary.json`; `build.py` recreates the CSV. All 168 entries remain `PENDING_VISUAL_REVIEW`.

## Current frozen-scoring snapshot

| Disposition against frozen wine-name target | Saved v2 |
|---|---:|
| Correct expected value | 77 |
| Wrong expected value | 44 |
| Missing expected value | 36 |
| Correct expected blank | 5 |
| Unsupported fill of expected blank | 3 |
| Unresolved reference, output blank / filled | 1 / 2 |

There are 157 expected-value slots, 8 expected blanks, and 3 unresolved slots. For the 165 scorable slots, this is 82 correct (77 values plus 5 correct blanks), 44 wrong, 36 missing, and 3 unsupported fills. The baseline uses exactly the same disposition and aliases in the CSV. These counts describe scoring against the frozen reference, not independently verified accuracy.

## What to audit first

1. **Review every wrong/missing prediction**, since that is where a reference mistake or a confirmed algorithm opportunity can change the score. Use the notes and photo; the saved prediction alone cannot distinguish OCR loss from extraction/selection failure.
2. **Review expected-blank fills and unresolved entries**. The three unsupported fills are not proof the reference blank is correct; check whether a distinct name is visible. Likewise, do not convert unresolved to blank just because output is empty.
3. **Use repeated identity groups as consistency checks.** Different views/vintages can change visible text. The frozen reference currently has consistent wine-name targets within its identity groups; verify that apparent duplicates are actually the same product.
4. For each confirmed error, tag one primary cause in reviewer notes: `REFERENCE`, `OCR`, `FIELD_ASSIGNMENT`, `NORMALIZATION_ALIAS`, `NOT_VISIBLE_OR_AMBIGUOUS`, or `OTHER`. Add a short evidence-based rationale.

Examples from the queue illustrate different error types but are not adjudications: a label may yield an OCR-like near-reading (`Duraine Sauvigno` for the reviewed `Touraine Sauvignon` cases), only a partial identity (`Brut` where the review target includes `Crémant d'Alsace Brut`), or a different prominent text line (appellation/producer rather than the reviewed cuvée). For each case, verify the actual photo before assigning cause. Never broaden accepted aliases merely to reward a prediction unless the value is genuinely an equivalent name under a consistent policy.

## Policy questions to settle before changing the scorer

- Count an appellation as the wine name when there is no separate cuvée? Existing references often do; preserve one consistent rule.
- How to handle grape/varietal names, brand/range names, cuvée names, and combinations (`Volcanie Rosé`, `Les Accords Syrah Marselan`, etc.)?
- Distinguish main wine identity from producer, bottler, classification (`Grand Cru Classé`), origin text, and marketing descriptors.
- Treat partial but meaningful names as incorrect, unresolved, or accepted aliases? Decide from the label and intended app field, not model behavior.
- When the photo does not show a distinct wine name, use expected blank only when absence is supported; otherwise keep unresolved.

Only after a reviewed set confirms a consistent policy should the canonical reference/aliases change. Then rescore saved outputs without rerunning OCR to isolate the effect of adjudication. Separately, use confirmed prediction errors to choose extraction/OCR improvements and measure them on held-out labels before any promotion.
