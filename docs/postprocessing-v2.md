# identity-v2: implemented shared post-processing

Production and Text Lab now share the revised `FieldExtractor`, `IdentityRoles`,
`IdentityAssignment`, `LineBuilder`, metadata rules and validator. Production merge
thresholds, raw OCR, measured scores, geographic conflict handling, manual edits,
15-second deadline and model stack are unchanged. Diagnostic lab values stay YELLOW.

## Implemented

- Classify each observed line before removing years or packaging tokens. Roles overlap:
  a legal appellation may also be a wine name. Original token strings and both coordinate
  spaces remain immutable. Excluded lines remain visible in role diagnostics.
- Recognize damaged alcohol syntax using numeric + BY + closed unit patterns; recognize
  bounded net-content fragments. No blanket rejection of names with digits, “Vol”, or
  a decimal number. Detect complete/near-complete legal denomination phrases and
  grape-production statements rather than letting leftover words become identities.
- Match exact country-language forms (e.g. España/Italia/Deutschland) only. Origin-only
  lines must consist entirely of verified place forms, not merely contain a place word.
- For a damaged district/valley/county/coast/region suffix, require an exact prefix of
  an existing local place and at most two suffix edits. This suppresses the identity
  role only: it **does not create or correct an origin claim**.
- Evaluate all exact KB/curated producer matches ahead of unknown-maker candidates.
  Keep boutique/unknown makers eligible, preserve adjacent maker-marker evidence and
  prevent redundant OCR-only interpretations of the same known-producer evidence from
  becoming false competitors. A known cuvée lookup requires an unambiguous exact maker
  and an exact cuvée hit; arbitrary fuzzy cuvée substitutions are no longer accepted.
- Producer phrase recovery uses individual or spatially adjacent lines rather than
  arbitrary whole-label concatenation. Nearby marker/name pairs retain their evidence.
- Group OCR lines only with vertical agreement AND a bounded horizontal gap (at most
  0.10 normalized label width and twice the larger line height). Join two adjacent,
  aligned appellation fragments only if their entire observed combination exactly
  matches a verified appellation form. Do not join distant blocks or invent characters.
- Rank verified cuvées, distinctive names/designations, varietals/styles and appellation
  names above weak fragments. Multiword unknown names remain eligible; proximity to
  a legal block lowers unsupported-name rank without globally banning unknown wines.
- Consider at most four producer candidates and four name candidates plus unknown:
  **25 joint assignments maximum**. Penalize shared evidence or duplicate producer/name
  use, except independently verified maker/cuvée interpretations of a compound line.
  Retain alternatives. Close competing assignments abstain with an empty RED field and
  an explicit ambiguity reason instead of letting list order decide.
- Remove the bottle-specific Reciente spelling completion. Printed readings and adjacent
  designations may still join, but a missing/misread name is not supplied from the bottle.
- Keep vintage tied to observed digits and context; packaging/importer lines cannot
  seed a vintage. Conflicting years remain confirmation-required. No split-digit or
  product-catalog vintage reconstruction was added.

## Ranking policy (not a probability model)

Initial priorities: exact KB 100, curated producer 95, bounded full-producer fuzzy match
70, explicit unknown maker cue 65, other unknown maker 30 plus capped prominence (0–4).
Wine priorities: exact cuvée 100, name+designation 80, distinctive multiword name 70,
printed non-appellation style 65, appellation 60, other unknown name 45. Unsupported
names next to a legal block lose 30 units. Shared-evidence/duplicate identity pairs lose
200. Rival assignments within 3 units cause abstention for the disputed field.
These are explicit heuristics with tests, **not calibrated confidence or validated
universal thresholds**. They must be evaluated on unseen labels before claiming a
measured accuracy gain. Same engine/variant repetitions never become independent votes.

## Diagnostics and replay

`FillResult` exposes up to four alternatives per identity field and bounded role traces;
the production copyable diagnostic formatter prints them. The lab CSV includes
`post_processing_version`, `role_diagnostics`, `producer_alternatives` and
`wine_name_alternatives`, as well as existing field states/reasons and raw OCR rows.
The existing field table displays the chosen value or unknown; it does not auto-select
an alternative. No expected/user-entered values are inputs to these decisions.

Run `tools/run-logic-checks.sh` with its documented real JDK/Kotlin/Android API setup.
It generates replay Kotlin from the immutable observed corpus and emits
`.ocr-harness/checks/postprocessing-replay.csv`: 12 per-engine lab replays plus six
combined production-rule replays. Lab replay uses the same estimated 0.5 inputs as the
old table. Combined replay restores reported measured/estimated scores and real engine
IDs, then applies production gates. It is not new OCR inference, a phone benchmark,
or a faithful reproduction of shutter timing/rectification. It uses the host fixture
KB plus the reference supplement, which may differ from the phone's SQLite/imports.
Old observed outputs are comparisons, not automatically asserted ground truth.

## Executed checks and remaining limits

The host suite exercises actual shared logic, using explicit host geometry/clock stubs.
New tests cover producer priority, unknown wineries, digit-bearing names, noisy alcohol,
legal text, split appellations, origin footers, damaged district suffixes without invented
geography, distant same-height lines, empty inputs, conflicting years, bounded alternatives
and ambiguous joint assignments. Existing manual-edit, confidence, deadline and geography
checks remain. Two old test expectations changed intentionally: the line-joining fixture
now places intended word pieces adjacently; the Olarra fixture expects preserved OCR
rather than the removed special-case spelling completion.

Robolectric wrappers are authored; full Android/Compose compilation and native execution
are separate from host validation. The replay is development data from six previously
seen wines, not a held-out accuracy test. In particular the Rhône label remains difficult,
and ML Kit's LACKSTONE is not fabricated into Blackstone. Incorrect YELLOW suggestions
remain possible. Independent annotations, per-field precision/coverage, ablations and
phone latency/memory validation from the review plan are still needed.

## Field-aware spelling extension

The subsequent `identity-v3-spelling` version adds bounded producer, style, cuvée and
anchored-geography suggestions without modifying OCR confidence. See
[field-aware spelling](field-aware-spelling.md) for bounds, metadata and limitations.
