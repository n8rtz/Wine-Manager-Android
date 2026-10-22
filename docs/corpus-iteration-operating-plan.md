# Corpus-guided implementation loop

The agent should execute successive development experiments under the existing
scope without asking the user to approve each test or parameter setting. A status
report is not the end of the iteration. Ask only about a genuinely new product,
scope, or deployment decision. Never claim an asynchronous agent will edit code
unattended: the CI automation executes authored experiments, scoring and gates.

## Current implemented batch

Saved diagnostics identify 54 producer and 29 wine-name fields with a correct v3
hypothesis not selected. This motivates three independent rule interventions:

1. Consolidate name values differing only in spacing/punctuation/diacritics when
   deciding ambiguity. Preserve original display text and meaningful words.
2. Add a bounded ordinal rank bonus for a full co-located name reading from two
   independent OCR engines. Repeated observations from one engine do not count;
   derived phrases are not extra OCR votes. This is not a confidence probability.
3. Interpret explicit `Cuvée ...` names as wine hypotheses, not competing producers.

A full 2×2×2 ablation executes the seven new combinations; the all-off v3 control
is reused from saved results. Defaults stay off outside this experiment.

Two additional architectural candidates apply targeted repairs to saved baseline
outputs using the all-on extraction, not another baseline run. They preserve
baseline geography and vintage. Two-engine support is required. One repairs only
blank/head-only estate identities and missing explicit cuvées. The other can also
replace an exact printed-appellation wine name with a directly supported named
cuvée. No reference answers enter the device, and neither mode changes production.

The entire nine-candidate batch executes, scores and saves its decision without
user intervention between candidates. Runtime errors invalidate acceptance; no
case is silently dropped. Saved OCR, baseline, v3 and references are reused.

## Predeclared decision gate

A candidate advances to further testing only if it:
- exceeds both saved controls in six-field-complete entries;
- has no more wrong fills than v3;
- has at least as many correct fields as the baseline;
- does not underperform baseline full-entry count with one photo per identity.

Report gains AND losses of complete entries, paired field transitions and
per-field/duplicate-sensitive metrics. `decision.json` records the gate result.
The gate is not deployment authorization. No automatic change to production.

If no candidate qualifies, retain the controls and investigate the paired losses
and unresolved candidate-selection cases for the next general rule intervention.
If a candidate qualifies, prioritize actual Android regressions and fresh grouped
labels before promotion. Current references are provisional single-agent review;
all of these 168 photos are development data, not held-out generalization proof.
