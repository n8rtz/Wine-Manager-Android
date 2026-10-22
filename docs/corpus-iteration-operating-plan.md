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

## Measured first batch → second implementation (2026-10-22)

Android run 39828977215 completed all nine candidates. Rules-001 (explicit cuvée role only)
was 602 correct / 82 wrong / 210 abstained, 19 complete. Rules-111 was 613 / 93 / 188,
20 complete, gaining 6 but losing 9 complete entries relative to the saved baseline.
Both baseline repair designs were 601 / 100 / 193, 23 complete: four useful fills,
no additional completed entry. No candidate met the predeclared advancement gate.
Production and default lab behavior remain unchanged.

Paired near-complete baseline failures motivated the next implemented experiment, not
another rank sweep: exact printed appellation fallback for missing wine names. Four
Android policies compare one versus two co-located independent OCR engines, and blank-only
versus also replacing administrative-text wine values. They require understood saved
country and region, consistency with all selected origin fields, an exact whole observed
appellation form, no visible cuvée marker, and no competing qualifying appellations.
They preserve the other five baseline fields; the host collector enforces that invariant.
The two-engine policy does not count repeated runs of the same engine as independent.
No baseline, previous extractor, or OCR engine executes. Only new fallback logic runs,
using saved baseline fields and observations; references stay on the scoring host.

The same strict advancement gate is retained, even though baseline-preserving variants
cannot remove the baseline's non-wine errors. A local component improvement is not sufficient
for promotion or evidence that an architecture beats both controls. Android synthetic
regressions run before the four-policy corpus batch. All 168 photos remain development data.

## Anchor architecture experiment

The first batch also motivates a whole-component hybrid: baseline producer/vintage anchors
plus rules-001 wine and **unsplit** geography selection. This is a fixed architecture for
all photos, not an oracle choosing per case. The four new Kotlin variants are anchors alone,
anchors with heading-only producer abstention, and that guarded design with one/two-engine
appellation fallback. Incomplete headings such as “Château de” do not constitute a name.

An offline saved-output preview of the unguarded architecture was 627 correct / 86 wrong /
181 abstained and 24 complete, motivating actual Android validation. A Python reproduction
of the existing structural heading guard yielded 627 / 82 / 185, 24 complete and 22 deduplicated
complete (baseline 21). These are **prototype previews, not Android results**; no advancement
is asserted from them. The device runs synthetic guard/field-preservation checks before the
four-policy corpus execution. Only the new hybrid executes: both component outputs and all
OCR are frozen inputs. The host reconstitutes and hashes those exact inputs and records the
rules-001 source hash, and rejects any hybrid that changes the chosen origin bundle or vintage.
The original advancement gate and provisional-development-reference caveats remain in force.

Appellation-only attempt 39831650464 compiled successfully but the emulator failed to boot
within 360 seconds; no device assertions or corpus policies executed. The next workflow
combines all four new appellation policies and four anchor policies in one emulator session,
with a 600-second boot allowance and separate, hashed inputs/results for each batch. It
clears stale device result files before each test so a preflight failure cannot reuse prior
outputs. This is an infrastructure retry, not an accuracy observation.
