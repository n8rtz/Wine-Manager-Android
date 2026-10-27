# Wine App Labels — OCR and identity review

**Active test set: 125 retained rows from the updated workbook.** It combines 103 workbook-linked images already staged in the debug corpus with 22 added Wine App Labels photos. The added-photo ZIP contained 23 JPEGs; IMG_2732.jpeg was excluded at your direction. The 103 linked images were verified byte-for-byte against each workbook SHA-256. This screen does not replay the full legacy 168-photo run. The table below documents the 22 added images; earlier retained workbook rows remain in the workbook and active test set. **Country follows your app convention: for U.S. wines, the country field is the U.S. state.** Region and subregion are split into separate columns.

| # | File name | Producer / label identity proposed | Wine name / style | Vintage | Country | Region | Subregion | Notes |
|---:|---|---|---|---:|---|---|---|---|
| 01 | `IMG_2164-1774x2048.jpg` | La Rioja Alta | Gran Reserva 904 | 2015 | Spain | Rioja | — | “Selección Especial” dropped from the wine name. Neck label shows La Rioja Alta, S.A. |
| 02 | `IMG_2211-1772x2048.jpg` | King Estate | Inscription Pinot Noir | 2023 | Oregon | Willamette Valley | — | U.S. state used as Country. |
| 03 | `IMG_2217-1968x2048.jpg` | Bodegas Vatan | Triton | 2022 | Spain | Toro | — | Wine name shortened to Triton; Tinta de Toro / Old Vines are other label text. |
| 04 | `IMG_2237-1794x2048.jpg` | Kirkland Signature | Pauillac | 2023 | France | Bordeaux | Pauillac | Per your correction, Kirkland Signature is the producer for these wines; name is Pauillac. |
| 05 | `IMG_2446.jpeg` | Ciabot | Baronetto | — | Italy | Piedmont | Barbaresco | Barbaresco is the appellation/subregion. No vintage visible. |
| 06 | `IMG_2477.jpeg` | Sean Minor | Signature Series Cabernet Sauvignon | 2022 | California | North Coast | — | U.S. state used as Country. |
| 07 | `IMG_2732.jpeg` **(excluded)** | — | — | — | — | — | — | Confusing label; removed from the candidate dataset per your correction. |
| 08 | `IMG_2741.jpeg` | Château du Glana | Saint-Julien | 2019 | France | Bordeaux | Saint-Julien | Wine name shortened to Saint-Julien; appellation also in Subregion. |
| 09 | `IMG_2850-1739x2048.jpeg` | Bodegas Olarra | Reciente Gran Reserva | Unclear | Spain | Rioja | — | Vintage is faint at the label edge and was not reliably read. |
| 10 | `IMG_3076-1682x2048.jpeg` | Kaiken | Ultra Malbec | 2021 | Argentina | Mendoza | — | — |
| 11 | `IMG_3100.jpeg` | Antinori | Bramìto della Sala Chardonnay | 2024 | Italy | Umbria | — | IGT printed. |
| 12 | `IMG_3112.jpeg` | François Lurton | Campo Eliseo | — | Spain | Toro | — | “Cuvée Alegre” removed from the wine name per your correction; no vintage visible. |
| 13 | `IMG_3155-1640x2048.jpeg` | Antinori | Pèppoli Chianti Classico | 2024 | Italy | Tuscany | Chianti Classico | Accepted wine-name alternate: “Peppoli”. |
| 14 | `IMG_3609-1843x2048.jpeg` | Marqués de Casa Concha | Heritage | 2021 | Chile | Puente Alto | — | Varietal not shown. |
| 15 | `IMG_3645.jpeg` | Reputation | Cabernet Sauvignon | 2023 | California | Napa Valley | Stags Leap District | Accepted subregion alternate: “Stags Leap”. U.S. state used as Country. |
| 16 | `IMG_3732-1862x2048.jpeg` | Trivento | Golden Reserve Malbec | 2023 | Argentina | Mendoza | Luján de Cuyo | — |
| 17 | `IMG_3759-2048x1843.jpeg` | Patricia Green Cellars | Volcanic Pinot Noir | 2023 | Oregon | Dundee Hills | — | U.S. state used as Country. |
| 18 | `IMG_3761.jpeg` | Collina San Ponzio | Barolo | 2021 | Italy | Piedmont | Barolo | DOCG printed; “Product and bottled by” identifies Collina San Ponzio. |
| 19 | `IMG_3763.jpeg` | Stags’ Leap | Merlot | 2023 | California | Napa Valley | — | Kept to the front-label name. U.S. state used as Country. |
| 20 | `IMG_3765.jpeg` | Le Noble | Bordeaux Sauvignon Blanc; Cuvée Réserve | — | France | Bordeaux | — | “Vin de Bordeaux” printed; no vintage visible. |
| 21 | `IMG_3819.jpeg` | Decoy | Pinot Noir | 2023 | California | — | — | California printed and used as Country; no more specific region on the front. |
| 22 | `IMG_3856-1.jpeg` | Kirkland Signature | Gigondas | 2024 | France | Rhône | Gigondas | Per your correction, Kirkland Signature is the producer for this wine. |
| 23 | `IMG_9703.jpg` | Il Poggione | Rosso di Montalcino | 2021 | Italy | Tuscany | Montalcino | DOC printed; Franceschi S.A. is identified as bottler, not substituted for producer. |

## OCR / source notes

- Raw detector output and confidence values: [`ocr-raw.json`](ocr-raw.json). It is a machine transcript, not ground truth; the table incorporates your corrections and visual label review.
- Image integrity, original filenames, byte sizes, and SHA-256 hashes: [`source-manifest.json`](source-manifest.json).
- The GitHub repository API reports no declared license. These are staged under your instruction for this project; do not redistribute the photos outside the authorized project.
- Workbook result: [`reference-data-scrubbed-updated.xlsx`](reference-data-scrubbed-updated.xlsx). Eight red-coded review rows were removed from both tabs; the row IDs and comments are recorded in [`workbook-update-audit.json`](workbook-update-audit.json). The owner-directed removal of review #14 (Club des Sommeliers) is recorded in the audit.
- Normal-app assets: `app/src/main/assets/wine-app-labels-v1/`. The “Large image dataset test” screen runs all 125 retained updated-workbook rows and accumulates one comparison row per short label: `Lab v4`, `Prod v3.0`, `Prod v2.0`, `Prod v1.0`. Lab v4 now uses verified LWIN producer data only; unverified TTB, OSM, and X-Wines name lists are excluded from the app database. Prod v3.0 remains the pre-supplement LWIN-only comparison. The full legacy 168-photo run is not replayed.
- Structured label targets: [`candidate-labels.json`](candidate-labels.json). Build order: `build_reference_workbook.py`, `build_candidate_labels_from_workbook.py`, `build_gazetteer_coverage.py`, then `build_phone_test_assets.py` (the workbook scripts require `openpyxl`).
- Scorer-side database coverage: [`app/src/main/assets/wine-app-labels-v1/gazetteer-coverage.json`](../../../app/src/main/assets/wine-app-labels-v1/gazetteer-coverage.json). Total parameter coverage across all consulted databases matches `101/125` known producer labels, `106/123` known wine-name labels (`42` in LWIN/KB wine-name indexes plus `64` appellation-named wines), `124/124` known country labels, `120/120` known region labels, and `77/77` known subregion labels (source-level breakdown also retained under `databases`). This audit uses known values only on the scoring side and is never supplied to OCR.
