# Second producer package: cached TTB permit data audit

## Verdict

**This package contains bulk data and reproduces its stated 77,668-entry build.** It is materially different from the first 128-entry demo. It is still **not a validated global wine-producer gazetteer**, and must not be enabled unchanged in Lab.

Use it as a source for a carefully typed **US permit-holder / operating-name reference layer**, potentially also useful for recognizing importer/bottler footer roles. International estate/brand coverage remains a separate problem. V1.0, v2.0, Lab and Android assets are unchanged by this review.

## Why the ZIP looked empty/small when extracted

The supplied repository ZIP is 5,808,137 bytes compressed and 28,712,365 bytes expanded. **28,659,452 bytes are in hidden `pipeline/.cache/`**. All other files together total only **52,913 bytes**. The bulk files are a combined permit JSON, six CSVs and a cached source landing page.

This explains the small visible folder listing. The original 28,717,377-byte release archive was inaccessible here, so byte-for-byte equivalence or its compression method cannot be established. The repacked ZIP itself was downloaded and verified successfully.

Source: [`build-wine-producer-gazetteer (rezip).zip`](https://github.com/n8rtz/Wine-Reference-Drop/blob/main/build-wine-producer-gazetteer%20(rezip).zip). Pinned Git blob: **8bf9737463092b0a2ef233139dee8e88a0f6fae8**. Per-file SHA-256/size inventory and audit output: `tools/producer-gazetteer-review/rezip/audit.json`.

## Measured offline build

Executed the inspected builder with offline cache loading and a network function that fails if called. No web app, npm installation, live TTB request, Android code, or OCR was run. The temporary SQLite file was deleted after auditing; bulk source files remain external rather than being copied into APK assets or Git.

| Measurement | Observed |
|---|---:|
| Combined JSON rows | 84,689 |
| Total rows loaded across JSON and CSVs | 169,456 |
| Unique parsed records (permit, owner, operating, city, state, industry) | 84,689 |
| Unique permit identifiers | 84,689 |
| Supplied builder's normalized entities / FTS rows | 77,668 / 77,668 |
| Full SQLite size | 40,878,080 bytes (~39.0 MiB) |
| Entities built from only Wine Producer rows | 25,559 |

The 169,456 input rows include overlapping JSON/CSV records; they are not independent sources corroborating each identity. The entity count includes separately generated owner and operating-name entries. **25,559 is not a count of verified wineries either.**

Unique permit records by source industry:

| Industry | Records |
|---|---:|
| Wholesaler (Alcohol) | 39,179 |
| Importer (Alcohol) | 21,657 |
| Wine Producer | 18,152 |
| Distilled Spirits Plant | 5,701 |

Even the Wine Producer category contains businesses such as cider makers; a wine permit is not proof of grape-wine brand identity. All generated entries receive `United States` as country and a permit-address state as region. Neither field establishes the origin of a photographed wine.

The cached report identifies its source as TTB FOIA List of Permittees, and the script has specific TTB source URLs. I have **not independently compared these cached bytes with TTB or verified their freshness or the README's “CC0” claim**. The package does not supply an independent chain of verification just by declaring that license in code.

## Identity hazards demonstrated

- **Owner and operating name become mutual aliases.** `Chateau Montelena` exactly matches both `Standing Journey` and `Château Montelena`. The database can return the legal owner instead of the printed estate. These should be linked entities with typed relationships, not spelling variants.
- **Blackstone matches spirits, not established wine identity.** Exact alias `Blackstone` maps to `Blackstone Spirits` and `Thornton Distilling`, associated with permit `IL-S-15024` / Distilled Spirits Plant. This is a regression test for rejection, not a new wine-producer alias.
- **Margaux is not necessarily Château Margaux.** The data has a US `Margaux & Company Imports` business. Stripping trade words creates a `Margaux` alias, which must not turn French label geography into a US importer identity.
- **Normalization changes display names.** `RIDGE VINEYARDS, INC.` can become `Ridge`; owner and operating spellings also create a separate `Ridge Vineyards` entry. Broad diacritic restoration can change words without source support, e.g. Rose → Rosé. Keep authoritative raw/display names distinct from search keys.
- **Role and provenance vanish in the output schema.** In-memory records retain `permits` and `industries`, but the SQLite writer discards them. A runtime resolver consequently cannot distinguish the dangerous examples above using the exported DB alone.
- **Name-only deduplication crosses business boundaries.** The key omits jurisdiction/permit identity; the first encountered state survives. Do not merge unrelated permit holders just because cleaned names coincide.
- **Missing data can silently fall back to ten hand-written seed records.** The optional minimum-count check occurs after writing the DB. A reviewed builder should fail before publishing output and must never fall back to sample data for recognition.
- **Web search returns ranked candidates, not validated producer decisions.** Trigram Dice/token scores, early stopping and BM25 do not establish identity or calibrated confidence. Retain our bounded field-aware verification/abstention design rather than porting the demo rank directly into form filling.

## Benchmark lexical checks: interpret carefully

The same exact normalized accepted-name comparison used for the first package produces:

- **22/165 known-producer photos** with an alias match in the unrestricted entity set, spanning eight distinct reference names.
- **3/165 photos**, spanning Hawk's Shadow Estate and Spottswoode, when building from Wine Producer records only.

These numbers are **not recognition accuracy, verified identity coverage, or an upper bound on potential recall**. The unrestricted result contains misleading name collisions, notably Margaux and Cavalier. Some reference aliases are broad. Conversely, the strict check misses legitimate longer variants: the cached source includes **Domaine Drouhin Oregon**, but the benchmark's shorter **Domaine Drouhin** does not exactly match it. No claim that the dictionary lacks Drouhin is warranted.

Next evaluation should separate: verified entity presence; safe alias/variant retrieval; top-result correctness; abstention; and final six-field outcomes. Do not use the inflated unrestricted count as evidence of improved OCR accuracy.

## Updated integration plan

This replaces the earlier plan's “obtain missing bulk file” blocker **for this US permit source only**. The first package's missing `ttb_cola_registry_raw.tsv.gz` remains missing; this is a different dataset and builder, not that claimed global corpus.

1. **Rebuild from uniquely identified source permit records.** Retain original source file hashes/URLs, permit IDs, roles, raw owner and operating names. Verify provenance/reuse terms before distributing an asset. Do not count duplicated CSV/JSON rows as extra evidence.
2. **Keep source identities and relationships typed.** Legal owner, operating/trade name, brand, bottler/importer and estate remain distinct. Link them; do not make their names unrestricted mutual aliases. Maintain source-supported display spellings; normalization is for matching only.
3. **Use a conservative positive subset.** Wine-permit operating names are a starting point, not automatic trusted label identities. Preserve uncertainty for ambiguous names, cider/mead businesses and owner-only records. Do not globally discard a company with importer permits if it also has independent wine-producer evidence. Separate importer/wholesaler/spirit-only names into role-disambiguation evidence, never positive producer autofill by default.
4. **Build the debug-only offline `ProducerGazetteer` adapter** using the existing appellation gazetteer's bundled SQLite/FTS5, checksum and off-main-thread mechanisms. Use the richer schema from the original plan, bounded retrieval and edit checks, distinctive-token support, top-two separation and explicit abstention. Share infrastructure, not appellation-specific cleaning rules.
5. **Feed original OCR and safe phrase alternatives, not destructive joins.** Preserve observation provenance, prevent shared-source vote inflation, keep geography and producer identity reasoning independent, and never substitute permit address/state for wine origin. Fix spatial over-merging before judging dictionary improvements.
6. **Evaluate Lab only.** First use role/alias collision unit tests and saved OCR where available; then one latest-Lab phone benchmark against saved v2. Keep existing three modes, scoring conventions, export size limits and acceptance goals. No v2 live rerun inside Lab, no normal-scanner promotion, no new OCR models.
7. **Expand international coverage separately.** A US importer list does not supply the foreign estates whose bottles it imports. Additional verified global producer/brand sources are needed; do not claim this source closes that gap.

**Recommendation:** reuse the cached source records, but replace the identity assembly and storage schema before importing recognition data. The infrastructure is feasible; correctness of entity roles and label-facing names is the key work, not maximizing the row count.

## Reproduction

Download the pinned repository blob via `gh api repos/n8rtz/Wine-Reference-Drop/git/blobs/8bf9737463092b0a2ef233139dee8e88a0f6fae8`, decode its JSON `content` from Base64 to an external ZIP, then run:

```sh
python tools/producer-gazetteer-review/rezip/audit.py --archive /path/to/source.zip
```

The script verifies the Git blob hash, extracts only the inspected builder/cache files into temporary storage, disables network fetching, builds/inspects the database, records counts and lexical probes, then removes temporary input/database files. The committed `audit.json` is an observed audit snapshot, not an approved gazetteer asset.
