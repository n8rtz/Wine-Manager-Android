# OCR ambiguity fallback chips

Implemented in the normal Add Wine scan-review form. This changes the review UI
and carries extra ambiguity metadata; it does **not** change the existing winner
ranking, OCR engines, deadlines, or the decision to fill a supported value.
The experimental gazetteer and residual branches remain default-off and separate.

## Decision contract

1. A populated, supported GREEN or YELLOW field stays populated. Do not replace
   it with a chip chooser. Existing YELLOW confirmation still applies.
2. Only an unresolved field with at least two distinct, supported rivals gets
   chips (up to three). A single weak candidate is not automatically upgraded.
3. No useful candidates means blank, without invented choices.
4. Explicit meaningful blanks suppress chips, including presentation mappings
   that move a valley to Region without a finer locality. This flag describes
   the field presentation, not proof that OCR saw every word on the bottle.

`IdentityAssignment` now exposes its actual unresolved rival assignments through
`fallbackCandidates`. Its existing output values and ranking rules are unchanged.
The general diagnostic `alternatives` list is deliberately **not** used as a menu:
that list can contain low-ranked or rejected assignments. The new field travels
through extraction and `FillResult` to `ScanFormState`.

The current producer of fallback candidates is the joint **producer/wine-name**
selector. The UI supports all six fields, but does not invent ambiguity data for
other fields. Vintage inputs require readable, in-range, non-historical evidence.
Geography choices additionally require an explicit matching observation, compatible
selected parents and verified path metadata. US/raw geography that cannot be
safely mapped to the cellar convention is suppressed, not guessed. A known region
never enumerates possible subregions from a catalogue. No narrowed origin is
inferred just to populate chips.

Residual fragments are not yet field-assigned and therefore cannot bypass these
checks or become producer/cuvée chips merely because they survived subtraction.
Their eventual selector can contribute supported typed rivals via the same channel;
original candidates remain available in parallel.

## Interaction

- Chips appear at the affected field only when it is blank and unedited.
- Case/diacritic/punctuation-only duplicate spellings are collapsed. Meaningfully
  different name extents remain distinct.
- Selecting a currently displayed chip writes that field and records a **confirmed
  user edit**, YELLOW, not algorithmic GREEN. Retakes cannot overwrite it.
- “None — leave blank / type below” dismisses choices and protects the empty value.
  The normal text input remains editable. Optional fields do not force a choice.
- Manual typing clears choices. Related choices are invalidated after producer or
  geography changes rather than silently leaving options based on stale context.
- Chips, confirmation and field retake controls are disabled while scanning.
- Stale or forged selection events that do not match a currently offered value
  are ignored.

## Verification

Executed locally, no Actions or Android run:

- **38 checks** execute the actual Kotlin joint selector, policy, form state,
  presentation and parser code on a JVM. Existing host `RectF`/log fixtures stand
  in for Android geometry/logging; this is not a Compose/device test.
- **146 host tests** plus offline-boundary and SQLite checks pass.
- Checks include preservation of GREEN/YELLOW autofills, actual tied assignments,
  choice bounds/deduplication, invalid/no-source/noise rejection, retake protection,
  blank dismissal, manual edits, vintage guards, no unsupported subregions, and
  valley/state presentation blanks.

Repeat with a genuine Kotlin 2.2.10 compiler and JDK:

```
JAVA_HOME=/path/to/jdk KOTLIN_HOME=/path/to/kotlin bash tools/run-choice-checks.sh
bash tools/run-host-checks.sh
```

Android/Compose compilation and on-device rendering/tapping remain **unverified**.
No new scan-accuracy gain is claimed. Next validation should measure top-three
correct-candidate availability, user selection effort and false suggestions,
separately from automatic complete-form accuracy.
