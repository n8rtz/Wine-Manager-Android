# Lab v5 designation, level and fragment rules

Date: 2026-10-27. Basis: the owner's 125-label run at build `d645b9fca7b9`
(`wine-app-labels-125-all-versions-*.csv` plus both raw parts), 4 versions, 3,226 scored
rows and 37 MB of raw trace. This records why five selection rules changed, and what was
measured before changing them. The baseline below retains the original Lab v4 label for that
historical capture; the current benchmark label is Lab v5.

## Baseline the analysis started from (Lab v4, 123 eligible labels)

| metric | value |
| --- | --- |
| complete forms | 32/123 |
| one error / two or more errors | 37 / 54 |
| values correct / wrong / missing | 498 / 84 / 95 |
| producer | 77 / 46 / 2 |
| wine_name | 63 / 33 / 27 |
| country | 112 / 0 / 12 |
| region | 89 / 3 / 28 |
| subregion | 53 / 2 / 22 |
| vintage | 104 / 0 / 4 |

Error sets: 16 labels fail on producer only, 16 on wine_name only, 15 on both; 30 of the 61
wine_name problems also carry a geography problem. Fixing only the two identity fields would
take completeness from 32 to 79; that is the whole reason these five rules exist.

## What the raw trace showed

* Of 31 labels with a wrong or missing `region`, **24 never had a region hypothesis in any
  finalist** — the place was matched but a country-only hypothesis, or a bare designation,
  won the assignment.
* On one Abelyce label the solver returned **twelve finalists tied at 270.0**, half of them
  `{WINE_NAME=Grand Cru}` + `{COUNTRY=France}` and half
  `{WINE_NAME=SAINT-ÉMILION Grand Cru}` + `{COUNTRY,REGION,SUBREGION}`. Because a field is
  only emitted when every finalist agrees, four fields went blank at once.
* `SAINT-ÉMILION GRAND CRU` matched the catalog appellation *Saint-Émilion Grand Cru*, so the
  subregion came out as `Saint-Émilion Grand Cru` where the benchmark expects `Saint-Émilion`.
* Eight producer labels and nine wine_name labels were blanked purely by spacing
  (`CHATEAU ABELYCE` vs `CHATEAU ABELY CE`).
* Single-word fragments won identity slots: producers `CRUCLASS`, `HATEAU`, `PREMIER`,
  `MARQUES`, `dak`; wine names `ITERNA`, `LANGUEDOc`, `DOCG`, `REPUTATION`, `ultra`.

## The five rules now in the Lab v5 extractor

1. **Classification base.** `WineDesignations.classificationBase` maps a
   classification-qualified name to the place it qualifies. Only `Grand Cru`,
   `Grand Cru Classé`, `Premier Grand Cru Classé`, `Supérieur`, `Supérieure` and
   `Supérieures` qualify; `Classico`, `Villages`, `Riserva`, `Superiore` and similar
   zone-forming suffixes do not, because those name a different appellation. The geography is
   taken from the base, the **full printed line stays the wine name**, and
   `EvidenceSolver.samePrintedAppellation` compares `classificationBase(wineName) ?: wineName`
   against the matched place (and its catalog spelling forms) so the wine-name offer never
   kills its own ancestor geography bundle via `ownership_conflict`.
2. **Designation splitting in the extractor.** `Supérieur`/`Supérieure`/`Supérieures` were
   added to `data/wine-knowledge/label-terms.csv` (121 → 124 terms), so
   `Bordeaux Supérieur` splits into `Bordeaux` + `Supérieur` everywhere the app already splits
   designations. `Grand Vin` was deliberately **not** added: it would strip a leading
   `Grand Vin de Bordeaux` down to `de Bordeaux`.
3. **Bare designations are never a wine name.** `GRAND CRU`, `DOCG`, `RISERVA` qualify a
   name; offering them at rank 75 is what blanked four fields per label through the tie above.
4. **Single-word fragments are never an identity.** A one-word phrase that another engine read
   as the start of a longer overlapping line (`CRUCLASS` under `CRU CLASSE`) no longer becomes
   a producer or wine name. Legitimate one-word brands are unaffected: they have no longer
   overlapping reading.
5. **One name fills one level.** A place may not be emitted as both region and subregion, so a
   collapsed hierarchy (`Bordeaux` → region Bordeaux → `Bordeaux`) yields region only.

Plus the ambiguity projection: `EvidenceRankingConfig(equivalentNames = true)` treats two
spellings that differ only by spacing or punctuation as one answer, which is what turned 17
blanked identity fields into a single value.

## Historical level convention: hierarchical, measured before owner correction

The original review targets for DO/AVA countries were not internally consistent: `Toro` and
`Dundee Hills` were expected as the *region* with a blank subregion, while `Stags Leap District`
was expected as a *subregion* of `Napa Valley`. Simulating both conventions over that earlier run:

| convention | region correct | subregion correct |
| --- | --- | --- |
| hierarchical (at the time) | 91 | 82 |
| flatten (deepest printed name → region, subregion blank) | 35 | 48 |

