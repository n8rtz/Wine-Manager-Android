# Label alternative evidence (identity-v5-evidence)

The previous batch passed identity-v4-layout, but batch `41dcff95-6b6a-4708-bcf5-0f9c9e2e4818` exposed different observations: ML Kit's `DE` scored .5410156 and was correctly rejected by the ordinary singleton merger; Medium omitted it. Medium also damaged the producer tail and the appellation core. This update addresses evidence handling without lowering the ordinary merger gate or enlarging general spelling tolerance.

## Changes

1. FillEngine may retain an otherwise dropped ML Kit connector (`de/du/des/di/della`, score >= .40 and < .60, valid normalized box) solely for a geometry-supported three-line title. Both flanking lines must have retained evidence with votes >= .75. Competing nearby connector readings abstain. These tokens never enter ordinary producer/geography/vintage extraction. Original scores, text and boxes remain unchanged; the title stays YELLOW and records the weak connector reason.
2. At a separate standalone title-tail location, two engines' retained readings (each >= .40) may provide alternative complete spellings. A different producer location must uniquely support one of those actual readings within one edit of its distinctive tail. Exact matches protect existing readings, ties abstain, and same-engine variants are not independent evidence. The full observed tail may then be reused at both locations. This is not a dictionary completion or confidence increase. Original sources remain untouched and structured spelling metadata records the inference.
3. A narrowly recognized damaged `COTES DU RH…` appellation shape is excluded from producer/name competition. This classifier creates **no** geographic claim and does not invent a Villages suffix.
4. Contextual producer repair is limited to the actual observed title-tail alternative with separate producer-fragment support. It cannot turn arbitrary similar text into a familiar producer. Conflicting proposals abstain.

## Latest observed replay

The six-field assertions for the first five wines remain unchanged. The sixth now yields:

| Field | Saved-reading replay |
|---|---|
| Producer | `Vignerons De L'ÊNCLaVE` (YELLOW) |
| Wine name | `LA Reserve de L'ÊNCLaVE` (YELLOW) |
| Country / Region / Subregion | blank |
| Vintage | 2022 |

The case and accent in `L'ÊNCLaVE` come from the actual ML Kit reading. No claim of typography restoration is made. In this scan, the geographic text is too damaged for supported extraction, so abstention replaces the previous erroneous wine-name fill. Earlier batch replays still recover geography where their saved readings support it.

## Verification and limits

`LabelEvidenceChecks` exercises scoped weak admission, the unchanged merger threshold, unchanged raw scores/text, missing/weak/misaligned flanks, competing connectors, cross-engine support, exact/tie/location guards and damaged-appellation negatives. `evidence-review.json` retains only the new batch's OCR readings, scores and boxes. The replay generator asserts all six images through the real combined preview adapter. Both older six-image replays remain in the host suite.

Executed: host logic suite, three six-image adapter replays, existing extraction regressions, 60 Python tests, offline-recognition boundary check and diff checks. No Android APK/Compose build, instrumentation/Robolectric, emulator, phone timing or fresh OCR inference was run. Replay uses the host fixture/reference KB; it is not an accuracy guarantee for unseen scans or the installed user database.

Production models, image passes, deadlines, native dependencies and manual-edit protection are unchanged.
