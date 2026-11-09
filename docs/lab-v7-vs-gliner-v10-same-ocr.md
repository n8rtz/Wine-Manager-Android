# Lab v7 vs GLiNER v10: same-OCR comparison

## Question and evidence

Compare the pre-GLiNER Lab v7 source at `3a89613113bf85e7132aa9204e7b5cfaddbf4115` with GLiNER v10 on all 188 eligible photos. Input is the owner's phone export `gliner-lab-1793554717926.json` (SHA-256 `e020b26a7734c33c7e05d394d449c74d658f0942e22357077df7c3108e3742d2`; phone build `d3c2815d553a`). All 193 attempted photos and the same 188 eligible references are included. The v10 rules were developed using these labels; none of these measurements is an unseen-holdout estimate.

The host experiment reconstituted Lab recognition journal rows from each saved independent ML Kit and PP-OCR reading's text, engine, normalized bounding box, score and estimated-score flag. It ran the **historical Lab v7 Kotlin code**, integrated Lab modules matching the export's 21-module signature, and the producer/wine SQLite databases shipped at `3a89613`. Scoring references were used only after the pipeline completed. No model hints or reference answers were provided to Lab v7. The original 124-photo cache was not used as a substitute for the 188-photo run.

The existing host harness uses the seeded fixture knowledge base and a SQLite bridge and does not enforce the Android lookup budget. It does not run Lab v7's own OCR, Android SQLite, or phone runtime. Thus this is Lab v7 **post-OCR on GLiNER's saved OCR**, not a fresh native end-to-end Lab v7 phone score. The historical and current expanded producer indexes produced identical host outputs across all 193 in this experiment; that does not establish they would always be equivalent.

## Controlled host replay

| Stage on identical saved OCR | Complete | Correct eligible fields | Net complete gain vs preceding stage |
|---|---:|---:|---:|
| Historical Lab v7 | 92/188 | 971/1,128 | — |
| Lab v7 output + current v10 deterministic policy, **no GLiNER hints** | 140/188 | 1,053/1,128 | +48 |
| Same policy + saved GLiNER hints | 147/188 | 1,063/1,128 | +7 |

Both post-processing stages are host replays of the actual Kotlin policy, with no newly incomplete labels at either step. The first stage contributes 82 net correct fields; adding retained model hints contributes another 10. The no-hints policy is a counterfactual, not an implementation already integrated into Lab v7. In this particular ordering 48/55 = 87.3% of the net complete-label gains occur without hints and 7/55 = 12.7% occur when hints are added. Interaction effects mean these percentages are not an intrinsic or unique division of causal credit.

## Phone-base cross-check

The actual phone export confirms 148/188 for v10, and its own same-OCR pre-decision base is 92/188, 967/1,128 eligible fields. V10 replay reproduces the phone final exactly. Removing its saved model hints while keeping that phone base and the same decision policy gives 141/188 and 1,053/1,128: +49 complete and +86 fields vs phone base without model hints, then +7 complete and +10 fields when hints are restored. On this different, phone-base comparison the increments are 49/56 = 87.5% and 7/56 = 12.5%. This does not rerun either OCR or inference.

The host Lab v7 output differs from the phone export's pre-decision base in five fields across two photos (#217 and #245), although both have 92 complete. The comparison on a fixed host base is 92→140→147; the comparison on a fixed phone base is 92→141→148. In particular, the host's populated #217 subregion causes its v10 outcome to remain incomplete. Do not combine the 92→140 host step with the 148 phone endpoint or describe the host as exact per-field device parity. The fixture KB, host bridge and lack of device budgets are possible divergence sources, not a proven diagnosis.

On the phone-base ablation, the hints change ten correct fields across eight eligible photos: #86, #106, #173, #186, #219, #233, #248 and #255. Seven become complete; #186 remains incomplete due to a different field. There are no model-hint-associated correct-to-incorrect field changes in this sample. This is not evidence that model hints never hurt on other photos.

## Interpretation and limitations

Most measured improvement can be obtained by applying the current evidence/layout/catalog rules without GLiNER inference on these saved inputs. Those rules still require implementation and validation if they are to be used in Lab v7 or production. Model assistance during rule development, complementary OCR quality, dependencies among policies, and different hint thresholds cannot be quantified by this simple ablation. Rules shaped using these images may overfit; this split is not expected performance on unseen labels.

The fresh phone run reported model-session load 1,121 ms and first-result wall time 21,911 ms. Across 193 images, median model inference was 87 ms, median total 6,204 ms and p90 total 9,307 ms. These are GLiNER measurements only, not a head-to-head latency comparison with Lab v7 on the phone.

To establish a true end-to-end Lab v7 vs v10 comparison, run the pre-GLiNER Lab pipeline's own OCR and post-processing on the 193 photos on a phone, match the same eligible photo identities and references, retain per-field results, and compare cold and warm timing. A separate unseen, diverse photo set is needed to assess generalization.
