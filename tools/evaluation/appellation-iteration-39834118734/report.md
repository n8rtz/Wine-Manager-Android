# Corpus-guided rule/architecture iteration

Actual Android/Kotlin execution of four baseline repair policies: one vs two co-located OCR engines, blank wine names only vs also administrative-text wine names. Only exact complete printed appellation forms compatible with saved country/region/subregion may be used; a visible named cuvée or competing appellations blocks repair. Other five baseline fields are unchanged. No baseline, prior extractor, or OCR rerun. These are experimental YELLOW suggestions, not promotion or calibrated probabilities.

| Variant | Correct / wrong / abstained | Complete / eligible | Complete gained / lost vs baseline |
|---|---:|---:|---:|
| saved baseline | 597 / 100 / 197 | 23 / 103 | 0 / 0 |
| saved v3 | 599 / 84 / 211 | 19 / 103 | — |
| appellation-1-blank | 600 / 103 / 191 | 24 / 103 | 1 / 0 |
| appellation-2-blank | 600 / 102 / 192 | 24 / 103 | 1 / 0 |
| appellation-1-metadata | 600 / 103 / 191 | 24 / 103 | 1 / 0 |
| appellation-2-metadata | 600 / 102 / 192 | 24 / 103 | 1 / 0 |

## Automatic next-step gate

{
  "advance_candidates": [],
  "production_promoted": false,
  "rule": "Advance for further testing only if full entries exceed both controls, wrong fills <= v3, correct fields >= baseline, deduplicated full entries >= baseline.",
  "next_action": "Do not promote. Use paired losses and hypothesis traces to target the next general rule change."
}

This is a development-set result, not held-out validation. Reference judgments remain provisional. The gate selects further testing only; it never changes production or claims a cure. Elapsed times cover only the new Kotlin selection/repair logic, excluding frozen component extraction and OCR. These are emulator timings, not phone performance. (Timing caption corrected after collection; scores and raw outputs unchanged.)
