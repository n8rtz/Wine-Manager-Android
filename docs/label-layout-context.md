# Label-local layout context (identity-v4-layout)

This update retains production ML Kit + PP-OCRv6 Medium/Medium. It changes field extraction only; no additional image passes, model downloads, network recognition, or camera/UI changes.

## Rules

- `Vignerons` is a producer marker. A leading long one-edit variant can be repaired only with a following de/du/des connector and name remainder. This is a role-word correction, not a producer-name catalog lookup.
- An exact normalized `Côtes du Rhône` core with a single 5–8-character `vil…` suffix within three edits of `villages` can suggest the full appellation. The deliberately wider suffix bound is confined to that exact core, never bare Côtes du Rhône or prefixed producer names. This changes the older VILLAC test expectation from preserving the fragment to offering a YELLOW suggestion.
- A three-line title candidate requires an isolated connector, overlapping/aligned geometry, bounded distance, and exactly one eligible line on each side. No fixed upper/lower label zones are used. Paragraphs, packaging and producer-cue lines cannot supply title pieces.
- A title's final distinctive word may use a unique one-edit match from a separate producer line. An exact match blocks correction; competing matches abstain. Producer tokens are not counted as title OCR votes. The supporting word is recorded in lexical metadata/reasons; all raw token strings, scores and boxes remain untouched.
- Groups of at least three similarly sized/aligned nearby lines, smaller than another prominent block, receive a paragraph ranking penalty. They are not deleted. Explicit producer cues are exempt, as are known high-priority identities from the name penalty.
- Joined titles and corrected fields require confirmation. Structured spelling suggestions and explanations preserve the distinction between lexical inference and OCR confidence. No title casing or accent restoration is invented.

## Validation

`layout-review.json` contains observed text/scores/boxes from uploaded batch `47f71e71-0211-4fc1-981d-50d9b0132b57`, not photos. The host adapter replay asserts all six fields for all six images, including unchanged results for the first five. Image 6 yields:

- Producer: `Vignerons De L'ENclave`
- Wine name: `LA Reserve de L'ENclave`
- France / Rhône / Côtes du Rhône Villages / 2022

Mixed case and missing accents reflect the OCR; these represent Vignerons de l’Enclave and La Réserve de l’Enclave without claiming typography recovery. Producer, wine name and repaired appellation are YELLOW.

`LabelLayoutChecks` tests immutable evidence, role and suffix negatives, false title joins, exact-match protection, ambiguous repeated tails, paragraph membership and small producer protection. The existing earlier-batch replay remains in place. The generated replay CSVs stay under ignored `.ocr-harness/checks`.

Executed: host logic suite including both six-image adapter replays and older extraction regressions; 60 Python tests; offline-recognition boundary check. Not executed: Android APK/Compose compilation, instrumentation/Robolectric, emulator, device timing or fresh photo OCR. These replays use the host fixture/reference KB, not the user's installed cellar database. Passing this batch is not an unseen-label accuracy guarantee.
