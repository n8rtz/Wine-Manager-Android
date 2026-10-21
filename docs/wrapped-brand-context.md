# Wrapped brand assembly — identity-v6-wrapped-brand

User-confirmed intake convention for the new sample: producer/brand **Altos de Valdoso**, wine name **Crianza**, Spain / Ribera del Duero / blank Subregion. The photo shows 2019 but the saved OCR only supplies `019`; the latter remains blank. Photo truth is not injected into extraction.

## General rules

- A glued trailing connector is split/deduplicated only when another engine actually supplies the prefix and separate connector, with compatible geometry. The line text changes, but its original tokens, member strings, scores and boxes do not.
- An unknown brand can span two aligned neighboring lines when the first ends in de/du/di, the second has one unambiguous identity continuation, and a separate unambiguous designation appears below it. Paragraph, legal, packaging, varietal/appellation and reserve-title cues are excluded. A separate explicit producer cue blocks this interpretation. This is a confirmation-required producer/**brand** convention, not proof of a legal winery identity.
- The joined brand remains below exact local producer evidence in ranking. Its fragments are not simultaneously reused as the wine name. The separate designation receives a supported name candidate; a geographic appellation cannot gain an equal competing name merely by appending that designation in this context.
- The exact-core Rhône suffix rule now allows `vi…` rather than requiring `vil…`, retaining its 5–8-character, three-edit bound. This handles `VIILAS`; it does not infer Villages from a bare Côtes du Rhône or broaden general fuzzy matching.

## Latest five-image replay

`brand-review.json` retains text, scores and boxes from batch `d1f345f1-b844-46b0-ba3c-4bdc645faeaf`, not the photo. All six fields for all five images are asserted through the combined production-rule preview adapter:

1. Kirkland Signature / Brunello di Montalcino / Italy / Tuscany / Montalcino / 2021
2. ALTOS de VALDOSO / Crianza / Spain / Ribera del Duero / blank / blank
3. Vignerons DE L'ENCLavE / LA Reserve de L'ENCLavE / France / Rhône / Côtes du Rhône Villages / 2022
4. Blackstone / Merlot / California / blank / blank / blank
5. Bodegas Olarra / RECIENTE Gran Reserva / Spain / Rioja / blank / 2019

Casing reflects actual OCR. Brand assembly and designation suggestions remain YELLOW. No bottle-name hardcoding, year completion, new OCR pass, model change, or normal confidence threshold change is used.

## Validation

Executed host tests cover synthetic unrelated brand names, missing or competing continuations/designations, misalignment/distance, separate producer cues, reserve titles, geography negatives, split-versus-joined cross-engine evidence, immutable raw strings/scores, and the Rhône suffix negative control. The three older six-image adapter replays and existing extraction tests also run. All 60 Python tests and the offline-recognition boundary check pass.

No Android APK/Compose compilation, instrumentation/Robolectric, emulator, new device timing or photo OCR was executed. Saved-reading host replays use the fixture/reference KB, not the installed user's cellar DB, and do not establish unseen-label accuracy.
