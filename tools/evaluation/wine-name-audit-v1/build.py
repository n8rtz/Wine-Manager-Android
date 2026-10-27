#!/usr/bin/env python3
"""Build a review queue for wine-name references and frozen benchmark outputs."""
import csv, hashlib, json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
REFERENCE = ROOT / "tools/evaluation/public-corpus-reference-v1/reference.json"
# The 168-photo corpus is retired from the APK; these inputs live in tools/ (see its README).
SCORING = ROOT / "tools/evaluation/retired-phone-corpus-v1/scoring.json"
ADJUDICATIONS = ROOT / "tools/evaluation/retired-phone-corpus-v1/reference-adjudications-v1.json"
V2 = ROOT / "tools/evaluation/retired-phone-corpus-v1/saved-production-v2.json"
OUT = Path(__file__).resolve().parent
FIELDS = ["review_number", "image", "source_url", "identity_group", "reference_status", "reference_target",
          "accepted_aliases", "reference_basis", "reference_notes", "imported_reference_target", "imported_reference_accepted",
          "adjudication_status", "adjudication_basis", "saved_v2_prediction", "saved_v2_outcome",
          "frozen_baseline_prediction", "frozen_baseline_outcome", "audit_status", "reviewer_decision", "reviewer_notes"]

def normalize(s):
    import unicodedata
    s = s.lower().replace("ß", "ss").replace("ς", "σ").replace("&", " et ")
    return "".join(c for c in unicodedata.normalize("NFKD", s) if c.isalnum())

def outcome(ref, value):
    if ref["status"] == "unresolved":
        return "unscored_filled" if value.strip() else "unscored_blank"
    if ref["status"] == "expected_blank":
        return "unsupported_fill" if value.strip() else "correct_blank"
    if not value.strip():
        return "missing_value"
    return "correct_value" if normalize(value) in {normalize(x) for x in ref["accepted"]} else "wrong_value"

def main():
    public = json.loads(REFERENCE.read_text())
    scoring = json.loads(SCORING.read_text())
    adjudication_doc = json.loads(ADJUDICATIONS.read_text())
    adjudications = {(x["image"], x["field"]): x for x in adjudication_doc["adjudications"]}
    v2 = json.loads(V2.read_text())
    rows, summary = [], Counter()
    for item in public["images"]:
        ref = item["fields"]["wine_name"]
        frozen = scoring["images"][item["review_number"] - 1]["fields"]["wine_name"]
        assert scoring["images"][item["review_number"] - 1]["image"] == item["image"]
        current = v2["predictions"][item["image"]]["wine_name"]
        baseline = scoring["baseline"][item["image"]]["wine_name"]
        adjudication = adjudications.get((item["image"], "wine_name"))
        row = {
            "review_number": item["review_number"], "image": item["image"], "source_url": item["source_url"],
            "identity_group": item.get("identity_group", ""), "reference_status": frozen["status"],
            "reference_target": frozen.get("target", ""), "accepted_aliases": " | ".join(frozen.get("accepted", [])),
            "reference_basis": ref.get("basis", ""), "reference_notes": item.get("notes", ""),
            "imported_reference_target": ref.get("value", ""),
            "imported_reference_accepted": " | ".join(ref.get("accepted", [])),
            "adjudication_status": "USER_CONFIRMED" if adjudication else "",
            "adjudication_basis": adjudication.get("basis", "") if adjudication else "",
            "saved_v2_prediction": current, "saved_v2_outcome": outcome(frozen, current),
            "frozen_baseline_prediction": baseline, "frozen_baseline_outcome": outcome(frozen, baseline),
            "audit_status": "USER_CONFIRMED_VISIBLE_LABEL" if adjudication else "PENDING_VISUAL_REVIEW",
            "reviewer_decision": f"Use {frozen.get('target', '')}" if adjudication else "",
            "reviewer_notes": adjudication.get("basis", "") if adjudication else "",
        }
        rows.append(row)
        summary[row["saved_v2_outcome"]] += 1
    with (OUT / "wine-name-review.csv").open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n"); w.writeheader(); w.writerows(rows)
    meta = {
        "purpose": "Human review queue; not a fresh independent image adjudication.",
        "images": len(rows), "reference_sha256": hashlib.sha256(REFERENCE.read_bytes()).hexdigest(),
        "scoring_sha256": hashlib.sha256(SCORING.read_bytes()).hexdigest(),
        "adjudications_sha256": hashlib.sha256(ADJUDICATIONS.read_bytes()).hexdigest(),
        "user_confirmed_adjudications": len(adjudications),
        "saved_v2_sha256": hashlib.sha256(V2.read_bytes()).hexdigest(),
        "note": "Imported reference values remain preserved; explicit image-level adjudications override only the scored target and are shown separately.",
        "saved_v2_outcomes": dict(summary),
        "policy": "Exact normalized accepted-alias scoring; review target semantics and image evidence before changing references or extraction behavior.",
    }
    (OUT / "summary.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(meta, indent=2))

if __name__ == "__main__": main()
