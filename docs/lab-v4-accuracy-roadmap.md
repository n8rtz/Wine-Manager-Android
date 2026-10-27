# Lab v4 125-Label Accuracy Roadmap & Root-Cause Checklist

Grounded in the full 125-label benchmark run (`0c88bda41164`, `cached_ocr`, 125 attempted / 123 fully scorable bottles, 748 scorable field cells). Work through the unchecked items (`- [ ]`) **one at a time**, verifying each step against the 125-label benchmark and host/Kotlin check suites while keeping `Prod v3.0` (`ProducerResolver.Policy.prod` and non-Lab extraction paths) untouched.

---

## Baseline Snapshot (`0c88bda41164`)

| Metric / Field | Lab v4 (`0c88bda41164`) | Prod v3.0 (`0c88bda41164`) | Notes |
| --- | --- | --- | --- |
| Complete (`6/6`) | `44/123` (`35.8%`) | `41/123` (`33.3%`) | `#125` (`wine_name`) & `#176` (`vintage`) have `unresolved` ref cells |
| `<= 1` field error | `89/123` (`72.4%`) | `86/123` (`69.9%`) | |
| Scorable cells correct | `617/748` (`82.5%`) | `611/748` (`81.7%`) | `558` value + `59` expected blank |
| `Producer` (`correct/incorrect`) | `81/44` | `78/47` | `+13` wins / `0` losses in Phase 0 (`d = 0` fast-path) |
| `Wine name` (`correct/incorrect`) | `71/53` | `70/54` | `+2` direct wins in Phase 0; `35/53` tied to `paragraph=true` |
| `Country` (`correct/incorrect`) | `122/3` | `121/4` | `+1` win (`#67`) via Phase 0 producer `d = 0` country backfill |
| `Region` (`correct/incorrect`) | `115/10` | `115/10` | `+3` wins / `0` losses in Phase 0c (`#30, #85, #92`) |
| `Subregion` (`correct/incorrect`) | `108/17` | `107/18` | `+7` wins / `0` losses in Phase 0c (`#5, #30, #70, #92, #142, #162, #169`) |
| `Vintage` (`correct/incorrect`) | `120/4` | `120/4` | `#176` is `unresolved` |

---

## Phase 0 — Exact-Match (`d = 0`) Gazetteer Fast-Paths (Completed)

- [x] **0a. `ProducerResolver` Lab v4 `d = 0` exact-match fast-path** (`ProducerResolver.kt`, `EvidenceExtractor.kt`, `TextLabSpatialPreview.kt`)
  - **Root cause:** `ProducerResolver` previously let noisy pre-gazetteer guesses (`before` like `MOUTONCADET`, `GERARD BERTRAND CENTIFI`, `SIGNATURE SERIES SeanMinn`, `LEO`, `ultra`), `d = 1` fuzzy rivals, cross-country duplicate brand rows, or single-word length gates (`< 8` chars / `< 2` words) block exact `d = 0` producer matches in `producers-expanded.sqlite`.
  - **Shipped fix & 3 guards:**
    1. Group `d = 0` hits by normalized `entity.key` (with `countryContext` preference and word-sequence subsumption), skipping `d >= 1` rival separation and noisy `existing` protection when a single `d = 0` producer identity group remains.
    2. Exclude geographic places, grape varietals, wine styles, and designations (`EvidenceExtractor.isNonProducerCatalogTerm` + `exactFastPathStopTerms`), and allow single-word `>= 5`-char `d = 0` spans only for primary LWIN records that are not fragments of an observed estate phrase.
    3. Protect clean estate-prefixed `existing` names (`Chateau` / `Domaine` / `Bodegas ...`) against bare non-estate `d = 0` tokens (`#68, #127, #164, #171`).
  - **Verified replay impact:** `+13` producer wins, `0` regressions (`#17, #32, #50, #67, #89, #106, #129, #135, #150, #152, #174, #177, #186`).
