# Local wine-knowledge catalog

**2,065 origin entries across 48 countries** (1,045 protected appellations and 1,020 broader wine geographies; 399 of them nest under a region or subregion), **121 label terms**, and **42 typical grape associations** in this revision. This is a large, inspectable reference, **not an exhaustive or legally authoritative worldwide register**. Regulations, geographical boundaries and permitted styles change. A name on a label does not prove regulatory compliance.

## Start here

- **[`origins.csv`](origins.csv)** — the large, spreadsheet-readable compiled catalog. One row per named origin: stable ID, country, zero/one/multiple broad regions, a `path` containing chain, aliases, name role, match policy, review status and provenance. Its rows encode 3,857 origin→country / origin→region memberships. Multiple region memberships are alternatives, not proof of a particular state.
- [`editorial-origins.txt`](editorial-origins.txt) and [`geography/`](geography) — editable project-authored inputs. Headers are `ISO|country|region path|name role`; following lines are names, optionally `~alias;alias`. The third header field is either one broad region (`Bordeaux`) or a slash path of up to three levels (`AU|Australia|South Australia/Barossa`), where the first segment is the broad region and later segments are the subregions that contain the names below them. Broad regions are a mixture of wine regions and administrative regions, not one universal administrative hierarchy. Blank region means **not established here**, not “there is no region.” `editorial-origins.txt` holds the original flat vocabulary; `geography/<area>.txt` files add the per-country region trees. A path segment that has no line of its own is still created as a place, so every name can be reached through its stated parents.
- [`label-terms.csv`](label-terms.csv) — suffixes, producer cues, administrative prefixes, classifications, sweetness and style terms with explicit permitted actions. Country context is descriptive **only**, never a country-fill instruction.
- [`typical-grapes.csv`](typical-grapes.csv) — typical, **non-exhaustive**, often colour-dependent associations. Every row says `never_autofill`. This table is documentation/reference only and is **not compiled into field-filling code**. An appellation is not permission to invent a grape, wine name or colour.
- [`sources/registry.json`](sources/registry.json) — source identity, revision, licensing and limitations. The 276 U.S. entries come from pinned UC Davis CC0 metadata. The other 1,789 entries and ancillary tables are project-authored reference knowledge; they have **not** each been checked against current regulator rules.

## Relationships actually used by the scanner

1. Exact known origin → country; the authored path is then walked level by level (`country → region → subregion`), so `Pauillac` resolves as France → Bordeaux → Médoc → Pauillac. A single broad region without a deeper path still produces just `region`. Read-only supplements preserve imported records and pre-existing shorthand aliases. Existing legacy vocabulary is retained alongside this catalog, so the CSV is not a dump of every name already known by the app.
2. **New/reference-derived suggestions stay YELLOW.** They do not inflate OCR scores or rescue weak text. Seeded/imported records keep their existing validation path; existing unchanged legacy facts are not globally downgraded.
3. Multi-state AVAs (18 here) retain **all** listed states. Walla Walla Valley alone cannot choose Washington over Oregon. A separately printed compatible state can resolve the form's state-as-Country field. Without a state, the U.S. cellar Country field stays blank for manual entry; the underlying geography still records United States.
4. Country-only entries such as Prosecco, Cava, Lugana and Orvieto do not invent one region — they are genuinely cross-region, so a later authored file cannot assign them one. A name that was country-only merely because the region was not yet authored is *refined* when a later file states it (for example Canberra District → New South Wales). Their constituent regions are intentionally not enumerated, so they cannot prove a same-country regional contradiction when that relationship is unknown.
5. Ambiguous/short names require an adjacent spelled-out country or broad-region anchor in the searched text. `Orange Australia` can match; `Orange`, `Orange Wine Australia` and `Orange Blossom Australia` cannot establish the Australian origin.
6. A longer exact origin suppresses contained substring matches within that span: `Cadillac Côtes de Bordeaux` is not three contradictory appellations. Separately printed contradictory places remain reviewable.
7. Designations qualify names and remain suffixes. Classification and sweetness terms do not establish a country or producer. Producer markers influence role selection only. Importer/distributor/warning prefixes are excluded from origin evidence and identity, including supported non-English phrases.
8. Typical grapes remain non-inferential. There is **no** producer→grape, producer→vintage, appellation→invented cuvée, or classification→guaranteed country rule.

## Provenance and updates

UC Davis snapshot: `UCDavisLibrary/ava` at `a00174df3809f4edcea101f4489961d8b97e4ff9`, selected as of **2026-10-18**. Only current/non-removed metadata properties are retained; no geometry, images, models or network recognition service is bundled. Full CC0 license is in `sources/UCD-CC0-LICENSE.txt`. Each CSV source record points to the original GeoJSON path at that revision.

The upstream report CSV was **not** used: it showed Arkansas AVAs with an incorrect AK code. Individual feature metadata correctly identifies Altus as AR. Import validation accepts only actual U.S. states, normalizes whitespace/full-state spellings, and preserves cross-state membership. Upstream `within` strings are retained in the audit snapshot but **not** promoted to strict parentage: overlap and containment must not be conflated.

Normal development is offline:

```sh
# Edit the editorial/term tables, then regenerate both inspectable CSV and bundled Kotlin.
python3 tools/generate-wine-knowledge.py
python3 tools/generate-wine-knowledge.py --check
python3 -m unittest discover -s tools/tests
bash tools/run-logic-checks.sh
```

An explicit maintainer-only network refresh is available via `tools/fetch-ava-reference.py` using the existing GitHub connection. Review/change its pinned revision and date deliberately; rerun generation and tests, inspect source differences, update counts/documentation, and review licensing. It never runs in the Android app or automatically during Gradle builds.

The app consumes generated Kotlin (~170 KiB source), not a runtime download. Raw metadata/license/editorial CSV files stay in the repository rather than APK assets. No native OCR, frame allocation, pass count, benchmark truth input or scanner deadline setting is changed.

## Known limits

No complete global legal classifications, vintage-specific blend requirements, minimum aging durations, vineyard/parcelle inventory, translations, producer catalog, or exhaustive alias coverage. Some historical label names are useful reference forms rather than current stand-alone protected names. Cross-region memberships are incomplete outside the U.S.; the safe fallback is country-only. Newly nested reference rows still produce conservative YELLOW confirmation warnings for otherwise compatible places, and deeper hierarchy levels are preserved in the knowledge base even though the form only surfaces country, region and sub-region. These tests validate data and synthetic token semantics, **not phone OCR accuracy or current wine law**.
