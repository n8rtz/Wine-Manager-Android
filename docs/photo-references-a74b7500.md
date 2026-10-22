# Photo-backed references for batch a74b7500

These annotations are in `tools/evaluation/photo-references-a74b7500.json`, separate from recognition knowledge and existing scored regression targets. Neither extraction pipeline imports this file. Adding photographs as evaluation evidence must not silently teach the recognizer the answers.

## Confirmed by the user

The user confirmed the preceding proposals for samples 14 (Kirkland Rioja Reserva 2020), 16 (Kirkland Sauvignon Blanc 2024, omitting Ti Point pending verification), 13 (Penfolds Koonunga Hill Chardonnay 2023), 18 (Los Haroldos Malbec Reserva 2020, Uco Valley as Region), and 17 (Juan Gil 2020, unresolved wine name).

Juan Gil's name is null/unscored, not a known-empty identity. Historical Penfolds dates and Rioja's split year are reference annotations only, not permitted hard-coded OCR completions.

## Newly supplied photographs

| Sample | Producer/brand proposal | Wine name proposal | Origin interpretation | Vintage |
|---|---|---|---|---|
| 5 | Réserve des Auléines — role unconfirmed | Sauvignon Blanc | France / Cévennes; hierarchy pending | Unknown |
| 6 | Kirkland Signature | Chianti Classico Riserva | Italy / Tuscany / Chianti Classico | 2023 |
| 7 | Kirkland Signature | Côtes du Rhône Villages | France / Rhône / Côtes du Rhône Villages | 2024 |
| 9 | Kirkland Signature | Saint-Julien — Demeure Bourgeoise inclusion unconfirmed | France / Bordeaux / Saint-Julien | 2023 |
| 11 | Bodegas Los Pinos | Monastrell Cabernet Sauvignon | Spain; finer geography unknown | 2021 |
| 12 | Covalli | Barolo | Italy / Piedmont / Barolo | 2019 |
| 10 | Kirkland Signature | Malbec | Argentina / Mendoza | 2022 |

New entries remain pending review rather than implicitly approved scoring targets. Country/region values inferred from appellations are distinguished from literal photo text in the JSON notes. Front-label absence of a year does not prove a non-vintage wine. Original photos are referenced by supplied filename but not committed.

This addition does not implement the planned role/composition/decision-trace refactor, alter a wine database on the phone, or change recognition behavior. It preserves reference evidence for that work without contaminating the scanner or retroactively changing the existing benchmark.