- [x] **0b. `LinkedWineResolver` Lab v4 `d = 0` exact-match fast-path** (`LinkedWineResolver.kt`, `TextLabSpatialPreview.kt`)
  - **Root cause:** When `ProducerResolver` updated a noisy `before` producer, `LinkedWineResolver` still checked compatibility against the stale pre-gazetteer `before` string; and when `wines.sqlite` had an unambiguous `d = 0` linked wine, echoed-producer or generic-place `existingWine` values (`ROCHE MAZET`, `Chianti Classico`) or `d = 1` rivals blocked it.
  - **Shipped fix:** Pair against the resolved producer key across same-key LWIN producer IDs when `producer.acceptedId != null`, and accept an unambiguous `d = 0` `(producerKey, wineKey)` pair immediately unless `existingWine` is already a distinctive non-catalog cuvée and the `d = 0` wine is only a bare catalog varietal (`#170`).
  - **Verified replay impact:** `+2` immediate `wine_name` wins (`#85` `Merlot`, `#180` `Peppoli`), `0` regressions, and pre-wires automatic `d = 0` wine promotion once Item 1 fixes `paragraph=true` over-merging.
- [x] **0c. `EvidenceExtractor` + `LabelContext` Lab v4 `d = 0` exact-geography fast-path, ancestral chain expansion, and context feed** (`EvidenceExtractor.kt`, `LabelContext.kt`, `TextLabEvidencePreview.kt`, `TextLabSpatialPreview.kt`)
  - **Root cause:** Exact `d = 0` catalog places printed cleanly on the label (`Pays d'Oc` `#85`, `Cotes de Bergerac` `#5`, `Hautes Cotes de Beaune` + `Bourgogne` `#30`, `Bourgogne` `#70`, `Pessac-Leognan` `#92`, `Saint-Emilion Grand Cru` `#142`, `Lalande-de-Pomerol` `#162`, `Rioja` `#169`) were poisoned or blanked in post-processing by `d >= 1` fuzzy `PlaceRepair` collisions (`HERITAGE` -> `Hermitage` `#85`), partial substring matches inside garbled/brand lines (`CTES DE BERGERAC` `#5`, `LALAND DE-DEP POMEROL` `#162`, `La RiojaAlta` `#169`), `Bourgogne` sibling-appellation conflicts (`#30, #70`), solver ownership/margin ties (`#142`), or slightly sub-threshold ML Kit confidence corroborated by an overlapping second-engine box (`#92`).
  - **Shipped fix (Lab v4 `exactGeographyFastPath = true`, Prod `v3.0` untouched):**
    1. Identifies whole-phrase, framed, classification-base, origin-wrapped, and compound-segment `d = 0` catalog places (plus corroborated `>= 8`-letter `d = 0` sub-country appellations at `score >= 0.45`), checks coherence across all `d = 0` places on the label (treating `Bourgogne` as region-wide under `Burgundy` alongside `ReferenceWineCatalog.isRegionWideIndication`), and intercepts incompatible fuzzy/partial-substring geography competitors and post-solver parent/child margin abstentions to lock the deepest coherent `d = 0` place and expand its full `Subregion -> Region -> Country` ancestral chain (`CellarFieldPresentation.apply` + `geographyPath`).
    2. Feeds `d = 0` appellation ancestors (`Country` + `Region`) directly into `LabelContext` (`anchors`, `explicitCountries`, `explicitRegions`), `AppellationFallback`, `ProducerResolver` (`policy.withCountryContext`), and `LinkedWineResolver` / `resolveLinkedWineGeography`.
  - **Verified replay impact:** `+10` geography cell wins (`+3` `Region`: `#30, #85, #92`; `+7` `Subregion`: `#5, #30, #70, #92, #142, #162, #169`), `0` regressions across all `375` geography cells.

---

## Item 1 — Fix Cross-Engine / Multi-Line `paragraph=true` Pooling Bug (Highest Remaining Impact)

