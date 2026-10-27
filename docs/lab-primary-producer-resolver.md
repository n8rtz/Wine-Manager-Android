# Primary LWIN catalog and Lab producer resolver

> The normal-app benchmark uses a separate producer-linked wine-name index built from the supplied LWIN export. Unverified TTB, OSM, and X-Wines lists are excluded from packaged assets and runtime producer lookup; their records remain only in offline source-review materials.

## Status

**The packaged producer catalog contains 33,160 eligible producer groups from the supplied primary LWIN export; it is not a worldwide census.** Coverage is limited to producer groups represented in eligible live LWIN wine records. TTB, OSM, and X-Wines lists were reviewed as possible additions but are not verified as label producers, so they are not included in app databases or runtime lookup. This benchmark and its 125 workbook photos are available in the normal app build.

The user supplied `LWINdatabase.zip` from the reference repository. We verified its Git blob, inspected the workbook, reconciled it against the earlier derivative and built the primary database from explicit producer fields. The packaged producer table and its linked-wine index are both in `app/src/main/assets/producer-gazetteer-v1/`. There are no TTB, OSM, or X-Wines support databases in app assets. Their source review is separate from the producer database.

This is **user-supplied primary-export provenance**, not an independent download from Liv-ex, vendor signature, or independent verification of every winery. The manifest's `primary_verified` gate refers to that recorded source review and validated build input; it is not a probability or an authority claim about each record.

## Source checks performed

- URL: https://github.com/n8rtz/Wine-Reference-Drop/blob/main/LWINdatabase.zip
- ZIP Git blob: `8e7457ee74946622c5858aa36ac609cf32e63ab6`; 24,286,931 bytes.
- Workbook: `LWINdatabase.xlsx`, 25,617,114 bytes.
- Workbook SHA-256: `0d918086ad37631fab15d15ec05e8f9f8dec38979fa538b09e788309fb4349f3`.
- Inspected the actual `LWINdatabase` worksheet and its 22 columns, including LWIN, STATUS, PRODUCER_TITLE, PRODUCER_NAME, COUNTRY and TYPE.
- Correctly interpret Excel numeric LWIN IDs (e.g. `1000001.0`) and `NA` placeholders; do not render `NA` as part of a producer name.

### Primary build results

| Measurement | Count |
|---|---:|
| Workbook records | 212,689 |
| Inactive/combined/deleted excluded | 6,724 |
| Non-explicit-wine types excluded | 20,369 |
| Explicit mixed/gift packs excluded | 643 |
| Missing producer/country excluded | 29 |
| Retained LWIN7 records | 184,924 |
| Producer-name/country groups | 33,160 |

Accepted types are Wine, Fortified Wine and Sparkling Wine. In particular, the six `TYPE=Still` rows are held out rather than assumed to mean grape wine. Product names never become producer aliases. Display names remain primary title/name text; normalization is only a match key. Country groups are source grouping metadata, not producer domicile or permission to fill bottle origin.

### Comparison with prior mirror

Compared by LWIN7, not by name-only entity merges:

- 184,821 mirror wine records match the normalized primary producer name.
- **Zero** producer-name disagreements among compared/admitted records.
- 21 mirror codes are not admitted by the stricter primary filter.
- 103 admitted primary codes are absent from the mirror.

The differences are retained in `tools/producer-sources/primary-comparison.json`. The mirror is audit input only, not fallback runtime data.

## Runtime behavior

