#!/usr/bin/env python3
"""Package the retained workbook labels and temporary internet additions for the benchmark."""
from __future__ import annotations

import hashlib
import json
import shutil
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DEST = ROOT / "app/src/main/assets/wine-app-labels-v1"
SOURCE_ZIP = "https://github.com/n8rtz/Wine-Reference-Drop/blob/main/Wine%20App%20Labels.zip"
WORKBOOK = "https://github.com/n8rtz/Wine-Reference-Drop/blob/main/reference-data-scrubbed.xlsx"
WORKBOOK_BLOB = "5e1e2b0f8938f1ed789c9f2430cd37881a2b2bdc"
EXPECTED_LABELS = 124
REMOVED_WORKBOOK_ROWS = [14, 49, 52, 55, 64, 67, 73, 133, 159, 168]


def normalize(value: str) -> str:
    value = value.lower().replace("ß", "ss").replace("ς", "σ").replace("&", " et ")
    return "".join(ch for ch in unicodedata.normalize("NFKD", value) if ch.isalnum())


def write_json(path: Path, data: object) -> bytes:
    payload = (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.write_bytes(payload)
    return payload


def main() -> None:
    manifest = json.loads((HERE / "source-manifest.json").read_text())
    labels = json.loads((HERE / "candidate-labels.json").read_text())
    source_entries = {entry["workbook_image_id"]: entry for entry in manifest["included_images"]}
    if len(labels["records"]) != EXPECTED_LABELS or len(source_entries) != EXPECTED_LABELS:
        raise ValueError(f"The active label batch and updated workbook selection must both contain {EXPECTED_LABELS} entries")
    DEST.mkdir(parents=True, exist_ok=True)
    image_dir = DEST / "images"
    image_dir.mkdir(exist_ok=True)
    updated_workbook = HERE / "reference-data-scrubbed-updated.xlsx"
    if not updated_workbook.is_file():
        raise FileNotFoundError("Build the updated reference workbook before packaging app test assets")
    updated_workbook_sha256 = hashlib.sha256(updated_workbook.read_bytes()).hexdigest()
    inputs = []
    target_images = []
    included = []
    total_bytes = 0
    for item in labels["records"]:
        entry = source_entries[item["workbook_image_id"]]
        source = ROOT / item["source_path"]
        data = source.read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        if sha256 != entry["source_sha256"] or sha256 != item["source_sha256"]:
            raise ValueError(f"Source image checksum mismatch: {source}")
        image_id = hashlib.sha1(data).hexdigest() + ".jpg"
        destination = image_dir / image_id
        if destination.exists() and destination.read_bytes() != data:
            raise ValueError(f"Image-ID collision: {image_id}")
        if not destination.exists():
            shutil.copyfile(source, destination)
        total_bytes += len(data)
        inputs.append({"image": image_id, "sha256": sha256, "review_number": item["review_number"],
                       "source_filename": item["source_filename"], "source_repo": item["source_repo"],
                       "source_url": item["source_url"]})
        included.append({"review_number": item["review_number"], "image": image_id,
                         "source_filename": item["source_filename"], "source_repo": item["source_repo"]})
        fields = {}
        for field in ("producer", "wine_name", "country", "region", "subregion", "vintage"):
            value = item.get(field)
            status = item.get(f"{field}_status")
            # Later owner correction supersedes the historical workbook's blank subregion.
            if item["review_number"] == 154 and field == "subregion":
                value, status = "Porto", "expected_value"
            if status not in {"expected_value", "expected_blank", "unresolved"}:
                raise ValueError(f"Invalid {field} target status for review #{item['review_number']}: {status!r}")
            if status == "expected_value":
                accepted_values = item.get(f"{field}_accepted_normalized")
                if accepted_values is None:
                    accepted_values = [value] + item.get(f"{field}_alternates", [])
                normalized_accepted = list(dict.fromkeys(normalize(str(candidate)) for candidate in accepted_values))
                target_key = normalize(str(value))
                if target_key not in normalized_accepted:
                    normalized_accepted.insert(0, target_key)
            else:
                normalized_accepted = []
            fields[field] = {
                "status": status,
                "accepted": normalized_accepted,
            }
            if status == "expected_value":
                if value is None:
                    raise ValueError(f"Missing {field} target for review #{item['review_number']}")
                fields[field]["target"] = str(value)
        target_images.append({"image": image_id, "review_number": item["review_number"],
                              "source_filename": item["source_filename"], "fields": fields})

    if len({item["image"] for item in inputs}) != EXPECTED_LABELS or len({item["review_number"] for item in inputs}) != EXPECTED_LABELS:
        raise ValueError(f"The {EXPECTED_LABELS} retained workbook rows must have unique photo and review identities")
    extra_path = HERE / "internet-labels-v1.json"
    extra = json.loads(extra_path.read_text())["records"]
    fields_order = ("producer", "wine_name", "country", "region", "subregion", "vintage")
    for item in extra:
        photo = image_dir / item["image"]
        data = photo.read_bytes()  # The selected downloaded photo is retained as a test asset.
        if hashlib.sha256(data).hexdigest() != item["sha256"]:
            raise ValueError(f"Internet photo checksum mismatch: {photo}")
        total_bytes += len(data)
        inputs.append({key: item[key] for key in ("image", "sha256", "review_number", "source_filename", "source_repo", "source_url", "source_page")})
        included.append({key: item[key] for key in ("review_number", "image", "source_filename", "source_repo")})
        fields = {}
        for field in fields_order:
            status = item[f"{field}_status"]
            if status not in {"expected_value", "expected_blank", "unresolved"}:
                raise ValueError(f"Invalid {field} target status for review #{item['review_number']}")
            value = item.get(field, "")
            if status == "expected_value" and not value:
                raise ValueError(f"Missing {field} target for review #{item['review_number']}")
            accepted = sorted({normalize(str(candidate)) for candidate in [value, *item.get(f"{field}_alternates", [])] if candidate}) if status == "expected_value" else []
            fields[field] = {"status": status, "accepted": accepted, "target": str(value) if status == "expected_value" else ""}
        target_images.append({"image": item["image"], "review_number": item["review_number"],
                              "source_filename": item["source_filename"], "fields": fields})
    if len({row["image"] for row in inputs}) != len(inputs) or len({row["review_number"] for row in inputs}) != len(inputs):
        raise ValueError("Duplicate photo or review identity in combined corpus")
    expected_image_ids = {item["image"] for item in inputs}
    for stale_image in image_dir.glob("*.jpg"):
        if stale_image.name not in expected_image_ids:
            stale_image.unlink()
    inputs_bytes = write_json(DEST / "inputs.json", {"version": 2, "images": inputs})
    eval_bytes = write_json(DEST / "evaluation-set.json", {
        "version": 2,
        "title": "Large image dataset test",
        "source_workbook": WORKBOOK,
        "source_workbook_blob_sha": WORKBOOK_BLOB,
        "updated_workbook": "tools/evaluation/wine-app-labels-review-v1/reference-data-scrubbed-updated.xlsx",
        "updated_workbook_sha256": updated_workbook_sha256,
        "source_zip": SOURCE_ZIP,
        "review_decision": "Run all 124 retained rows in the updated owner-reviewed workbook. Owner-removed workbook rows including review #67 and IMG_2732.jpeg are excluded; this is not a replay of the full legacy 168-photo run. Front-label internet photos are appended for temporary testing.",
        "included_count": len(included),
        "excluded_count": len(REMOVED_WORKBOOK_ROWS) + 1,
        "included_images": included,
        "excluded_review_numbers": REMOVED_WORKBOOK_ROWS,
        "excluded_images": [{"source_filename": "IMG_2732.jpeg", "reason": "Owner-directed removal from the added candidate photo set; not packaged or sent to OCR."}],
    })
    scoring_bytes = write_json(DEST / "scoring.json", {
        "version": 2,
        "reference_sha256": hashlib.sha256((HERE / "candidate-labels.json").read_bytes() + extra_path.read_bytes()).hexdigest(),
        "note": "Owner-corrected workbook references and internet front-label candidate references; unresolved fields are unscored. Targets are scorer-only and never provided to OCR.",
        "images": target_images,
    })
    coverage = DEST / "gazetteer-coverage.json"
    if not coverage.is_file():
        raise FileNotFoundError("Generate scorer-side gazetteer coverage before packaging the full test")
    write_json(DEST / "manifest.json", {
        "count": len(inputs),
        "bytes": total_bytes,
        "source_repositories": ["AntoninLeroy/wine_label_reader_toolkit", "n8rtz/Wine-Reference-Drop", "CostcoWineBlog.com", "rogerioxavier/X-Wines"],
        "source_commits": sorted({entry["source_url"].split("/blob/")[-1].split("/")[0] for entry in inputs if "/blob/" in entry["source_url"]}),
        "updated_workbook_sha256": updated_workbook_sha256,
        "rights": "No license is declared for the two original source repositories. Images are included in the normal-app project at the owner's direction; do not redistribute beyond authorized use without confirming rights. Additional CostcoWineBlog.com label photographs credited by source page; site FAQ permits label photo use elsewhere online. X-Wines repository declares CC0 but underlying web photo rights have not been independently verified; see per-photo source and image member. Temporary testing assets; remove when model is finalized.",
        "inputs_sha256": hashlib.sha256(inputs_bytes).hexdigest(),
        "evaluation_set_sha256": hashlib.sha256(eval_bytes).hexdigest(),
        "scoring_sha256": hashlib.sha256(scoring_bytes).hexdigest(),
        "gazetteer_coverage_sha256": hashlib.sha256(coverage.read_bytes()).hexdigest(),
    })
    print(f"Wrote {len(inputs)} workbook and internet test images ({total_bytes:,} bytes) and scoring metadata to {DEST}")


if __name__ == "__main__":
    main()
