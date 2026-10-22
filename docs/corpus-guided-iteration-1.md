# Corpus-guided iteration 1: heading versus complete identity

## Feedback driving this change

The frozen v2 experimental result has 24 correct producer fields, 70 wrong fills,
and 71 producer abstentions, versus the lab baseline's 71 / 38 / 56. The separate
60s production run only gains six correct fields overall. More timeout is not a
sufficient explanation for identity-assignment failures.

Inspection of saved OCR and output alternatives shows bare `Château`, `Domaine`,
and incomplete estate headings competing with complete names. In several photos
both the heading and following name are readable, but vertical composition only
supports connectors/designations, not an estate heading followed by its name.
Other failures include truncated OCR, footer attribution, names containing place
words, and complete producer/cuvée competition; this first change does not claim
to solve all of them.

## Bounded first intervention

- Debug-only v3 distinguishes generic producer headings from named estate phrases.
- A heading alone cannot fill producer or wine name.
- Adjacent, aligned heading/name observations may form an estate phrase using
  the existing geometric bounds and source ownership; no missing letters invented.
- An explicitly headed estate is not automatically treated as grape origin merely
  because its name contains a place, or offered as a competing wine title.
- Decision traces record filter reasons, ownership conflicts, retained search
  limits and competing finalists. Counters and bounded trace rows do not feed ranks.
- No evaluated estate names, photo IDs, vintages or frozen targets are runtime rules.

## Evaluation, not a claim of improvement

The dedicated Android instrumentation replay executes **only the revised experimental
extractor** on all 168 saved lab OCR inputs. It does not rerun production scanning,
OCR engines, the production FillEngine, or the old experimental extractor. Frozen
baseline and v2 predictions are read by the host scorer for comparison only.
Neither reference answers nor expected old predictions are sent to the device.
Small synthetic role regression checks remain separate from the corpus loop.

This is the standing policy for future iterations: reuse unchanged baseline outputs
and saved OCR. Do not repeat baseline execution merely to compare a revised version.

The CI job will preserve per-field outcomes and bounded traces on this branch.
An improvement is not established until that execution and scoring succeed.
Correct/wrong/abstained counts, complete-entry results and identity-deduplicated
sensitivity will guide whether to retain, revise or revert this intervention.
This corpus is now development feedback, not held-out proof of generalization.

Production code, its 15-second default, the OCR engines/models, and the reference
are unchanged. The experimental lab comparison remains separately selectable and
default-off. Phone/end-to-end validation follows promising saved-reading results;
there is no reason to repeat a two-hour OCR acquisition for each parser edit.
