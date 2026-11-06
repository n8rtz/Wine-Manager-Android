# GLiNER v10: classification of the 40 remaining incomplete photos

## Method and scope

Audit of the actual phone export `gliner-lab-1793554717926.json`, SHA-256 `e020b26a7734c33c7e05d394d449c74d658f0942e22357077df7c3108e3742d2`, build `d3c2815d553a`: 193 attempted, 188 eligible, 148 fully correct, 25 one-error and 15 multi-error photos. I inspected all 40 incomplete photos, their current scorer references and accepted alternatives, and each engine's saved readings and boxes. This is a diagnosis, not a rerun, a change to references, or validation on unseen labels. The 40 contain repeated label photos, including #10/#63, #32/#89 and #42/#61.

There are **65 failed fields** among the 40: 22 producer, 19 wine name, 7 country, 9 region, 6 subregion and 2 vintage. Assigning one *primary* cause to each failed field produces the table below. Real failures can have interacting causes; each row's notes mention important secondary issues. Counts are an informed qualitative audit, not measured estimates of what an intervention will fix.

| Code | Primary cause | Failed fields | Photos containing at least one |
|---|---|---:|---:|
| O | Required visible text missing, cut up or corrupted in the saved OCR | 32 | 27 |
| R | Relevant text is available but its role, assembly or selection fails | 8 | 8 |
| U | One-engine or identity/role ambiguity: suggest or collect more evidence, rather than assume | 5 | 5 |
| H | Recognized geographic indicator needs guarded meaning/hierarchy resolution | 4 | 2 |
| A | Required origin not explicitly established on the pictured front; needs verified product evidence or confirmation | 11 | 7 |
| D | Non-vintage year treated as vintage | 2 | 2 |
| S | Potential alias or reference/normalization issue requiring review; not established as a model error | 3 | 3 |

Field counts sum to 65. Photo counts overlap because a photo can have more than one cause. O includes near-matches and fragments; it does **not** mean higher-resolution retries will necessarily work. A does not mean the wine has no such origin—only that it is not clearly established by this picture's own wine-origin text. Do not infer a wine's origin from a producer's address. A separately identified vineyard and its wine-origin relationship can be useful evidence, but must not be confused with a winery address.

## Individual audit

