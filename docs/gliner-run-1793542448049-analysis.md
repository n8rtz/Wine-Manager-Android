# Analysis and v7 iteration: GLiNER run 1793542448049

## Run identity and reproducibility

The phone export SHA-256 is `346ee39d7b1d7fec2f19de082ec9201ef89d9655d51c255acb2d3606a9fffc4b`; build `2182f78850f1`, policy v6, Small INT4, threshold 0.50, width 12, word limit 320. All 193 images completed; 188 had all six references resolved. There are no runtime failures. The frozen scoring file was not changed during this iteration. The exact v6 Kotlin policy replay reproduces **all 1,158 exported field values**, not merely the aggregate, using exported base fields, decision readings/boxes and retained hints.

Although this is a new phone run, its parsed raw OCR readings and retained lane hints are identical to the earlier 1793501924004 run for all 193 images; base fields differ on one image. That makes the new export a useful on-device confirmation of the v6 result, but it does **not** provide independent OCR/model evidence or a holdout. The v6 source performs OCR on each image; the exports alone cannot establish differences in physical acquisition. The prior 114/188 was under the older references. Rescoring that previous v5 output gives 116/188; the new v6 result is 123/188. Do not attribute the entire 114→123 difference to extraction.

## Results and opportunities

- **123/188 fully correct** (65.4%); 38 one-error and 27 multi-error eligible labels. Among eligible fields, **1,013/1,128** correct; across all 193 images, **1,030/1,152** resolved fields correct. The excluded images' resolved fields are still useful for regression checks.
- Eligible fields: producer 157/188, wine name 143/188, country 177/188, region 172/188, subregion 178/188, vintage 186/188. Failures by outcome across all rows: 82 wrong, 37 missing, three unsupported fills; six fields unresolved. Blank target fills are counted as errors.
- The 38 one-error eligible cases are 24 wine-name, 12 producer and two region. Correcting every one of these without a regression would yield 161/188, but that is an upper bound for these single-error cases, not a prediction.
- Original 124 photos: 104/122 eligible complete; six Costco: 2/5; 63 X-Wines: 17/61. These are descriptive cohorts, not a population sample or a controlled Lab/Prod comparison. The first 124 and later additions differ substantially in sources, visual styles and reference conventions.
- 101/193 rows have no retained lane hint. V6 same-OCR base 92→123 complete (+31, no complete losses), so much of the gain is deterministic post-processing. In the v7 paired replay, retained hints yield 129/188 versus 125/188 with hints removed. The latter is a post-processing ablation, not a different model run; discarded spans and lower thresholds cannot be measured from this export.
- Reported phone per-photo OCR mean/median 4,859/4,794 ms; model inference 109/97 ms; post 1,968/1,480 ms; total 7,000/6,572 ms (p90 10,465 ms). Session load 1,311 ms and first-result wall 21,220 ms. These observations are not a controlled single-scan comparison and do not predict v7 latency.

## General failure paths and bounded changes

1. **Role contamination and split producers.** Producer names can leak into names (#191, #248) or be truncated at a printed connector/designator (#212, #257); legal text can swallow a winery (#224). V7 repairs the first only when a separately read producer and exact grape support it, completes producer phrases only with corroborated linked geometry, and recovers a legal-contaminated two-part winery only when a separate company-role line repeats it. The #219 prefix guard also prevents inserting a producer fragment into an already incomplete wine title; it remains an error, not a scored gain. A connected phrase is not proof of legal ownership, and generic label semantics can still be ambiguous.
2. **Loss of printed title components/order.** #207/#208 and #259 show grape/style/date lines missing or reordered; #246 shows a separate article dropped; #248 shows a grape blend contaminated by a maker word. V7 uses only actually observed components, independent matching readings, ordering and proximity, generic descriptor/grape vocabulary, uniqueness and, for the blend correction, a retained model hint. It never guesses an unread word. An observed marketing phrase or place can still masquerade as a title; these guards reduce rather than eliminate that risk.
3. **Origin gaps.** #227 prints a regional geographical indication, and #257 prints a region while the region field contains the country. V7 uses small general administrative-region vocabularies and corroborated role/layout cues; exact `Product of` text can fill a missing country. It does not use a postal address as a wine origin, overwrite a conflicting country or region with a guess, or choose between multiple candidates. #226, excluded from complete-label scoring, also gains a resolved country field. This is not a comprehensive worldwide hierarchy resolver.
4. **Unresolved identity and OCR gaps.** #12/#117, #36, #45, #114, #140, #210 and #235 show damaged or absent name characters. #186 has an incorrect producer identity and only one damaged reading of the printed name; Stags' Leap and Stag's Leap Wine Cellars are distinct and must not be merged. #198's 1931 and #206's ANNO 1947 do not provide a sufficiently corroborated date-role cue. #203, #242 and German origin failures lack clearly readable complete geographic origin in both engine outputs. No identity, year or appellation is guessed from targets.
5. **Remaining semantic or geographic ambiguity.** #201 appends a designation to the product title; #194, #204, #211, #213, #219 and #250 need different combinations of title, grape, classification and geography. #225 and #234 have coherent printed Italian cues but an existing conflicting origin. Two engines agreeing on the pixels does not establish the intended role or whether a producer address is an appellation. The iteration deliberately avoids broad overrides for these cases until safely generalized rules and negative cases are available.

## Staged frozen-input replay

| Stage | Complete eligible | Eligible field gains / losses vs v6 |
|---|---:|---|
| v6 phone and exact Kotlin replay | 123/188 | — |
| v7a title/producer-prefix, article and dated-varietal guards | 126/188 | 3 / 0 |
| v7b ordered grape/descriptors | 126/188 | 4 / 0 |
| v7c blank title and supported regional origin | 127/188 | 7 / 0 |
| v7d linked producers, blend and country-region collision | 129/188 | 11 / 0 |
| v7f legal-paragraph producer recovery and final guards | **129/188** | **12 / 0** |

Final eligible field gains: producer 3 (#212, #224, #257), wine name 6 (#191, #207, #208, #246, #248, #259), country 1 (#227), region 2 (#227, #257); zero field or complete-label losses. Six labels become fully correct (#191, #227, #246, #248, #257, #259). One additional excluded-but-resolved country field improves (#226). The #219 value changes from one wrong value to another, without adding the producer fragment. Resolved fields become **1,025/1,128 eligible** and **1,043/1,152 overall** on this replay. No reference edits were made. This is a development set used to design the changes, so no generalized accuracy claim follows.

A separate, previously used 26-photo owner-reviewed personal audit was replayed from its saved OCR with both v6 and v7 using matching SHA-256s and the corrected bundled audit references: exact-normalized complete **15/26 → 15/26**, zero field gains or losses. It is a regression check, **not** an unseen holdout; exact normalization does not include semantic aliases. Tool: `tools/host-lab/compare_gliner_audit.py`.

## Validation and next step

Kotlin policy compiled on a host JVM; positive/negative synthetic checks cover missing engines, competing locations, legal/address text, conflicting countries, duplicated articles, distant or uncorroborated lines, and date/producer ambiguities. Host wall time for the entire 193-image replay including JVM/Python startup in three trials was 0.758–0.879 s for v6 and 0.919–1.014 s for v7; that is not an Android or per-scan latency benchmark. No OCR/model change or new retry is claimed. Android compilation and fresh phone pixels remain unverified in this environment. Build v7 on the phone, rerun the full set, export JSON and compare field/label losses as well as first-scan and per-image timings. Then test a truly unseen, diverse holdout before treating this as representative real-world accuracy.
