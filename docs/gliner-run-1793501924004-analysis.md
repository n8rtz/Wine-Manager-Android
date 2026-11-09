# GLiNER curated run: analysis and improvement plan

Source: `gliner-lab-1793501924004.json` (SHA-256 `35a13b0fbe969c65b079eb7767e1f01ec8981bee52f8d782e9776b9894609824`), build `34a3c5bcd226`, policy v5, GLiNER-Small INT4, threshold 0.50, width 12. The export contains 193 curated results and no personal-image results. It does not contain per-photo Lab v7 or Prod v6 baseline predictions, so it cannot establish a head-to-head win over those pipelines. It does contain GLiNER's own pre-decision base fields on the same OCR inputs.

## Results

- 193 attempted; all 193 executed successfully. **114/188 eligible labels fully correct** (60.6%); 37 have one error and 37 have multiple errors. The other 5 have at least one unresolved reference and cannot count toward the whole-label denominator; their resolved fields remain scored.
- Correct resolved fields: **1,011/1,153**; among the fully eligible labels alone **994/1,128**. These are different denominators from whole-label accuracy.
- Original 124: **104/122** eligible fully correct. Six Costco photos: **1/5**. X-Wines: **9/61**. Combined additions: **10/66**. The sets differ in origin, label composition and reference conventions; this split points to generalization problems but does not isolate a cause.

| Field | Correct / resolved | Incorrect | Outcome breakdown |
|---|---:|---:|---|
| Producer | 161/193 | 32 | 32 wrong |
| Wine name | 142/192 | 50 | 44 wrong, 6 missing |
| Country | 180/193 | 13 | 10 missing, 3 wrong |
| Region | 168/191 | 23 | 14 missing, 9 wrong |
| Subregion | 173/192 | 19 | 12 missing, 5 wrong, 2 unsupported fills |
| Vintage | 187/191 | 4 | 1 wrong, 3 unsupported fills |

The 37 one-error eligible labels comprise 25 wine-name-only, 10 producer-only, 1 region-only, and 1 subregion-only. Fixing all 37 without regressions would give a *mathematical ceiling of 151/188 from that group alone*, not a forecast or a promise. Many other wines need multiple fixes. Repeated photographs of the same or related labels mean these are not 37 independent problems.

## What caused the difference from the base?

The export's same-OCR comparison is **90/188 base → 114/188 final**, 24 label gains and no whole-label losses. On the eligible set there are 12 producer field gains, 20 wine-name gains and 1 subregion gain, with one wine-name field loss (#242). The loss did not turn a previously complete label incomplete because #242 was already incomplete. This compares the GLiNER workflow's base extractor and its GLiNER-specific decision policy on the *same saved OCR*, not an actual Lab v7/Prod v6 run. Replaying the current Kotlin decision policy on the export's saved raw readings and lane scores reproduced 114/188 with zero field-score changes relative to the exported final. No OCR, ONNX inference, or phone run was repeated.