| # | Failed fields and primary cause | Evidence and interpretation |
|---:|---|---|
| 10 | Wine **O** | Volcanie Rosé is in script; Paddle reads `Volcanic G`, ML reads `leanie`. The accepted short title is Volcanie; Rosé is not required by the scorer. |
| 12 | Producer **O** | La Francette: Paddle `Trancette`; ML misses the script brand. No full independent reading. |
| 32 | Wine **O** | Both read `LEO` and `by`; neither reads the second script Léo. Catalog completion alone would be an inference, not OCR agreement. |
| 36 | Producer **O** | Château La Croix is broken into partial fragments across engines; no reliable full maker text. |
| 42 | Producer **O** | Brand and `de Bordeaux` are split/overlapped with the Bordeaux appellation; ML reads `DE B` and noisy Bordeaux, Paddle misses `de`. Role assembly is a secondary risk. |
| 45 | Producer **O** | La Chapelle de Calon appears as Paddle `La Chapelle de Caler`; ML has only a partial line. |
| 61 | Producer **O** | Same brand as #42. ML does read `DE` and fragmented Bordeaux; Paddle sees La Petite, Chapelle and partial `DEAUX`, so this is incomplete/conflicting cross-engine assembly—not an assertion that neither engine saw `de`. |
| 63 | Wine **O** | Same Volcanie label as #10; Paddle `Volcanic Lasi`, ML misses the title. |
| 70 | Wine **O** | Les Cailloux Chardonnay: Paddle reads `Les` and `Caillow`, ML `Gails`; no full reliable title. |
| 71 | Wine **O** | Gamay is visible but Paddle reads `AMAY`, ML `AM`; the regional phrase wins the name field. A bounded grape candidate could be considered but must not be assumed correct. |
| 89 | Wine **O** | Same label as #32: both read `LEO` and `by`, not the final script Léo. |
| 114 | Producer **O** | Le Pas du Templier: both read `EMPLIER`, missing the stylized initial T. Two engines agreeing on the same error does not establish correctness. |
| 117 | Producer **O** | La Francette on another varietal: Paddle `Trancette`, ML misses the script brand. |
| 129 | Wine **O** | Chemin des Olivettes: Paddle `Chemin des Olivelts`, ML largely garbled. Product title is plausible but incompletely recognized. |
| 139 | Producer **O**, wine **O** | Tilted label fragments Château Guillaume Blanc and Bordeaux Supérieur; neither full target is reliably read. |
| 140 | Producer **O** | Paddle `Chateau Ameli`, ML misses the producer script; target Château Amélisse has unread suffix. |
| 169 | Producer **O**, wine **U** | La Rioja Alta is near-read only by Paddle; ML reads the founding-text line instead. ML has exact `GRAN RESERVA 904`, Paddle has `CRAN RESERVA 904`: the title has useful cross-engine approximate support but was not selected. |
| 186 | Producer **U** | Paddle `Slags Leap` and no ML maker read. The app chose Stag's Leap Wine Cellars, but the reference is Stags’ Leap; these are distinct wineries, so the near-name collision must not be auto-resolved as if interchangeable. The scorer's punctuation-stripping normalization also collapses `Stag's Leap` and `Stags' Leap` into the same key; it rejects the current longer output but could falsely accept the other winery's short name. This needs a targeted scorer identity-safety review, not a change to the frozen score here. |
| 195 | Subregion **S** | Reference accepts `Asolo`; app returns `Asolo Prosecco Superiore`, the complete legally recognized DOCG denomination. Whether the scorer should accept that full denomination or the app should map it to the geographic field warrants review; do not shorten merely to improve the benchmark. |
| 198 | Country, region, subregion **O**; vintage **D** | The seal says Serra Gaúcha; ML splits/misreads `DA SERR4` and `GAUCHA`, Paddle misses the place. Recognizing this as a wine-origin cue and resolving hierarchy is a further step. `1931` is on the seal, not established as the pictured vintage; `SAFRA` has no legible year. |
| 203 | Region **A** | Bairrada is the reference, but no readable or visibly printed region appears on the pictured front. A verified product match could offer a candidate; a winery's address is not proof of this wine's origin. |
| 205 | Producer **O** | Paddle `MariaZabel` is near Maria Izabel, with no second-engine producer read; stylized letters obscure the missing character. |
| 206 | Producer **R**; country, region, subregion **H**; vintage **D** | Both read the adjacent `NIK WEIS` and `ST. URBANS-HOF`, but only the first is retained. Saar is read by both, Mosel by Paddle and almost by ML; resolving country/region/subregion needs contextual hierarchy and a guard against treating any name as an origin. `ANNO 1947` is a crest date, not established as vintage. |
| 207 | Country, region **A** | The front says Wachtstetter/Felix/Lemberger Trocken, not Germany or Württemberg. Correct origin could be suggested through independently verified wine evidence, not an address alone. |
| 208 | Country, region **A** | The front says Weingut Aldinger/Merlot, not Germany or Württemberg. Same limitation as #207. |
| 210 | Producer, wine, subregion **O** | `Fourie` vs Fourrier, `Dieille Digne` vs Vieille Vigne, and `Geurey-Chambertin` vs Gevrey-Chambertin. Chambertin is a different place from the full expected appellation; do not casually truncate. |
| 212 | Wine **R** | The app has the independently read brand `Moonlight & Roses` as producer; that same string is an accepted wine-name alternative. `Rosé` is not printed on the front; the full reference contains it, but the accepted shorter title avoids needing to infer colour. Whether a brand may fill both roles needs general handling, not a label-specific rule. |
| 213 | Wine **U** | Paddle exactly reads `Les Baronnes`, which is an accepted short title; ML reads an overlapping garble. This is not missing the whole title, and the scorer does not require appending Rosé. Whether the single exact read is safe needs a guarded policy and adversarial tests. |
| 218 | Producer **O** | `Chateau Croixdes Pins` misses the printed `La`; OCR did not clearly recover the complete maker. |
| 220 | Producer **O**, wine **R** | Paddle alone misreads Château Jouclary as `Chiteau Souclary`; both see Cabardès and Rosé, but the app outputs only `Rose` as wine name. |
| 223 | Producer **S**, wine **R** | Both read the prominent `NEGRO` and `NICOLON`; app chooses the appellation instead of the accepted Nicolon title. Reference requires `Angelo Negro` and rejects the printed short brand `Negro`; the producer/brand alias is worth reviewing, not automatically declaring a reference error. |
| 230 | Wine **O** | Torgaio's ornate initial T is missed: Paddle reads `orgaio`, ML reads different nearby descriptive text. |
| 231 | Producer **R**, wine **O** | Both read `SANSILVESTRO`, but app assigns the garbled product script `Duta` as producer. The expected Dulcis script is not fully read by either engine. |
| 235 | Producer **O**, wine **R** | `MUSTER` / `MUSTELP` do not reliably spell Mustela. Both engines read `LANGHE` and `CHARDONNAY`, but app retains just Chardonnay. |
| 239 | Wine **R**, subregion **H** | Both read Rare, Cream, Solera and Sherry; app retains `Lustau Solera Reserva`. Jerez is absent as a separate readable word, but Sherry is a regulated designation linked to the Jerez–Sherry appellation; resolve only with a defensible designation mapping, not from an address. |
| 240 | Producer **O**, wine **U**, country/region **A** | Both see the abbreviated `T° Albala`, not the complete producer. Paddle alone reads Eléctrico; both read Fino 3 Fases. The front does not explicitly establish Spain/Andalusia; an independently verified product suggestion is possible, but an address/producer lookup alone is insufficient. |
| 242 | Region **A** | Chile is printed/read; Central Valley is not. Do not derive the wine region simply from the producer's location or assume every Chilean wine is Central Valley. |
| 243 | Country/region **A**, subregion **U** | `Cachapoal` is read within the product name `Espíritu de Cachapoal`; no separate explicit appellation is visible. The place-like title might suggest origin, but is not by itself independent proof; Chile and Central Valley are not separately printed. |
| 245 | Producer **R**, country **O**, region **A** | Both engines read prominent `GARZON`, yet app chooses prose `Yexposicion Solar`. The paragraph refers to grapes from a vineyard in Garzón, Uruguay, but OCR reads `Unuguay`/`lnguay`; interpreting the vineyard sentence as a wine-origin claim is distinct from inferring from a producer address. Maldonado is not printed and would require justified location mapping or product evidence. |
| 247 | Producer **S** | Both read `DE ANGELES` and `VIÑA 1924`. The reference requires the joined form, while sources also describe De Angeles as the bodega and Viña 1924 as a vineyard; other sources use the combined winery name. The short-brand alias versus required full brand should be reviewed. The year appears in a vineyard name, not as an established vintage. |

