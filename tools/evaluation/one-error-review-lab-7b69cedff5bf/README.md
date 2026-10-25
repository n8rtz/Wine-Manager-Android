# Reviewed OCR evidence fixture

This directory holds the human-reviewed OCR-evidence audit for the 37 one-error labels in Lab run `7b69cedff5bf`.

## Rebuild the audit outputs

The original run exports are supplied inputs, not checked into this repository. Re-run with the Lab field export and every raw-trace part:

```sh
python3 tools/evaluation/one-error-review-lab-7b69cedff5bf/diagnose_one_error_ocr.py \
  --lab-csv '/path/to/wine-107-lab-7b69cedff5bf.csv' \
  --raw-csv /path/to/wine-107-raw-lab-7b69cedff5bf-part-01.csv \
  --raw-csv /path/to/wine-107-raw-lab-7b69cedff5bf-part-02.csv
```

The script joins the run's single error field per form to raw `recognition` trace rows and the reviewed evidence ledger in `ocr-evidence-annotations.json`. It verifies every cited fragment against its engine's trace before writing `ocr-evidence-review.csv` and `.md`. `run_expected` is retained alongside `current_scored_target`, so the #63 user adjudication does not rewrite the historical run expectation.

Evidence statuses distinguish:

- `sufficient`: required literal terms exist in OCR text (possibly across boxes or engines); investigate field selection/assignment.
- `insufficient`: a required literal term is absent or materially corrupted; investigate recognition.
- `mapping_required`: the label term is read, but the benchmark target is a broader mapped region.
- `policy_blank`: the label term is read, but field policy requires an empty subregion.

For a side-by-side inspection of the twelve recognition gaps, see [`12-recognition-gap-raw-comparison.md`](12-recognition-gap-raw-comparison.md). It includes the full imported-reference and final six-field rows plus every raw recognition line from both engines.

The reviewed ledger is not an automatic fuzzy matcher and does not repair OCR. This is intentional: near-matches such as `Cour`/`Tour`, `Caler`/`Calon`, and `Prorence`/`Provence` remain recognition gaps until separately evaluated, avoiding a hidden correction from ground truth.

Run the checked-in regression fixtures with:

```sh
python3 -m unittest tools.tests.test_one_error_ocr_diagnosis
```

The tests freeze the 37-case classification totals, the 12 recognition-gap IDs and their observed fragments, and exact (non-fuzzy) comparisons for representative confusions.