Flattening lost badly, so the hierarchy stays. The prior run also showed genuine wrong values;
rules 1, 2 and 5 remove `Bordeaux Supérieur`, `Médoc Supérieur` and `Saint-Émilion Grand Cru`
from the subregion slot.

**Owner correction, 2026-10-27:** the historical reference convention is superseded for four
Wine App Labels: #171 and #179 use Region `Castilla y Leon` / Subregion `Toro`; #181 uses
Region `Maipo Valley` / Subregion `Puente Alto`; and #184 uses Region `Willamette Valley` /
Subregion `Dundee Hills`. The workbook, scorer assets and corrected-reference rescore now reflect
those parent/child levels; the table above remains a historical measurement under the prior targets.

Still open for the owner: whether `Margaux`, `Porto` and `Crémant d'Alsace` should be accepted as
subregions under their broader regions. Keep this separate from the four explicit corrections above.

## Context clues, framed appellations, and accent-free matching (follow-up)

On top of the five designation/fragment rules above, the current `EvidenceExtractor`
(`evidence-lab-v5-vintage-recovery`) and `TextLabSpatialPreview` apply unified label-wide context clues in Lab v5 and scrub
letter accent marks across all OCR inputs and database/catalog outputs:

* **`LabelContext` (`textlab/evidence/LabelContext.kt`).** Collects supported country/region
  anchors, legal classification cues (`DOCG`/`IGT` → `IT`, `DOCa`/`DOQ` → `ES`, `AVA` → `US`,
  `DAC` → `AT`, `VQA` → `CA`, `Wine of Origin` → `ZA`, `Mis en bouteille au Chateau|Domaine`
  and `Appellation ... Controlee` → `FR`, plus narrowing `AOC`/`AOP`, `Qualitatswein` and `DOC`/`DO`),
  and unambiguous KB producer countries once per label. Legal cues never fabricate standalone
  fields; they only unlock `context_required` origins on another line (`PlaceGazetteer.matches`
  4-arg overload) or supply country hints to bounded `PlaceRepair`.
* **Framed appellations on legal lines (`WineDesignations.framedAppellation`).** Lines such as
  `APPELLATION SAINT-EMILION GRAND CRU CONTROLEE`, `DENOMINAZIONE DI ORIGINE CONTROLLATA E GARANTITA BAROLO`
  or `RIOJA DENOMINACION DE ORIGINE CALIFICADA` extract the inner appellation for geography and
  (at rank 62/64 and in `AppellationFallback`) wine-name fallback while still blocking arbitrary
  producer/vintage identity on boilerplate lines.
* **Three-tier `PlaceRepair`.** Bounded appellation spelling repair now accepts (1) an explicit
  compatible country/region anchor (`rankPenalty` 8.0), (2) a `LabelContext` legal/producer
  country cue (penalty 10.0), or (3) an unanchored globally unique non-`context_required`
  appellation of `>= 10` letters separated from every rival worldwide by at least `best + 2` edits
  (penalty 12.0).
* **Lab v5 `country_group` homonym tie-break & `YELLOW` geography backfill.** `ProducerGazetteer`
  reads `country_group` from `producers.sqlite` / `producers-expanded.sqlite`. In Lab v5 only
  (`policy.minimumIndependentEngines < 2`), `ProducerResolver` uses an already-resolved label
  country to break cross-country LWIN homonym ties, and `TextLabSpatialPreview` backfills still-blank
  geography as `YELLOW` from a confirmed `supported_pair` LWIN wine's unambiguous catalog
  appellation ancestors (or `COUNTRY` alone from an unambiguous LWIN producer `country_group`,
  mapping `Russian Federation` → `Russia`), never overwriting existing values or touching Prod.
* **Accent-free normalization (`TextNorm.scrub`).** Incoming OCR text (`MlKitOcrEngine`,
  `PaddleCtc`, `CoordMapper`, `TokenMerger`, `TextLab*`, `FieldExtractor`, `WineDesignations`)
  and all KB/SQLite/gazetteer catalog outputs replace accented letters, Latin ligatures/stroked
  letters (`ß` → `ss`, `œ` → `oe`, `æ` → `ae`, `ø` → `o`, `ł` → `l`, `đ` → `d`, `ı` → `i`)
  and standalone spacing diacritics (`´`, `¨`, `¸`, `¯`, `ˆ`, `˜`) with plain ASCII letters.

## Measurement switches for the next run

`PhoneCorpusRunner.REPAIR_PLACES_IN_BENCHMARK` and
`SINGLE_READING_RESOLVERS_IN_BENCHMARK` are both **true** in this build, so the next
**New Lab run** measures the place repair, `LabelContext` context clues, and the single-engine
resolver policy together. Frozen Prod v1.0–v4.0 scoreboard rows automatically filter to Lab v5's
attempted photo subset while a pass is in progress (`attempted` non-empty) and revert to the
full frozen set once complete or idle.