## Priorities suggested by this audit

1. **Targeted OCR/geometry recovery:** script, missing initials, rotated labels and split fragments dominate the primary field count. Run on image crops with a time limit; compare new raw evidence against the original and never treat two identical mistakes as two confirmations.
2. **Role and identity handling:** prefer explicitly supported brand/title/varietal candidates over administrative prose, and allow a brand to play a title role only when supported. Test similar-name producer collisions such as #186 and place-name collisions such as #210.
3. **Geography and dates:** map independently supported geographic designations through a verified hierarchy; distinguish location embedded in product names and vineyard-origin statements from winery addresses. Guard `anno`, seal and founding/vineyard dates before assigning vintage. Do not fill absent values from producer address.
4. **Review references separately:** #195, #223 and #247 merit a human decision about denomination/brand aliases. No reference or expected result has been changed by this audit. A valid alternative should be evaluated on its merits, not added merely to increase the count.

Potential fixes must be evaluated on diverse unseen labels and on regressions, not only on these cases. There is no claim that resolving all fields is possible from these images, or that the 65 field causes can be fixed independently.

## Research used for ambiguous roles

- Italian agriculture ministry rules for the Asolo denomination: https://masaf.gov.it/flex/cm/pages/ServeAttachment.php/L/IT/D/a/b/c/D.200cf641945e92216b46/P/BLOB:ID=14385/E/pdf
- Stag's Leap Wine Cellars vs Stags’ Leap Winery: https://vinepair.com/wine-blog/you-may-not-be-drinking-the-famous-stags-leap-wine-you-think-youre-drinking/
- Angelo Negro / Nicolon: https://www.xtrawine.com/products/angelo-negro-barbera-dalba-nicolon-2024
- De Angeles as bodega and Viña 1924 as vineyard: https://www.lechoixdesvins.com/de-angeles-vina-1924/47-de-angeles-gran-malbec.html ; combined producer usage: https://gismondionwine.com/tasting-notes/200908/vina-1924-de-angeles-gran-malbec-2007/?note=13408
- Jerez–Sherry designation/product: https://www.skurnik.com/sku/rare-cream-sherry-solera-reserva-e-lustau-2/
