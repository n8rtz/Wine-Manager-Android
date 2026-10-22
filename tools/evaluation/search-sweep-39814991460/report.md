# Experimental search-parameter sweep

Only new search variants executed in real Android/Kotlin. Frozen OCR, references, lab baseline and v3 outputs reused. Interpretation/ranking rules and ambiguity margin unchanged; all variants allow 60 seconds per case. No production changes.

| Search | Complete / skipped | Correct / wrong / abstained | Scored fields | All six correct / eligible | V3 correct / wrong / abstained on SAME subset | Median ms |
|---|---:|---:|---:|---:|---:|---:|
| candidates-64-64 | 168 / 0 | 599 / 84 / 211 | 894 | 19 / 103 | 599 / 84 / 211 | 203.0 |
| beam-16-256 | 168 / 0 | 599 / 83 / 212 | 894 | 19 / 103 | 599 / 84 / 211 | 189.0 |
| wide-64-256 | 168 / 0 | 599 / 83 / 212 | 894 | 19 / 103 | 599 / 84 / 211 | 210.5 |
| diverse-16-64 | 168 / 0 | 599 / 84 / 211 | 894 | 19 / 103 | 599 / 84 / 211 | 196.5 |
| diverse-64-256 | 168 / 0 | 599 / 83 / 212 | 894 | 19 / 103 | 599 / 84 / 211 | 189.5 |
| exhaustive-small | 168 / 0 | 599 / 83 / 212 | 894 | 19 / 103 | 599 / 84 / 211 | 199.5 |

Diversity groups normalized field values and selects supporting hypotheses round-robin, without summing confidence or erasing source ownership. Different support sets remain alternatives. Exhaustive eligibility is determined without targets: product of (all candidate counts + blank) across groups ≤20,000. Eligible cases have no candidate/beam pruning; oversized cases are explicitly skipped, never counted as abstentions. Exhaustive is only over generated hypotheses, not all possible readings.

Compare exhaustive results only with the same-subset comparators, not full-corpus totals. scores.json contains per-field counts, identity-deduplicated sensitivity, candidate/beam pruning totals, timing and paired outcome changes. field-results.csv includes every revised output beside the saved v3 result. Wider search is not automatically a win: it can expose genuine ambiguity or amplify bad rankings.

Single-agent visual references remain provisional. This is development feedback, not held-out accuracy; emulator timing is not phone performance. No variant is automatically promoted.
