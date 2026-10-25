#!/usr/bin/env python3
"""Measure exact database coverage of workbook-known names; never run by OCR."""
from __future__ import annotations

import json
import re
import sqlite3
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
LABELS = HERE / "candidate-labels.json"
OUT = ROOT / "app/src/debug/assets/wine-app-labels-v1/gazetteer-coverage.json"
DBS = ROOT / "app/src"


def producer_key(value: str) -> str:
    value = unicodedata.normalize("NFKD", value.lower().replace("’", "'"))
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    return " ".join("".join(ch if ch.isalnum() else " " for ch in value).split())


def appellation_key(value: str) -> str:
    value = unicodedata.normalize("NFD", value.lower())
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    words = " ".join("".join(ch if ch.isalnum() else " " for ch in value).split()).split()
    replacements = {"st": "saint", "ste": "sainte", "mt": "mount"}
    return " ".join(replacements.get(word, word) for word in words)


def target_names(record: dict, field: str) -> set[str]:
    value = record.get(field)
    if value is None or record.get(f"{field}_status") != "expected_value":
        return set()
    return {str(value), *(str(item) for item in record.get(f"{field}_alternates", []))}


def main() -> None:
    doc = json.loads(LABELS.read_text())
    records = doc["records"]
    if len(records) != 125:
        raise ValueError(f"Expected 125 workbook labels, got {len(records)}")

    producer_db = DBS / "main/assets/producer-gazetteer-v1/producers.sqlite"
    wine_db = DBS / "debug/assets/producer-gazetteer-v1/wines.sqlite"
    ttb_db = DBS / "debug/assets/producer-gazetteer-v1/ttb-support.sqlite"
    osm_db = DBS / "debug/assets/producer-gazetteer-v1/osm-support.sqlite"
    xwines_db = DBS / "debug/assets/producer-gazetteer-v1/xwines-candidates.sqlite"
    appellation_db = DBS / "debug/assets/gazetteer-v1/wine_gazetteer.sqlite"

    def query_keys(path: Path, sql: str) -> dict[str, set[str]]:
        conn = sqlite3.connect(path)
        try:
            result: dict[str, set[str]] = {}
            for name, key in conn.execute(sql):
                result.setdefault(producer_key(str(key)), set()).add(str(name))
            return result
        finally:
            conn.close()

    producer_indexes = {
        "lwin_producers": query_keys(producer_db, "SELECT name,key FROM producers"),
        "ttb_support": query_keys(ttb_db, "SELECT id,key FROM support"),
        "osm_support": query_keys(osm_db, "SELECT id,key FROM support"),
        "xwines_candidates": query_keys(xwines_db, "SELECT id,key FROM candidates"),
    }
    wine_index = query_keys(wine_db, "SELECT id,key FROM wine_names")

    conn = sqlite3.connect(appellation_db)
    try:
        appellation_names: set[str] = set()
        place_names: set[str] = set()
        appellation_ids: dict[str, set[str]] = {}
        place_ids: dict[str, set[str]] = {}
        for entity_id, name, level, aliases_json in conn.execute(
            "SELECT id,canonical_name,level,aliases_json FROM entities"
        ):
            aliases = json.loads(aliases_json or "[]")
            forms = {appellation_key(str(value)) for value in [name, *aliases] if value}
            if level == "appellation":
                bucket_names, bucket_ids = appellation_names, appellation_ids
            else:
                bucket_names, bucket_ids = place_names, place_ids
            for form in forms:
                bucket_names.add(form)
                bucket_ids.setdefault(form, set()).add(str(entity_id))
    finally:
        conn.close()

    definitions = [
        ("lwin_producers", "LWIN producers", "producer"),
        ("ttb_support", "TTB candidates", "producer"),
        ("osm_support", "OSM candidates", "producer"),
        ("xwines_candidates", "X-Wines candidates", "producer"),
        ("lwin_wines", "LWIN wine names", "wine_name"),
        ("appellations", "Appellations", "appellation"),
        ("wine_geography", "Regions/subregions", "place"),
    ]
    results = []
    for key, label, kind in definitions:
        matched_labels: set[int] = set()
        matched_records: set[str] = set()
        matched_values: set[str] = set()
        for record in records:
            if kind == "producer":
                values = target_names(record, "producer")
                index = producer_indexes[key]
                normalize = producer_key
                matching_keys = {normalize(name) for name in values}
                ids_by_key = index
            elif kind == "wine_name":
                values = target_names(record, "wine_name")
                matching_keys = {producer_key(name) for name in values}
                ids_by_key = wine_index
            else:
                fields = ("wine_name", "region", "subregion") if kind == "appellation" else ("country", "region", "subregion")
                values = set().union(*(target_names(record, field) for field in fields))
                matching_keys = {appellation_key(name) for name in values}
                ids_by_key = appellation_ids if kind == "appellation" else place_ids
            found_keys = matching_keys & set(ids_by_key)
            if found_keys:
                matched_labels.add(int(record["review_number"]))
                matched_values.update(found_keys)
                for found_key in found_keys:
                    matched_records.update(ids_by_key[found_key])
        results.append({
            "id": key,
            "label": label,
            "matched_labels": len(matched_labels),
            "total_labels": len(records),
            "unique_known_values_matched": len(matched_values),
            "database_records_matched": len(matched_records),
        })

    output = {
        "schema_version": 1,
        "dataset_count": len(records),
        "source_workbook": "tools/evaluation/wine-app-labels-review-v1/reference-data-scrubbed-updated.xlsx",
        "source_workbook_sha256": doc["source_workbook_sha256"],
        "method": "Exact normalized match of workbook-known targets and accepted alternatives to each database's canonical normalized keys; appellations and regions are matched to canonical names and aliases. Each label is counted once per database. This scorer-side audit is separate from OCR and its reference values are never provided to the OCR runner.",
        "databases": results,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for item in results:
        print(f"{item['label']}: {item['matched_labels']}/{item['total_labels']} labels; {item['database_records_matched']} distinct records")


if __name__ == "__main__":
    main()
