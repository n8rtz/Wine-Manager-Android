# Lab: producer-linked LWIN wine names

## Implemented scope

Historical implementation note: this linked-wine resolver became part of the normal-app benchmark migration. The current benchmark displays **Lab v5** (the pre-refresh capture used the legacy `lab-v4-expanded-producers` pipeline ID) and scores producer candidates from the expanded index with linked-wine matches remaining LWIN-backed; production scanner behavior remains unchanged. Saved historical comparison rows remain comparisons only, not rerun contenders.

Lab uses two separate indices:

1. Primary LWIN producer/title names, with source producer/country-group IDs.
2. Explicit primary LWIN **WINE** text, linked back to those groups and source LWIN7 codes.

Wine names are never producer aliases. A known producer alone cannot fill an unseen wine; a wine alone cannot invent its producer. This is local/offline, with no new model, online service or OCR budget.

## Actual asset build

Same user-supplied primary workbook and provenance as the preceding version; no new external source or mirror-name fallback.

| Item | Count |
|---|---:|
| Producer/country groups | 33,160 |
| Distinct explicit wine-name strings | 101,326 |
| Producer–wine-name links | 154,405 |
| LWIN7 product records represented by wine links | 158,946 |
| Otherwise eligible product records without explicit WINE text | 25,978 |

The last group remains available for producer matching but contributes **no inferred wine name**. Missing/NA values are not reconstructed from DISPLAY_NAME, product geography or producer names. Product names sharing the same literal WINE string can map to multiple LWIN codes; a name match does not uniquely identify a bottle, vintage, colour or site.

`wines.sqlite` uses distinct names, an external-content FTS5 trigram index, and compact integer links to producer IDs. This avoided duplicating long producer identifiers in every index entry. The added wine file is **23,003,136 bytes (~21.94 MiB)** and is now in the main asset source set. The migrated benchmark also packages 124 photos; these asset sizes do not predict compressed APK or installed size.

Manifest schema is 2. Both packaged LWIN database hashes are included in runtime diagnostics. Source identity, licensing, strict live-wine filtering and attribution remain in the manifest and NOTICE. Unverified TTB/OSM lists are not packaged supporting layers; the producer and linked-wine indexes contain only LWIN-derived fields.

## Matching and decisions

- Preserve original OCR observations; spatial phrases are alternatives with explicit original observation IDs.
- Obtain producer candidates from the existing primary resolver. A producer candidate needs support from two distinct OCR engines with disjoint observations. For an already-present single-word producer, exact matching of both its raw OCR and primary name can validate its link identity without introducing or renaming that producer.
- Search the wine index only within the independently supported producer candidates (maximum eight groups). Match wine OCR separately, using exact normalized names first, then bounded trigram/edit-distance candidates.
- Require two distinct engines and disjoint observations for the wine as well. Producer and wine evidence must not overlap. A joined phrase and its underlying fragments are not independent votes or permission to fill two fields from one observation.
- Reject boilerplate, bare generic headings and vintage-contaminated spans. Alphanumeric wine names such as `Bin 389` are permitted; standalone years are not.
- Require an unambiguous producer/name pair with separation from rivals. A genuinely observed wine can disambiguate two independently observed producer candidates, but cannot select an unrelated producer or override a conflicting independently selected producer.
- Shared wine names across unresolved producers abstain. Unseen wines, wrong producer links and saturated result sets abstain.
- Preserve different existing producer and wine values. A directly observed one-edit correction is allowed; the extension does not broadly replace existing wine predictions merely because a dictionary hit ranks higher.
- Suggestions are **YELLOW / require confirmation**. No country, region, subregion, vintage, colour or other product field is inferred from the relationship.

This deliberately prioritizes precision. It can fill missing names, confirm observed pairs, make bounded spelling corrections and resolve supported ambiguity. It is not a blanket replacement for the existing extraction pipeline and does not promise to fix every incorrect nonblank wine prediction.

## Bounds and diagnostics

- *(Update 2026-10-27.)* Setup and lookup now have **separate** budgets: `TextLabBudgets.GAZETTEER_SETUP_MS` = 800 ms for manifest checks, asset copy/verification and SQLite open, then `GAZETTEER_LOOKUP_MS` = 2000 ms for the producer lookup plus the linked-wine lookup of one contender. Previously one 500 ms slice covered setup **and** lookups, so a slow open handed the resolvers the leftovers; `producer.execution` / `wine.execution` were blocked by budget exhaustion on about 30 % of labels. The cached database copy is also no longer re-hashed on every open (see `ProducerGazetteer.load`). OCR remains 60 seconds per engine in the experimental harness.
- Maximum 128 observed/alternative spans, eight producer identities, and 32 candidate wine links per span. A saturated 33-row retrieval causes abstention for that span.
- Loader checks schema, hashes, table counts and FTS availability off the main thread. Per-file installation cap is now 32 MiB to accommodate the measured 21.94 MiB wine file. Missing, mismatched or over-budget assets do not silently substitute sample data.
- New CSV rows: `linked_wine_candidate`, including observed text, engine, observation IDs, wine-name ID, linked producer ID, LWIN7 codes, edit distance and database version/hashes.
- Summary additions include before-value, wine decision/status/reason, selected wine and producer IDs, linked LWIN codes and lookup time. Existing file splitting remains unchanged.

## Reproduction and checks

Use `tools/producer-sources/prepare_primary.py` with the same pinned workbook and provenance file; no supplemental support layers are used. The builder requires the explicit WINE column, builds its own name/relationship tables, checks internal foreign keys and preserves deterministic producer IDs. Packaged tests verify that every linked producer ID exists in the primary producer database.

Executed checks:

- **186 host tests**, including actual packaged SQLite lookup SQL, FTS, source-field filtering, missing-WINE/no-DISPLAY_NAME-fallback cases, hashes, link counts and foreign keys.
- **23 actual Kotlin extraction integration checks**, including the final linked-wine summary and CSV diagnostics.
- **18 actual Kotlin producer resolver checks**.
- **27 actual Kotlin linked-wine resolver checks**, including wrong-producer rejection, same-name ambiguity, two-engine requirements, overlapping evidence, existing-value protection, budget expiry, numeric wine names and producer disambiguation.

These checks do **not** constitute an Android build, native on-phone SQLite test or measured recognition improvement. No Android SDK/device is available here. The next validation is a fresh latest-Lab phone run against the saved v2 results, with attention to cold asset initialization, abstention reasons, wrong/missing producer and wine fields, and complete-form gains and regressions.
