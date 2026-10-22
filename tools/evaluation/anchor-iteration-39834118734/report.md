# Corpus-guided rule/architecture iteration

Actual Android/Kotlin hybrid selection over frozen baseline and rules-001 component outputs. Every image uses baseline producer/vintage and the entire revised wine/geography group; no reference-dependent selection. Four policies ablate structural abstention on heading-only producers and exact appellation fallback with one/two engines. No baseline, prior extractor, or OCR rerun. Defaults unchanged; development data only.

| Variant | Correct / wrong / abstained | Complete / eligible | Complete gained / lost vs baseline |
|---|---:|---:|---:|
| saved baseline | 597 / 100 / 197 | 23 / 103 | 0 / 0 |
| saved v3 | 599 / 84 / 211 | 19 / 103 | — |
| anchors-only | 627 / 86 / 181 | 24 / 103 | 5 / 4 |
| anchors-role-guard | 627 / 82 / 185 | 24 / 103 | 5 / 4 |
| anchors-role-guard-appellation-1 | 628 / 83 / 183 | 24 / 103 | 5 / 4 |
| anchors-role-guard-appellation-2 | 627 / 83 / 184 | 24 / 103 | 5 / 4 |

## Automatic next-step gate

{
  "advance_candidates": [
    "anchors-role-guard",
    "anchors-role-guard-appellation-1",
    "anchors-role-guard-appellation-2"
  ],
  "production_promoted": false,
  "rule": "Advance for further testing only if full entries exceed both controls, wrong fills <= v3, correct fields >= baseline, deduplicated full entries >= baseline.",
  "next_action": "Validate candidate on fresh grouped labels and Android regression suite."
}

This is a development-set result, not held-out validation. Reference judgments remain provisional. The gate selects further testing only; it never changes production or claims a cure. Elapsed times cover only the new Kotlin selection/repair logic, excluding frozen component extraction and OCR. These are emulator timings, not phone performance. (Timing caption corrected after collection; scores and raw outputs unchanged.)
