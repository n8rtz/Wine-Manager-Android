# Corpus-guided rule/architecture iteration

Actual Android/Kotlin execution; seven rule combinations plus two targeted baseline correction strategies. Saved OCR and all old comparisons reused. No production change.

| Variant | Correct / wrong / abstained | Complete / eligible | Complete gained / lost vs baseline |
|---|---:|---:|---:|
| saved baseline | 597 / 100 / 197 | 23 / 103 | 0 / 0 |
| saved v3 | 599 / 84 / 211 | 19 / 103 | 0 / 0 |
| rules-001 | 602 / 82 / 210 | 19 / 103 | 5 / 9 |
| rules-010 | 604 / 94 / 196 | 19 / 103 | 5 / 9 |
| rules-011 | 608 / 89 / 197 | 20 / 103 | 6 / 9 |
| rules-100 | 603 / 86 / 205 | 19 / 103 | 5 / 9 |
| rules-101 | 606 / 84 / 204 | 19 / 103 | 5 / 9 |
| rules-110 | 609 / 97 / 188 | 19 / 103 | 5 / 9 |
| rules-111 | 613 / 93 / 188 | 20 / 103 | 6 / 9 |
| baseline-repair | 601 / 100 / 193 | 23 / 103 | 0 / 0 |
| baseline-cuvee-repair | 601 / 100 / 193 | 23 / 103 | 0 / 0 |

Rules bits: equivalence of name typography / independent-engine name agreement / explicit cuvée role. Rules-000 is the saved v3 result and is not rerun. The two hybrid strategies use rules-111 results with saved baseline fields, preserving baseline geography and vintage. They require two-engine direct support for changes; a stricter variant only repairs missing/head-only identities, while the other can replace an exact appellation wine name with an explicitly named cuvée.

## Automatic next-step gate

{
  "advance_candidates": [],
  "production_promoted": false,
  "rule": "Advance for further testing only if full entries exceed both controls, wrong fills <= v3, correct fields >= baseline, deduplicated full entries >= baseline.",
  "next_action": "Do not promote. Use paired losses and hypothesis traces to target the next general rule change."
}

This is a development-set result, not held-out validation. Reference judgments remain provisional. The gate selects further testing only; it never changes production or claims a cure. Hybrid elapsed times in raw output reuse the shared extraction time, not an additional baseline execution.
