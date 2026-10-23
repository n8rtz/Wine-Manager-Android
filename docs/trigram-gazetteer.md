# User-provided gazetteer: SQLite FTS5 trigram experiment

## Dataset inspection

Source: `n8rtz/Wine-Reference-Drop/wine-app-global-wine-appellation.zip`, pinned
Git blob `f9a91093b97fd8a65d6f020733fa498628012c7b`. SHA-256 checksums and the
structural audit are in `data/gazetteer/provenance.json` and the debug asset manifest.
The supplied ZIP contains builders and a JSON export, **not** the claimed prebuilt
`dist/wine_gazetteer.sqlite`. We rebuild from the exported data, without executing
the supplied application or fetching registries.

The export has 3,244 records: **2,989 appellations, 230 regions, 25 subregions**.
There are no duplicate IDs, missing parents, hierarchy cycles or cross-country
parents. There are **18 duplicate country/name groups**, often spanning regional
or state boundaries. We do not silently merge these or choose a state by BM25.
1,535 records have no official code. Registry-source labels are supplied claims,
not independently verified references. No dataset license was found in the ZIP;
this user-supplied material is included for debug evaluation only. Review source
rights and official hierarchy accuracy before production redistribution.

The specification's example code `PDO-FR-A0821` identifies Bordeaux in this export;
Pauillac's supplied code is `PDO-FR-A0713`. Integer export IDs and official codes
remain distinct. **Château Margaux is an estate/producer, not Margaux appellation.**
`Chatau Margux` is therefore a negative role-safety test, not a fabricated
appellation entry or a promised producer correction.

## Implementation

- Builder: `tools/gazetteer/build_sqlite.py`. Source JSON is preserved unchanged.
  Output is the ~2 MiB debug asset `gazetteer-v1/wine_gazetteer.sqlite`, not a release
  asset. Metadata includes source digest and schema version.
- `appellations_fts` uses real FTS5 `tokenize='trigram'`. Country and hierarchy
  columns are **UNINDEXED** so a country word cannot make every child a name match.
  Alternate forms include accent-stripped and hyphen/space variants. Query
  normalization expands whole-word St/Ste/Mt to Saint/Sainte/Mount.
- Exact long-string `MATCH` does **not** implement typo search. We parameterize an
  OR query of quoted trigrams, scope it to name/alias columns, and retrieve at most
  64 records ordered by ascending `bm25()` and ID. Neither SQL nor FTS operators
  are accepted from raw input.
- Rerank against full aliases using edit distance with adjacent transpositions.
  Require ≤0/1/2 edits for names shorter than 5/9/otherwise, similarity ≥0.82 and
  a ≥0.06 margin to the next entity. Ambiguous duplicates abstain even on exact
  text. This is a conservative lexical policy, not calibrated confidence.
- There is deliberately **no universal BM25 cutoff or invented 0.92 probability**.
  BM25 varies with query length, alias lengths and corpus frequency. Returned
  `confidence_score` is explicitly labeled *uncalibrated lexical similarity*,
  separate from `bm25_rank`, alternatives and decision reason. A deployment
  confidence calibration requires real labeled OCR and hard negatives.
- Input is bounded to 160 characters; number/volume/alcohol/warning/administrative
  and estate-prefixed lines are rejected. Bare grapes/styles and short fragments
  do not become origin evidence. Simple appellation wrappers and trailing
  reserve/riserva qualifiers can be excluded from the name query; raw text is
  retained. Qualifier normalization does not claim wine-name correction.

## Android integration and safeguards

`TrigramGazetteer.kt` uses the app's **existing** AndroidX bundled SQLite driver,
not platform SQLite and not a new native library. It checks the packaged DB hash,
schema/count, and proves FTS5+trigram with a temporary native probe, then makes the
connection query-only. Open/query work is forbidden on the UI thread.

In OCR Text Lab, enable **Compare SQLite trigram gazetteer**. It defaults off and
adds a separate `gazetteer_summary`; production and existing comparisons are
unchanged. The sequence is raw ML Kit/Medium readings → resolver → tagged geography
observations → existing rule parser on unclaimed readings. Accepted geography
cannot be reinterpreted as producer/grape in this route. Original journal rows,
engine/pass IDs, original/rectified boxes, alternatives, ID/code/path and scores
are retained. No form/manual values are changed, and no suggestion is GREEN.

Only compatible ancestor/descendant matches form an origin bundle. Conflicting
accepted geography, or a conflict with residual-rule geography, clears the new
bundle for manual review rather than silently selecting a winner. US mapping
keeps state in Country and valley-level origin in Region: California alone adds
no Region/Subregion; Oakville maps California / Napa Valley / Oakville. Other
countries use the supplied region and selected finer entity. Full hierarchy is
exported separately; these field mappings still need real-corpus verification.

Appellation metadata is **not automatically copied into Wine name**: a geographical
name need not be a cuvée. Removing tagged lines may consequently change wine-name
recall; this must be measured in the next complete-form comparison. This version
prioritizes testing role ownership rather than claiming a general form gain.

Missing/corrupt assets, unsupported tokenizer, excessive observations or an expired
60-second diagnostic budget produce an explicit failed/RED preview, not a partially
filled success. The cap is 128 observations and 64 candidates per query. Runtime
queries are bounded, but this is not a hard-interrupt native SQLite deadline.

## Executed host verification

```
python3 tools/gazetteer/build_sqlite.py
python3 tools/gazetteer/verify_trigram_search.py
bash tools/run-host-checks.sh
```

Current measured benchmark: **443 cases**, **421/429 positives correct (98.1%)**,
**0/14 negative matches**; all 8 misses abstain. Standard positive examples pass.
The larger positive set adds deterministic dropped/swapped characters for distinct
catalogue names selected by ID modulus, not hand-selected winning names. This is a
synthetic development check, not an independent held-out OCR/identity test. Reports,
per-query predictions, score distributions and Android parity input are in
`tools/evaluation/gazetteer-trigram-v1/`. It meets the 90% lexical target on this
suite, not an end-to-end scanning acceptance target.

Host tests also cover deterministic DB reproduction and checksums, actual FTS5
integrity, aliases, noisy/producer input rejection, safe defaults and routing
source contracts. A host-only test does not prove Kotlin compilation or device
routing. Production stack, dependencies, baseline outputs and frozen human
references are unchanged.

## Pending actual Android verification

`TrigramGazetteerDeviceTest` is authored, **not executed yet**. It uses real bundled
SQLite on device, compares all 443 decisions to the host oracle, verifies ≥90%
positive recall and zero negative fills, and runs synthetic routing, box retention,
producer exclusion, vintage preservation, US hierarchy, conflict, fail-closed and
YELLOW-only assertions. No image/OCR/baseline corpus rerun is required.

The already registered **Corpus-guided producer refinement** workflow now has a
`gazetteer_validation` boolean. Use **Run workflow**, select
`arena/fd8dc3be-wine-manager-android`, and enable only `gazetteer_validation`.
Do not re-run an older job: that uses its old commit. This mode overrides the
producer experiment, disables artifact uploads regardless of repository settings,
and commits compact reports to `tools/evaluation/gazetteer-validation-RUN_ID/`.
It still needs available hosted compute and manual-dispatch permission. The current
integration previously returned 403 for manual dispatch. No paid storage or deletion
of existing artifacts is needed. This implementation push skips CI to avoid
launching historical corpus experiments merely because a workflow changed.

Next after native parity: evaluate this pre-parser against frozen OCR with existing
form controls, audit wrong geography and lost producer/wine names, then use separate
identities for acceptance and confidence calibration. Do not promote based on
synthetic 98.1% alone.
