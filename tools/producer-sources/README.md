# Producer source layers: primary LWIN, separate support and debug candidates

> Current: the user-supplied LWIN catalog remains the primary layer. The debug-only primary asset and Lab resolver are implemented; see `docs/lab-primary-producer-resolver.md`. Current supplemental acquisition is detailed below and in `docs/producer-source-expansion.md`.

**Reference boundary:** `lexical-audit.json` is a retired historical report against the former 168-image reference. Do not use its counts or names for current source selection, coverage, or evaluation. `audit_lexical.py` now requires an explicit reference and refuses the retired reference by content hash (even if renamed); use only the current scrubbed reference after it is available and approved.

## Historical source-staging baseline

The initial source exports were staged under `/home/user/producer-sources/curated/` (that path is not guaranteed to exist in a fresh checkout):

| Layer | Kept | Removed / held out |
|---|---:|---|
| TTB wine permit holders | 16,793 permit records | 66,537 importer/wholesaler/spirits-only source rows; 1,359 wine-permit records with cider/mead/sake/kombucha name indicators |
| LWIN wine producer/brand groups | 33,146 groups | Mirror already filters spirits, beer, retired codes and cases; linked 184,842 wine records checked |
| OSM explicit named wineries | 15,316 mapped records | 25,223 not explicitly craft/industrial wineries; 1,731 unnamed; 62 non-grape-name cases; 1 duplicate OSM ID |

These figures are a historical initial-staging snapshot, not current package counts. They are **not unique verified wineries** and must not be added together as such. Current primary LWIN contains 33,160 filtered producer groups. Current supplemental snapshots and their exact counts are recorded in `docs/producer-source-expansion.md` and `app/src/debug/assets/producer-gazetteer-v1/supplemental-source-refresh.json`. Different permits/sites/brands can belong to the same business. Businesses with both wine and non-wine permits keep their wine-permit evidence; non-wine permit rows are excluded. Non-grape name checks are conservative holds for review, not proof a business never makes grape wine. Winery tags and LWIN producer/brand fields are source evidence, not independent verification of every business's physical production role.

No importer-only or wholesaler-only layer, wine shop/bar, vineyard-only or cellar-only layer is emitted. The original mixed archives and historical audits are retained as **audit inputs only**, outside these working candidate layers. They are not loaded into the app. No legitimate producer was removed merely because it also has an importer permit.

`build.py` reads data only; it does not run either third-party builder. It retains raw owner/operating names separately, does not invent aliases, does not strip display-name words, and does not combine product names with producer names. It uses the combined TTB JSON once instead of double-counting the overlapping CSVs. All layers remain separate, including licensing boundaries; separation is not a blanket legal conclusion about any future combined database.

The original filtered source exports are reproducible with `build.py`. Current OSM and X-Wines snapshots are refreshed by `refresh_open_candidates.py`; only the separate OSM support and X-Wines candidate SQLite files enter debug assets. The verified primary LWIN SQLite remains unchanged. The Android asset manifest records each source commit, hash, license/status, counts and attribution.

## Reproduce

Python 3 and GitHub/codeload access are sufficient for automatic refresh; the supplemental refresh uses the public GitHub API without credentials:

```sh
python tools/producer-sources/fetch.py --out /path/outside/repo/inputs
python tools/producer-sources/build.py \
  --ttb-zip /path/outside/repo/inputs/ttb-rezip.zip \
  --lwin-dir /path/outside/repo/inputs \
  --osm-zip /path/outside/repo/inputs/openwinemap.zip \
  --out /path/outside/repo/curated
```


Refresh debug-only candidate/support assets from the latest public GitHub snapshots:

```sh
python tools/producer-sources/refresh_open_candidates.py
# Reproduce a saved source revision:
python tools/producer-sources/refresh_open_candidates.py \
  --osm-commit 1f4bf605d1938844c275e7ec0ae0e23141b4a61e \
  --xwines-commit 7f9b50c1241995693d708f5a628a57dae3aee7d5
```

The X-Wines supplement extracts only the 30,510-name list from the precomputed Atlas payload; it does not execute the HTML or use wine labels/ratings. Its candidate DB excludes normalized keys already present in the primary LWIN index while retaining all source names in a separate exact-support table. X-Wines candidates are unverified, have no country inferred, and remain confined to debug/Lab. The Atlas repository has no standalone license declaration; upstream X-Wines declares CC0-1.0, and both source revisions plus the paper citation are retained in the manifest/NOTICE.

Inputs are pinned, checksum-checked and not executed. A mismatch fails rather than silently fetching another dataset. JSONL gzip outputs have stable ordering and gzip timestamps. Source IDs remain separate; names alone are never a cross-source deduplication key.

## Attribution / reuse

- **TTB:** supplied cached FOIA List of Permittees, Git blob `8bf9737463092b0a2ef233139dee8e88a0f6fae8` from `n8rtz/Wine-Reference-Drop`. Its claim of public-domain/CC0, freshness and authenticity were not independently validated against a fresh TTB export. Retain source permit IDs; treat this as a review input, not an authoritative label identity catalogue.
- **LWIN by Liv-ex**, https://www.liv-ex.com/lwin/, Creative Commons Attribution 4.0, https://creativecommons.org/licenses/by/4.0/. Downloaded derivative from **Open Wine Atlas contributors**, `doma17-wc/open-wine-atlas` commit `92ee7f1ea991cca862a86b86173e1c28ab5d57b6`, `web/data/producers.json` and `web/data/wines.json`, snapshot date 2026-10-21. Its DATA_LICENSE.md and Liv-ex's own license page identify CC BY 4.0. Changes here: retain producer/country groups and linked LWIN7 IDs only; discard wine names and atlas place assignments; no inferred aliases. Primary XLSX not downloaded or independently compared. The upstream pipeline allows missing TYPE and a DISPLAY_NAME fallback for missing producer fields; the derivative does not mark those fallback rows. These candidates are not verified physical producer identities. Country groups describe the source's wine grouping, **not producer domicile**.
- **© OpenStreetMap contributors**, https://www.openstreetmap.org/copyright, **ODbL 1.0**. The initial staged derivative used **OpenHistoryMap/openwinemap** commit `98a5bf983abd23b1f6255bcb60bc73bd99b6b69e` (historical). The current debug support snapshot is refreshed from commit `1f4bf605d1938844c275e7ec0ae0e23141b4a61e`, recorded with archive hash, timestamp, counts and attribution in the manifest/report. It retains named, explicit craft/industrial wineries, deduplicated by exact OSM ID; it omits phone/address details and coordinates. Remains a separate attributed ODbL-derived layer. Do not casually merge it into differently licensed data or assume separation alone resolves every downstream obligation.
- **X-Wines / X-Wines Atlas**: source identity, exact revisions, archive hashes, license caveat, counts and the de Azambuja, Morais & Filipe (2023) paper citation are in the main asset manifest, debug refresh report and `NOTICE.md`. The dataset repository declares CC0-1.0; the Atlas repository has no standalone license declaration. Therefore the extracted names are a separate unverified debug-only candidate layer, not a blanket license conclusion or verified producer identities.

Keep these notices and manifest with any redistribution. No label images were downloaded; database metadata licenses do not automatically license third-party label artwork.

## Other requested sources — honest status

See `docs/producer-source-expansion.md`. COLA/TRACES public interfaces were inspected; no bulk records acquired from them. Wikidata SPARQL explicitly rejected this environment's proxy, so no retries/circumvention and no claimed download. OFF was probed with two wine-category products; both had conflicting non-wine food classifications and were not admitted. No source is marked complete merely because its documentation was found.
