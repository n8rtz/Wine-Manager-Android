# Search-parameter experiment

This experiment holds v3 identity/role rules, hypothesis ranks, source ownership,
and the three-point ambiguity margin fixed. It varies search mechanics only.
Production and the default experimental 16/64 behavior remain unchanged.

| New variant | Candidate slots per group | Partial assignments retained | Diversity |
|---|---:|---:|---|
| candidates-64-64 | 64 | 64 | off |
| beam-16-256 | 16 | 256 | off |
| wide-64-256 | 64 | 256 | off |
| diverse-16-64 | 16 | 64 | on |
| diverse-64-256 | 64 | 256 | on |
| exhaustive-small | all generated | all valid | off |

Diversity groups interpretations by normalized field values, then selects support
alternatives round-robin. It does **not** sum OCR scores, erase ownership, or merge
mutually exclusive support sets. This prevents duplicate interpretations consuming
all slots before different answers are considered. Lower-ranked support variants
can still be pruned, and that pruning is reported.

Exhaustive eligibility is the product of (distinct candidate count + blank) across
all four field groups ≤20,000, before compatibility rejection. This is independent
of reference answers and output quality. Eligible cases have no candidate or beam
pruning; oversized cases are recorded as skipped. This is exhaustive over the
**generated hypothesis set only**, not every possible OCR reading or segmentation.
Each result is compared with frozen v3 and lab baseline on the exact same subset.

Only the six revised variants execute on Android, using saved OCR. No baseline,
v2/v3 corpus extractor, image OCR, or production scan is rerun. Small synthetic
regressions test search behavior separately. Each image/variant retains the
60-second diagnostic limit; the CI job has a four-hour overall resource limit.
Incomplete runs fail acceptance instead of presenting partial accuracy as complete.
Execution order rotates per image to reduce fixed-order timing bias.

The report includes correct/wrong/abstained counts, fully correct entries, per-field
and duplicate sensitivity, changed outcomes beside saved v3, timing and explicit
candidate/beam pruning totals. Time/space limits, ownership rejection and finalist
ambiguity remain distinct. No variant is automatically promoted.

Reference labels remain frozen and provisional single-agent judgments. This is
an exploratory development-set ablation, not a held-out generalization result.
Rule changes (embedded-year segmentation, ownership refinement and identity
filters) should be evaluated separately after these search results, not mixed into
this parameter experiment.
