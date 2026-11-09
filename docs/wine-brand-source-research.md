# Research: wine brands and marketed label names

Research date: 2026-10-31. This is source research, not a new scanner database or a measured
improvement. The app's **Producer** field may contain the visible marketed brand rather than the
legal producer. For the personal audit, **Altos de Valdoso** remains the expected producer/brand,
with **Crianza** as the wine name. The [distributor's 2019 listing](https://www.tri-vin.com/products/brands/bodegas-olarra/altos-de-valdoso-crianza-750ml-2019)
separately calls Bodegas Olarra the producer and Altos de Valdoso the brand; that does not prove
the bottler, nor mean the scanner may invent Olarra from an image on which it is not printed.
Private labels can have different makers for different bottles or vintages: see the
[Kirkland producer survey](https://costcowineblog.com/who-are-the-real-producers-behind-costcos-kirkland-signature-wines/).

## What is already in this checkout

These are exact substring checks of the bundled SQLite databases on the research date, not evidence
that a scanner run will successfully select or join those names.

- The primary LWIN producer index has **33,160** entries; it already contains `Kirkland Signature`
  and `Charles Shaw` as marketed identities. Its separate LWIN wine-name index has **101,326**
  names and links to producers. Neither database had a name containing `Valdoso`.
- The experimental expanded Lab index has **78,615** producer candidates, including `Kirkland
  Signature`, `Charles Shaw`, and `California Roots`. It also had no name containing `Valdoso`.
  The expanded index already includes LWIN and unverified X-Wines winery, TTB *permit-holder*,
  and OpenStreetMap winery candidates. A TTB permit list is not a list of product brands.
- The pinned [X-Wines Atlas snapshot](https://github.com/JamesOH5/Xwines-Atlas/tree/7f9b50c1241995693d708f5a628a57dae3aee7d5),
  already used as a source of winery-name candidates, contains **100,646** wine records with
  winery and wine-title fields. In that snapshot `Kirkland Signature` has 38 linked titles,
  `Charles Shaw` has four, and neither the winery-name nor title list contains `Valdoso`.
  These are snapshot-specific checks, not evidence about the live market. The Atlas repository
  has no declared license; the upstream [X-Wines repository](https://github.com/rogerioxavier/X-Wines)
  states CC0 and requests a paper citation. Verify provenance and reuse rights for any actual
  dataset before distribution; do not assume a downstream snapshot licenses all underlying material.
  Current app assets use only X-Wines winery candidates, not a new X-Wines wine-title/brand index.

## Ranked sources to investigate for a separate brand index

1. **[TTB Public COLA Registry](https://www.ttb.gov/public-information/news/using-cola-registry-search-certificates)**
   has explicit **brand name** and optional **fanciful name** for alcohol labels. TTB defines brand
   as the name under which a product is sold, not necessarily its maker. The
   [official export instructions](https://ttb.gov/media/70944/download?inline=) state search
   results and CSV exports are capped at 500 per query. Use wine class codes, status and origin;
   keep the TTB ID, source date and exact field roles. This is especially relevant to U.S.-sold
   and imported wines, not worldwide coverage. A filing is not proof a bottle was marketed, a
   permit holder can be an importer rather than the maker, and label-image rights must be checked
   separately. Bulk access and reuse terms have not been independently established here; do not
   assume the separate TTB permit list already has these brand fields.
2. **[Open Food Facts](https://world.openfoodfacts.org/data)** provides crowdsourced product
   records with explicit `brands` and wine categories, plus nightly CSV/JSONL exports. Its
   [Kirkland Malbec record](https://world.openfoodfacts.org/product/0096619139767/malbec-kirkland-signature)
   is a concrete brand-and-wine example. Category and brand fields can be missing or noisy, and a
   non-wine product can share a brand; filter by reliable wine-category evidence and retain source
   provenance. The full food CSV is listed around 0.9 GB compressed, so build a small filtered
   subset **off-device** rather than bundling the dump. Database ODbL attribution/share-alike,
   DbCL contents and separate CC BY-SA image rights must be reviewed and satisfied; the project's
   [FAQ](https://world.openfoodfacts.org/faq) warns that combining databases can require making
   the resulting database open. Do not silently mix it into another database or use label images.
   The service recommends bulk exports for offline analysis rather than hammering its live API.
3. **[X-Wines](https://github.com/rogerioxavier/X-Wines)** provides product titles alongside
   winery names and country/region context (100,646 labels in its published dataset). This is a
   potentially useful source of *candidate* marketed names that don't occur as wineries. However,
   a product title can contain grape varieties, locations or generic aging terms rather than a
   brand. Do not mechanically treat complete titles or every token as producer. Assess a source-
   traced extraction against manually reviewed examples and negatives before enabling it. The
   checked 2022-era snapshot did not contain Valdoso; it cannot be presented as a solution for
   that bottle. The [paper](https://www.mdpi.com/2504-2289/7/1/20) describes the fields.
4. **[Wikidata](https://www.wikidata.org/wiki/Wikidata:Database_download/en)** structured data is
   CC0 and has explicit commercial [brand (P1716)](https://www.wikidata.org/wiki/Property:P1716)
   links. Examples include [Mateus](https://www.wikidata.org/wiki/Q460398),
   [Yellow Tail](https://www.wikidata.org/wiki/Q8051845) and
   [Charles Shaw](https://www.wikidata.org/wiki/Q5082471). Classification is inconsistent across
   examples, so one class query will miss brands; coverage and candidate quality remain unmeasured.
   A curated small subset can add evidence without downloading a complete multi-gigabyte dump to
   the phone. Structured data rights do not automatically cover label images or trademarks.
5. **[LWIN](https://www.liv-ex.com/lwin/)** already supplies a large product-and-identity index in
   this app. Liv-ex provides a [CC BY license summary](https://www.liv-ex.com/lwin-3/lwin-creative-commons/).
   Refreshing or inspecting its producer, brand and product fields may uncover more candidates,
   but this checkout's snapshot already contains Kirkland and misses Valdoso. It is not a complete
   private-label registry; product titles and producer identities have distinct roles.

Additional research-only sources: [UC Davis historic labels](https://library.ucdavis.edu/wine-labels-in-archives-and-special-collections/)
are valuable for examples and layout evaluation but are largely historical and have unverified
bulk reuse/image permissions. Commercial or retailer catalog services may have newer labels but
must be checked for license, cost, redistribution and update terms. An MIT-licensed scraper is
not a license for the catalog it retrieves; for example, this
[Systembolaget-derived project](https://github.com/matalve/vindeklaration) explicitly grants no
license for its data. The [COLA Cloud sample](https://huggingface.co/datasets/cola-cloud/whiskey-beer-and-california-wine-label-metadata)
is small and mixes types; its full commercial service is a separate licensed product.

## Safe next implementation, if pursued

Keep `brand`, `wine/title`, `maker`, `importer` and `source` as distinct roles, even if the UI
places a visible brand in Producer. Create a small, source-tagged, reproducible **candidate** index
separate from the existing verified producer index. Preserve source ID, license, snapshot date,
classification and conflicts; do not infer owner, geography or hidden label text from a brand.
Require observed OCR text and compatible context, support wrapped lines, and abstain on ambiguous
competing identities or common grape/place/designation words. Show uncertain candidates for
confirmation rather than treating them as verified legal producers. Measure paired field gains and
losses on existing fixtures, then run a genuinely unseen holdout; the personal audit has already
been used to develop and review this workflow and is not independent accuracy. Any local index or
preparation tool must preserve the app's offline-recognition boundary. No new external catalog is
bundled or queried by the scanner as a result of this research.
