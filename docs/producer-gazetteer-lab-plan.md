# Producer gazetteer: source audit and Lab integration plan

## Decision

**Useful prototype/search ideas; not a ready-to-import authoritative producer database.** Import the supplied package into a review-only tools folder, not Android assets. Keep Production v1.0 and v2.0 unchanged. Do not enable its data or ranking in Lab until the data gates below pass.

Source: [`build-wine-producer-gazetteer (2).zip`](https://github.com/n8rtz/Wine-Reference-Drop/blob/main/build-wine-producer-gazetteer%20(2).zip), Git blob **3749baa923a12efdb28b2bb03119a734c4faac58**, 182,426 bytes. Original archive, hash/provenance manifest and reproducible offline audit are in `tools/producer-gazetteer-review/`.

## What was actually supplied and executed

- Two Python scripts: builder/normalizer and SQLite FTS5 trigram search.
- A Next.js/Postgres web demo, including hard-coded ingestion metrics and search examples.
- **No SQLite database.**
- **No `pipeline/data/ttb_cola_registry_raw.tsv.gz`**, despite comments describing 69,719 raw records and tens of thousands of producers.
- No license file in the ZIP; software and underlying data permissions/provenance are not established by the archive.

Executed the inspected Python builder with **`TTB_OFFLINE_MODE=1`**, in an isolated review directory. It built **128 producer rows / 128 FTS rows**, a **118,784-byte** database, entirely from its 128 hard-coded curated entries. No live TTB download, web app, npm install, Postgres service or OCR was run. The temporary database is not committed as recognition data.

Actual country counts: US 85; France 23; Italy 5; Spain 3; Germany 3; Chile/Australia/Argentina 2 each; South Africa/Portugal/New Zealand 1 each. These describe supplied row labels, not verified registry facts.

The demo's 92,500-producer / 62.25 MB / 12.35-second example is seeded code, **not a measurement of the ZIP**. The builder can silently complete without the large registry. Its `TTB-*` identifiers and permit-looking raw legal names are not evidence that those entries were obtained from official records. The UI also estimates raw ingestion counts from normalized counts. Do not carry these claims into our diagnostics.

### Measured lexical coverage, not accuracy

Comparing canonical names/aliases with the frozen benchmark's accepted producer names gives matches for **14/165 known-producer photos**, representing only **four distinct reference names**: Domaine Drouhin, Château Margaux, Vega Sicilia and Spottswoode. Repeated Château Margaux photos account for 11 of those matches. This is exact normalized alias presence, not OCR recall, independently verified coverage or held-out evaluation.

The package therefore does not currently cover most of our troublesome front-label producers. Adding the existing 128 rows cannot plausibly solve the producer deficit by itself.

## Risks found in the supplied implementation

1. **Wine names and producer identities are conflated.** `Pavillon Rouge` is an alias for Château Margaux; `Cristal` for Louis Roederer; `La Tache` for Domaine de la Romanée-Conti; `Sassicaia` for Tenuta San Guido. Those are potentially useful relationships but not interchangeable producer-name spellings.
2. **Short place/name aliases are ambiguous.** `Margaux` directly retrieves Château Margaux. This must not turn a printed appellation into a producer—or use the estate to invent an unprinted appellation.
3. **Broad matching returns unrelated producers.** Offline queries for `La Cave d’Augustin Florent` return Justin/Austin Hope/Mt. Brave; `Château Amélisse` retrieves unrelated châteaux. `PRODUCT OF FRANCE` retrieves Francis Ford Coppola/Frank Family/Dr. Konstantin Frank. These are search results, not accepted corrections.
4. **Seed-biased ranking.** `compute_relevance_key()` gives an `is_flagship` preference based on a specific `TTB-*` ID pattern. Remove this; seeded demo records must not get privileged truth status. BM25 and manually assigned rank offsets are retrieval scores, not confidence probabilities.
5. **Aggressive shortening.** Prefix/descriptor/initial removal can discard identity-bearing text such as Estate, Cellars, Père et Fils, personal initials or Domaine. Use typed query variants only; never overwrite raw OCR or authoritative display names with these transformations.
6. **Unsafe defaults and deduplication.** Missing country defaults to US; live import also defaults missing state to CA. Alias-based suppression can hide distinct entities. Empty search defaults to Château Montelena; missing DB automatically invokes a build. All are inappropriate for a recognition resolver.
7. **Provenance is lost at storage time.** The SQLite schema stores only ID, name, country, combined state/region text and aliases—not raw source name, source record, license, entity role or alias type. Town/address/AVA and owner/importer/producer meanings are mixed.

## Intended pipeline placement

```text
Fresh ML Kit + PP-OCRv6 readings (unchanged original observations)
    ├─ Existing identity candidates (preserved)
    ├─ Non-destructive spatial phrase proposals
    ├─ Independent appellation/geography evidence
    └─ Producer-name candidates → producer gazetteer shortlist
                                     ↓
                    role + spelling + ambiguity checks
                                     ↓
                candidate evidence / confirmation / abstention
```

This is a **parallel producer-resolution branch**, not a replacement for identity extraction or a filter that consumes words. Spatial phrases must be alternatives to originals, with shared observation IDs; a merged phrase and its source fragments cannot count as independent support. Fix the observed destructive over-merging before using this dictionary as a way to “correct” contaminated phrases.

Do not query only residual strings after removing every recognized appellation/varietal. Names can contain geography and those words may be essential to identity. Tag overlapping evidence without deleting it. The upcoming residual path can contribute candidates, but must not become the only producer/name path.

## Implementation phases

### Phase A — obtain and validate data (blocking asset adoption)

Ask the source generator for the missing `ttb_cola_registry_raw.tsv.gz` **and its real source manifests**: source URLs or export identifiers, retrieval dates, record identifiers, licenses/attributions, raw versus normalized counts, and which rows were manually supplied or generated as demonstrations. A prebuilt `.sqlite` without that provenance is insufficient.

- Reject fabricated/demo permit IDs and unverified “legal” names from production-quality assets; retain them only as clearly marked test fixtures if useful.
- Separate actual producers/estates, front-label brands, legal companies, bottlers and importers. A permit holder is not automatically the front-label producer. US permit data also is not automatically a global producer registry.
- Fail the builder if required source data is missing, counts collapse unexpectedly, or a source checksum changes. No silent 128-row success posing as a large registry.
- Measure coverage against the development corpus by distinct producer and photo count, without copying benchmark answers into a whitelist. Reserve additional identities/photos for held-out checks. Real names absent from the dictionary remain valid open-vocabulary candidates.
- Review code/data reuse permissions before shipping a database. No live registry or subscription API dependency in the app.

**Deliverable:** reviewed source manifest, measured coverage report, exclusion list and deterministic build inputs. Until this is available, a quarantined fixture-only resolver can test mechanics, but not claim broad recognition improvements.

### Phase B — build a small, typed, offline SQLite asset

Use a separate producer schema and asset namespace, e.g. `producer-gazetteer-v1/`, not the appellation database file.

Proposed tables:

- `producers`: stable internal ID, authoritative display name, entity type, raw source name, provenance ID; jurisdiction/address context stored explicitly, not as wine origin.
- `producer_aliases`: alias text, normalized match key, alias type (spelling, abbreviation, trade name, weak short form), provenance and ambiguity markers. One alias may point to several entities.
- `producer_relations`: brand/owner/bottler/product relationships. Product names such as Cristal belong here, **not in the producer-spelling alias index**.
- `sources`: original source record, URL/export ID, retrieval/version/hash and license/attribution.
- `metadata`: schema/data version, hashes, measured counts and build recipe.
- `producer_aliases_fts`: trigram search over approved identity aliases, with entity/alias IDs unindexed. Keep an exact-normalized lookup as the first stage.

Reuse the **mechanics** of our appellation gazetteer: bundled SQLite driver, FTS5/trigram capability check, hash verification, read-only DB and worker-thread I/O. Do **not** copy its appellation cleaner: it intentionally excludes estate words such as Château/Domaine, which are useful producer evidence.

Measure actual APK/installed size before adopting the asset; do not budget against the demo's 62.25 MB claim. Keep real-data assets within the existing soft budget or surface the tradeoff explicitly.

### Phase C — conservative `ProducerGazetteer` resolver

Input: original observed span, source observation IDs, engine/pass, boxes, source scores, candidate role evidence and optional independently established wine-origin context. Never reference expected benchmark answers.

Output: entity/alias IDs, authoritative name, exact observed text, match method, edit distance, retrieval rank, competing candidates, acceptance/abstention reason and provenance. No model probability fabricated from BM25.

1. Filter standalone boilerplate, addresses, vintages, grapes and classifications from *producer candidacy*, without deleting the original observations or blocking other branches.
2. Retrieve exact approved aliases first; otherwise bounded FTS candidates (proposed initial cap 64). Cap span length, query trigrams, edit-distance work and per-image lookup work; check the shared experimental deadline. Final bounds/thresholds must be measured on device.
3. Rerank by distinctive-word agreement, field-aware edit distance, role evidence, ambiguity and top-two separation. Prefix/descriptor stripping is only a candidate-generation aid. Short/generic aliases and initials require stricter treatment; “Estate” or “Domaine” alone must abstain.
4. Country/region context is soft evidence from independently recognized wine origin—not a mandatory country filter or the producer's office address. Do not write gazetteer domicile into Country/Region/Subregion. US state-as-Country UI policy remains intact.
5. A defensible correction is editable/confirmation-required unless existing validation independently supports more. Ambiguous supported rivals can become choices; unsupported low-rank hits do not become chips. Unknown/absent producers remain blank or retain supported raw identity, never force the nearest famous estate.
6. Do not overwrite an already supported producer or take over wine-name assignment merely because a lexicon hit exists. Log disagreements for evaluation; reject role conflicts and product-name-to-maker leaps.

### Phase D — integrate only the latest Lab candidate

Keep the three benchmark options. V1.0 and V2.0 remain unchanged. Lab alone enables the new branch, once data gates pass; it does not launch additional old contenders. Saved v2.0 remains a loaded comparison row.

Add stage-specific raw CSV rows for input span, source observation IDs, normalized query, returned entity/alias IDs, candidate count, BM25, edit distance, role evidence, rejected rivals, selected value, reason, elapsed time, DB hash/version and before/after field values. Preserve original and spatial-alternative observations. The existing 25 MB export splitting handles these diagnostics.

Surface dictionary missing/unavailable separately from “no producer found”; never silently replace the lookup with a generated sample database.

### Phase E — prove value and prevent regressions

1. **Dictionary tests:** provenance, exact names, aliases/collisions, duplicated entities, malformed/untrusted data, missing corpus fail-fast, Unicode and byte-hash reproducibility.
2. **Contrastive tests:** near-miss names against unrelated châteaux; Estate/Domaine/PREMIER; bottler/address lines; `Margaux` as origin vs `Château Margaux` as estate; Cristal/Pavillon Rouge/Sassicaia as wine names; known versus unseen producers; inconsistent geographic context; genuine ambiguity. Include unrelated negatives, not just the builder's preferred Montelena examples.
3. **Spatial regression tests:** the observed producer+Médoc+appellation join and vintage+Brouilly join must not erase usable originals or earn extra support. A dictionary must not make those joins look like valid names.
4. **Saved-evidence extraction analysis:** when available, use the OnePlus raw CSV to isolate resolver changes without repeating OCR. Label this as saved-OCR replay, not an end-to-end image benchmark. The OnePlus rerun's detailed diagnostics have not been supplied in this conversation; emulator traces alone have Paddle timeouts.
5. **Fresh phone validation:** one latest-Lab pass on the same 168 photos after the candidate passes isolated checks. Compare against saved v2.0, with same-attempted-photo rows for partial runs. Report complete gains **and losses**, producer correct/wrong/missing, wine-name spillover, unsupported geographic fills, multi-error forms, engine completion and runtime.

Acceptance is not “more filled fields” or “the right answer is somewhere in top 15.” We need fewer wrong producers and more completely correct forms without losing valid wine/origin fields. The existing ≥80% complete / <5% multi-error goal remains; no gain is claimed from this source audit.

## Immediate next action

Secure the missing source corpus and provenance, while making spatial proposals non-destructive. Then implement a debug-only producer resolver with typed aliases and explicit abstention. Do not drop this demo database straight into the current Lab pipeline: its missing coverage and unsafe aliases would likely amplify exactly the role-confusion errors we are trying to fix.
