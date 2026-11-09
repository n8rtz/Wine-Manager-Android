#!/usr/bin/env python3
"""Build the updated workbook from the user-linked scrubbed reference and curated labels.

Requires openpyxl (`python -m pip install openpyxl`). The source workbook is intentionally
provided as an argument; it is not duplicated in this repository. Red rows, rejected Club des
Sommeliers producer rows, and the explicitly owner-removed review IDs are removed from both tabs
and recorded in a separate audit file. The existing workbook's comments-as-columns and formatting are kept.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import unicodedata
from pathlib import Path
from typing import Any

from openpyxl import load_workbook

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / "reference-data-scrubbed-updated.xlsx"
ZIP_URL = "https://github.com/n8rtz/Wine-Reference-Drop/blob/main/Wine%20App%20Labels.zip"
FIELDS = {
    "producer": ("producer", "Producer"),
    "wine_name": ("wine_name", "Wine name"),
    "country": ("country", "Country"),
    "region": ("region", "Region"),
    "subregion": ("subregion", "Subregion"),
    "vintage": ("vintage", "Vintage"),
}
RED_FILL_SUFFIXES = {"F4CCCC", "EA9999", "E6B8AF", "FFC7CE", "FF0000", "C00000", "E06666"}
APPENDED_REVIEW_IDS = set(range(169, 191))
OWNER_REMOVED_REVIEW_IDS = {67}
OWNER_ADJUDICATED_FIELD_CORRECTIONS = [
    {"review_number": 90, "fields": {"wine_name": "Premier Grand Cru Classé", "country": "France", "region": "Bordeaux", "subregion": "Margaux"},
     "note": "Owner-adjudicated wine name and Bordeaux/Margaux geography."},
    {"review_number": 92, "fields": {"region": "Bordeaux", "subregion": "Pessac-Léognan"},
     "note": "Pessac-Léognan is the printed appellation/subregion; Bordeaux is its parent region."},
    {"review_number": 171, "fields": {"region": "Castilla y Leon", "subregion": "Toro"}},
    {"review_number": 179, "fields": {"producer": "Campo Eliseo", "wine_name": "Cuvée Alegre", "region": "Castilla y Leon", "subregion": "Toro"}},
    {"review_number": 181, "fields": {"region": "Maipo Valley", "subregion": "Puente Alto"}},
    {"review_number": 184, "fields": {"region": "Willamette Valley", "subregion": "Dundee Hills"},
     "note": "Canonical catalog spelling is Willamette Valley."},
]


def norm(value: Any) -> str:
    text = str(value).lower().replace("ß", "ss").replace("ς", "σ").replace("&", " et ")
    text = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in text if ch.isalnum())


def json_array(values: list[Any]) -> str:
    return json.dumps(values, ensure_ascii=False, separators=(",", ":"))


def red_cell(cell: Any) -> bool:
    fill = cell.fill
    if fill.patternType != "solid":
        return False
    color = fill.fgColor
    if color.type != "rgb" or not color.rgb:
        return False
    rgb = color.rgb.upper()
    return any(rgb.endswith(suffix) for suffix in RED_FILL_SUFFIXES)


def row_id(sheet: Any, row: int) -> int | None:
    value = sheet.cell(row, 1).value
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def build_record(item: dict[str, Any], image_hash: str, sha256: str) -> dict[str, Any]:
    record: dict[str, Any] = {
        "review_number": item["review_number"],
        "image": f"{image_hash}.jpg",
        "image_sha256": sha256,
        "source_url": ZIP_URL,
        "identity_group": f"identity-{item['review_number']:03d}",
        "original_review_notes": f"Source photo {item['source_filename']}. {item['notes']}",
    }
    for field, (key, _) in FIELDS.items():
        value = item.get(key)
        original_status = "known" if value is not None else "unknown"
        scored_status = item.get(f"{key}_status", "expected_value" if value is not None else "expected_blank")
        alts = [value] if value is not None else []
        alts.extend(item.get(f"{key}_alternates", []))
        if field == "vintage" and value is not None:
            value = int(value)
            alts = [int(x) for x in alts]
        original_basis = {
            "producer": "visible_label",
            "wine_name": "visible_label",
            "country": "visible_label_or_app_convention",
            "region": "label_or_appellation_inference_see_notes",
            "subregion": "visible_appellation_normalized",
            "vintage": "visible_label",
        }[field]
        if value is None:
            original_basis = "not_visible_or_not_established"
        record[f"{field}_original_status"] = original_status
        record[f"{field}_original_value"] = value
        record[f"{field}_original_accepted"] = json_array(alts)
        record[f"{field}_original_basis"] = original_basis
        record[f"{field}_scored_status"] = scored_status
        record[f"{field}_scored_target"] = value if scored_status == "expected_value" else None
        record[f"{field}_scored_accepted"] = json_array([norm(x) for x in alts] if scored_status == "expected_value" else [])
    return record


def apply_scored_targets(sheet: Any, labels_by_review: dict[int, dict[str, Any]], drop_ids: set[int]) -> None:
    headers = {sheet.cell(1, col).value: col for col in range(1, sheet.max_column + 1)}
    for row in range(2, sheet.max_row + 1):
        review_number = row_id(sheet, row)
        if review_number is None or review_number in drop_ids:
            continue
        item = labels_by_review.get(review_number)
        if item is None:
            continue
        if "original_review_notes" in headers:
            sheet.cell(row, headers["original_review_notes"]).value = item.get("notes", "")
        for field in FIELDS:
            status = item.get(f"{field}_status", "expected_value" if item.get(field) is not None else "expected_blank")
            value = item.get(field)
            if status not in {"expected_value", "expected_blank", "unresolved"}:
                raise ValueError(f"Invalid {field} status for review #{review_number}: {status!r}")
            if status == "expected_value" and value is None:
                raise ValueError(f"Missing {field} target for review #{review_number}")
            accepted = list(item.get(f"{field}_accepted_normalized", [])) if status == "expected_value" else []
            if status == "expected_value":
                target_key = norm(value)
                if target_key not in accepted:
                    accepted.insert(0, target_key)
            sheet.cell(row, headers[f"{field}_scored_status"]).value = status
            sheet.cell(row, headers[f"{field}_scored_target"]).value = value if status == "expected_value" else None
            sheet.cell(row, headers[f"{field}_scored_accepted"]).value = json_array(accepted)


def rebuild_rows(sheet: Any, drop_ids: set[int], records: list[dict[str, Any]]) -> None:
    old_max = sheet.max_row
    old_dimensions = {r: copy.copy(sheet.row_dimensions[r]) for r in range(1, old_max + 1) if r in sheet.row_dimensions}
    kept: list[tuple[int, list[Any]]] = []
    for old_row in range(2, old_max + 1):
        rid = row_id(sheet, old_row)
        if rid is None or rid in drop_ids:
            continue
        vals = [sheet.cell(old_row, col).value for col in range(1, sheet.max_column + 1)]
        kept.append((old_row, vals))

    # Remove all old body rows, then reconstruct compactly so row dimensions and filters
    # follow the records instead of leaving stale hidden-row metadata behind.
    sheet.delete_rows(2, max(0, old_max - 1))
    for key in list(sheet.row_dimensions):
        if key > 1:
            del sheet.row_dimensions[key]

    style_source_old = kept[-1][0] if kept else 1
    # Cell styles and hyperlinks were cached before the old body was compacted.
    body_styles = getattr(sheet, "_wine_body_styles", {})
    body_hyperlinks = getattr(sheet, "_wine_body_hyperlinks", {})
    new_row = 2
    for old, values in kept:
        for col, value in enumerate(values, 1):
            cell = sheet.cell(new_row, col, value)
            style = body_styles.get((old, col))
            if style is not None:
                cell._style = copy.copy(style)
            hyperlink = body_hyperlinks.get((old, col))
            if hyperlink:
                cell._hyperlink = copy.copy(hyperlink)
        if old in old_dimensions:
            dim = copy.copy(old_dimensions[old])
            dim.index = new_row
            dim.r = new_row
            sheet.row_dimensions[new_row] = dim
        new_row += 1

    max_col = sheet.max_column
    for data in records:
        for col in range(1, max_col + 1):
            cell = sheet.cell(new_row, col)
            if col <= len(data):
                value = data.get(sheet.cell(1, col).value)
                cell.value = value
            # Match the last retained ordinary row's formatting, but keep new records visible.
            style = body_styles.get((style_source_old, col))
            if style is not None:
                cell._style = copy.copy(style)
            if col == 4:
                cell.hyperlink = ZIP_URL
            if sheet.title == "Condensed Table" and col in (49, 50):
                cell.value = None
        if new_row in sheet.row_dimensions:
            del sheet.row_dimensions[new_row]
        new_row += 1

    last = new_row - 1
    if sheet.max_column:
        end_col = sheet.cell(1, sheet.max_column).column_letter
        sheet.auto_filter.ref = f"A1:{end_col}{last}"
    delattr(sheet, "_wine_body_styles")
    delattr(sheet, "_wine_body_hyperlinks")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_workbook", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    manifest = json.loads((HERE / "source-manifest.json").read_text())
    labels = json.loads((HERE / "candidate-labels.json").read_text())
    images = {entry["source_filename"]: HERE / "images" / entry["source_filename"] for entry in manifest["included_images"]}
    workbook = load_workbook(args.source_workbook)
    if not {"reference-data-168", "Condensed Table"}.issubset(workbook.sheetnames):
        raise ValueError(f"Unexpected source workbook sheets: {workbook.sheetnames}")

    # Discover red IDs, explicit owner removals and rows whose producer is rejected branding.
    # Use the union across tabs so the two workbook copies remain synchronized.
    red_rows: dict[str, list[dict[str, Any]]] = {}
    red_ids: set[int] = set()
    club_producer_rows: dict[int, dict[str, Any]] = {}
    owner_removed_rows: list[dict[str, Any]] = []
    for sheet in workbook.worksheets:
        matches = []
        for row in range(2, sheet.max_row + 1):
            rid = row_id(sheet, row)
            if rid is None:
                continue
            if rid in OWNER_REMOVED_REVIEW_IDS:
                owner_removed_rows.append({
                    "sheet": sheet.title,
                    "review_number": rid,
                    "row": row,
                    "image": sheet.cell(row, 2).value,
                    "original_review_notes": sheet.cell(row, 6).value,
                    "reason": "Owner-directed removal from the active wine-label dataset.",
                })
            if any(red_cell(sheet.cell(row, col)) for col in range(1, sheet.max_column + 1)):
                red_ids.add(rid)
                matches.append({"review_number": rid, "row": row,
                                "image": sheet.cell(row, 2).value,
                                "original_review_notes": sheet.cell(row, 6).value,
                                "problem": sheet.cell(row, 49).value if sheet.max_column >= 49 else None,
                                "problem_solution_ideas": sheet.cell(row, 50).value if sheet.max_column >= 50 else None})
            producer_value = sheet.cell(row, 8).value
            producer_target = sheet.cell(row, 12).value
            if norm(producer_value or "") == "clubdessommeliers" or norm(producer_target or "") == "clubdessommeliers":
                club_producer_rows[rid] = {"review_number": rid, "image": sheet.cell(row, 2).value,
                                           "producer_original_value": producer_value, "producer_scored_target": producer_target,
                                           "original_review_notes": sheet.cell(row, 6).value}
        red_rows[sheet.title] = matches
    club_producer_ids = set(club_producer_rows)
    owner_removed_ids = set(OWNER_REMOVED_REVIEW_IDS)
    drop_ids = red_ids | club_producer_ids | owner_removed_ids

    original_counts = {sheet.title: sum(1 for row in range(2, sheet.max_row + 1) if row_id(sheet, row) is not None)
                       for sheet in workbook.worksheets}
    present_drop_counts = {
        sheet.title: sum(1 for row in range(2, sheet.max_row + 1) if row_id(sheet, row) in drop_ids)
        for sheet in workbook.worksheets
    }
    for sheet in workbook.worksheets:
        styles = {(r, c): copy.copy(sheet.cell(r, c)._style) for r in range(2, sheet.max_row + 1)
                  for c in range(1, sheet.max_column + 1) if sheet.cell(r, c).has_style}
        hyperlinks = {(r, c): copy.copy(sheet.cell(r, c).hyperlink) for r in range(2, sheet.max_row + 1)
                      for c in range(1, sheet.max_column + 1) if sheet.cell(r, c).hyperlink}
        # Cache styles/hyperlinks transiently while compacting the sheet.
        sheet._wine_body_styles = styles
        sheet._wine_body_hyperlinks = hyperlinks

    records: list[dict[str, Any]] = []
    appended_items = [item for item in labels["records"] if item["review_number"] in APPENDED_REVIEW_IDS]
    if len(appended_items) != 22:
        raise ValueError(f"Expected 22 added candidate photos, got {len(appended_items)}")
    for item in appended_items:
        path = images[item["source_filename"]]
        data = path.read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        declared = next(x["sha256"] for x in manifest["included_images"] if x["source_filename"] == item["source_filename"])
        if sha256 != declared:
            raise ValueError(f"Image hash mismatch: {item['source_filename']}")
        sha1 = hashlib.sha1(data).hexdigest()
        records.append(build_record(item, sha1, sha256))

    labels_by_review = {item["review_number"]: item for item in labels["records"]}
    for sheet in workbook.worksheets:
        apply_scored_targets(sheet, labels_by_review, drop_ids)
        rebuild_rows(sheet, drop_ids, records)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    workbook.save(args.output)
    updated_workbook_sha256 = hashlib.sha256(args.output.read_bytes()).hexdigest()
    output_row_counts = {
        sheet.title: sum(1 for row in range(2, sheet.max_row + 1) if row_id(sheet, row) is not None)
        for sheet in workbook.worksheets
    }
    audit = {
        "source_workbook_url": "https://github.com/n8rtz/Wine-Reference-Drop/blob/main/reference-data-scrubbed.xlsx",
        "source_workbook_blob_sha": "5e1e2b0f8938f1ed789c9f2430cd37881a2b2bdc",
        "source_workbook_sha256": "ab61848cb41903a6bf17c9c1628fe0b071997ca39b5bf44764d10f29f90c0b46",
        "red_rows_removed_from_both_tabs": sorted(red_ids),
        "red_row_detail": red_rows,
        "owner_removed_club_des_sommeliers_review_ids": sorted(club_producer_ids),
        "club_producer_row_detail": list(club_producer_rows.values()),
        "owner_removed_dataset_review_ids": sorted(owner_removed_ids),
        "owner_removed_dataset_row_detail": list(owner_removed_rows.values()),
        "all_removed_review_ids": sorted(drop_ids),
        "existing_rows_before_update": original_counts,
        "existing_rows_retained": {name: count - present_drop_counts[name] for name, count in original_counts.items()},
        "rows_after_appending_22": {sheet.title: sum(1 for row in range(2, sheet.max_row + 1) if row_id(sheet, row) is not None)
                                     for sheet in workbook.worksheets},
        "retained_candidate_count": len(records),
        "producer_exclusion": "Rows whose producer/original producer target is Club des Sommeliers are removed at the owner's direction; branding is not treated as the actual producer.",
        "owner_adjudication_date": "2026-10-28",
        "updated_workbook_sha256": updated_workbook_sha256,
        "updated_active_dataset_count": next(iter(output_row_counts.values()), 0),
        "owner_adjudicated_field_corrections": OWNER_ADJUDICATED_FIELD_CORRECTIONS,
        "output_workbook": args.output.name,
    }
    (args.output.parent / "workbook-update-audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n")
    print(f"Saved {args.output}; removed {len(red_ids)} red rows, {len(club_producer_ids)} rejected Club des Sommeliers producer rows, and {len(owner_removed_ids)} owner-removed rows; appended {len(records)} candidate wines to both tabs.")


if __name__ == "__main__":
    main()