101/193 photos produced **no accepted lane hints** at threshold 0.50; 10 of the 24 improved complete labels had no hints at all. Much of the improvement therefore comes from deterministic, evidence-driven rules in the GLiNER decision stage, not necessarily from the neural model. Three decision traces explicitly mention a GLiNER/model-confirmed decision (#86, #173, #186), though trace wording alone cannot establish every model influence. Do not describe all +24 as a GLiNER model gain. Test model contribution via a no-hints ablation on identical saved OCR, and rerun the model for any threshold/prompt change: retained hints and top probes are not a complete low-threshold candidate set.

## Major error patterns

### 1. Wine-name assembly and role separation: highest whole-label opportunity

- #197: `ORIGEM` + `MERLOT` are visible but only `Merlot` is returned. #199 `DONA ANTONIA` is dropped; #204 returns `Douro` instead of `Lacrau Field Blend`. #259 returns `Private Reserve` and drops `Riesling`.
- #191 prepends producer `Groth` to the wine; #194 returns a list of grapes instead of `Côte des Roses`; #195 misses `Asolo Prosecco Superiore` and puts the long designation in subregion.
- Existing title completion can contaminate output: #242 changes a previously accepted `Las Mulas Sauvignon Blanc` to `VINO ORGANICO & VEGANO Las Mulas. SAUVIGNON BLANC`; #224 similarly prepends the legal phrase `DENOMINAZIONE DI ORIGINE CONTROLLATA` to the grape. #201 appends `VINHO VERDE` and is scored wrong, although that particular reference needs separate synonym review.
- #207 and #208 show separate title, grape and style lines; #211, #216, #219, #236, #237, #250 show an appellation plus a specific cuvée/vineyard/classification. Do not assume every adjacent line is a title.

### 2. Geography has both recognition and hierarchy problems

- #241 and #244 print `Valle de Colchagua` and `Valle del Maule`, but region/subregion are blank. #249, #254 and #255 recognize a specific place but put it in `region` instead of placing it under the expected broader region. #234 reads `Vin Santo del Chianti Classico` but picks Greece / Aegean Islands from `Vinsanto` instead of the printed Italian designation. #225 reads `Toscana` and `Italy` yet outputs Spain / Valencia.
- #198 shows `Serra Gaúcha`, although OCR fragments it as `SERR4` / `GAUCHA`; #253 OCR splits `Lodi` into `Lod i`/`L o d i`. A narrowly bounded spatial join plus vetted origin lookup may help, but do not infer a place from a company address or an uncorroborated substring.
- Some references need review *before* any score claims: #213 `Loire` vs `Loire Valley`, #218 `Rhone` vs `Rhône Valley`, #221 `Chablis Premier Cru` vs `Chablis`, and #228 `Trentino-Alto Adige` / `Alto Adige` vs `Alto Adige` / blank involve synonyms or alternative hierarchy granularity. Do not simply declare every such result correct, nor hard-code answers; agree a consistent representation and accepted exact aliases. #249/#254 may likewise be informative specific-region outputs even though they fail the current two-field hierarchy.
- **Reference review correction needed:** I previously marked #240's `Montilla-Moriles` as expected even though it does not appear on its photographed front. This conflicts with the user's front-label rule and must be adjudicated as expected blank if the photo remains the reference. With the observed blank, that would change one subregion field outcome but would not by itself make #240 fully correct. #239's `Jerez` should also be checked carefully: it appears only in very small lower-label context alongside `Sherry`, and an address must not automatically become an appellation. Do not change these references solely to improve a score. Any approved reference correction needs a new signature and a separately reported rescore.

### 3. Producer identity, OCR repair and role confusion

- #186 confuses the distinct `Stags’ Leap` producer with `Stag's Leap Wine Cellars`; #192 incorrectly joins `Karia` to the latter producer. Any producer repair must protect identity conflicts and avoid attaching the product name to a winery.
- #42/#61 show the same producer `La Petite Chapelle de Bordeaux` split across lines; #12/#117 `La Francette` appears as `Trancette`; #114 `Templier` loses `T`; #140 truncates `Amélisse`. These need corroborated, unique context-aware repair, not arbitrary nearest-neighbor completion.
- #225 chooses product `Al Poggio` as producer despite `CASTELLO DI AMA` being read; #224 chooses an Italian recycling/legal line; #245 picks text from a long paragraph despite `GARZON`. Use role and layout evidence to prevent product/address/legal text from outranking a credible producer. #205 `Mariazabel` vs `Maria Izabel` and #208 `Weingut Aldinger` vs `Aldinger` also merit an independent accepted-name review.

### 4. Vintage disambiguation

#192 selects `1970` (explicitly `ESTABLISHED`) over the printed `2024`. #198 takes `1931`; #206 takes `ANNO 1947`; #247 treats vineyard designation `Viña 1924` as vintage. Use local context, not a blanket ban on older years: reject establishment/founding and vineyard-name contexts; prioritize a supported current vintage candidate; allow a blank when no vintage is printed. Include difficult regressions with real old vintages and split dates.

### 5. Model/OCR limits and performance

Both engine types appear in raw readings on 192/193 photos, but that alone does not mean their readings agree. Some targets are cleanly present (#197, #241, #244); others are partial or noisy (#10, #36, #210), so measure evidence availability before assuming a decoder fix can recover them. The model emitted at least one retained hint on 92/193; low coverage can reflect prompts, threshold, OCR content, language, or model limits—not proof of a tokenizer defect. Test these separately.

Per-image logged mean/median: OCR **4.75/4.75 s**, model inference **0.105/0.092 s**, post-processing **2.35/1.82 s**, total **7.27/6.65 s**. First-result wall time was **44.18 s**, including **2.40 s** model session initialization; first photo total was 41.77 s. These are logged device measurements, not a controlled latency comparison; the sum of image durations is not necessarily full wall time. The model inference itself is small relative to OCR and extraction, so prioritize accuracy/evidence and cold-path profiling before swapping to a bigger model.

## Proposed implementation sequence

0. **Reference audit and freeze.** Review #240, #239, equivalent place names/hierarchies and producer/name alternatives with label images and source evidence, independent of current predictions. Keep unresolved text unresolved; log each decision; preserve the original 124; version the scoring signature and present before/after scores separately. Do not silently change reference criteria while tuning.
1. **Fix safe title contamination, then bounded title completion.** Separate marketing/legal/organic phrases and winery names from wine title candidates; fix #242/#224 without regressing #174/#178/#249/#251/#253 wins. For title + grape/style, use independent OCR, reading geometry, role classification, and unique candidates. Start with #197/#199/#204/#259 and one-error wine cases, then inspect multi-field cases. Reject ambiguous joins and producer/geography-only prefixes. One module at a time; do not tune by hard-coding label numbers or answers.
2. **Geographic aliases and coherent hierarchy.** First verify catalog entries and parser behavior for `Valle de Colchagua`, `Valle del Maule`, `Clare Valley`, `Dry Creek Valley`, `Horse Heaven Hills`; canonicalize unambiguous printed forms and map to a consistent parent/subregion chain. Establish country and product/place context before candidate selection; add negative tests for `Vin Santo` vs Greek `Vinsanto`, `Castello` vs Spain, cross-regional Delle Venezie, and legal/address lines. Track region and subregion jointly; require a genuine trusted parent, otherwise abstain.
3. **Role-aware producer recovery.** Detect complete producer line/continuations, prevent cuvée and legal-text contamination; use constrained near matching only with a uniquely supported identity and geography. Explicitly protect #186 versus #192, #224/#225/#245, and the existing strong complete labels. Where evidence is only ambiguous (e.g. #36), leave unchanged rather than fabricate.
4. **Vintage context.** Separate founding, classification and named-vineyard dates from a vintage using neighboring words and geometry; prefer directly supported vintage #192; leave blank for #198/#206/#247. Test against valid 19xx and 20xx vintages and no-vintage wines, including all formerly correct photos.
5. **Model contribution investigation.** On saved exact OCR, ablate lane hints versus policy with hints, comparing each field, whole labels, and regression lists. Inspect multilingual prompt/entity coverage and threshold calibration using new model inference on-device or compatible host; do not extrapolate from top probes. Keep INT4 and the existing phone size limits; a model or prompt change must prove accuracy and latency on-device.
6. **Only then address weak OCR.** If target text is absent from both engines, profile crop/localization, orientation, contrast and targeted alternate OCR reads under a strict time/memory budget; do not use the scorer reference to drive OCR. Retry only where needed, preserving the untouched reading and diagnostic provenance.

### Measurement and guardrails

- Establish a frozen, versioned starting reference and saved-input replay parity. Each candidate change gets an isolated before/after replay on the exact 193 exported photos, with per-field gains/losses and eligible complete labels, plus resolved fields on the 5 ineligible rows; no score-only aggregate claims.
- Report old 124 and new 69 separately; do not combine improvements from the same label/photo family as independent tests. These images, including the X-Wines data also represented in the producer index, have influenced development and are not a clean holdout. The previously used 26 personal photos also influenced v5 and are not a fresh holdout.
- Confirm winners with a **new phone run** from fresh pixels, preserving selected Lab modules and scoring signature; compare Lab v7 and Prod v6 only on byte-matched photos actually run, with build, OCR method and coverage shown. Do not claim this export contains those values. Account for separate OCR paths. Include cold first-scan latency, median/p90, memory and failures.
- Use a genuinely unseen set of distinct wines/labels where possible, with no reference values in extraction or tuning. Avoid broad scoring relaxation to inflate results. Set success criteria before implementation: positive net eligible full-label improvement, no unreviewed regressions on previously correct labels, and field-level error reduction without a material cold-scan penalty. Any unavoidable loss must be reviewed explicitly, not hidden by a higher total.

## Per-photo error inventory

`E` means fully eligible; `U` means partly unresolved. These are raw observed values versus current accepted references, not proposed hard-coded replacements. Exact-match alternatives may make a displayed canonical difference correct, so only recorded wrong/missing/unsupported outcomes are listed.

| # | Eligibility | Field: observed → reference (outcome) |
|---:|:---:|---|
| 10 | E | **wine_name**: Languedoc → Volcanie Rosé (wrong_value) |
| 12 | E | **producer**: Trancette → La Francette (wrong_value) |
| 32 | E | **wine_name**: Fronsac → Léo by Léo (wrong_value) |
| 36 | E | **producer**: Hateau → Château La Croix (wrong_value) |
| 42 | E | **producer**: La Petite CHAPELLE → La Petite Chapelle de Bordeaux (wrong_value) |
| 45 | E | **producer**: La Chapelle de Caler → La Chapelle de Calon (wrong_value) |
| 61 | E | **producer**: La Petite CHAPELLE → La Petite Chapelle de Bordeaux (wrong_value) |
| 63 | E | **wine_name**: Languedoc → Volcanie Rosé (wrong_value) |
| 70 | E | **wine_name**: Chardonnay → Les Cailloux Chardonnay (wrong_value) |
| 71 | E | **wine_name**: Cotes du Tarn → Gamay (wrong_value) |
| 89 | E | **wine_name**: Fronsac → Léo by Léo (wrong_value) |
| 114 | E | **producer**: Le Pas du Emplier → Le Pas du Templier (wrong_value) |
| 117 | E | **producer**: Trancette → La Francette (wrong_value) |
| 129 | E | **wine_name**: Languedoc → Chemin des Olivettes (wrong_value) |
| 139 | E | **producer**: Guillaume Blang Chate de Chen → Château Guillaume Blanc (wrong_value); **wine_name**: Bordeaux → Bordeaux Supérieur (wrong_value) |
| 140 | E | **producer**: Chateau Ameli → Château Amélisse (wrong_value) |
| 169 | E | **producer**: Bodegas Fundadas en 1890 → La Rioja Alta (wrong_value); **wine_name**: Rioja → Gran Reserva 904 (wrong_value) |
| 176 | U | **wine_name**: Rioja → Reciente Gran Reserva (wrong_value) |
| 186 | E | **producer**: Stag's Leap Wine Cellars → Stags’ Leap (wrong_value) |
| 191 | E | **wine_name**: Groth Sauvignon Blanc → Sauvignon Blanc (wrong_value) |
| 192 | E | **producer**: Stag's Leap Wine Cellars Karia → Stag's Leap Wine Cellars (wrong_value); **vintage**: 1970 → 2024 (wrong_value) |
| 194 | E | **wine_name**: Grenache - Syrah - Cinsault → Côte des Roses Rosé (wrong_value) |
| 195 | E | **wine_name**: (blank) → Asolo Prosecco Superiore (missing_value); **subregion**: Asolo Prosecco Superiore → Asolo (wrong_value) |
| 196 | U | **country**: (blank) → California (missing_value) |
| 197 | E | **wine_name**: Merlot → Origem Merlot (wrong_value) |
| 198 | E | **country**: (blank) → Brazil (missing_value); **region**: (blank) → Rio Grande do Sul (missing_value); **subregion**: (blank) → Serra Gaúcha (missing_value); **vintage**: 1931 → (blank) (unsupported_fill) |
| 199 | E | **wine_name**: Reserva Tawny → Dona Antonia Reserva Tawny (wrong_value) |
| 200 | E | **wine_name**: Porto → Fine Ruby Porto (wrong_value) |
| 201 | E | **wine_name**: MARE ALTA VINHO VERDE → Maré Alta (wrong_value) |
| 202 | E | **producer**: Alem → Azueira (wrong_value); **wine_name**: (blank) → Além do Rio Branco (missing_value) |
| 203 | E | **region**: (blank) → Bairrada (missing_value) |
| 204 | E | **wine_name**: Douro → Lacrau Field Blend (wrong_value) |
| 205 | E | **producer**: Mariazabel → Maria Izabel (wrong_value) |
| 206 | E | **producer**: Nik Weis → Nik Weis St. Urbans-Hof (wrong_value); **country**: (blank) → Germany (missing_value); **region**: (blank) → Mosel (missing_value); **subregion**: (blank) → Saar (missing_value); **vintage**: 1947 → (blank) (unsupported_fill) |
| 207 | E | **wine_name**: (blank) → Felix Lemberger Trocken (missing_value); **country**: (blank) → Germany (missing_value); **region**: (blank) → Württemberg (missing_value) |
| 208 | E | **producer**: Weingut Aldinger → Aldinger (wrong_value); **wine_name**: Trocken Reserve → Merlot Reserve Trocken (wrong_value); **country**: (blank) → Germany (missing_value); **region**: (blank) → Württemberg (missing_value) |
| 210 | E | **producer**: Domaine Fourie → Domaine Fourrier (wrong_value); **wine_name**: Dieille Digne → Gevrey-Chambertin Vieille Vigne (wrong_value); **subregion**: Chambertin → Gevrey-Chambertin (wrong_value) |
| 211 | E | **wine_name**: Pommard → Pommard Grand Clos des Épenots Premier Cru (wrong_value) |
| 212 | E | **producer**: Moonlight → Moonlight & Roses (wrong_value); **wine_name**: Coteaux d'Aix-en-Provence → Coteaux d'Aix-en-Provence Rosé (wrong_value) |
| 213 | E | **wine_name**: Sancerre → Sancerre Les Baronnes Rosé (wrong_value); **region**: Loire → Loire Valley (wrong_value) |
| 216 | E | **wine_name**: Fleurie → Fleurie La Madone (wrong_value) |
| 217 | E | **wine_name**: (blank) → Nuits-Saint-Georges Les Plateaux (missing_value); **subregion**: Romanee-Conti → Nuits-Saint-Georges (wrong_value) |
| 218 | E | **producer**: Chateau Croixdes Pins → Château La Croix des Pins (wrong_value); **region**: Rhone → Rhône Valley (wrong_value) |
| 219 | E | **wine_name**: 1er Cru "les Fuees" → Chambolle-Musigny Les Fuées Premier Cru (wrong_value) |
| 220 | E | **producer**: Chiteau Souclary → Château Jouclary (wrong_value); **wine_name**: " Rose" → Cabardès Rosé (wrong_value) |
| 221 | E | **subregion**: Chablis Premier Cru → Chablis (wrong_value) |
| 222 | E | **wine_name**: Piemonte → Piemonte Rosato (wrong_value) |
| 223 | E | **producer**: Negro → Angelo Negro (wrong_value); **wine_name**: Barbera d'Alba → Nicolon Barbera d'Alba (wrong_value) |
| 224 | E | **producer**: Non Disperdere il Vetho Nell'Ambiente -L 12.191 Cascina → Cascina Ballarin (wrong_value); **wine_name**: DENOMINAZIONE DI ORIGINE CONTROLLATA NEBBIOLO → Langhe Nebbiolo (wrong_value) |
| 225 | E | **producer**: Al Poggio → Castello di Ama (wrong_value); **wine_name**: (blank) → Al Poggio Chardonnay (missing_value); **country**: Spain → Italy (wrong_value); **region**: Valencia → Tuscany (wrong_value); **subregion**: Castello → (blank) (unsupported_fill) |
| 226 | U | **country**: (blank) → Italy (missing_value); **subregion**: (blank) → Delle Venezie (missing_value) |
| 227 | E | **country**: (blank) → Italy (missing_value); **region**: (blank) → Puglia (missing_value) |
| 228 | E | **region**: Trentino-Alto Adige → Alto Adige (wrong_value); **subregion**: Alto Adige → (blank) (unsupported_fill) |
| 230 | E | **wine_name**: Toscana → Torgaio (wrong_value) |
| 231 | E | **producer**: Duta → San Silvestro (wrong_value); **wine_name**: Moscato d'Asti → Dulcis Moscato d'Asti (wrong_value) |
| 233 | E | **producer**: Solatio → Roberto Sarotto (wrong_value); **wine_name**: Moscato d'Asti → Solatio Moscato d'Asti (wrong_value) |
| 234 | E | **wine_name**: Belcaro del Chianti Classico → Belcaro Vin Santo del Chianti Classico (wrong_value); **country**: Greece → Italy (wrong_value); **region**: Aegean Islands → Tuscany (wrong_value); **subregion**: Vinsanto → Chianti Classico (wrong_value) |
| 235 | E | **producer**: Muster → Mustela (wrong_value); **wine_name**: Chardonnay → Langhe Chardonnay (wrong_value) |
| 236 | E | **wine_name**: Riserva → Barolo Riserva (wrong_value) |
| 237 | E | **wine_name**: Barolo → Cerequio Barolo (wrong_value) |
| 238 | U | **wine_name**: Vendemmia Fiano del → Fiano del Salento (wrong_value); **region**: (blank) → Puglia (missing_value); **subregion**: (blank) → Salento (missing_value) |
| 239 | E | **wine_name**: Lustau Solera Reserva → Rare Cream Solera Reserva (wrong_value); **subregion**: (blank) → Jerez (missing_value) |
| 240 | E | **producer**: Electrico → Toro Albalá (wrong_value); **wine_name**: Pedro Ximenez → Eléctrico Fino 3 Fases (wrong_value); **country**: (blank) → Spain (missing_value); **region**: (blank) → Andalusia (missing_value); **subregion**: (blank) → Montilla-Moriles (missing_value) |
| 241 | E | **region**: (blank) → Central Valley (missing_value); **subregion**: (blank) → Colchagua Valley (missing_value) |
| 242 | E | **wine_name**: VINO ORGANICO & VEGANO Las Mulas. SAUVIGNON BLANC → Las Mulas Reserva Sauvignon Blanc (wrong_value); **region**: (blank) → Central Valley (missing_value) |
| 243 | E | **producer**: Chateau los Boldos Espiritu de Cachapoal → Château Los Boldos (wrong_value); **wine_name**: (blank) → Espíritu de Cachapoal Brut (missing_value); **country**: (blank) → Chile (missing_value); **region**: (blank) → Central Valley (missing_value); **subregion**: (blank) → Cachapoal Valley (missing_value) |
| 244 | E | **region**: (blank) → Central Valley (missing_value); **subregion**: (blank) → Maule Valley (missing_value) |
| 245 | E | **producer**: Yexposicion Solar, → Garzón (wrong_value); **country**: (blank) → Uruguay (missing_value); **region**: (blank) → Maldonado (missing_value) |
| 246 | E | **wine_name**: Malbec → Los Cardos Malbec (wrong_value) |
| 247 | E | **producer**: De Angeles → De Angeles Viña 1924 (wrong_value); **vintage**: 1924 → (blank) (unsupported_fill) |
| 248 | E | **wine_name**: Landing Cabernet Sauvignon → Cabernet Sauvignon Shiraz (wrong_value) |
| 249 | E | **region**: Clare Valley → South Australia (wrong_value); **subregion**: (blank) → Clare Valley (missing_value) |
| 250 | E | **wine_name**: Cabernets → Rocky Gully Cabernets (wrong_value) |
| 253 | E | **producer**: Maggio → Maggio Family Vineyards (wrong_value); **country**: United States → California (wrong_value); **region**: (blank) → Lodi (missing_value) |
| 254 | E | **region**: Dry Creek Valley → Sonoma County (wrong_value); **subregion**: (blank) → Dry Creek Valley (missing_value) |
| 255 | E | **wine_name**: Syrah Reserve → Coyote Canyon Reserve Syrah (wrong_value); **region**: Horse Heaven Hills → Columbia Valley (wrong_value); **subregion**: (blank) → Horse Heaven Hills (missing_value) |
| 257 | E | **producer**: Gobelsburg → Schlosskellerei Gobelsburg (wrong_value); **region**: Osterreich → Niederösterreich (wrong_value) |
| 259 | E | **wine_name**: Private Reserve → Private Reserve Riesling (wrong_value) |
