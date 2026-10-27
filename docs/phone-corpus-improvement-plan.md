# Plan to exceed 80% complete forms on the curated phone corpus

> **Historical analysis (superseded screen):** The 168-photo `phone-corpus-v1` debug page these measurements came from was retired on 2026-10-26 (see `docs/phone-corpus-benchmark.md`). The live benchmark is the 125-label **Large image dataset test**, where Prod v1.0/v2.0/v3.0 are captured once and frozen and only Lab v4 re-runs, so a "production-v2 comparator" on that screen is a frozen record, not a fresh run.
>
> This plan and its OCR metrics refer to the previous 107-photo selection. At the owner’s request, review #14 is now excluded because Club des Sommeliers is branding rather than an established producer; the active set is 106 images (105 eligible, one partly unresolved). Re-run OCR before using the historical counts below as a current baseline.

## Target and evidence

The current frozen curated set contains 107 included photos. One form has an unresolved reference and is not scoreable, leaving **106 eligible forms**. This plan is grounded in the latest supplied **OCR Text Lab** run (`build=7b69cedff5bf`, device `CPH2583`), not in the older frozen baseline or a production-only result. That Lab export scored **22/106 complete (20.8%)**: 37 forms have exactly one field error and 47 have two or more. A strict result above 80% therefore requires at least **85/106 complete forms**—a net gain of 63 complete forms from the current Lab result. Correct blanks count as correct; forms and fields with unresolved references remain outside the score.

Current exact-field error counts on the 106 eligible forms are wine name 48, producer 47, subregion 30, region 26, country 11, and vintage 4. Errors overlap: wine name + subregion co-occur on 21 forms; wine name + producer on 20; region + subregion on 19; wine name + region on 17. These are intersections, not independent recoverable cases. Do not add field counts to estimate the number of forms that can be made complete.

The latest export also had 431 correct values, 49 wrong values, 108 missing values, 43 correct blanks, 10 unsupported fills, and 1 unresolved field. For wine name specifically, among 105 expected values there were 58 correct, 27 wrong, and 20 missing; one expected blank received an unsupported fill, and one unresolved reference was filled but is unscored. The same-run production-v2 comparator had 57 correct, 27 wrong, and 21 missing expected wine names. This small gain is not evidence that a broad change will generalize.

## Guardrails

- Preserve the frozen 107-photo inclusion list and its workbook provenance. Do not send excluded photos to OCR or tune the denominator after seeing predictions.
- Keep the six field outcomes separate and score only against frozen references. Expected blanks receive correct-blank credit; unresolved references are N/A, not misses.
- Keep benchmark correctness separate from runtime/process gates. A successful OCR process is not proof of a correct field.
- Make no acceptance decision from confidence, field fill rate, OCR execution success, or a single easier subset. Do not weaken abstention or add timing/model budgets as a shortcut.
- Any updated expected answer needs image-level human review and an audit trail; structured wine databases may cross-check metadata, but do not independently establish what a photographed label says.

## Work plan

### 1. Freeze and diagnose the baseline

Keep the supplied scored and raw CSVs, their build/device metadata, and a machine-readable derived report together. Confirm row IDs, image IDs, expected-value/expected-blank/unresolved states, and that all 107 included photos have one image summary. Recompute metrics from per-form six-field outcomes, not row totals. Produce a form-level error signature for each eligible photo and verify the exact intersections above. Treat the existing export's `working_tree: dirty` metadata as a reproducibility caveat, not as a clean-checkout release result.

**Exit check:** rerunning the scorer over those inputs reproduces 22 complete / 106 eligible, the six error counts, and correct-blank / unresolved treatment exactly.

### 2. Repair the highest-impact field families, without changing unrelated behavior

1. **Wine name (48 errors).** Split the 27 wrong values, 20 missing values, and the one unsupported fill into separately reviewed cases. For each, inspect the crop, accepted OCR lines, line positions, candidate/pass and raw transcript. Classify the miss as (a) text not detected/read, (b) text read but assigned to producer/appellation/variety instead, (c) unsupported inference, or (d) genuinely ambiguous/unreadable. Prioritize the 21 wine-name + subregion and 20 wine-name + producer intersections with coordinated role assignment rather than independent name heuristics. Keep abstention for unreadable or ambiguous text.
2. **Producer (47 errors).** Review the 20 forms that also fail wine name first, then producer-only errors. Separate actual label maker/brand from variety, cuvée, importer, legal footer, and appellation. Require local, aligned evidence; corroboration may support a suggestion but must not fabricate a complete maker name or silently promote it to truth.
3. **Subregion and region (30 and 26 errors).** Analyze the 19 region + subregion intersection and 17 wine-name + region intersection. Distinguish label geography from appellation text and preserve administrative hierarchy. Correct blanks are valid: add explicit negative cases for expected-blank subregions and prevent a broad gazetteer from filling them. The existing 43 correct blanks must not regress; the current 10 unsupported fills make this a safety-critical area.
4. **Country (11 errors) and vintage (4 errors).** Treat these as bounded cleanup after the larger identity/geography clusters. Do not infer country from a generic appellation or vintage from unrelated numerals; retain explicit missing/ambiguous outcomes.

For each proposed rule or model/pipeline change, add the smallest possible labeled replay fixture from reviewed images, including positive, negative, blank, and ambiguity examples. Keep experimental outputs side-by-side with the current production-v2 baseline until measured.

