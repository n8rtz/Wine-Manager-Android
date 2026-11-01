# GLiNER policy v8: saved-evidence development evaluation

## Scope and limits

This is a local replay of the actual Kotlin policy, **not a new phone run**. Input is the saved base fields, original per-engine decision readings and retained model hints from `gliner-lab-1793542448049.json` (SHA-256 `346ee39d7b1d7fec2f19de082ec9201ef89d9655d51c255acb2d3606a9fffc4b`): 193 attempted, 188 eligible. The 188-photo scoring references are unchanged from v7. OCR, model inference, and base extraction are not rerun. V8 was designed using these cases; the replay is in-sample development, **not unseen-holdout accuracy**. The policy only affects the experimental GLiNER workflow.

## Paired results

| Saved evidence | Eligible complete | Eligible correct fields | Eligible field gains / losses vs phone v6 |
|---|---:|---:|---:|
| Phone v6 / exact Kotlin replay | 123/188 | 1,013/1,128 | — |
| v7 Kotlin replay | 129/188 | 1,025/1,128 | 12 / 0 |
| v8 Kotlin replay | **135/188** | **1,038/1,128** | **25 / 0** |

Compared with v7, six further labels become complete (#200, #204, #211, #219, #233, #234); no eligible complete label is lost. Additional eligible field gains are producer 1, wine 7, country 1, region 2, subregion 2, vintage 0; no eligible field losses. Relative to the v6 phone output the gains are producer 4, wine 13, country 2, region 4, subregion 2, vintage 0. One additional resolved field on an excluded image improves (#226 country), for 1,056/1,152 resolved fields overall. Wrong-to-wrong changes are not scored as gains or losses. The independent engine readings may still share the same visual ambiguity.

The previously reviewed 26-photo personal audit is a separate regression check, but was also used in prior iterations: v7 and v8 both produce 15/26 exact-normalized complete, with **zero field changes**. The older 124-photo exports `1793415677173` and `1793410885896` share identical saved base fields, decision readings, raw readings and lane hints with the same 124 photos in the current export. They therefore provide no independent additional evidence. A no-hints ablation produces 129/188, with nine new complete and three lost vs the v6 phone output; it removes a real deployed input and is not the deployed result.

## Generalized policy changes

- Bounded classification/cuvée assembly, fortified type plus designation, and separately printed wine-style recovery use independently corroborated text, legal/layout role cues and candidate uniqueness. A known style embedded between two already-selected, corroborated title fragments can be restored only when the two outside fragments exactly reconstruct the selected title.
- A high-confidence model-confirmed maker/title split requires a two-word maker hint, independent maker/title reads, a separate bottling role and a style appellation. It abstains on weak hints or competing titles. It does not assert legal corporate ownership.
- A place/style/quality/DOCG sequence can reconstruct an observed compound appellation without truncating its subregion. Quoted Premier Cru names require a model-confirmed appellation, an independently corroborated title, matching regulatory text, geometry and uniqueness; the standard printed abbreviation `1er Cru` is rendered as `Premier Cru`.
- The ordinary bundled `ReferenceWineCatalog`, not benchmark targets, provides an exact-alias index of Italian appellations having one parent region. An independently corroborated printed place must be immediately tied to independently corroborated DOC wording, with no intervening line. Same-alias competing hierarchies abstain. The rule does not use a producer address as origin, does not overwrite a visibly supported conflicting country/region, and only replaces an existing conflicting subregion when that value is separately printed immediately above the place as a recognized wine style. This resolves one observed Italian style versus Greek appellation collision; these guards cannot prove semantic intent in every layout.
- A selected child of an existing hierarchy may be fuzzy-corroborated only by both original engines, anchored by a matching selected exact region and country, with blank subregion and uniqueness. No wine region is inferred from winery address.

Synthetic positive and negative checks cover missing OCR engines, ambiguous titles or locations, low/missing model hints, intervening/distant lines, marketing and serving text, conflicting printed origins, differing appellation hierarchies, producer addresses, and a conflicting non-style subregion. These are targeted guard tests, not a substitute for unseen scans. A genuine title or geographic alias can still resemble marketing text or a style, and catalog coverage, OCR disagreements, compounded place names and nationality aliases remain limitations.

## Reference caveat: #195

The frozen reference accepts `Asolo` as subregion but the app's `Asolo Prosecco Superiore` describes a legitimate full denomination. The [Italian ministry decree](https://masaf.gov.it/flex/cm/pages/ServeAttachment.php/L/IT/D/a/b/c/D.200cf641945e92216b46/P/BLOB:ID=14385/E/pdf) recognizes Asolo–Prosecco DOCG and the superiore variant. The wine-name field improves, but the case stays incomplete under the unchanged frozen reference. We did **not** shorten the app's output or silently change the reference to gain a point. The scoring/reference alias deserves separate review.

Other unresolved failures include insufficient readable identities and vintage-role evidence, potential title/designation convention disagreements, and conflicting or incomplete geography. In particular #186 is not safe to infer from a similar producer name, and a founding year must not be promoted to a vintage without a suitable role cue.

## Validation

- 55 host Kotlin policy tests pass, including nine v8 structural positive/negative tests. Host compilation includes the actual decision policy and the ordinary bundled reference catalog. No Android SDK/device was available; app integration and device compilation remain unverified here.
- 45 targeted Python tests pass (three skipped), the offline-recognition boundary check passes for 199 sources and seven entry points, and the replay/audit Python files compile. Some unrelated older corpus tests have stale source/layout assertions; this is not a claim that the full suite passes.
- Three host wall-clock replays of all 193 rows including Python/JVM startup: v7 0.892–0.976 s and v8 0.838–1.020 s. These noisy batch measurements do **not** measure Android startup, per-photo latency, model load or fresh inference. The v8 catalog index is lazily initialized once per process; its device cost has not been measured.
- Existing phone-v6 timings and exact v6 reconstruction are in `docs/gliner-run-1793542448049-analysis.md`. None of these replay results validate a new device run. Further local counterexamples and a genuinely unseen, diverse holdout remain important; avoid imposing another hour-long phone run for every small iteration.
