# Lab v4 designation, level and fragment rules

Date: 2026-10-27. Basis: the owner's 125-label run at build `d645b9fca7b9`
(`wine-app-labels-125-all-versions-*.csv` plus both raw parts), 4 versions, 3,226 scored
rows and 37 MB of raw trace. This records why five selection rules changed, and what was
measured before changing them.

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

## The five rules now in the Lab v4 extractor

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

## Level convention: kept hierarchical, measured

The reviewer's own expectations are not internally consistent for DO/AVA countries
(`Toro` and `Dundee Hills` are expected as the *region* with a blank subregion, while
`Stags Leap District` is expected as a *subregion* of `Napa Valley`). Simulating both
conventions over the same run:

| convention | region correct | subregion correct |
| --- | --- | --- |
| hierarchical (current) | 91 | 82 |
| flatten (deepest printed name → region, subregion blank) | 35 | 48 |

Flattening loses badly, so the hierarchy stays. What the run *did* show is genuinely wrong
values, and those are what rules 1, 2 and 5 remove: `Bordeaux Supérieur`, `Médoc Supérieur`
and `Saint-Émilion Grand Cru` were being written into the subregion slot.

Still open for the owner (not changed here): `Toro` → `Castilla y León`, `Dundee Hills` →
`Willamette Valley`, `Margaux`, `Porto` and `Crémant d'Alsace` as subregions. Resolving those
needs one convention decision per country, or a marker in the catalog for "this appellation is
the region in its market", and neither can be guessed from the label.

## Measurement switches for the next run

`PhoneCorpusRunner.REPAIR_PLACES_IN_BENCHMARK` and
`SINGLE_READING_RESOLVERS_IN_BENCHMARK` are both **true** in this build, so the next
**New Lab run** measures the place repair and the single-engine resolver policy together.
Prod v3.0 is a frozen baseline and is not re-run, so its comparison row is unaffected.
