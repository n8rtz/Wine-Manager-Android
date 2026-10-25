#!/usr/bin/env python3
"""Join a Lab field export to raw OCR traces and a reviewed one-error evidence ledger.

The ledger is deliberately human-reviewed: automatic string matching alone cannot tell
whether a split OCR phrase belongs together or whether a parent region is implied.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCORING = ROOT / "app/src/debug/assets/phone-corpus-v1/scoring.json"
ALLOWED = {"sufficient", "insufficient", "mapping_required", "policy_blank"}
ERROR_OUTCOMES = {"wrong_value", "missing_value", "unsupported_fill"}


def key(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text).casefold()
    return "".join(ch for ch in decomposed if ch.isalnum())


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def field_errors(lab_path: Path) -> dict[str, dict[str, str]]:
    rows = [r for r in read_csv(lab_path)
            if r.get("row_type") == "field" and r.get("pipeline") == "lab-linked-wine-v1"
            and r.get("form_status") == "one_error"]
    grouped: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in rows:
        if row.get("review_number") and row.get("outcome") in ERROR_OUTCOMES:
            grouped[row["review_number"]].append(row)
    return {number: values[0] for number, values in grouped.items() if len(values) == 1}


def raw_readings(raw_paths: list[Path]) -> dict[tuple[str, str], list[str]]:
    evidence: dict[tuple[str, str], list[str]] = defaultdict(list)
    for path in raw_paths:
        for row in read_csv(path):
            if row.get("row_type") != "trace" or row.get("stage") != "recognition":
                continue
            text = row.get("raw_text", "").strip()
            engine = row.get("candidate", "")
            if text and engine in {"mlkit_reference", "candidate_3"}:
                evidence[(row.get("image", ""), engine)].append(text)
    return evidence


def current_scored_targets() -> dict[tuple[str, str], str]:
    doc = json.loads(SCORING.read_text(encoding="utf-8"))
    return {(image["image"], field): value.get("target", "")
            for image in doc["images"] for field, value in image["fields"].items()}


def validate(annotations: dict, errors: dict[str, dict[str, str]],
             evidence: dict[tuple[str, str], list[str]]) -> list[dict]:
    cases = annotations["cases"]
    if set(cases) != set(errors):
        raise ValueError(f"Annotation/error review IDs differ: annotations-only={sorted(set(cases)-set(errors))}; "
                         f"errors-only={sorted(set(errors)-set(cases))}")
    results = []
    targets = current_scored_targets()
    for number, annotation in sorted(cases.items(), key=lambda item: int(item[0])):
        row = errors[number]
        if annotation["field"] != row["field"]:
            raise ValueError(f"#{number}: annotated field {annotation['field']} != Lab error {row['field']}")
        status = annotation["evidence_status"]
        if status not in ALLOWED:
            raise ValueError(f"#{number}: unsupported evidence_status {status!r}")
        all_lines = []
        engines = set()
        for fragment in annotation["evidence"]:
            engine = fragment["engine"]
            text = fragment["text"]
            if engine not in {"mlkit_reference", "candidate_3"} or not text:
                raise ValueError(f"#{number}: malformed evidence fragment {fragment}")
            found = evidence.get((row["image"], engine), [])
            if not any(key(text) in key(line) for line in found):
                raise ValueError(f"#{number}: evidence fragment {text!r} not present in {engine} raw trace")
            all_lines.append(f"{engine}: {text}")
            engines.add(engine)
        if not annotation["evidence"]:
            raise ValueError(f"#{number}: evidence excerpt is required")
        results.append({
            "review_number": number,
            "image": row["image"],
            "field": row["field"],
            "run_expected": row.get("expected", ""),
            "current_scored_target": targets.get((row["image"], row["field"]), ""),
            "lab_prediction": row.get("recognized", ""),
            "outcome": row["outcome"],
            "evidence_status": status,
            "interpretation": annotation["interpretation"],
            "engines": "+".join(sorted(engines)),
            "raw_evidence": " | ".join(all_lines),
        })
    return results


def write_outputs(results: list[dict], output_dir: Path, run_id: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    csv_path = output_dir / "ocr-evidence-review.csv"
    columns = list(results[0])
    with csv_path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(results)
    counts = Counter(r["evidence_status"] for r in results)
    insufficient = [r for r in results if r["evidence_status"] == "insufficient"]
    lines = [f"# OCR evidence sufficiency — Lab run `{run_id}`", "",
             f"Reviewed **{len(results)}** one-error fields. The status describes raw OCR evidence, "
             "not final field accuracy; snippets may be split across separate recognition boxes.", "",
             "| Evidence status | Count | Meaning |", "|---|---:|---|",
             f"| sufficient | {counts['sufficient']} | All required literal label terms were recognized; investigate downstream role/assignment. |",
             f"| insufficient | {counts['insufficient']} | A required literal term was missed or materially corrupted. |",
             f"| mapping required | {counts['mapping_required']} | OCR read the printed term, but the benchmark target is a broader inferred region. |",
             f"| blank policy | {counts['policy_blank']} | OCR read the printed appellation, but expected subregion is blank. |", "",
             "## Recognition gaps", "",
             "| # | Field | Expected text | Lab value | OCR fragments |", "|---:|---|---|---|---|"]
    for row in insufficient:
        lines.append(f"| {row['review_number']} | {row['field']} | {row['current_scored_target'] or row['run_expected']} | "
                     f"{row['lab_prediction'] or '—'} | {row['raw_evidence']} |")
    lines += ["", "## Interpretation", "",
              f"**{counts['sufficient']} of {len(results)-counts['mapping_required']-counts['policy_blank']} literal-value cases** "
              "had sufficient OCR text. The remaining cases in the cohort include mapping and expected-blank policy "
              "issues; those are not character-recognition failures. The #63 run expectation is kept separate from "
              "the current adjudicated target in the CSV.", ""]
    (output_dir / "ocr-evidence-review.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lab-csv", type=Path, required=True)
    parser.add_argument("--raw-csv", type=Path, action="append", required=True,
                        help="Raw trace export; may be supplied more than once.")
    parser.add_argument("--annotations", type=Path, default=Path(__file__).with_name("ocr-evidence-annotations.json"))
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).parent)
    args = parser.parse_args()
    annotations = json.loads(args.annotations.read_text(encoding="utf-8"))
    errors = field_errors(args.lab_csv)
    evidence = raw_readings(args.raw_csv)
    results = validate(annotations, errors, evidence)
    run_ids = {row.get("run_id", "") for row in read_csv(args.lab_csv) if row.get("row_type") == "field"}
    run_id = ", ".join(sorted(x for x in run_ids if x)) or "unknown"
    write_outputs(results, args.output_dir, run_id)
    print(f"Reviewed {len(results)} one-error fields; wrote evidence report to {args.output_dir}")


if __name__ == "__main__":
    main()
