# GLiNER field policy v6: saved-evidence evaluation

This is a development-set replay, not a new phone run or an independent holdout. Input: `gliner-lab-1793501924004.json`, SHA-256 `35a13b0fbe969c65b079eb7767e1f01ec8981bee52f8d782e9776b9894609824`, 193 attempted, 188 eligible, five excluded. The Kotlin replay uses the exported base fields, independent OCR lines and boxes, and retained lane spans/scores. It uses the current policy and vocabulary. Neither OCR nor model inference runs again. See `gliner-run-1793501924004-analysis.md` for the original baseline, timing, cases and methodology. This set guided the changes, so the numbers are not an unbiased generalization estimate.

## Reference audit, revision front-label-audit-2026-10-31-v2

These are separately documented label/reference decisions, not model fixes. The audit was performed by the implementer, not independently by a second reviewer. #240: Montilla-Moriles is not printed on the front; subregion is expected blank under the user's front-label convention. #208: accept Weingut Aldinger as well as Aldinger ([producer listing](https://www.ripleywines.com/product-category/explore-producers/germany-explore-producers/wurttemberg-germany-explore-producers/aldinger-germany-explore-producers/)). #213 accept Loire/Loire Valley; #218 accept Rhone/Rhône Valley; #221 accept Chablis Premier Cru/Chablis. #228 region Trentino-Alto Adige and subregion Alto Adige ([hierarchy](https://italianwinecentral.com/region-province/trentino-alto-adige/)). #239 retains Jerez as the sherry appellation, not merely a postal address ([product](https://www.skurnik.com/sku/rare-cream-sherry-solera-reserva-e-lustau-2/)). See the source records and packaged scoring manifest hashes for versioned details. Unresolved remains unresolved; expected blank does not assert that a wine is inherently non-vintage.

With the *unchanged v5 policy and OCR*, rescoring these references changes the original 114/188 to **116/188**, and base 90/188 to 92/188. This +2 is rescoring, not an extraction gain. Audit is provisional pending a reviewer and, for ambiguous images, clearer front-label evidence.

## Staged replay, corrected references

| Stage | Fully correct / 188 | Increment | Changes versus preceding stage |
|---|---:|---:|---|
| v5 | 116 | — | Reference correction only |
| v6 title/legal | 118 | +2 | #197, #199, #242 title |
| v6 initial vintage roles | 118 | 0 | #247 vintage |
| v6 producer and additional date role | 119 | +1 | #192 producer and vintage |
| v6 geography | 123 | +4 | #241, #244, #249, #254 region and subregion |

Overall v6 versus v5: seven complete-label gains, zero complete-label losses; eligible field gains producer 1, wine name 3, country 0, region 4, subregion 4, vintage 2; zero eligible field losses. Excluded images have no resolved-field changes in correctness. Stage attribution is from intermediate replays; the final implementation includes an extra collision guard confirmed by rerunning the complete replay. The overlay requires the expected country/state, consistent existing hierarchy, a unique mapping and the same place text from both engines in overlapping boxes; it is not a global geographic resolver. Legal/address text is vetoed. Names resembling places or dates can still be ambiguous, and the full population of such labels was not tested.

The role/date logic avoids `ANNO` alone, requires independent overlapping evidence of marked establishment/vineyard dates and preserves unmarked old years. Alternative vintage must also be corroborated. No correction maps Stags' Leap Winery to the distinct Stag's Leap Wine Cellars. Title assembly uses ordered, overlapping evidence and legal/place/producer guards; it is not guaranteed to resolve all semantic roles.

## Matched no-hints ablation

Run the exact same v6 replay with and without `--no-hints`; references, base fields and OCR readings are unchanged. With retained hints: **123/188**; without hints: **120/188**. Hints improve four eligible fields' wine names (#86, #106, #173, #186) and one producer (#86), with zero field losses, yielding three additional fully correct labels; #186 still has a producer error. These are retained-hint effects in this policy, not evidence of model recall at other thresholds, and disabling hints does not remove deterministic processing. No model coverage/threshold tuning was performed: the export lacks the discarded candidates and no live inference was available. INT4 and the existing threshold are unchanged.

Reproduce with the compiler command in `tools/host-lab/replay_gliner_decisions.py`; use `--rescore` for this reference revision, `--no-hints` for the paired ablation, and optionally `--output-json` for per-image comparisons.

## OCR gaps and remaining work

The source export shows examples where both engines omit or damage critical text: #12/#117 La Francette, #36 Château La Croix, #45 Calon, #114 Templier, #140 Amélisse, #210 Fourrier; #198 and #253 contain fragmented geography. Where one engine supports a partial producer or title, the existing bounded two-engine spatial evidence policy can repair it and records a decision trace; v6 also repairs #192's producer from corroborated evidence. It cannot reconstruct text absent from both engines without guessing. No new pixel crop/retry is enabled: a saved-OCR replay cannot show that it recovers missing characters, quantify runtime or rule out regressions. The current engines and raw provenance remain unchanged. A targeted retry is a device experiment, not a demonstrated improvement.

Required follow-up: fresh-pixel full corpus on the phone with this build, compare per-field and per-label changes and single-scan/model-initialization timings; collect and label a genuinely unseen holdout and rerun both systems on identical photos with identical preprocessing. Verify threshold/coverage from raw model candidates only via controlled live inference. Recheck reference changes independently and inspect regressions before any deployment decision. Phone results and generalization are not claimed here.
