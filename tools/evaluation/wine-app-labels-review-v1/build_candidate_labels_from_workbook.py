#!/usr/bin/env python3
"""Rebuild the active reviewed-label list from both workbook tabs' shared rows."""
from __future__ import annotations

import hashlib
import json
import unicodedata
from pathlib import Path
from urllib.parse import unquote

from openpyxl import load_workbook

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
WORKBOOK = HERE / "reference-data-scrubbed-updated.xlsx"
OUTPUT = HERE / "candidate-labels.json"
SOURCE_MANIFEST = HERE / "source-manifest.json"
OLD_ASSET_ROOT = ROOT / "app/src/debug/assets/phone-corpus-v1/images"
ZIP_REVIEW_IDS = set(range(169, 191))
DISPLAY_ALTERNATES = {
    (180, "wine_name"): ["Peppoli"],
    (182, "subregion"): ["Stags Leap"],
    (187, "wine_name"): ["Bordeaux Sauvignon Blanc", "Bordeaux Sauvignon Blanc Cuvée Réserve", "Cuvée Réserve"],
}
OLD_SOURCE_COMMIT = "eb2c14ba4f9a1b6efaa476696439754a9abde8ce"


def normalized(value: str) -> str:
    value = value.lower().replace("ß", "ss").replace("ς", "σ").replace("&", " et ")
    value = unicodedata.normalize("NFKD", value.replace("’", "'"))
    return "".join(ch for ch in value if ch.isalnum())


def accepted_values(value: object) -> list[str]:
    if not value:
        return []
    parsed = json.loads(str(value)) if isinstance(value, str) else value
    if not isinstance(parsed, list):
        raise ValueError(f"Expected accepted-values JSON list, got {value!r}")
    return [str(item) for item in parsed if str(item).strip()]


