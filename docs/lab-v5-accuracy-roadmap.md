# Lab v5 125-Label Accuracy Roadmap & Root-Cause Checklist

> **Version-label note (2026-10-27):** the just-before-refresh captured results are now named **Lab v5** and **Prod v4.0**. Older snapshots and implementation notes in this roadmap keep their original version names when they describe historical builds; the latest `a838c0ffb01b` capture below is explicitly relabeled and retains its original pipeline IDs for provenance.

Grounded in the earlier 125-label benchmark run (`0c88bda41164`, `cached_ocr`, 125 attempted / 123 fully scorable bottles, 748 scorable field cells). Work through the unchecked items (`- [ ]`) **one at a time**, verifying each step against the 125-label benchmark and host/Kotlin check suites while keeping the frozen Prod v4.0 (`ProducerResolver.Policy.prod` and non-Lab extraction paths) untouched.

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

- [x] **1a. Scope `EvidenceGraph` / `TextLabSpatialPhrases` paragraph merging per engine pass and cap vertical/role span merges** — done 2026-10-29 as `bounded_wine_title_pairs`, integrated into Lab v6: **66/122, +4/−0** (#170, #177, #184, #186). The boundary caps already existed; the residual fixes were pair evaluation before the 1.45 cap, a role-veto exemption, varietal-only pair lowers, height-tiered rank, and paragraph suppression lifted for exact grape lines. See diagnostic §13.
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

- [x] **2a. Punctuation/hyphen-insensitive `nameKey` matching for appellation-as-`WINE_NAME` hypotheses** — closed 2026-10-29 as covered: all 2a target labels already score correct via another route (see gap analysis).
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceRanking.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Hyphenated/apostrophe French appellation spellings (`Montagne-Saint-Emilion` vs `Montagne Saint Emilion`, `Cotes-du-Rhone` vs `Cotes du Rhone`, `Pessac-Leognan`) diverge between OCR spacing and catalog spelling during `WINE_NAME` hypothesis deduplication/selection.
  - **Target bottles:** `#46, #94, #122, #167`.
  - **Covered (2026-10-29):** all four targets are correct via the Item 2c fallback route (supplemented-KB `AppellationFallback` + bounded one-edit repair; diagnostic report Sections 8/9); phone-verified 2026-10-29. The hypothesis-dedup root cause above remains as documented but no longer affects these bottles.

- [x] **2b. Fix `designation-linked` rank inversion in `EvidenceRanking` / `EvidenceSolver`**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceRanking.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceSolver.kt`
  - **Root cause:** A shorter parent appellation or bare classification/designation hypothesis can outrank the fuller `<Appellation> <Grand Cru / Villages / Superieur>` phrase on the same label.
  - **Target bottles:** `#142, #157`.
  - **Resolved (2026-10-29, host-verified 60/122, phone pending):** Lab module `appellation_classification_precedence` raises the classification-qualified appellation offer (70/64 -> 96) above designation-linked titles (90) while the explicit named-cuvee offer rises to 100 so a printed appellation cannot steal a named cuvee (#97 `Cuvee Garance`). #142 was already correct; #157 gained. Baseline parity unchanged (module default off).

- [ ] **2c. Select deepest compatible appellation descendant in `AppellationFallback`**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/AppellationFallback.kt`
  - **Root cause:** When Old World labels have no distinct cuvée or varietal and `AppellationFallback` fills `WINE_NAME` from resolved geography, broader region/parent candidates can win or tie instead of selecting the deepest compatible village/cru/subregion appellation printed on the label.
  - **Target bottles:** `#36, #99, #105, #164, #167`.
  - **Progress (2026-10-29):** #36, #99, #164, #167 gained via supplemented-KB fallback + one-edit/framing-strip repair (phone-verified for #36/#164/#167; #99 host-verified). #105 remains open: its wine slot holds wrong free text, and the fallback deliberately only fills blanks.

---

## Item 3 — Non-`d = 0` Producer Recovery & Hybrid Anchor Fallback

- [ ] **3a. Controlled `d = 1` / de-concatenation producer & linked-wine lookup in Lab v4**
  - **Host-tested 2026-10-29:** the `d = 1` half already ships in the baseline config (`final_producer_near_match`). Extending the final canonicalization to `d = 2`, lifting the observed-producer preservation wholesale, and contiguous-word containment were each implemented behind a Lab gate and measured: `d = 2` +0, preservation-lift -4 producer (correct names replaced), containment net -1 (loses #105 producer, #119 not gained). All reverted. Conclusion: the remaining producer errors are decided at span-selection (see Item 4c), not final canonicalization; the Margaux-garbage cases (#56/#62/#69) have no catalog-proximate string to match against. The two dormant modules that DO help are now in the winning set: `exact_producer_protection` (+1: #146 Citran) and `role_aware_joins` (+1 wine: #105).
  - **Files:** `app/src/main/java/com/yourapp/wine/gazetteer/ProducerResolver.kt`, `app/src/main/java/com/yourapp/wine/gazetteer/LinkedWineResolver.kt`
  - **Root cause:** OCR occasionally drops a single space (`MOUTONCADET` vs `Mouton Cadet` `#106`, `CHATEAUMARGAUX` `#56`) or has a 1-char OCR substitution (`SeanMinn` vs `Sean Minor`, `l`/`1` or `o`/`0`) while `before` holds a classification fragment (`CRUCLASS` `#56`).
  - **Suggested fix (Lab v4 only):** When no `d = 0` match exists and `existing` is blank, all-caps classification noise, or `d = 1` from the candidate, permit unambiguous well-separated (`rival >= d + 2`) primary LWIN `d = 1` matches to replace clearly non-producer `existing` noise.
  - **Target bottles:** `#56, #69, #96, #119, #126, #169`.

- [ ] **3b. `EvidenceAnchorHybrid` fallback to `revised[PRODUCER]` when anchor producer is blank or dropped** — retested 2026-10-29 on top of integrated 4c spans (`producer_anchor_fallback`): still zero effect; extractor abstains on the targets. Closed as tested, module stays selectable.
  - **Host-tested 2026-10-29:** implemented behind a Lab gate (`producer_anchor_fallback`); measured **zero effect** on the 124-label corpus (the evidence extractor itself abstains on #43/#58/#62/#71, so there is no revised producer to fall back to) and reverted to keep the shipped tree evidence-only. Revisit together with Item 4c span generation.
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceAnchorHybrid.kt`
  - **Root cause:** When `form_summary` (`anchor`) leaves `PRODUCER` blank or its noisy token was removed, `EvidenceAnchorHybrid` can still reject a valid multi-word estate/producer found by `EvidenceExtractor` (`revised[PRODUCER]`).
  - **Target bottles:** `#43, #58, #62, #71`.

- [ ] **3c. Parenthetical LWIN sub-estate producer linking in `LinkedWineResolver`** — implemented 2026-10-29 (`sub_estate_wine_linking`, parent display preserved) and host-measured inert: the sub-estate entity is not reached through the host bridge lookup for #106. Not integrated; needs phone/bridge lookup investigation.
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

- [x] **4b. Vertically stacked cuvée/tier + varietal or appellation (`WINE_NAME`)** — done 2026-10-29 as `stacked_wine_titles`, integrated into Lab v6: +4/−0 alone (#50, #152, #154, #183). Residual: #32/#89 (line not read), #60 (producer-pair competition), #174 (word-split upstream), #178 (three-line extension removed as unsafe). See diagnostic §14.
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Labels printing a short cuvée/tier line directly above a varietal or appellation (`Inscription` + `PINOT NOIR` `#170`, `ultra` + `MALBEC` `#177`, `Peppoli` + `CHIANTI CLASSICO` `#180`, `Cote des Roses` `#50/#152`, `Leo` + `by` + `Leo` `#32/#89`) need bounded 2-line `cuvée + varietal/appellation` hypothesis generation without pulling in region/vintage lines below.
  - **Target bottles:** `#32, #50, #60, #89, #152, #154, #170, #174, #177, #178, #180, #183, #184`.

- [x] **4c. Vertically split estate/particle producer names** — done 2026-10-29 as `split_producer_spans`, integrated into Lab v6: +4/−0 alone (#92, #136, #164 producer; #137 wine). Residual: #42/#61 (no catalog entity, `de` unprinted), #43 (rotated reading), #54/#104 (repair/resolver fragment preference). See diagnostic §14.
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

- [x] **5e. Optional single-region LWIN producer geography backfill when no place is printed on the front label** — closed 2026-10-29 as moot: geography is saturated on the current corpus (country/region 124/124, subregion 122/124); reopen with new labels.
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/TextLabSpatialPreview.kt`, `app/src/main/java/com/yourapp/wine/kb/WineKnowledgeBase.kt`
  - **Root cause:** Minimalist estate/cuvée front labels (`Domaine Rene Rostaing` `#67`, `Chateau La Fleur-Petrus` `#133`, `Vietti` `#171`) print no geographic appellation on the visible face or curved neck, leaving `Region`/`Subregion` blank unless linked-wine or single-appellation estate metadata is consulted.
  - **Target bottles:** `#67, #133, #171`.

---

## Item 6 — Remaining 4 Vintage Recoveries (`120/4` -> `124/0`)

Closed 2026-10-29 as moot on the current corpus: vintage scores 123/123 eligible (the one remaining label is dataset-unresolved), so none of 6a-6d has headroom. Reopen with new labels.

- [x] **6a. Repair OCR `I`/`l` -> `1` in 20th-century 4-character vintage tokens (`[Il]9[5-9]\d`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Serif `1` in `1989` / `1996` is read by OCR as `I989` or `l996` and skipped by `\b(?:19|20)\d{2}\b` (and rejected by `any(Char::isDigit)` rules). Guard against lot codes (`L9...` with >4 alphanumerics).
  - **Target bottles:** `#56, #96`.

- [x] **6b. Normalize spaced 4-digit vintage tokens (`(?:1\s*9|2\s*0)\s*\d\s*\d`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Wide-tracked vintage typography (`2 0 1 9` or `20 20`) splits into spaced digits on a standalone centered line and misses contiguous 4-digit regexes.
  - **Target bottles:** `#160`.

- [x] **6c. Rejoin horizontally aligned split crest years (`20` + `16`)**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceGraph.kt`, `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceExtractor.kt`
  - **Root cause:** Labels flanking a central crest with `20` on the left and `16` on the right (`TextLabBottleFilter` already preserves split crest halves!) need a bounded horizontal pair combiner (`left = "19"|"20"`, `right = "\d\d"`, matching vertical center & height, symmetric around label midline) to emit `2016` when no contiguous 4-digit year exists.
  - **Target bottles:** `#26`.

- [x] **6d. `EvidenceAnchorHybrid` fallback to `revised[VINTAGE]` when `anchor[VINTAGE]` is blank**
  - **Files:** `app/src/main/java/com/yourapp/wine/textlab/evidence/EvidenceAnchorHybrid.kt`
  - **Root cause:** Ensures any vintage recovered by 6a–6c in `EvidenceExtractor` flows into the final summary whenever `form_summary` (`anchor`) missed the vintage.
  - **Target bottles:** `#26, #56, #96, #160`.

---

## Latest Analysis — Pre-refresh capture relabeled Lab v5 / Prod v4.0 (2026-10-27)

### Capture provenance and re-scoring boundary

The user-supplied scored export is `wine-app-labels-125-all-versions-a838c0ffb01b (1).csv` (3,226 rows; SHA-256 `93554bc392d7956cf2b860a53b3c692f1ceb17859a2d9ea0c2a5987d3ae6173d`). It was exported by build `a838c0ffb01b`; its Lab rows were produced under legacy pipeline `lab-v4-expanded-producers`, and its frozen Prod v3 rows were captured by build `14d18846f50a` under `production-v3.0-lwin-only`. For the current benchmark naming, these results are **Lab v5** and **Prod v4.0**; the original CSV values remain provenance, not current IDs. The matching raw exports are `wine-app-labels-125-raw-all-versions-a838c0ffb01b-part-01 (1).csv` (21,841 rows; SHA-256 `976f149afcce03e134d0a2328e2d9f6a7349993629b002c68d3830ca01fbc3a1`) and `wine-app-labels-125-raw-all-versions-a838c0ffb01b-part-02 (2).csv` (12,398 rows; SHA-256 `2cf9465a59e214e30c92d2aaefb0434496fcb679d8564d383c67d92c2960e748`). The scored field rows match the earlier unsuffixed export; the Lab `run_id` is the identified difference.

The uploaded summary row records the capture-time reference score (`579` correct values, `65` wrong, `33` missing, `60` correct blanks, `11` unsupported fills, `2` unscored; `639/748` correct including blanks; `52/123` complete). The owner-adjudicated workbook changes below were applied after capture, so the same predictions were re-scored against the current generated `scoring.json` without rerunning OCR. A local mirror of `PhoneCorpusMath`/`TextNorm.scrub` applied the scoring rules below; no Android/Kotlin execution was available in this environment.

| Current-reference rescore | Lab v5 (`a838c0ffb01b`, captured predictions) | Frozen Prod v4.0 (`14d18846f50a`, not same-build A/B) |
| --- | ---: | ---: |
| Correct values | `589` | `561` |
| Wrong values | `59` | `78` |
| Missing values | `33` | `42` |
| Correct blanks | `60` | `59` |
| Unsupported fills | `7` | `8` |
| Unscored | `2` | `2` |
| Correct including blanks | `649/748` (`86.8%`) | `620/748` (`82.9%`) |
| Complete labels | `53/123` (`43.1%`) | `42/123` (`34.1%`) |

This is a **reference-only rescore** of saved outputs; no model behavior changed in that comparison. Against the uploaded summary rows, owner corrections change 10 Lab field outcomes and 9 frozen Prod outcomes. Lab's remaining `59` wrong values are all in producer and wine name (`29` producer, `30` wine name). Lab field totals: Producer `95/29/1` correct/wrong/missing; Wine name `74/30/19` plus `1` unsupported and `1` unscored; Country `122` correct, `2` missing, `1` unsupported; Region `117` correct, `3` missing, `1` unsupported, `4` correct blanks; Subregion `77` correct, `4` missing, `4` unsupported, `40` correct blanks; Vintage `104` correct, `4` missing, `16` correct blanks (`1` unscored cell is included in the total of two unscored). Frozen Prod field totals: Producer `79/44/2`; Wine name `70/34/19` plus `1` unsupported and `1` unscored; Country `121` correct, `3` missing, `1` unsupported; Region `115` correct, `5` missing, `1` unsupported, `4` correct blanks; Subregion `72` correct, `9` missing, `5` unsupported, `39` correct blanks; Vintage `104` correct, `4` missing, `16` correct blanks (`1` unscored).

### Owner-adjudicated benchmark targets (now reflected in both workbook tabs and generated assets)

| Review | Producer | Wine name | Region | Subregion |
| ---: | --- | --- | --- | --- |
| `#171` | Bodegas Vatan | Triton | Castilla y Leon | Toro |
| `#179` | Campo Eliseo | Cuvée Alegre | Castilla y Leon | Toro |
| `#181` | Marqués de Casa Concha | Heritage | Maipo Valley | Puente Alto |
| `#184` | Patricia Green Cellars | Volcanic Pinot Noir | Willamette Valley | Dundee Hills |

Use canonical/catalog spelling **Willamette Valley**. The saved Lab predictions already contain these parent/child geography assignments; correcting the reference moves four Region errors and four unsupported Subregion fills into correct values. The #179 producer and wine-name predictions also become correct under the owner's adjudication. Remaining captured-run errors include #171 wine name (`Toro` vs `Triton`), #181 producer (`Marquesa` vs `Marqués de Casa Concha`), and #184 wine name (blank vs `Volcanic Pinot Noir`).

### Root-cause recommendations from the latest run

1. **Keep the final producer/database comparison as an independent last pass.** It is additive to the upstream span resolver, not gated on upstream span eligibility or acceptance, per owner direction. The current implementation interprets “a few characters” as at most two normalized edits; that threshold is a working choice, not an owner-specified exact value. The owner accepts occasional correct-to-wrong canonicalizations. In the previous 14-replacement audit, however, the pass was net `-3` correct (3 wrong→correct, 6 correct→wrong, 5 still wrong). FTS-first ties selected `Chateau Labat` over OCR-supported `Chateau Fabas` (#116); other ties include #62, #181 and #70. Unique-nearest false replacements included #146 `CITRAN`→`Citra` and #160 `CHATEAU DES ROCHES`→`Chateau des Rochers`. Improve tie ranking with OCR-supported producer-role evidence while preserving the independent terminal comparison and its deliberate owner-approved recall trade-off.
2. **Prioritize wine-name phrase/role/layout ranking, not a larger beam alone.** Before reference corrections, wine name had `32` wrong and `19` missing; all 19 missing cases abstained with competing assignments. Thirteen expected names were already recoverable by OCR-token concatenation, 13 reached the 64-state beam cap, and 16 showed ownership conflicts. The target is better segmentation, phrase ownership and role ranking; simply increasing beam width does not address these conflicts.
3. **Normalize vintage OCR conservatively and keep hybrid fallback guarded.** Missing reads were #56 `I998`→1998, #96 `I996`→1996, #160 `2 0 2 0`→2020, and #26 split `20` + `16`→2016. The current worktree now recognizes context-bounded `I`/`l` and `O` confusions, spaced digits, and same-engine split halves with level/similar-size boxes and tolerant symmetry around the observed text center (not a brittle fixed `x=0.5`). The hybrid preserves every populated baseline vintage and fills only a blank with a canonical `19xx`/`20xx` year within the supplied maximum year. Instrumented regression coverage was added; keep Item 6 open until Android compilation/device validation and the saved-reading replay pass.
4. **Apply global producer-name normalization and role penalties (regression example: #58).** These are shared, catalog-driven rules for every producer candidate and completed producer value, not special cases for one wine; #58 is a motivating regression only. The zero-cost `de` omission applies only to producer-name distance, leaving linked-wine name comparison unchanged. Grape-variety phrases receive a strong producer penalty (3 edit-equivalent units); geography receives a lighter penalty (1). A unique, supported producer may replace an existing baseline only when that baseline carries the strong grape-variety penalty; a geography-only baseline remains protected. For #58, both OCR engines read `Les Ormes` and `Cambras`; candidate 3 also reads `de`. The expanded producer index has `Les Ormes de Cambras`, while the primary LWIN-only index does not. The worktree passes clean same-engine, same-variant producer hypotheses from the evidence graph into `ProducerResolver`; omission of `de` has zero producer-name edit cost. Re-run #58 with the expanded index as one regression, while measuring these shared rules across the full Lab v5 corpus.
5. **Protect field-hierarchy correctness in scoring.** The four corrected references show why Region and Subregion must be evaluated as separate hierarchy levels. Regenerate workbook-derived targets and coverage after every owner correction; never count an old local appellation in Region as correct merely because the OCR text is present somewhere on the label.

### Implementation and validation status

The working tree contains the vintage normalization/split-pair/blank-baseline fallback, producer evidence-span routing, zero-cost `de`, stronger grape and lighter geography penalties, workbook corrections, regenerated `candidate-labels.json`, `scoring.json`, `gazetteer-coverage.json`, phone-test assets and assertions. The workbook builder also falls back to the already packaged, hash-verified legacy image map when the retired debug corpus files are absent, so the documented build order remains reproducible without duplicating the old photo set. Host validation passed after the version-rename contract tests were updated: `268` Python tests. Android/Kotlin/Gradle and device validation remain unavailable in this environment (no Java/JDK, Android SDK, Gradle, or adb); do not mark Items 3/6 measured-complete until those checks and the saved-reading replay confirm behavior and regressions.

**Small performance improvement:** producer-role penalties reuse lazily computed maximum catalog phrase lengths, and vintage regexes compile once per extractor/hybrid rather than once per candidate. Keep the current `MAX_SPANS` and existing bounded search limits; the measured wine-name root cause does not justify widening them blindly.
