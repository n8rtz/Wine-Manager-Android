#!/usr/bin/env python3
"""Export all 168 labels' original references alongside current scoring dispositions."""
import csv, json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ORIGINAL = ROOT / "tools/evaluation/public-corpus-reference-v1/reference.json"
# The 168-photo corpus is retired from the APK; these inputs live in tools/ (see its README).
SCORING = ROOT / "tools/evaluation/retired-phone-corpus-v1/scoring.json"
ADJUDICATIONS = ROOT / "tools/evaluation/retired-phone-corpus-v1/reference-adjudications-v1.json"
OUTPUT = Path(__file__).resolve().parent / "reference-data-168.csv"
FIELDS = ("producer", "wine_name", "country", "region", "subregion", "vintage")

def main():
    original = json.loads(ORIGINAL.read_text())
    scoring = json.loads(SCORING.read_text())
    adjudication_doc = json.loads(ADJUDICATIONS.read_text())
    adjudications = {(x["image"], x["field"]): x for x in adjudication_doc["adjudications"]}
    by_image = {row["image"]: row for row in scoring["images"]}
    assert len(original["images"]) == 168 and len(by_image) == 168
    columns = ["review_number", "image", "image_sha256", "source_url", "identity_group", "original_review_notes",
               "scored_adjudication_status", "scored_adjudication_basis"]
    for field in FIELDS:
        columns += [f"{field}_original_status", f"{field}_original_value", f"{field}_original_accepted",
                    f"{field}_original_basis", f"{field}_scored_status", f"{field}_scored_target", f"{field}_scored_accepted"]
    with OUTPUT.open("w", newline="", encoding="utf-8-sig") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        for row in original["images"]:
            score = by_image[row["image"]]
            adjudication = adjudications.get((row["image"], "wine_name"))
            out = {"review_number": row["review_number"], "image": row["image"],
                   "image_sha256": row.get("image_sha256", ""), "source_url": row.get("source_url", ""),
                   "identity_group": row.get("identity_group", ""), "original_review_notes": row.get("notes", ""),
                   "scored_adjudication_status": "USER_CONFIRMED" if adjudication else "",
                   "scored_adjudication_basis": adjudication.get("basis", "") if adjudication else ""}
            for field in FIELDS:
                old = row["fields"][field]
                new = score["fields"][field]
                out.update({f"{field}_original_status": old["status"],
                    f"{field}_original_value": old.get("value", ""),
                    f"{field}_original_accepted": json.dumps(old.get("accepted", []), ensure_ascii=False),
                    f"{field}_original_basis": old.get("basis", ""),
                    f"{field}_scored_status": new["status"],
                    f"{field}_scored_target": new.get("target", ""),
                    f"{field}_scored_accepted": json.dumps(new.get("accepted", []), ensure_ascii=False)})
            writer.writerow(out)
    print(f"Wrote {len(original['images'])} label rows to {OUTPUT}")

if __name__ == "__main__": main()
