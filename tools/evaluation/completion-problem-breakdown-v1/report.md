# Recognition problem breakdown

Current guarded hybrid; blank-aware scoring. 168 photos; 160 fully scorable, 8 still partly unresolved.

## Completion target

| Metric | Current | Required on these 160 forms |
|---|---:|---:|
| Completely correct | 32 (20%) | At least 128 (80%) |
| Two or more errors | 82 (51.25%) | At most 7 (strictly below 5%) |

## Priorities

| Field | Missing required | Wrong value | Unsupported fill | Total |
|---|---:|---:|---:|---:|
| producer | 60 | 34 | 0 | 94 |
| wine_name | 35 | 45 | 3 | 83 |
| subregion | 34 | 3 | 11 | 48 |
| region | 37 | 0 | 3 | 40 |
| country | 15 | 0 | 3 | 18 |
| vintage | 4 | 0 | 0 | 4 |

## Reference-assisted opportunity buckets

These are diagnostic work queues, not confirmed causes or guaranteed fixes. Historical candidates may be incompatible with other selections. Literal-text matches do not prove correct OCR.

### producer
- literal_witness_without_correct_saved_candidate: 14
- no_exact_saved_witness_cause_not_established: 32
- correct_value_only_in_saved_candidates: 39
- correct_value_in_saved_component_output: 9

### wine_name
- correct_value_in_saved_component_output: 13
- no_exact_saved_witness_cause_not_established: 28
- correct_value_only_in_saved_candidates: 28
- literal_witness_without_correct_saved_candidate: 11
- unsupported_fill_expected_blank: 3

### country
- geographic_evidence_or_path_needs_review: 15
- unsupported_fill_expected_blank: 3

### region
- geographic_evidence_or_path_needs_review: 30
- correct_value_in_saved_component_output: 7
- unsupported_fill_expected_blank: 3

### subregion
- geographic_evidence_or_path_needs_review: 22
- correct_value_in_saved_component_output: 12
- unsupported_fill_expected_blank: 11
- correct_value_only_in_saved_candidates: 3

### vintage
- no_exact_saved_witness_cause_not_established: 3
- literal_witness_without_correct_saved_candidate: 1

## Workstream plan

See `docs/recognition-problem-workstreams.md` for separate producer, wine identity and geographic-origin contracts, diagnostic tasks and next experiment designs.

The target calculation and diagnostic export have been executed. New recognition fixes have not been executed as part of this breakdown. No production or default extraction behavior changed.