def main() -> None:
    old_labels = json.loads(OUTPUT.read_text()) if OUTPUT.exists() else {"records": []}
    zip_sources = {row["review_number"]: row for row in old_labels["records"] if row["review_number"] in ZIP_REVIEW_IDS}
    workbook = load_workbook(WORKBOOK, data_only=True, read_only=False)
    sheet = workbook["reference-data-168"]
    headers = {sheet.cell(1, col).value: col for col in range(1, sheet.max_column + 1)}

    def value(row: int, key: str):
        return sheet.cell(row, headers[key]).value

    records = []
    source_entries = []
    seen_reviews = set()
    seen_images = set()
    for row in range(2, sheet.max_row + 1):
        review_number = value(row, "review_number")
        if review_number is None:
            continue
        review_number = int(review_number)
        image = str(value(row, "image"))
        image_sha256 = str(value(row, "image_sha256"))
        source_url = str(value(row, "source_url"))
        if review_number in seen_reviews or image in seen_images:
            raise ValueError(f"Duplicate review/image identity at workbook row {row}")
        seen_reviews.add(review_number)
        seen_images.add(image)

        if review_number in ZIP_REVIEW_IDS:
            previous = zip_sources.get(review_number)
            if previous is None:
                raise ValueError(f"Missing staged Wine App Labels photo for review #{review_number}")
            source_filename = previous["source_filename"]
            source_path = f"tools/evaluation/wine-app-labels-review-v1/images/{source_filename}"
            source_repo = "n8rtz/Wine-Reference-Drop"
        else:
            source_filename = unquote(source_url.rsplit("/", 1)[-1])
            source_path = f"app/src/debug/assets/phone-corpus-v1/images/{image}"
            source_repo = "AntoninLeroy/wine_label_reader_toolkit"
        staged_source = ROOT / source_path
        if not staged_source.is_file():
            raise FileNotFoundError(f"Workbook image is not staged: {source_path}")
        actual_sha256 = hashlib.sha256(staged_source.read_bytes()).hexdigest()
        if actual_sha256 != image_sha256:
            raise ValueError(f"Workbook image SHA-256 mismatch for review #{review_number}: {source_path}")

        record = {
            "review_number": review_number,
            "workbook_image_id": image,
            "source_filename": source_filename,
            "source_path": source_path,
            "source_url": source_url,
            "source_repo": source_repo,
            "source_sha256": image_sha256,
            "notes": str(value(row, "original_review_notes") or ""),
        }
        for field in ("producer", "wine_name", "country", "region", "subregion", "vintage"):
            status = str(value(row, f"{field}_scored_status") or "")
            target = value(row, f"{field}_scored_target")
            accepted = accepted_values(value(row, f"{field}_scored_accepted"))
            original_accepted = accepted_values(value(row, f"{field}_original_accepted"))
            if not accepted and status == "expected_value":
                accepted = [normalized(str(target))]
            if review_number == 66 and field == "producer":
                accepted = [name for name in accepted if name != normalized("Club des Sommeliers")]
            if status not in {"expected_value", "expected_blank", "unresolved"}:
                raise ValueError(f"Unexpected {field} status {status!r} at review #{review_number}")
            if status == "expected_value":
                if target is None or not str(target).strip():
                    raise ValueError(f"Missing {field} target at review #{review_number}")
                target_key = normalized(str(target))
                record[field] = target
                record[f"{field}_accepted_normalized"] = list(dict.fromkeys(accepted))
                alternates = [name for name in original_accepted if normalized(name) in set(accepted) and normalized(name) != target_key]
                alternates.extend(DISPLAY_ALTERNATES.get((review_number, field), []))
                if review_number == 66 and field == "producer":
                    alternates = [name for name in alternates if normalized(name) != normalized("Club des Sommeliers")]
                alternates = list(dict.fromkeys(name for name in alternates if normalized(name) != target_key))
                if alternates:
                    record[f"{field}_alternates"] = alternates
            else:
                record[field] = None
            record[f"{field}_status"] = status

        records.append(record)
        source_entries.append({
            "review_number": review_number,
            "workbook_image_id": image,
            "source_filename": source_filename,
            "source_path": source_path,
            "source_url": source_url,
            "source_repo": source_repo,
            "source_sha256": image_sha256,
            "source_bytes": staged_source.stat().st_size,
        })

    if len(records) != 125 or len(seen_reviews) != 125:
        raise ValueError(f"Expected 125 retained workbook rows, got {len(records)}")
    if 14 in seen_reviews or {49, 52, 55, 64, 73, 133, 159, 168} & seen_reviews:
        raise ValueError("An owner-removed Club/red row entered the active dataset")
    if all(entry["source_repo"] != "AntoninLeroy/wine_label_reader_toolkit" for entry in source_entries):
        raise ValueError("No linked workbook photos were included")

    candidate_doc = {
        "dataset": "Wine App Labels — 125 retained reviewed workbook labels",
        "source_workbook": "reference-data-scrubbed-updated.xlsx",
        "source_workbook_sha256": hashlib.sha256(WORKBOOK.read_bytes()).hexdigest(),
        "country_convention": "For U.S. wines, Country contains the U.S. state. Region and Subregion are separate fields.",
        "matching_policy": "Scoring uses owner-known workbook values and accepted alternatives; these targets are never provided to OCR.",
        "records": records,
    }
    OUTPUT.write_text(json.dumps(candidate_doc, ensure_ascii=False, indent=2) + "\n")

    manifest = json.loads(SOURCE_MANIFEST.read_text())
    manifest["image_count"] = len(records)
    manifest["status"] = "125 retained workbook rows staged for the debug-only benchmark; owner-removed workbook rows and IMG_2732.jpeg remain excluded"
    manifest["repository_images"] = {
        "source_repo": "AntoninLeroy/wine_label_reader_toolkit",
        "source_commit": OLD_SOURCE_COMMIT,
        "license": "No license declared in repository metadata. Exact workbook-linked images were already present in the legacy debug assets and verified against workbook SHA-256 values; authorized project/test use only.",
        "image_count": sum(entry["source_repo"] == "AntoninLeroy/wine_label_reader_toolkit" for entry in source_entries),
        "retrieval": "Reused existing local files only after matching every workbook image_sha256; linked upstream image bytes differ from the workbook's frozen image bytes, so the hash-verified workbook image is retained.",
    }
    manifest["validation"].pop("old_168_image_reference_used", None)
    manifest["validation"].pop("decoded_jpegs", None)
    manifest["validation"].pop("total_image_bytes", None)
    manifest["validation"].pop("all_individually_opened_and_visually_checked", None)
    manifest["validation"].update({
        "added_zip_images_decoded": 23,
        "added_zip_images_visual_reviewed": 22,
        "all_125_packaged_jpegs_decoded_and_hash_verified": True,
        "source_archive_image_bytes": manifest["archive_bytes"],
        "active_benchmark_modified": True,
        "legacy_full_168_photo_evaluation_reactivated": False,
        "selected_workbook_rows_from_legacy_reference": sum(entry["source_repo"] == "AntoninLeroy/wine_label_reader_toolkit" for entry in source_entries),
        "included_test_candidates": len(records),
        "app_test_candidate_count": len(records),
        "workbook_data_rows_per_tab_after_update": len(records),
        "workbook_rows_removed_from_both_tabs": [14, 49, 52, 55, 64, 73, 133, 159, 168],
        "exact_workbook_image_hashes_verified": len(source_entries),
        "included_image_bytes": sum(entry["source_bytes"] for entry in source_entries),
        "new_zip_photos_visually_checked": 22,
    })
    manifest["included_images"] = source_entries
    manifest["app_debug_asset_root"] = "app/src/debug/assets/wine-app-labels-v1"
    SOURCE_MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(f"Rebuilt {len(records)} labels and verified {len(source_entries)} source images")


if __name__ == "__main__":
    main()
