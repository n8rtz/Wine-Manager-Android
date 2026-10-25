# Primary LWIN catalog and Lab producer resolver

> Lab now has a separate producer-linked wine-name index and a source-isolated X-Wines supplemental name-candidate index. The former uses the supplied LWIN export; the latter broadens candidate recall but is not a verified producer roster.

## Status

**The main catalog remains the 33,160 eligible producer groups from the supplied primary LWIN export; it is not a worldwide census.** Coverage is limited to producer groups represented in eligible live LWIN wine records. A separate debug/Lab index now adds **19,085 X-Wines name-candidate rows** (18,945 distinct normalized names not already in LWIN; 18,006 not exact matches in the current LWIN/TTB/OSM sources). They are unverified winery/producer/brand names and remain candidates, never primary truths. The OCR resolver is Lab/debug-only; production OCR and normal Add Wine are unchanged.

The user supplied `LWINdatabase.zip` from the reference repository. We verified its Git blob, inspected the workbook, reconciled it against the earlier derivative and built the primary database from explicit producer fields. The primary table remains in `app/src/main/assets/producer-gazetteer-v1/`; linked wines, TTB/OSM support and X-Wines candidates are in debug assets. The X-Wines names are stored in a separate index, with no country or aliases inferred and no change to the verified LWIN table.

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

- `app/src/main/assets/producer-gazetteer-v1/` packages the verified 33,160-row producer table for the read-only Settings lookup. The producer resolver is still only wired in debug Lab; it does not influence normal scanner output. `wines.sqlite` and TTB/OSM support databases remain in debug assets.
- Lookup uses the bundled SQLite driver and ranks all producer rows in the selected country/catalog in bounded-memory chunks; the Lab resolver uses prepared queries, FTS5 trigram and exact-key lookup. SHA-256, schema, row count and FTS checks gate opening. No online lookups or sample fallback.
- Original OCR observations now feed guarded extraction unchanged. Spatial joins remain alternative producer lookup spans with original observation IDs, not replacement readings or additional votes. This removes the previously destructive spatial replacement step.
- Candidate replacements may come from the primary LWIN producer/brand table or the supplemental X-Wines winery-name list. The X-Wines source is explicitly identified in diagnostics and is not treated as verified. Two distinct OCR engines with disjoint source observations must support the same candidate. Multiple variants/joins from one engine do not provide independent votes.
- Whole-span edit distance is bounded (one edit, or two for long names). Ambiguous identities, close rivals, saturated retrieval, incomplete/generic headings, boilerplate, numeric/vintage contamination and over-budget work abstain.
- Single-word names are deliberately not filled by this new resolver: they can be geography or generic names. Existing extraction remains responsible for those cases. Fuzzy retrieval may abstain often on saturated broad trigram queries; no recall claim is made.
- Existing nonblank, different producers are protected. Only a directly observed one-edit correction can replace such a value; normalized-equal existing text is retained as-is.
- Every new suggestion is **YELLOW / confirm manually**, never GREEN based on dictionary membership. No country, region, subregion, wine name or vintage is changed by this resolver.
- TTB operating-name and OSM exact-name support remain separate source files. X-Wines exact names can support LWIN entities or appear as separate fallback candidates. These sources are lexical/name evidence, never extra OCR engines or confidence votes; exact normalized name matching does not prove shared legal identity.
- Bounded at 128 spans and 32 candidate hits per span, with a 500 ms producer-stage budget covering setup plus lookup, excluding the existing guarded extractor's time. Deadline exhaustion, missing assets or lookup failure retain the prior producer and log the reason. These bounds are implemented, not yet phone-profiled.
- Diagnostics preserve before/after producer, source/database version, observed span, engine, observation IDs, matched group ID/name, edit distance, lexical support IDs, decision reason and lookup time. Existing split CSV export remains in use.

## Build/reproduce

Install `openpyxl` in an isolated Python environment for XLSX input. The raw workbook is external, not duplicated in this repo. Retrieve the pinned ZIP with GitHub's blob API and decode its Base64 content; verify its blob identity, then extract the workbook. Generate supporting layers with the previously committed source-fetch/build tools.

```sh
python tools/producer-sources/prepare_primary.py \
  --primary /path/to/LWINdatabase.xlsx \
  --provenance tools/producer-sources/primary-provenance.json \
  --support-dir /path/to/filtered/source/layers \
  --out /tmp/producer-gazetteer-v1

# Publish the primary producer table, manifest and NOTICE for the Settings lookup.
mkdir -p app/src/main/assets/producer-gazetteer-v1
cp /tmp/producer-gazetteer-v1/{producers.sqlite,manifest.json,NOTICE.md} app/src/main/assets/producer-gazetteer-v1/
# Keep wine and auxiliary support indexes in the debug-only Lab asset directory.
cp /tmp/producer-gazetteer-v1/{wines.sqlite,ttb-support.sqlite,osm-support.sqlite} app/src/debug/assets/producer-gazetteer-v1/

python tools/producer-sources/compare_primary.py \
  --asset /tmp/producer-gazetteer-v1 \
  --mirror /path/to/pinned/mirror/files \
  --out tools/producer-sources/primary-comparison.json

# Refresh latest OpenWineMap and X-Wines candidates in debug assets; primary LWIN stays unchanged.
python tools/producer-sources/refresh_open_candidates.py
```

Keep NOTICE.md with the assets; all source licenses and modifications are documented there. A different workbook requires its own accurate acquisition review and hash, not silently changing the attestation to make a mismatched file pass.

## Validation and limits

Host tests exercise real SQLite/FTS asset generation and packaged hashes, strict type/status/name filtering, X-Wines candidate provenance and LWIN non-overlap, numeric Excel IDs, unavailable/mismatched source gates and debug-only wiring. Actual Kotlin checks exercise resolver decisions and the final Lab summary, including original-observation preservation, two-engine evidence, shared-observation rejection, collisions, budget expiry and protection of other fields.

No Android SDK is installed in this workspace. These checks do **not** constitute a complete Android build, native SQLite execution on a phone or a new recognition benchmark. The next test is the latest Lab on phone with the packaged primary asset, compared with saved v2 results. No accuracy improvement or ≥80% completion result is claimed.

Executed validation for this change: **183 host tests**, **18 actual Kotlin extraction-integration checks**, and **18 actual Kotlin conservative-resolver checks** passed. SQLite quick-check also passed for all three packaged databases. Android/native/on-phone validation remains outstanding.