- [ ] **1a. Scope `EvidenceGraph` / `TextLabSpatialPhrases` paragraph merging per engine pass and cap vertical/role span merges**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`, `app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPhrases.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Multi-line `paragraph=true` / spatial phrase merging concatenates vertically adjacent lines of completely different semantic roles (producer + cuvée + varietal + appellation + founding-year banner) into one mega-phrase (`71/125` labels affected; implicated in `35/53` `wine_name` errors). Examples from `0c88bda41164`:
    - `#170`: `'Inscription PINOT NOIR WILLAMETTE VALLEY'` (merges cuvée + varietal + region, blocking `d = 0` `'Inscription Pinot Noir'`).
    - `#186`: `'Stags Leap Merlot NAPA VALLEY —FIRST VINTAGE 1893'` (merges producer + varietal + region + founding year).
    - `#32, #89`: `'2019 FRONSAC APPELLATION FRONSAC CONTROLEE BERTRAND RAVACHE AUDACE PAR'`.
    - `#177`: `'KAIKEN ultra MALBEC 2021 MENDOZA ARGENTINA'`.
  - **Suggested fix (Lab v4 gated):** Stop paragraph/phrase growth across hard semantic boundaries (4-digit vintage lines, `APPELLATION ... CONTROLEE` / `DENOMINACION DE ORIGEN` framing lines, known region/country lines below a varietal, or font-height jumps `> 1.45x`), while keeping individual line observations and bounded 2-line pairwise joins intact.
  - **Target bottles:** `#32, #50, #89, #106, #152, #170, #174, #177, #186` (and unlocks downstream `LinkedWineResolver` `d = 0` matches on `#170` `Inscription Pinot Noir`, `#186` `Merlot`, etc.).

---

## Item 2 — Appellation `WINE_NAME` Normalization, Ranking & Fallback

- [ ] **2a. Punctuation/hyphen-insensitive `nameKey` matching for appellation-as-`WINE_NAME` hypotheses**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceRanking.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Hyphenated/apostrophe French appellation spellings (`Montagne-Saint-Emilion` vs `Montagne Saint Emilion`, `Cotes-du-Rhone` vs `Cotes du Rhone`, `Pessac-Leognan`) diverge between OCR spacing and catalog spelling during `WINE_NAME` hypothesis deduplication/selection.
  - **Target bottles:** `#46, #94, #122, #167`.

- [ ] **2b. Fix `designation-linked` rank inversion in `EvidenceRanking` / `EvidenceSolver`**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceRanking.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceSolver.kt`
  - **Root cause:** A shorter parent appellation or bare classification/designation hypothesis can outrank the fuller `<Appellation> <Grand Cru / Villages / Superieur>` phrase on the same label.
  - **Target bottles:** `#142, #157`.

- [ ] **2c. Select deepest compatible appellation descendant in `AppellationFallback`**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/AppellationFallback.kt`
  - **Root cause:** When Old World labels have no distinct cuvée or varietal and `AppellationFallback` fills `WINE_NAME` from resolved geography, broader region/parent candidates can win or tie instead of selecting the deepest compatible village/cru/subregion appellation printed on the label.
  - **Target bottles:** `#36, #99, #105, #164, #167`.

---

## Item 3 — Non-`d = 0` Producer Recovery & Hybrid Anchor Fallback

- [ ] **3a. Controlled `d = 1` / de-concatenation producer & linked-wine lookup in Lab v4**
  - **Files:** `app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt`, `app/src/main/java/com/yourapp/wine/gazetteer/LinkedWineResolver.kt`
  - **Root cause:** OCR occasionally drops a single space (`MOUTONCADET` vs `Mouton Cadet` `#106`, `CHATEAUMARGAUX` `#56`) or has a 1-char OCR substitution (`SeanMinn` vs `Sean Minor`, `l`/`1` or `o`/`0`) while `before` holds a classification fragment (`CRUCLASS` `#56`).
  - **Suggested fix (Lab v4 only):** When no `d = 0` match exists and `existing` is blank, all-caps classification noise, or `d = 1` from the candidate, permit unambiguous well-separated (`rival >= d + 2`) primary LWIN `d = 1` matches to replace clearly non-producer `existing` noise.
  - **Target bottles:** `#56, #69, #96, #119, #126, #169`.

