# GLiNER policy v9: saved-evidence iteration

## Scope

This is a development replay, not fresh OCR or inference. It uses the 193-row phone export `gliner-lab-1793542448049.json` (SHA-256 `346ee39d7b1d7fec2f19de082ec9201ef89d9655d51c255acb2d3606a9fffc4b`), its saved base fields, original decision readings and retained model hints, and the **unchanged** reference set. These same images helped shape the rules; they are not an unseen holdout. Only the experimental GLiNER workflow is changed. See `docs/gliner-policy-v8-evaluation.md` for v8, including the unmodified #195 full-denomination scoring caveat.

## Paired development results

| Version, identical evidence | Eligible complete | Eligible correct fields | Eligible field gains/losses vs v6 phone |
|---|---:|---:|---:|
| Phone v6 / exact Kotlin replay | 123/188 | 1,013/1,128 | — |
| v8 replay | 135/188 | 1,038/1,128 | 25 / 0 |
| v9 replay | **138/188** | **1,049/1,128** | **36 / 0** |

Compared with v8, #224, #225 and #253 become complete, and #243 gains producer and title without a complete-label gain. Eleven eligible fields improve (producer 3, wine 3, country 2, region 2, subregion 1); no eligible fields or complete labels regress. No wrong-to-wrong field changes occurred among all 193 rows. This observation only covers these scored inputs. Across all resolved fields, including excluded images, v9 gives 1,067/1,152 correct. The output `AL POGGIO` in #225 is an accepted shorter reference alias; the label also prints a varietal/origin phrase. We do not infer #243's geographic fields from a title or producer address.

Two earlier exports of the same 26 owner-reviewed personal photos give exact-normalized complete 15/26 → 15/26 and 14/26 → 14/26 from v8 to v9, with zero field changes in each. The second export has two different base-field records but identical saved OCR and hints; neither is an independent holdout. With model hints removed, the diagnostic ablation is 132/188 (12 complete gains and 3 losses vs phone v6), not the deployed 138/188. Both v8 and v9 continue to use retained hints in the normal workflow.

## Changes and cautions

- Exact, independently read producer lines may acquire a uniquely adjacent generic winery suffix, such as `Family Vineyards`; arbitrary vineyard names are not suffixes. An estate and title accidentally concatenated into the producer may split only when an independently corroborated estate prefix, maker, title and recognized winemaking-method line are ordered and spatially connected. The base producer must exactly match the three printed pieces, and the wine field must be blank.
- A misassigned prominent producer may become the wine title when a different printed estate name is independently read and repeated in a nearby independently read bottling-company role. This is not ownership inference from an address. A selected estate designator leaked into the subregion can be cleared, and unsupported conflicting geography corrected, only when a separate corroborated grape-plus-regional Italian IGT is printed, a corroborated bottling company supports the estate, no standalone subregion or conflicting old country/region is visible, and the regional candidate is unique. Printed nationality aliases, including `U.S.A.`, block a conflict override.
- A standalone, independently read US AVA directly below an independently read selected grape can fill the otherwise blank origin fields using the existing UCD AVA catalog, only when the base already selected the US and no competing hierarchy exists. The app convention places the state in the country slot and the AVA in the region slot. The country context and layout are important because short AVA names may have other meanings; this is still a heuristic, not proof of origin.
- An already-selected Italian subregion, a complete independently read DOC cue, and a selected independently read grape may form a wine title in printed order when nearby and unambiguous. A competing grape or missing cue blocks the rule. Wine-name conventions differ; this and the US layout heuristic require unseen-label validation.
- Narrowed an older title-prefix rule that claimed to handle styles but also acted on arbitrary product names. It now requires a recognized style or a combination of style words, and explicitly vetoes a grape-plus-geography phrase as a prefix. This prevents a newly recovered product title from being polluted by a regional varietal line. No eligible v8 correct field is lost on the saved export.
- The Android adapter and host replay now compile the **same** catalog-index builder (`GlinerCatalogIndexes.kt`) rather than maintaining separate catalog filters. Neither builder receives scoring targets.

The new evidence rules have positive and negative structural tests for missing engines, ambiguous AVAs and maker suffixes, additional grapes, arbitrary address/company text, unrecognized methods, distant or absent cues, conflicting regions and visible country aliases. Two-engine text agreement, legal wording and layout reduce risk but do not establish legal ownership or guarantee role classification. Unread characters, conflicting identity evidence, and apparently missing vintages must not be guessed from references. Other title/designation conventions and hierarchy ambiguities remain unresolved.

## Validation and timing

- 61 host Kotlin policy tests pass; six new v9 methods contain multiple positive/negative assertions. The actual Android catalog-index adapter also compiles as part of the host replay. A full Android app build and fresh device inference remain unverified: no Android SDK/device is present here.
- 45 targeted Python tests pass, three skipped; the offline boundary check passes for 200 sources and seven entry points; host Python files pass syntax compilation. These are not a full-suite pass.
- Three host whole-batch replay wall times including JVM/Python startup: v8 0.881–1.052 s, v9 0.873–1.049 s. They are noisy host measurements, **not** Android startup, model load or per-photo latency. V9 lazily builds an additional catalog index once per process; its phone cost remains unmeasured.
- Continue local counterexample checks and genuinely unseen diverse-label evaluation before interpreting a replay gain as general accuracy. Do not require another hour-long phone run for each small change.
