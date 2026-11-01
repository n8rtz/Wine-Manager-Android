# GLiNER policy v10: saved-evidence development replay

## Scope and result

The unchanged reference set and saved OCR, base fields and retained model hints from the 193-photo v6 export `gliner-lab-1793542448049.json` (SHA-256 `346ee39d7b1d7fec2f19de082ec9201ef89d9655d51c255acb2d3606a9fffc4b`) are replayed through the actual Kotlin decision policy. The images were used to develop the changes. This is **not** a fresh phone run, independent validation, or a representative estimate of unseen-label accuracy.

| Identical saved evidence and references | Eligible complete | Eligible fields correct | Eligible field gains / losses vs phone v6 |
|---|---:|---:|---:|
| v6 phone / replay | 123/188 | 1,013/1,128 | — |
| v9 replay | 138/188 | 1,049/1,128 | 36 / 0 |
| v10 replay | **148/188** | **1,063/1,128** | **50 / 0** |

V10 has 25 newly complete labels and none newly incomplete vs v6. Relative to v9, ten labels become complete (#194, #201, #202, #216, #217, #222, #236, #237, #250, #255) and no complete label becomes incomplete. Eligible gains vs v6 are producer 9, wine 26, country 4, region 7, subregion 4 and vintage 0; there are no eligible field losses. The requested 150/188 is **not met**. Nothing has been added to the scoring references to force that threshold.

Two exports of the same 26 owner-reviewed personal photos remain 15/26 → 15/26 and 14/26 → 14/26 vs v9 with zero field changes in both. The exports share OCR and hints, differing only in two base-field maps; they are not independent tests. With retained model hints removed, the diagnostic replay is 141/188, including 21 newly complete and three newly incomplete compared with v6, and five individual field losses. This is an ablation, not the configured workflow.

## Policy changes

- Recover independently read maker/title roles where a separately corroborated bottling role identifies the maker. The French appellation index resolves selected AOC hierarchy with St/Saint aliases while protecting explicit conflicting origin; it is shared between the Android adapter and host replay and receives no scoring references.
- Recover selected adjacent title/varietal, regional-colour, appellation/quality, brand/product and Portuguese DO structures only under their explicit context, ordering, uniqueness and evidence guards. A single-engine quality word can be supported by a retained model span, which is **not** a second OCR vote.
- Permit a single-engine article-led cuvée between independently corroborated French AOC/legal and bottling/maker roles only when there is no overlapping other-engine reading. A quoted cuvée needs independent OCR and a known French AOC. The same-line reconstruction rule instead joins actual adjacent OCR fragments when the other engine independently supports the assembled text and maker and selected blend are corroborated; it does not invent missing words.
- `GlinerNamedSites` contains the published 181 Barolo MGA names and ten Fleurie lieux-dits, not this photo set's answers or producer identities. A single-engine exact site is eligible as a title only with the already selected appellation, corroborated legal and maker roles, spatial ordering, and no competing known-site reading or conflicting legible phrase. The other engine's garbled text in #237 is not treated as agreement. This remains a **higher-risk heuristic**: a site can be printed in another role, or a non-site single-word competing reading can be ignored. Catalog membership cannot prove provenance or title. It needs genuinely unseen and adversarial-label validation before treating the measured gain as general accuracy.
- Moved later title rules into a separate JVM method to stay below the 64 KiB method limit, without changing their execution order. This is not evidence of an Android build or device timing improvement.

## Boundaries and remaining work

Two incomplete photos (#32 and #89) show the same label, whose final script word was not read by either saved OCR engine. A linked-catalog entry exists, but selecting it despite absent OCR would infer unread characters and would count the same label twice. It is not implemented merely to hit 150. Other incomplete cases likewise have indistinct producer/title text, visible but unrecognized accent/word fragments, missing country/region cues, or ambiguous producer-versus-vineyard roles. Do not infer origin from a producer's address, a vineyard's country of operation, or a catalog entry alone.

The #195 reference accepts `Asolo` but not `Asolo Prosecco Superiore`, an official DOCG denomination; this known reference/scoring ambiguity was not changed in the reported comparison. See `docs/gliner-policy-v9-evaluation.md` and `docs/gliner-policy-v8-evaluation.md`. The app's full denomination is retained rather than truncated to improve the score.

## Checks and limitations

- 64 host Kotlin policy tests pass, including v10 positive/adversarial cases for independent fragment assembly, differing OCR, missing role/legal cues and competing known sites. These tests do not establish false-positive rates for natural labels. The actual catalog-index adapter also compiles in the host replay.
- 66 targeted Python tests ran successfully, three skipped; the offline recognition boundary check passes for 201 local sources and seven scan entry points. Host Python scripts compile. This is not a full-suite pass.
- No Android SDK or phone is available here. Android app compilation, device inference, memory, model-load time and single-scan latency are **unverified**; saved-OCR host replay timing is not a device measurement. An unseen, diverse-label evaluation is still needed. No fresh hour-long phone run is requested for this local iteration.
