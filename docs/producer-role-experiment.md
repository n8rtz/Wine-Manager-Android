# Next producer experiment: explicit role evidence

This is a separate debug/device experiment; the lab recommendation and production
behavior stay unchanged. It uses frozen OCR and hybrid fields. Historical producer
alternatives are ignored at extraction time; no baseline, prior extractor, or OCR
runs. Four policies vary role evidence, not recognition models:

- `producer-maker-2`: a maker label immediately above the name must be corroborated
  by two independent engine families, as must the name itself.
- `producer-maker-1`: same two-engine name requirement, but one engine may supply
  the explicit maker label. This is an ablation, not a recommendation.
- `producer-signature-2`: also allow explicit owner/grower wording immediately below
  a name; two-engine role support remains required. This may identify a legal entity
  rather than the front brand, so wrong-fill regressions must be measured.
- `producer-unique-alias-2`: maker-2 plus rejection of local catalogue aliases that
  match more than one canonical producer name. No canonical-name replacement.

Relationships require the same engine and preprocessing pass, bounded vertical
separation and horizontal alignment. Candidate and marker observations are traced
separately. Co-located name support must come from independent families; duplicate
Paddle passes do not count twice. Bottler/importer-only wording cannot establish
maker role. Explicit combined 'produced and bottled by' wording is distinct from
'bottled by'. Reliable existing producers are protected; unrelated competing names
cause abstention. Font prominence is not used to establish producer role.

Tests cover missing role evidence, insufficient independent engines, different
preprocessing variants, low-confidence/far-away markers, bottlers/importers, grapes,
ambiguous identities and catalogue aliases, provenance, and original preservation.
Host tests additionally verify paired comparisons against the frozen current
recommendation (33/160 complete, 80/160 multi-error), not just the earlier hybrid.

## Catalogue investigation

No expanded catalogue is shipped. `BundledProducerNames.kt` explicitly describes
its contents as source-authored vocabulary, not a downloaded world catalogue.
The inspected GitHub repositories `ronanguilloux/OpenWines`,
`OpenWines/ReferencesBundle`, and `OpenWines/Scraper` exposed regional/appellation/
grape material rather than a suitable verified broader producer/brand catalogue.
Software licensing alone would not establish a usable data provenance chain.
No test-reference producer names are copied into extraction. Sourced catalogue
expansion and bounded spelling correction remain separate, unfinished work.

## Execution

Run host checks here with `bash tools/run-host-checks.sh`. The manually dispatched
`producer-roles.yml` runs the actual Kotlin policies on Android, 168 x 4 predictions,
with 60 seconds per image/policy. There is no artifact-upload step. Scores, compressed
prediction traces, paired comparison and diagnostics use the existing branch/check
publication path. Compute availability remains necessary. No production promotion
is automatic, even if development metrics improve.
