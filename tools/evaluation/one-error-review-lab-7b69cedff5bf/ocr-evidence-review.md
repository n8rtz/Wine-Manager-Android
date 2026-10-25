# OCR evidence sufficiency — Lab run `7b69cedff5bf-1792899530701-LAB-eaece938-596d-4fef-83b0-14539102f5a2`

Reviewed **37** one-error fields. The status describes raw OCR evidence, not final field accuracy; snippets may be split across separate recognition boxes.

| Evidence status | Count | Meaning |
|---|---:|---|
| sufficient | 20 | All required literal label terms were recognized; investigate downstream role/assignment. |
| insufficient | 12 | A required literal term was missed or materially corrupted. |
| mapping required | 2 | OCR read the printed term, but the benchmark target is a broader inferred region. |
| blank policy | 3 | OCR read the printed appellation, but expected subregion is blank. |

## Recognition gaps

| # | Field | Expected text | Lab value | OCR fragments |
|---:|---|---|---|---|
| 12 | producer | La Francette | — | candidate_3: Trancette | mlkit_reference: CABERNET SAUVIGNON |
| 18 | producer | Château d'Agassac | CHÂTEAU D'AGASSAO | candidate_3: D'AGASSAO | mlkit_reference: DAGASA |
| 31 | producer | Château La Tour de By | — | candidate_3: La Cour de By | mlkit_reference: HATEAU |
| 45 | producer | La Chapelle de Calon | La Chapelle de Caler | candidate_3: La Chapelle de Caler |
| 55 | wine_name | Léo by Léo | SAINT-EMILION Grand Cru | candidate_3: LÉO | candidate_3: by | candidate_3: eo |
| 63 | wine_name | Volcanic Rosé | LANGUEDOC | candidate_3: Volcanic Lasi | mlkit_reference: LANG EDOC |
| 75 | producer | Château La Tour de By | CHÂTEAU La Cour de By | candidate_3: La Cour de By |
| 114 | producer | Le Pas du Templier | — | candidate_3: Le Pas du | candidate_3: EMPLIER |
| 117 | producer | La Francette | — | candidate_3: Trancette | mlkit_reference: MERLOT |
| 133 | wine_name | Terre de Provence | cÔTES DE PROVENCE | candidate_3: Terre de Prorence |
| 159 | producer | Domaine Georges Bertrand | GEORGES BERTRAN bEORGES BERTRAM DOMAINE | mlkit_reference: DOMAINE | candidate_3: GEORGES BERTRAN | mlkit_reference: bEORGES BERTRAM |
| 168 | wine_name | Léo by Léo | SAINT-EMILION Grand Cru | candidate_3: LÉO | candidate_3: by | candidate_3: eo |

## Interpretation

**20 of 32 literal-value cases** had sufficient OCR text. The remaining cases in the cohort include mapping and expected-blank policy issues; those are not character-recognition failures. The #63 run expectation is kept separate from the current adjudicated target in the CSV.
