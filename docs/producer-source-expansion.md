# Producer-source cleanup and expansion

## Completed, not just proposed

The working candidate layers now exclude importer-only, wholesaler-only, spirits-only and other non-producer categories. The original mixed inputs remain historical audit material only; **no mixed permit database is an active recognition asset**.

Three filtered, source-separated datasets were actually generated outside Git at `/home/user/producer-sources/curated/`. Filters, pinned download tooling, output hashes, licensing/attribution and measured reports are committed under `tools/producer-sources/`. No app code, Lab scoring mode, OCR budget or production scanner changed.

| Source layer | Actual retained records | Status |
|---|---:|---|
| TTB Wine Producer permit records | **16,793** | Legal/operating names separate; wine-permit evidence, not verified label identities |
| LWIN producer/brand groups | **33,146** | Pinned attributed derivative, linked to **184,842 wine records**; primary XLSX not independently compared |
| OSM explicit named wineries | **15,316** | Pinned map-data derivative; only `craft=winery` / `industrial=winery`, no shops, bars, vineyard-only or building-only entries |

These counts overlap and are not a count of distinct verified wineries. Some label-facing wine brands do not physically manufacture wine; those remain typed as producer-or-brand candidates, not silently promoted to physical wineries.

### TTB removal accounting

From 84,689 unique permit rows:

- **66,537** non-Wine-Producer rows removed from the working dataset.
- **1,359** additional Wine Producer rows with cider/mead/sake/kombucha name indicators held out, not included in the producer layers. Some may also produce grape wine; this is a conservative review exclusion, not an assertion about their whole business.
- **16,793** remaining wine-permit records retained. No automatic conversion of a legal company into a brand alias. A mixed-role business keeps its wine permit but not its unrelated permit rows.

The OSM pass likewise removes non-winery features, unnamed entries and non-grape-name cases. The LWIN derivative's pipeline uses wine type/status filtering and separates `PRODUCER_TITLE`/`PRODUCER_NAME` from wine names; our importer validates its producer-to-LWIN7 relationships and does not import atlas geographic assignments or turn wine names into aliases.

### Preliminary benchmark usefulness

Exact normalized name presence against the frozen benchmark's 165 known-producer photos:

- **LWIN: 123/165**, across **53 distinct reference producer names**.
- **OSM: 24/165**, across eight names.
- **Strict raw TTB names: 2/165**, one name.

This is **lexical overlap only**, not verified entity coverage, recognition accuracy or a forecast. Source filters never read benchmark answers. Repeated producers, ambiguous short names, raw legal suffixes and naming variants all affect this count. The TTB metric is intentionally stricter than the previous alias-generating builder and is not directly comparable. The LWIN result is promising enough to prioritize primary-source verification and a conservative resolver; it does not justify autofill by itself.

## Requested sources: evidence and actual acquisition status

### 1. US TTB COLA registry — useful product/label evidence, not a direct producer roster