- [ ] **3b. `EvidenceAnchorHybrid` fallback to `revised[PRODUCER]` when anchor producer is blank or dropped**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceAnchorHybrid.kt`
  - **Root cause:** When `form_summary` (`anchor`) leaves `PRODUCER` blank or its noisy token was removed, `EvidenceAnchorHybrid` can still reject a valid multi-word estate/producer found by `EvidenceExtractor` (`revised[PRODUCER]`).
  - **Target bottles:** `#43, #58, #62, #71`.

- [ ] **3c. Parenthetical LWIN sub-estate producer linking in `LinkedWineResolver`**
  - **Files:** `app/src/main/java/com/yourapp/wine/gazetteer/LinkedWineResolver.kt`
  - **Root cause:** In `producers.sqlite` / `wines.sqlite`, several iconic wines are linked under parenthetical sub-estate rows such as `Baron Philippe de Rothschild (Mouton Cadet)` (`#106`) or `Antinori (Guado al Tasso)` rather than the bare parent house key `baron philippe de rothschild`.
  - **Suggested fix (Lab v4 only):** When looking up linked wines for a confirmed parent producer `P`, also query primary LWIN sub-estate entities whose normalized key starts with `"$parentKey "` from a parenthetical `P (...)` display name, while keeping the resolved producer display name consistent.
  - **Target bottles:** `#106` (`Mouton Cadet`) and sub-estate LWIN labels.

---

## Item 4 — Bounded Vertical Multi-Line Joins in `EvidenceGraph`

- [ ] **4a. Vertically stacked 2-line appellation names**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`
  - **Root cause:** Long appellations split across two centered lines (e.g. `SAINT-EMILION` / `GRAND CRU` or `COTES DU RHONE` / `VILLAGES`) fail to form a single candidate phrase when line spacing slightly exceeds the tight inline threshold.
  - **Target bottles:** `#103, #151, #163`.