- `app/src/main/assets/producer-gazetteer-v1/` packages the verified 33,160-row producer table and LWIN-linked `wines.sqlite` for the normal-app benchmark. The benchmark uses only these verified LWIN sources; the standard Add Wine scanner remains unchanged.
- Lookup uses the bundled SQLite driver and ranks all producer rows in the selected country/catalog in bounded-memory chunks; the Lab resolver uses prepared queries, FTS5 trigram and exact-key lookup. SHA-256, schema, row count and FTS checks gate opening. No online lookups or sample fallback.
- Original OCR observations now feed guarded extraction unchanged. Spatial joins remain alternative producer lookup spans with original observation IDs, not replacement readings or additional votes. This removes the previously destructive spatial replacement step.
- Producer replacements come only from the primary LWIN producer/brand table. Two distinct OCR engines with disjoint source observations must support the same producer; multiple variants/joins from one engine do not provide independent votes.
- Whole-span edit distance is bounded (one edit, or two for long names). Ambiguous identities, close rivals, saturated retrieval, incomplete/generic headings, boilerplate, numeric/vintage contamination and over-budget work abstain.
- Single-word names are deliberately not filled by this new resolver: they can be geography or generic names. Existing extraction remains responsible for those cases. Fuzzy retrieval may abstain often on saturated broad trigram queries; no recall claim is made.
- Existing nonblank, different producers are protected. Only a directly observed one-edit correction can replace such a value; normalized-equal existing text is retained as-is.
- Every new suggestion is **YELLOW / confirm manually**, never GREEN based on dictionary membership. No country, region, subregion, wine name or vintage is changed by this resolver.
- Only primary LWIN producer/brand names are eligible database matches in this primary resolver configuration. (Update 2026-10-26: the Lab v4 benchmark contender additionally admits source-tagged TTB, OSM and X-Wines candidates from the separate Lab expanded producer index — `app/src/main/assets/producer-gazetteer-expanded-v1/`, opened only via `ProducerGazetteer.openExpanded` — under the identical evidence rules, with the winning source named in the decision reason and `lexical_support_sources` telemetry. Prod v3.0 uses this primary-only configuration (`producer-resolver-v4`); production behavior is unchanged. See `producer-source-expansion.md`.)
- Bounded at 128 spans and 32 candidate hits per span, with setup and lookup on **separate** budgets since 2026-10-27 (500 ms setup, then 2000 ms for the producer and linked-wine lookups of one contender, `TextLabBudgets`), excluding the existing guarded extractor's time. Deadline exhaustion, missing assets or lookup failure retain the prior producer and log the reason. These bounds are implemented, not yet phone-profiled.
- Diagnostics preserve before/after producer, source/database version, observed span, engine, observation IDs, matched group ID/name, edit distance, lexical support IDs, decision reason and lookup time. Existing split CSV export remains in use.

## Build/reproduce

Install `openpyxl` in an isolated Python environment for XLSX input. The raw workbook is external, not duplicated in this repo. Retrieve the pinned ZIP with GitHub's blob API and decode its Base64 content; verify its blob identity, then extract the workbook. Generate supporting layers with the previously committed source-fetch/build tools.

```sh
python tools/producer-sources/prepare_primary.py \
  --primary /path/to/LWINdatabase.xlsx \
  --provenance tools/producer-sources/primary-provenance.json \
  --out /tmp/producer-gazetteer-v1

# Publish the primary producer table, manifest and NOTICE for the Settings lookup.
mkdir -p app/src/main/assets/producer-gazetteer-v1
cp /tmp/producer-gazetteer-v1/{producers.sqlite,manifest.json,NOTICE.md} app/src/main/assets/producer-gazetteer-v1/
# Package the linked-wine index in main assets with the primary producer database.
cp /tmp/producer-gazetteer-v1/wines.sqlite app/src/main/assets/producer-gazetteer-v1/

python tools/producer-sources/compare_primary.py \
  --asset /tmp/producer-gazetteer-v1 \
  --mirror /path/to/pinned/mirror/files \
  --out tools/producer-sources/primary-comparison.json

# Optional offline source audit only; output is kept outside app assets.
python tools/producer-sources/refresh_open_candidates.py
```

Keep NOTICE.md with the assets; all source licenses and modifications are documented there. A different workbook requires its own accurate acquisition review and hash, not silently changing the attestation to make a mismatched file pass.

## Validation and limits

Host tests exercise real SQLite/FTS asset generation and packaged hashes, strict type/status/name filtering, offline supplemental-source provenance, numeric Excel IDs, unavailable/mismatched source gates, and normal-app benchmark wiring. Actual Kotlin checks exercise resolver decisions and the benchmark summary, including original-observation preservation, two-engine evidence, shared-observation rejection, collisions, budget expiry and protection of other fields.

No Android SDK is installed in this workspace. These checks do **not** constitute a complete Android build, native SQLite execution on a phone or a new recognition benchmark. The next test is the latest Lab on phone with the packaged primary asset, compared with saved v2 results. No accuracy improvement or ≥80% completion result is claimed.

Executed validation for this change: **183 host tests**, **18 actual Kotlin extraction-integration checks**, and **18 actual Kotlin conservative-resolver checks** passed. SQLite quick-check also passed for all three packaged databases. Android/native/on-phone validation remains outstanding.
