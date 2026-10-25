# Scanner accuracy recovery plan

Updated 2026-10-24. Goal: **>80% completely correct reads; <5% with two or more field errors**.

## 1. Start from the actual source and evidence

This session remains on `arena/259cc0eb-wine-manager-android`. Latest `main` was fetched and fast-forwarded into it at **`8fce0c6` (PR #9)**, including `0a1e84d`: the previous agent's 200-candidate lookup/skip-removal fix, regression test, replay harnesses and experiment reports. Do not implement that fix again.

The current investigation successfully read all three newly supplied files:

| File | SHA-256 |
|---|---|
| `wine-168-lab-f9a8891dc192.csv` | `fc9259dd214d6d5cb71413b59f477375e6491d7d8a7f3c152fbdf188d5cb853d` |
| `wine-168-raw-lab-f9a8891dc192-part-01.csv` | `e322297203477a1c449d6e185b4759a46c34802635184036c751495dff632b75` |
| `wine-168-raw-lab-f9a8891dc192-part-02.csv` | `3778d70d33417ea9d67a15b55985cf856d3ff7ab00d3f2e046fbc662dd64502a` |

These are a **pre-PR-#9** phone run on device `CPH2583`, build label `f9a8891dc192`, **working tree dirty**. The SHA alone therefore does not identify all installed source changes. They cannot measure the subsequently merged lookup fix. The earlier `6c54a40` files are still absent; conclusions below apply to the supplied f9 run, not retrospectively to an unread run.

### What this run demonstrates

- Exactly 168 first attempts; both engines completed on all photos; no reported execution failures/deadline-limited images.
- Selected pipeline is `lab-linked-wine-v1`; all final preview rows record `raw-plus-linked-wine-v2` and `linked-wine-v1`.
- Both primary databases opened/validated on all 168 photos: the recorded producer hash `4e5a50c9…2345f` and wine hash `83ac9248…ba01c` match the packaged schema-2 manifest, asset version `producer-wine-primary-v2`. Host hash checks and SQLite `quick_check` also passed. This is evidence against an initialization/budget failure **in this run**, not proof of all future cold starts.
- All **1,008 current pipeline fields** agree between final raw summaries, raw `pipelines` records and the scored export. The exporter is not substituting saved v2 results.
- Lab changed **nine producer fields from missing to correct**, with zero scored regressions relative to saved v2. It changed **no wine values**. One linked wine pair was accepted, but merely confirmed an already-present Cabernet Sauvignon.
- Complete forms stayed flat because the nine corrected photos still had other errors; five moved from multiple errors to one error.

| Metric | Saved phone v2 | Supplied Lab run |
|---|---:|---:|
| Completely correct | 34/160 (21.25%) | 34/160 (21.25%) |
| Exactly one error | 43 | 48 |
| Two or more errors | 83/160 (51.875%) | 78/160 (48.75%) |
| Correct nonblank fields | 630 | 639 |
| Wrong values / unsupported fills / missing values | 80 / 20 / 184 | 80 / 20 / 175 |

### Where matching stops

Label counts, not candidate/line counts:

| Producer gate | Labels passing | Blocked at this gate |
|---|---:|---:|
| Resolver invoked | 168 | 0 |
| Two-engine, disjoint producer support | 27 | 141 |
| One viable producer identity | 26 | 1 |
| Separated from observed rivals | 22 | 4 |
| Existing-value protection permits selection | 18 | 4 |
| Actual producer value changed | 9 | 9 confirmed existing names; other labels abstained |

Wine matching was invoked on 168 labels. **149** stopped before wine search because no compatible independently observed producer survived; **19** proceeded; **18** lacked independent linked-wine agreement; **one** pair was confirmed; **zero** wine values changed. The three `linked_wine_candidate` rows on two labels are **not** a count of database consultations.

A host replay of the **pre-fix shipped SQL** reproduced all 84 recorded producer-candidate rows, with no discrepancies. Every one of 1,031 non-exact producer lookups returned the 33-row sentinel and was discarded. This is the concrete defect PR #9 already addresses. Do not now label every remaining `no independently supported producer` result an OCR failure: eligibility, retrieval, distance, provenance and competing identities are different causes.

The actual source feeds **2,689 original observations + 597 multi-observation joins = 3,286 spans**. Another 397 spatial proposals are singletons, not additional resolver inputs. Static eligibility evaluation found 1,111 eligible spans, not the prior harness's 1,230/3,683. The merged Python replay's documented 167/168 prediction parity and different span population must be resolved before treating its small gains as measured app accuracy. Preserve originals and provenance; never count singleton proposals twice.

## 2. Freeze an honest success contract

Score the automatic final displayed/exported values **before human edits or alternative selection**. Correct YELLOW suggestions can count as correct text, but remain manual-confirmation suggestions, not calibrated confidence.

The current benchmark covers producer, wine name, country/US state, region, subregion and vintage. Missing required values, wrong values, unsupported fills and execution failures are errors. Expected blanks left blank are correct. Seventeen unresolved slots leave eight forms partly unscorable: retain all 168 photos, show 160 complete-form denominator and the unresolved count; do not silently drop difficult labels or call unknown a correct blank.

For the owner's literal **strictly greater than** 80% goal:

| Fully scorable forms | Minimum completely correct | Maximum with 2+ errors |
|---|---:|---:|
| Current 160 | **129** | **7** |
| If all 168 references are independently resolved | **135** | **8** |

Use integer/fraction comparisons and boundary tests. Older benchmark text/helper logic uses **at least 80% = 128/160**; that is a different, weaker gate and needs alignment before declaring acceptance. Reference judgments/aliases are not changed to improve scores.

From today's measured Lab baseline the gap is **+95 complete forms** and **71 fewer multi-error forms**. Fixing every current one-error form would produce only 82 complete forms. At least 47 currently multi-error forms must also become completely correct; this is not a two-producer-fix problem.

## 3. Delivered first: observable pipeline bottlenecks

The debug benchmark now has a **Pipeline bottlenecks** table, also included in copied statistics and both CSV exports (`row_type=pipeline_step`). It has separate ML Kit and Paddle branches, their text/mapping steps, rejoin, extraction, reference setup, per-engine producer/wine gates, independent support, ambiguity/separation/protection, final fields and scoring-only outcomes. See [benchmark documentation](phone-corpus-benchmark.md#pipeline-bottleneck-table).

Counts are distinct labels, latest attempt only. Columns distinguish passed, blocked, not reached, not recorded and N/A. Partial OCR text is not erased because an engine timed out; successful empty OCR is not called useful text; an existing correct value retained is not called a new fill. Branches, optional changes and correctness outcomes are not one monotonically decreasing chain.

New runs preserve compact per-label snapshots in checkpoints before raw traces are removed. Old evidence supplies only provable facts; absent retrieval/eligibility telemetry stays **not recorded**, not a fabricated failure or success. Reference answers enter only the two explicitly scoring-only rows.

**Status:** 191 Python host tests pass; 52 actual Kotlin funnel/checkpoint/scorer/CSV checks pass on both default and attached evidence, alongside 27 spatial + 18 producer + 27 linked-wine + 2,030 scoring + 43 assignment/policy checks. Android/Compose/native smoke test added but not executed. Full Gradle build is blocked here by the Gradle distribution TLS/download restriction. A redundant merged `buildCommit` declaration was removed while retaining all six BuildConfig fields; complete build validation is still required.

## 4. Prioritize by complete-form and error-tail impact

All 168 photos' scorable field slots, including the partly unresolved forms:

| Field | Missing | Wrong value | Unsupported fill | Total errors | Sole error on eligible forms |
|---|---:|---:|---:|---:|---:|
| Producer | 47 | 33 | 0 | 80 | 28 |
| Wine name | 36 | 44 | 3 | 83 | 15 |
| Country/state | 15 | 0 | 3 | 18 | 0 |
| Region | 37 | 0 | 3 | 40 | 1 |
| Subregion | 35 | 3 | 11 | 49 | 4 |
| Vintage | 5 | 0 | 0 | 5 | 0 |

Additionally, **22 forms have exactly the producer + wine-name pair wrong**. Name selection must address both fields coherently, not just fill whichever is easiest.

Reference-assisted dependency illustrations, **not achievable predictions or matching input**:

| Assume these fields perfect | Complete | Still 2+ errors |
|---|---:|---:|
| Producer only | 62 | 51 |
| Wine name only | 49 | 45 |
| Producer + wine | **99** | **33** |
| Producer + wine + country | 99 | 30 |
| Producer + wine + region + subregion | 137 | 0 |

Names alone cannot reach the target even under an oracle. **Wine identity and observed-origin/hierarchy work are required workstreams**, not later cosmetic cleanup.

## 5. Ordered implementation work packages

### P0 — Prove the synced implementation and measurements (first small change set)

1. Build debug and unit/instrumentation APKs with the real Android toolchain; verify installed SHA, dirty state, ABI and packaged hashes. The current branch is fixed; do not reuse another agent's branch.
2. Run the new `PipelineFunnelDeviceTest` on a **disposable emulator**. It exercises real bundled SQLite -> resolver -> spatial preview -> compact checkpoint -> scoreboard/CSV without OCR. Verify one supported pair reaches the final values and table, not merely a candidate trace.
3. Validate the new funnel with a small positive/negative saved-evidence set, unavailable assets, budget exhaustion, empty text, one failed engine, ambiguous names and existing-value protection. Screen, copied stats, scored CSV and raw aggregate must agree.
4. Reuse the supplied raw OCR to replay **actual Kotlin** postprocessing and Android SQLite. Supply observations/geometry/provenance only; freeze outputs before separately scoring. Make replay span construction identical to `TextLabFormPreview.records` and the multi-source-only spatial alternatives. Reconcile old Python replay differences rather than silently changing its denominator.
5. Measure the already-merged 200-candidate fix, its remaining budget, cap exhaustion and final-field effects before adding another matcher rule. Exact/fuzzy retrieval recall must be separated from precision gates; a finite result window is not evidence that no unseen rival exists.

**Exit:** native positive path and exports pass, every changed prediction is attributable, actual replay has one current contender, and source/asset/reference hashes are recorded. No full 168-photo OCR rerun needed for this gate.

### P1 — Correct evidence selection and blanket automatic-value protection

**Files:** producer/linked-wine resolvers, `ProducerRepair`, `EvidenceAnchorHybrid`, identity-role/assignment logic, corresponding JVM/device tests.

- Build a work queue per failed field: correct raw text already present; correct alternative rejected; incomplete/overlong construction; missing primary entry; producer-link gate; unresolved OCR/image evidence. Trace the first rejecting gate and the final competing values.
- Distinguish manual/confirmed values, genuinely supported automatic values and unsupported automatic guesses. Protect the first two; do not grant an automatic guess immunity just because it is nonblank.
- Require stronger independent observed role/extent evidence to replace a guess. A printed appellation acting as an incorrect producer or an appended administrative suffix is a candidate for correction; another real producer appearing on the label is not automatic permission to overwrite.
- Use contrastive tests from the audited blocked cases: protection correctly retains the full Château Haute Brande; it also retains erroneous FRONSAC and an appended producer suffix; a different Gerard Bertrand candidate is **not** the correct replacement for the reference's Domaine Georges Bertrand. Do not remove all protection to recover two examples.
- Distinguish estate/brand, named cuvée, attributed maker and legal importer/bottler using the observed label context. A reference hit is lexical evidence, not role proof. Keep ambiguity as alternatives/abstention.

**Exit:** positive and contrastive-negative cases pass actual Kotlin/native replay, all previously correct complete forms retained during small development experiments, no new unsupported/wrong fills, and improvements measured in complete forms and the multi-error tail—not only candidate yield.

### P2 — Wine-name construction and full-name extent

**Files:** evidence graph/phrase construction, ranking/identity assignment, guarded wine selection, linked resolver integration.

- Recover observed wrapped names/word breaks through local same-engine, geometrically plausible alternatives. Keep original observations and share their IDs; never turn a join, variant or duplicate database row into another vote.
- Prefer an observed distinctive cuvée over generic style/appellation fallback when the role evidence warrants it. Preserve meaningful designations; avoid producer duplicated into wine or legal text appended to a name.
- Keep the primary-linked resolver's separate two-engine/disjoint evidence for each field and no cross-field observation reuse. Do not invent an unseen wine from producer membership, DISPLAY_NAME or a guessed catalogue entry. Explicit primary WINE values only.
- Ordinary observed appellation text can legitimately support a wine-name interpretation **and** geographic origin; that is different from reusing a producer observation to assert a linked wine.
- Preserve useful OCR-only extraction for names absent from LWIN. Measure source coverage against frozen annotations only in the evaluator, never as runtime answer input.

**Exit:** gains on name-pair/near-complete forms without new generic-phrase collisions or unsupported name fills. Reject changes that increase accepted names while leaving complete/tail metrics flat.

### P3 — Observed geographic origin and hierarchy (required, alongside P1/P2)

**Files:** existing origin KB/aliases/hierarchy and evidence-origin selection; not producer-country membership.

- Audit the 37 missing regions, 35 missing subregions and 17 unsupported geography fills separately: literal origin recognition, alias normalization, parent path, conflicting place selection or address/legal-line rejection.
- Infer supported **ancestors** of a printed appellation; never infer an unprinted child origin from a parent. Preserve expected blanks at unsupported finer levels. Maintain coherent country/region/subregion bundles.
- Follow the existing US-state-as-Country convention consistently. Do not turn LWIN country groups, TTB/OSM records, importer addresses, winery domicile or estate reputation into bottle geography.
- Curate missing mappings from independently sourced/general origin data, not a per-benchmark-bottle answer table. Add ambiguous place, address, parent-only and conflicting-origin negatives.

**Exit:** measured complete-form gains and reduction of multiple-error forms, with no increase in unsupported geographic fills. Oracle name-only ceiling must no longer be the architectural ceiling.

### P4 — Only then consider narrower gate/data changes or residual reading work

- The asset builder **exists**: `tools/producer-sources/prepare_primary.py`. Do not ask for its creation or rebuild the data through a missing-source assumption.
- The 431 repeated normalized keys across 975 rows include genuine source-country groups (e.g. Tussock Jumper across 11 countries). **Never keep MIN(id) and discard the rest.** If lexical-name grouping is justified, build a one-time name-key-to-all-source-IDs bridge, retain every producer group, explicit wine link and LWIN ID, and keep identity ambiguity when the bottle evidence cannot resolve it. Short/generic entries need runtime/context safeguards, not data deletion to hit a size target.
- Review the now-merged partial-match, token-overlap, single-word and OCR-scrub experiments before proposing another sweep. Do not repeat them or ship their precision losses. A future exact single-word experiment would need an explicit new role/ambiguity discriminator and held-out contrastive tests—not blanket one-word, prefix or token-overlap acceptance.
- For remaining absent/garbled text, inspect the existing images, original detections, line crops and transforms to distinguish clipped text, missed regions, blur/glare, bad grouping, score filtering and genuinely unreadable labels. “No engine matched” alone establishes none of these.
- Only after a measured reading limitation is demonstrated, test corrections to existing localization/resizing/coordinate mapping/preprocessing **within existing models and budgets**, replacing rather than multiplying passes. No new OCR models, desktop OCR substitution, recurring recognition API, larger OCR allowance or device/emulator blame.

**Exit:** whole-form/precision gates met on actual replay and selected real-phone smoke cases; otherwise reject/revert the experiment, document why, and move to the next failure class.

### P5 — Frozen evaluation and authorized production promotion

- The 168-photo set is heavily iterated development evidence. Retain it as a regression suite, not a fresh validation claim.
- Freeze candidate logic, reference versions and the full decision policy before evaluating additional owner-approved labels. Group splits by producer/product identity and near-duplicate image family so the same wine does not leak across development and held-out sets. Adjudicate expected values/blanks independently of predictions; report unresolved and failure counts.
- Report complete / one-error / multi-error distributions, field-level gains and regressions, unsupported fills, abstention/coverage, same-photo saved-v2 comparisons, actual device/runtime/budget and cold/warm timings. Show sample size and uncertainty intervals; passing a small sample is not proof of population accuracy. Repeatedly tuned holdout data becomes development data.
- A promising replay gets a small real OnePlus/other-phone smoke set first. Only then schedule one authorized full latest-Lab run; do not rerun frozen v1/v2 baselines.
- Normal Add Wine remains production v1.0. Preserve three modes, one live latest-Lab contender and saved-v2 comparisons. Promotion into normal Add Wine requires separate owner authorization and validation of its different capture path and 15-second shared budget. No claim that 60s/engine crop-Lab gains automatically transfer to production.

## 6. Execution discipline

For every small experiment record: exact hypothesis; source/asset/input/reference hashes; files and safeguards changed; before/after funnel; per-photo final-field deltas; complete gains/losses; multi-error transitions; unsupported fills; tests/device status; accept/reject decision. Keep intermediate data/private photos out of Git and public artifacts. Never run uninstalling instrumented tests on the real cellar installation.

Stop promoting tweaks that change only matcher coverage, database size or alternative counts. The acceptance scoreboard and the independently observed evidence must both improve. No numerical gain is promised for an unimplemented work package.