- [ ] **4b. Vertically stacked cuvée/tier + varietal or appellation (`WINE_NAME`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Labels printing a short cuvée/tier line directly above a varietal or appellation (`Inscription` + `PINOT NOIR` `#170`, `ultra` + `MALBEC` `#177`, `Peppoli` + `CHIANTI CLASSICO` `#180`, `Cote des Roses` `#50/#152`, `Leo` + `by` + `Leo` `#32/#89`) need bounded 2-line `cuvée + varietal/appellation` hypothesis generation without pulling in region/vintage lines below.
  - **Target bottles:** `#32, #50, #60, #89, #152, #154, #170, #174, #177, #178, #180, #183, #184`.

- [ ] **4c. Vertically split estate/particle producer names**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/ProducerRepair.kt`
  - **Root cause:** Estate headers split across lines at particles or prefixes (`DOMAINE` + `DE CASSAN` `#43`, `LA PETITE` + `CHAPELLE DE BORDEAUX` `#42`, `DOMAINE ALLARY` + `HAUT-BRION` `#92/#136`, leading `d'` / `de la` lines `#54, #61, #104, #181`) lose their prefix or second line before `ProducerResolver` runs.
  - **Target bottles:** `#42, #43, #54, #61, #92, #104, #136, #181`.

---

## Item 5 — Geography Conflict, Region-Wide Indication & Non-`d = 0` Appellation Recovery

- [x] **5a. Whole-phrase `d = 0` precedence over garbled partial-substring place matches** (`EvidenceExtractor.kt`, shipped in Phase 0c)
  - **Resolved:** Garbled partial-substring reads (`APELLATION CTES DE BERGERAC CONTROLEE` -> `Bergerac` `#5`, `LALAND DE-DEP POMEROL` -> `Pomerol` `#162`) no longer block or tie against coherent whole-phrase `d = 0` appellations (`Cotes de Bergerac`, `Lalande-de-Pomerol`).

- [x] **5b. Regional `Bourgogne` region-wide hierarchy handling in Lab v4** (`EvidenceExtractor.kt`, `LabelContext.kt`, shipped in Phase 0c)
  - **Resolved:** Lab v4 treats `Bourgogne` (`FR` -> `Burgundy`) as region-wide, keeping `Subregion` blank on regional `Bourgogne` bottles (`#70`) while making `BOURGOGNE` + `HAUTES COTES DE BEAUNE` ancestrally coherent (`#30`).

- [x] **5c. Suppress sub-zone partial-substring place extraction when a `d = 0` region is printed** (`EvidenceExtractor.kt`, shipped in Phase 0c)
  - **Resolved:** Partial brand-line substring matches (`La RiojaAlta` -> `Rioja Alta` `#169`) are filtered out when an explicit whole-phrase `d = 0` `Region` (`RIOJA`) is present without a whole-phrase `d = 0` subregion.

- [ ] **5d. Multi-edit (`d <= 2` / `d <= 3`) hyphenated distinctive appellation `PlaceRepair` & split-word appellation rejoining**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/PlaceRepair.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`
  - **Root cause:** Heavily stylized/curved script appellations (`Cote-Rotie` `#58`, `Chateauneuf-du-Pape` `#78`) or vertically split two-line appellations (`Montagne` / `Saint-Emilion` `#103`) fall outside `d = 0` and 1-edit bounds when unanchored or split across lines.
  - **Target bottles:** `#58, #78, #103`.

- [ ] **5e. Optional single-region LWIN producer geography backfill when no place is printed on the front label**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt`, `app/src/main/java/com/yourapp/wine/kb/WineKnowledgeBase.kt`
  - **Root cause:** Minimalist estate/cuvée front labels (`Domaine Rene Rostaing` `#67`, `Chateau La Fleur-Petrus` `#133`, `Vietti` `#171`) print no geographic appellation on the visible face or curved neck, leaving `Region`/`Subregion` blank unless linked-wine or single-appellation estate metadata is consulted.
  - **Target bottles:** `#67, #133, #171`.

---

## Item 6 — Remaining 4 Vintage Recoveries (`120/4` -> `124/0`)

- [ ] **6a. Repair OCR `I`/`l` -> `1` in 20th-century 4-character vintage tokens (`[Il]9[5-9]\d`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Serif `1` in `1989` / `1996` is read by OCR as `I989` or `l996` and skipped by `\b(?:19|20)\d{2}\b` (and rejected by `any(Char::isDigit)` rules). Guard against lot codes (`L9...` with >4 alphanumerics).
  - **Target bottles:** `#56, #96`.

- [ ] **6b. Normalize spaced 4-digit vintage tokens (`(?:1\s*9|2\s*0)\s*\d\s*\d`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Wide-tracked vintage typography (`2 0 1 9` or `20 20`) splits into spaced digits on a standalone centered line and misses contiguous 4-digit regexes.
  - **Target bottles:** `#160`.

- [ ] **6c. Rejoin horizontally aligned split crest years (`20` + `16`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Labels flanking a central crest with `20` on the left and `16` on the right (`TextLabBottleFilter` already preserves split crest halves!) need a bounded horizontal pair combiner (`left = "19"|"20"`, `right = "\d\d"`, matching vertical center & height, symmetric around label midline) to emit `2016` when no contiguous 4-digit year exists.
  - **Target bottles:** `#26`.

- [ ] **6d. `EvidenceAnchorHybrid` fallback to `revised[VINTAGE]` when `anchor[VINTAGE]` is blank**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceAnchorHybrid.kt`
  - **Root cause:** Ensures any vintage recovered by 6a–6c in `EvidenceExtractor` flows into the final summary whenever `form_summary` (`anchor`) missed the vintage.
  - **Target bottles:** `#26, #56, #96, #160`.