TTB offers a public searchable registry of approved, expired, surrendered and revoked COLAs. Its export instructions describe CSV search-result downloads capped at **500 records**, and explicitly say that a brand may be a bottler/packer/importer name when a product is not sold under a brand. [1](https://www.ttb.gov/regulated-commodities/labeling/cola-public-registry) [14](https://www.ttb.gov/system/files/images/pdfs/labeling_colas-docs/save-search-results-in-public-cola-registry.pdf)

**Actually done:** opened the public search form and inspected its documented workflow. **No COLA result rows downloaded**, no bulk completeness claim, no unsupported “official API” invented. This is distinct from the already acquired TTB permit lists.

Next extraction should use bounded date windows, wine class/type filtering, COLA IDs for deduplication, explicit status and product origin, and distinct applicant/permit-holder/brand fields. Saturated windows need subdivision rather than silently taking the first 500. A label can help identify a producer, but the applicant or importer field alone cannot. No paid intermediary/API subscription is required or proposed. Public federal metadata does not automatically license label artwork.

### 2. Wikidata — desirable CC0 identity/alias backbone; acquisition blocked here

Wikidata documents structured-data reuse under CC0 and supports SPARQL data access. [1](https://www.wikidata.org/wiki/Wikidata:Database_download) [6](https://www.wikidata.org/wiki/Wikidata:Data_access/en)

**Actually attempted:** a small SPARQL query. The endpoint explicitly rejected this environment's automated proxy traffic and requested an identified/authorized client. **Stopped; no workaround or retries through alternate proxies. Zero Wikidata records ingested.** `wikidata-producers.sparql` is an authored, unexecuted sample query for winery / wine-company classes, not a completed export.

Next step requires a permitted execution environment or a provenance-preserving export. Keep QIDs, class statements, language-tagged labels/aliases, statement provenance and snapshot revision. Exclude people, wine products, appellations and vineyard locations without business/producer evidence. A full extraction needs paging; the supplied sample's LIMIT is not full coverage.

### 3. Open Food Facts wine subset — useful brand/product evidence, not trusted producer identities

OFF supports downloadable subsets and publishes its database under ODbL with attribution/share-alike requirements; product images have separate terms. [1](https://world.openfoodfacts.org/data) [13](https://support.openfoodfacts.org/help/en-gb/12-api-data-reuse/94-are-there-conditions-to-use-the-api)

**Actually probed:** the public API with `categories_tags=en:wines`, page size two, requesting code/name/brands/categories/manufacturing fields. The response reported 16,928 matching products, but the two returned rows had **jam and fruit-juice classifications** despite also carrying wine tags. Neither was admitted. This is a concrete category-quality warning, not a claim that all OFF wine entries are bad.

Probe URL: https://world.openfoodfacts.org/api/v2/search?categories_tags=en%3Awines&page_size=2&fields=code,product_name,brands,manufacturing_places,categories_tags

No bulk OFF ingestion completed. Require category-consistency checks and explicit role evidence; `brands` cannot automatically become producer identities, nor can `countries`/manufacturing-place fields automatically establish wine origin. Keep source product barcodes and update timestamps for provenance, not as independent producer votes. Keep any future ODbL-derived layer separately attributed and review downstream combination obligations.

### 4. EU organic / e-certification registries — targeted corroboration

TRACES publishes organic operator certificates. Its interface exposes **Wine** product category, **Production/Preparation** activities, status and dates; other activities include distribution, storage, import and export. Organic certificate scope is broader than wine production. [1](https://webgate.ec.europa.eu/tracesnt/directory/publication/organic-operator/index) [2](https://food.ec.europa.eu/horizontal-topics/traces/modules_en) [11](https://webgate.ec.europa.eu/IMSOC/tracesnt-help/Content/en/documents-certificates/ooc/ooc-eu/before-starting.html)

**Actually done:** inspected the public directory and filters. **No operator export acquired.** The fetched page is an interactive form, not a populated bulk result. No published bulk API or unrestricted redistribution right was established during this review.

Filter to wine scope and actual production evidence, preserving certificate ID, operator, activities, product scope, validity/status and source URL. “Preparation” alone may mean packing/bottling, and a certificate listing both activities and products does not necessarily prove each activity applies to each product. Import/export-only certificates and shipment e-COIs must not become producer identities. Organic-only coverage also cannot replace a general global roster.

### 5. LWIN by Liv-ex — highest-priority acquired global layer

Liv-ex makes the LWIN database freely downloadable under a Creative Commons license. [1](http://www.liv-ex.com/lwin/) The primary license page specifies CC BY 4.0: https://www.liv-ex.com/lwin-creative-commons-licence/ .

**Actually acquired:** producer and wine JSON files from the explicitly attributed Open Wine Atlas derivative, commit `92ee7f1ea991cca862a86b86173e1c28ab5d57b6`, snapshot date 2026-10-21. The source pipeline and DATA_LICENSE.md were inspected, not executed. Important limitation: the upstream pipeline accepts missing TYPE values and can derive a producer from the first DISPLAY_NAME segment when producer fields are empty. The derivative does not mark which groups used those fallbacks; therefore none of these groups is promoted to a verified physical producer without primary-source reconciliation. Producer/name-country groups and wine identifiers retained; inferred atlas place mapping discarded. This is a mirror-derived snapshot, **not a verified primary Liv-ex export**.

Use as the next main candidate layer, with clear producer-or-brand roles, primary-source reconciliation, ambiguity checks and supporting names from independent sources. No live pricing/subscription service, account, or recognition API is proposed.

### 6. OpenStreetMap winery records — acquired secondary evidence

OpenHistoryMap/openwinemap publishes OSM-derived winery and other wine-related features with upstream attribution. [3](https://github.com/openhistorymap/openwinemap)

**Actually acquired:** pinned snapshot `98a5bf983abd23b1f6255bcb60bc73bd99b6b69e`; filtered its per-country data by source tags rather than trusting the broad “winery” category, which also includes building-only features. Exact OSM IDs deduplicated; no names-only business merge. Output remains ODbL-derived and separate from the CC BY LWIN layer. Source map names are candidate evidence, not automatically label display names; a winery's location is not every bottle's origin.

## Next implementation step

Build the Lab-only bounded producer resolver against source-separated candidates, prioritizing the LWIN layer after primary reconciliation. Retain source IDs/roles, preserve original OCR alternatives, and abstain on conflicting short names and role ambiguity. Reconcile legal owner versus label brand explicitly. Do not use the new names as a blanket replacement for raw OCR, geography, wine names, or supported existing producer choices.

No automatic production promotion or performance improvement is claimed. These are acquisition/filtering results; native Android builds and fresh phone recognition have not been run for this change.
