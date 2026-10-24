# Producer-only source staging (not Android recognition assets)

> Update: the user has now supplied the primary LWIN workbook. The debug-only primary asset and Lab resolver are implemented; see `docs/lab-primary-producer-resolver.md`. The acquisition notes below describe the earlier source-staging phase.

## Executed

Built three separate candidate layers in `/home/user/producer-sources/curated/`:

| Layer | Kept | Removed / held out |
|---|---:|---|
| TTB wine permit holders | 16,793 permit records | 66,537 importer/wholesaler/spirits-only source rows; 1,359 wine-permit records with cider/mead/sake/kombucha name indicators |
| LWIN wine producer/brand groups | 33,146 groups | Mirror already filters spirits, beer, retired codes and cases; linked 184,842 wine records checked |
| OSM explicit named wineries | 15,316 mapped records | 25,223 not explicitly craft/industrial wineries; 1,731 unnamed; 62 non-grape-name cases; 1 duplicate OSM ID |

Counts are **not unique verified wineries** and must not be added together as such. Different permits/sites/brands can belong to the same business. Businesses with both wine and non-wine permits keep their wine-permit evidence; non-wine permit rows are excluded. Non-grape name checks are conservative holds for review, not proof a business never makes grape wine. Winery tags and LWIN producer/brand fields are source evidence, not independent verification of every business's physical production role.

No importer-only or wholesaler-only layer, wine shop/bar, vineyard-only or cellar-only layer is emitted. The original mixed archives and historical audits are retained as **audit inputs only**, outside these working candidate layers. They are not loaded into the app. No legitimate producer was removed merely because it also has an importer permit.

`build.py` reads data only; it does not run either third-party builder. It retains raw owner/operating names separately, does not invent aliases, does not strip display-name words, and does not combine product names with producer names. It uses the combined TTB JSON once instead of double-counting the overlapping CSVs. All layers remain separate, including licensing boundaries; separation is not a blanket legal conclusion about any future combined database.

The compressed outputs total roughly 2.8 MB, external to Git and Android. `audit.json` records the measured counts, exclusions, source revisions, input hashes, output hashes and attribution. It is the reproducibility manifest, not an asset manifest used by the scanner.

## Reproduce

Python 3, `gh`, authenticated GitHub access to the supplied reference repository, and access to GitHub/codeload are sufficient:

```sh
python tools/producer-sources/fetch.py --out /path/outside/repo/inputs
python tools/producer-sources/build.py \
  --ttb-zip /path/outside/repo/inputs/ttb-rezip.zip \
  --lwin-dir /path/outside/repo/inputs \
  --osm-zip /path/outside/repo/inputs/openwinemap.zip \
  --out /path/outside/repo/curated
```

Inputs are pinned, checksum-checked and not executed. A mismatch fails rather than silently fetching another dataset. JSONL gzip outputs have stable ordering and gzip timestamps. Source IDs remain separate; names alone are never a cross-source deduplication key.

## Attribution / reuse

- **TTB:** supplied cached FOIA List of Permittees, Git blob `8bf9737463092b0a2ef233139dee8e88a0f6fae8` from `n8rtz/Wine-Reference-Drop`. Its claim of public-domain/CC0, freshness and authenticity were not independently validated against a fresh TTB export. Retain source permit IDs; treat this as a review input, not an authoritative label identity catalogue.
- **LWIN by Liv-ex**, https://www.liv-ex.com/lwin/, Creative Commons Attribution 4.0, https://creativecommons.org/licenses/by/4.0/. Downloaded derivative from **Open Wine Atlas contributors**, `doma17-wc/open-wine-atlas` commit `92ee7f1ea991cca862a86b86173e1c28ab5d57b6`, `web/data/producers.json` and `web/data/wines.json`, snapshot date 2026-10-21. Its DATA_LICENSE.md and Liv-ex's own license page identify CC BY 4.0. Changes here: retain producer/country groups and linked LWIN7 IDs only; discard wine names and atlas place assignments; no inferred aliases. Primary XLSX not downloaded or independently compared. The upstream pipeline allows missing TYPE and a DISPLAY_NAME fallback for missing producer fields; the derivative does not mark those fallback rows. These candidates are not verified physical producer identities. Country groups describe the source's wine grouping, **not producer domicile**.
- **© OpenStreetMap contributors**, https://www.openstreetmap.org/copyright, **ODbL 1.0**. Snapshot/derivative from **OpenHistoryMap/openwinemap**, commit `98a5bf983abd23b1f6255bcb60bc73bd99b6b69e`. Changes here: named, explicit craft/industrial wineries only; exclude other categories, inactive/unnamed and non-grape-name records; deduplicate exact OSM IDs; omit phone/address details and coordinates. Remains a separate attributed ODbL-derived layer. Do not casually merge it into differently licensed data or assume separation alone resolves every downstream obligation.

Keep these notices and manifest with any redistribution. No label images were downloaded; database metadata licenses do not automatically license third-party label artwork.

## Other requested sources — honest status

See `docs/producer-source-expansion.md`. COLA/TRACES public interfaces were inspected; no bulk records acquired from them. Wikidata SPARQL explicitly rejected this environment's proxy, so no retries/circumvention and no claimed download. OFF was probed with two wine-category products; both had conflicting non-wine food classifications and were not admitted. No source is marked complete merely because its documentation was found.
