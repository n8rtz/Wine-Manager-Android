# Wine-name reference audit queue

This directory includes `reference-data-168.csv`, a one-row-per-photo export of the **original six-field reference data being compared against**, alongside the current blank-aware scoring disposition. It carries source URL, image hash, identity group, review notes, original status/value/aliases/basis, and scored status/target/aliases for Producer, Wine name, Country, Region, Subregion, and Vintage. Alias lists are JSON arrays inside CSV cells. Empty values must be interpreted together with their status.

This is a reproducible **review queue**, not a claim that 168 images have now been independently re-adjudicated. It joins the frozen public-corpus wine-name reference, the blank-aware scoring disposition, the saved Production v2.0 phone predictions, and the frozen baseline. Every row starts `PENDING_VISUAL_REVIEW`; reviewer fields are intentionally blank.

Rebuild the all-fields reference export with:

```sh
python3 tools/evaluation/wine-name-audit-v1/export_reference_data.py
```

Build/update from the checked-in inputs:

```sh
python3 tools/evaluation/wine-name-audit-v1/build.py
```

Open `wine-name-review.csv` in a spreadsheet. Review image evidence from the `source_url` or local bundled image; consider the reference note, target, accepted aliases, and both predictions. Record a decision and rationale, then mark the audit status (for example `CONFIRMED`, `REFERENCE_CORRECTED`, `UNRESOLVED`, or `POLICY_AMBIGUOUS`). Do not change a target solely because a model predicted something else. If reference policy changes, update the canonical reference via its normal review process and regenerate dependent scoring assets; do not hand-edit generated CSV or loosen normalizer matching to make outputs pass.

Suggested review order: all saved-v2 `wrong_value` and `missing_value` rows; then `unsupported_fill` / expected-blank cases; then unresolved cases and duplicated identity groups for consistency. For each case distinguish (1) reference target/policy issue, (2) unreadable or genuinely absent name, (3) OCR/text-recognition miss, (4) extraction/field assignment issue, and (5) acceptable alias absent from the reference. Names can legitimately be appellations, varietals, cuvées, branded ranges, or a combination; bottler, producer, classification, tagline, and marketing text are not automatically the wine name. Apply one documented rule consistently and avoid inferring a name from outside knowledge when the photo does not establish it.

`summary.json` records SHA-256 hashes of inputs and aggregate outcomes so the queue can be reproduced and compared. The current reference is frozen and provisional; only the human-reviewed queue can establish new adjudications. No recognition code, frozen reference, or production behavior is changed by this tool.