### 3. Work by complete-form cohorts, not summed field totals

Build a prioritized table of image IDs grouped by their exact sets of failing fields. Start with one-error forms (37) where a safe fix could directly make a complete form, then work on the most frequent two-/three-error bundles, especially wine name + subregion, wine name + producer, and region + subregion. A fix to a field only advances the complete-form metric if every other field on that image is already correct or also repaired. Track: forms newly complete, forms made worse, field-level correct/wrong/missing/correct-blank/unsupported outcomes, and changes to unresolved/N/A coverage.

### 4. Use paired evaluation and independent review

For every candidate, run the identical 107 included images and compare per-image against both the frozen references and saved production v2. Report the full six-field confusion/outcome counts, complete / one-error / multi-error forms, correct blanks, unsupported fills, unresolved N/A, and the 107-image runtime funnel. Review every regression and every newly filled blank. Avoid optimizing aggregate accuracy by reducing coverage or changing the eligible denominator.

### 5. Promotion gate and next dataset

Do not claim the target is reached until a reproducible run on the frozen 107-photo set has at least **85 complete eligible forms out of the same 106**, with the unresolved form still N/A and no denominator changes. Require no increase in unsupported fills and no regression in correct blanks without an explicit reviewed tradeoff; preserve current execution/deadline safeguards. Re-run from a clean recorded source revision and retain the exports/build metadata. Since this set will become development data after repeated iteration, assemble a separate, rights-cleared, high-resolution held-out label set with human-verified six-field values plus visible/ambiguous/unreadable and correct-blank annotations before making a general accuracy claim.

## Progress: first one-error cohort review

The latest Lab CSV and raw trace were joined by image ID for all 37 eligible one-error forms. Their lone error fields are producer on 20 forms, wine name on 12, subregion on 3, and region on 2. This confirms that fixes must first target producer/wine role and OCR-evidence assignment, while preserving the blank/geography checks.

One high-confidence role error is review #48: the bottle clearly prints **SAINT-ÉMILION** as the appellation/name line and **PROPRIÉTAIRE - RÉCOLTANT** as a legal footer. The run's candidate ranking put an OCR-joined legal footer above the printed name, yielding the wrong wine name. A narrowly anchored debug-Lab rule now rejects only the whole French owner-grower legal phrase (including the clipped/duplicated OCR form in this trace); it does not reject a real identity that merely contains those words. A regression fixture checks both the rejection and the printed name selection. This is a targeted candidate change, not yet a measured corpus gain: a fresh 107-photo phone run is needed.

Review #63 was adjudicated by the user on 2026-10-25: the photo visibly reads “Volcanic Rosé,” not the imported “Volcanie Rosé.” The scored target and accepted normalization now use “Volcanic Rosé”; the imported transcription remains unchanged and the override is recorded in `reference-adjudications-v1.json`. The current Lab prediction “LANGUEDOC” remains wrong under either spelling, so this correction does not change that run's outcome.

## Current reading of the result

Lab currently has the same 22 complete forms as saved production v2, but 10 more correct values and 10 fewer missing values; wrong values, correct blanks, and unsupported fills are unchanged. This is useful evidence for targeted work, not evidence that the 80% target is close: 63 additional eligible forms must become fully correct. The arithmetic requires bundled, image-level error correction and careful prevention of blank/geography regressions, not merely improving the most frequent individual field.

### 6. One-error OCR evidence audit and regression fixtures (complete for run 7b69cedff5bf)

The 37 one-error forms are now separated by raw OCR evidence using the reviewed ledger and trace-join utility under `tools/evaluation/one-error-review-lab-7b69cedff5bf/`. The tool joins only the selected Lab pipeline's one-error field rows to the raw engine recognition events; every quoted snippet is checked against the matching image and OCR engine before export. Its generated report retains run expectation separately from current scored target for the #63 adjudication.

The reviewed split is **20/32 literal-value cases with sufficient OCR text**, **12/32 with a recognition gap**, plus **2 region-parent mappings** (#44/#85) and **3 correct-blank subregion policy cases** (#59/#72/#93). This distinction means 20 errors are downstream role/assignment candidates, while 12 require better recognition or safe abstention; the five schema cases should not be counted as OCR character errors. The twelve recognition-gap fixtures record the needed label phrase, observed engine fragments, and exact missing/corrupted component. Their tests deliberately reject fuzzy equality and do not repair from the expected answer.

**Test result:** `python3 -m unittest tools.tests.test_one_error_ocr_diagnosis` passes the four focused tests: cohort/status totals, exact twelve-case membership and missing-component assertions, engine/provenance fragment coverage, and non-fuzzy near-match behavior. The full `tools.tests` suite passes with **201 tests**.

**Next-step judgment:** first work on the **12 recognition gaps** with targeted, independent crop/preprocessing experiments, then replay the 20 sufficient-evidence cases as a separate downstream assignment cohort. Do not begin with producer fuzzy matching: the gaps include stylistic logos, one-character substitutions, and false-friend text, so blanket distance-one acceptance would be unsafe. Evaluate a localized second OCR pass and require independent visual/text support before any producer correction. Keep the Bordeaux Supérieur blank policy and Pays d'Oc parent mapping as explicit non-recognition tests.
