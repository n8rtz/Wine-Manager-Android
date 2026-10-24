# Completion-driven recognition workstreams

## User acceptance targets

At least 80% of forms completely correct; strictly fewer than 5% of forms with more
than one incorrect attribute. A missing required value and an unsupported fill are
both incorrect. A required blank left blank is correct. Uncertain references stay
unscored and their count must always be reported.

On the current 160 fully scorable forms, this means at least 128 complete forms and
at most 7 forms with two or more errors. Current guarded hybrid: 32 complete,
46 with exactly one error, 82 with two or more. The earlier advancement gate was
only an experiment filter and is not sufficient for this user acceptance target.
The new target calculation is implemented in `tools/corpus/completion_problem_breakdown.py`
and has boundary tests; it is not a claim that production has been changed or that
fresh validation has occurred. Before release, test the frozen design on fresh,
identity-grouped labels with expected-value/expected-blank references adjudicated
independently; do not remove difficult photos to improve the denominator.

## Field ranking (all 168 photos, scorable attributes only)

| Field | Missing required | Wrong value | Unsupported fill | Total failures |
|---|---:|---:|---:|---:|
| Producer | 60 | 34 | 0 | 94 |
| Wine name | 35 | 45 | 3 | 83 |
| Subregion | 34 | 3 | 11 | 48 |
| Region | 37 | 0 | 3 | 40 |
| Country / US state | 15 | 0 | 3 | 18 |
| Vintage | 4 | 0 | 0 | 4 |

Country is fifth, not third. Geography is a shared hierarchy problem but its three
outputs must retain separate metrics.

## 1. Producer identity

Contract: extract the supported front-label estate/brand or explicitly attributed
maker, not a legal bottler, classification, town, or incomplete heading. Preserve
meaningful name words; spelling corrections remain supported, reviewable suggestions.

Split the work:
- **Evidence selection:** 9 failed fields have the correct value selected by a saved
  component; 39 more have it in saved alternatives/hypotheses. Audit rejected candidates,
  role ownership and ambiguity margins before changing OCR. These are reference-assisted
  opportunities, not 48 guaranteed recoveries.
- **Name construction:** 14 have a normalized literal witness but no exact correct saved
  candidate. Inspect line grouping, full-name extent, truncation and appended legal prose.
- **Unresolved reading/support:** 32 lack those exact saved witnesses. Inspect image and
  raw engine readings; do not label all 32 OCR failures or fill from estate reputation.

Next experiment: candidate provenance and rejection reasons for the first two queues;
separately test full-name assembly and legal-line exclusion, with contrastive synthetic
examples and bounded alternatives. Do not merely prefer every baseline producer value.

## 2. Wine identity

Contract: select the supported named cuvee, varietal/style or appellation according to
label context; not the producer duplicated into wine name, legal prose, or an isolated
quality descriptor. Preserve meaningful qualifications such as Grand Cru or Reserva.

Split the work:
- **Selection/roles:** 13 failed fields have a correct value selected by a component;
  28 more have a correct saved alternative/hypothesis.
- **Construction:** 11 have literal witnesses without a correct saved candidate.
- **Reading/support review:** 28 lack the above evidence; cause not established.
- **Abstention:** 3 values filled where the reviewed photo supports no separate name.

Next experiment: trace wine-versus-producer decisions and full appellation/designation
construction. Explicitly test that the same printed appellation may legitimately support
wine name and geography; do not globally forbid shared evidence. Include contrastive
examples where a distinctive cuvee supersedes an appellation fallback. The four former
baseline-complete Saint-Emilion Grand Cru regressions are a diagnostic slice, not a list
of bottle-specific exceptions to implement.

## 3. Geographic knowledge and origin selection

Contract: infer supported ancestors from a printed appellation; in the US, state occupies
Country and valley occupies Region. Never infer a more specific child origin merely from
its parent. California-only evidence must not invent Napa Valley or St. Helena. Bottler
addresses, brand names and estate reputation alone do not establish grape origin.

Keep three subproblems:
- **Country/state:** 15 missing supported values, 3 unsupported fills. Separate failure
  to recognize an origin from failure to follow its ancestor path and state convention.
- **Region:** 37 missing, 3 unsupported. Seven of the missing/wrong cases have a correct
  value already selected by a component; inspect bundle rejection versus missing mappings.
- **Subregion:** 34 missing, 3 wrong, 11 unsupported. Twelve failures have a correct value
  selected by a component, three more have a saved candidate. Avoid recovering finer origins
  by simply accepting every place token; that increases unsupported fills.

Next experiment: a path-level diagnostic listing origin evidence, place match, ancestor
path, address/brand exclusion and abstention reason. Then test recognition, alias mapping,
and hierarchy propagation independently while keeping the selected origin bundle coherent.
Retain supported parent inference and expected blanks at unsupported finer levels.

## Why names alone cannot meet the target

Reference-assisted scenarios on the same 160 forms (NOT measured new algorithms):
- Perfect producer only: 63 complete, 50 with two or more remaining errors.
- Perfect wine name only: 42 complete, 46 with two or more remaining errors.
- Perfect producer AND wine name: 101 complete, 33 with two or more remaining errors.
- Also perfect country: still 101 complete, 30 with two or more remaining errors.
- Perfect producer, wine, region and subregion: 138 complete, zero with two or more
  remaining errors. This is an oracle dependency illustration, not an accuracy forecast.

Producer is the first priority for completing nearly finished forms, wine identity is
second, and geographic region/subregion work must proceed as its own track. Country is
part of that origin track, not the third-largest isolated bottleneck.

## Experiment discipline

Use frozen OCR/control outputs; only changed logic runs on Android. Keep revisions
separately selectable and default-off. Score per-field changes AND complete-form gains,
losses, exactly-one-error and multiple-error counts; never accept a field-total gain as
proof of completion improvement. Do not change reference judgments based on predictions.
Current annotations and all opportunity buckets are provisional development diagnostics.

Machine-readable evidence and per-case work queues:
`tools/evaluation/completion-problem-breakdown-v1/`.
