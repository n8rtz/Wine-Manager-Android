#!/usr/bin/env python3
"""Measure exact database coverage of workbook-known names; never run by OCR.

Covers every lookup database the scanners consult: the LWIN producer/wine
SQLite asset, the Lab v4 expanded producer index (LWIN subset plus X-Wines,
TTB and OpenStreetMap supplemental candidates), the debug appellation
gazetteer, and the scanner knowledge base (fixture seed plus the bundled
origins supplement: the generated editorial / UC Davis AVA catalog merged
with the legacy list).
"""
from __future__ import annotations

import csv
import json
import re
import sqlite3
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
LABELS = HERE / "candidate-labels.json"
OUT = ROOT / "app/src/main/assets/wine-app-labels-v1/gazetteer-coverage.json"
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


def norm_key(value: str) -> str:
    """Mirror of TextNorm.normalize: NFKD, strip combining marks, lowercase."""
    value = unicodedata.normalize("NFKD", value)
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    return " ".join(value.lower().split())


# BundledOrigins.countryCode sources: the authored table plus the generated catalog.
LEGACY_COUNTRY_CODES = {
    "France": "FR", "Italy": "IT", "Spain": "ES", "Portugal": "PT", "Germany": "DE",
    "Greece": "GR", "Georgia": "GE", "United States": "US", "Australia": "AU",
    "New Zealand": "NZ", "South Africa": "ZA", "Chile": "CL", "Argentina": "AR",
}


def raw_block(kotlin_source: str) -> str:
    match = re.search(r'val source = """(.*?)"""', kotlin_source, re.S)
    if match is None:
        raise ValueError("Bundled source block not found")
    return match.group(1)


def load_scanner_kb() -> dict[str, dict[str, set[str]]]:
    """Rebuild the scanner knowledge base exactly as the runtime assembles it.

    FixtureKnowledgeBase.snapshot seeds wine-label-kb.sqlite; the extractors also
    consult BundledProducerNames; SupplementedWineKnowledgeBase folds BundledOrigins
    terms (generated wine-knowledge catalog with legacy aliases, then legacy terms)
    into the place list with runtime type assignment.
    """
    kb_dir = ROOT / "app/src/main/java/com/yourapp/wine/kb"
    producers: dict[str, set[str]] = {}
    wine_names: dict[str, set[str]] = {}
    appellations: dict[str, set[str]] = {}
    places: dict[str, set[str]] = {}

    def index(bucket: dict[str, set[str]], forms: set[str], record_id: str) -> None:
        for form in forms:
            if form:
                bucket.setdefault(form, set()).add(record_id)

    fixture = (kb_dir / "FixtureKnowledgeBase.kt").read_text()
    known_place_type: dict[tuple[str, str], str] = {}
    for name, place_type, code in re.findall(
        r'Place\(\d+,\s*"([^"]+)",\s*"(country|region|subregion|appellation)",\s*(?:null|\d+),\s*"([A-Z]{2})"', fixture
    ):
        known_place_type[(code, norm_key(name))] = place_type
        index(appellations if place_type == "appellation" else places, {appellation_key(name)}, name)
    for name, aliases in re.findall(
        r'Producer\(\d+,\s*"([^"]+)",\s*"[A-Z]{2}",\s*\d+(?:,\s*listOf\(([^)]*)\))?\)', fixture
    ):
        forms = {name, *(a.strip().strip('"') for a in aliases.split(",") if a.strip())}
        index(producers, {producer_key(form) for form in forms}, name)
    for name in re.findall(r'Cuvee\(\d+,\s*"([^"]+)"\)', fixture):
        index(wine_names, {producer_key(name)}, name)

    for line in raw_block((kb_dir / "BundledProducerNames.kt").read_text()).splitlines():
        line = line.strip()
        if not line:
            continue
        name, _, alias_text = line.partition("|")
        forms = {name.strip(), *(a.strip() for a in alias_text.split(";") if a.strip())}
        index(producers, {producer_key(form) for form in forms if form}, name.strip())

    legacy_terms = []
    for line in raw_block((kb_dir / "BundledOrigins.kt").read_text()).splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("|")
        aliases = [a.strip() for a in parts[5].split(";")] if len(parts) > 5 else []
        legacy_terms.append((parts[0].strip(), parts[1].strip(), parts[2].strip(), aliases))
    legacy_aliases = {(country, norm_key(name)): aliases for name, country, _, aliases in legacy_terms}

    with (ROOT / "data/wine-knowledge/origins.csv").open(newline="") as handle:
        catalog = list(csv.DictReader(handle))
    named: dict[tuple[str, str], tuple[str, list[str]]] = {}
    for row in catalog:
        regions = [r.strip() for r in row["regions"].split(";") if r.strip()]
        named[(row["country_code"], norm_key(row["name"]))] = (row["name_role"], regions)
    terms = []
    for row in catalog:
        regions = named[(row["country_code"], norm_key(row["name"]))][1]
        aliases = [a.strip() for a in row["aliases"].split(";") if a.strip()]
        aliases += legacy_aliases.get((row["country"], norm_key(row["name"])), [])
        terms.append((row["name"], row["country"], regions[0] if len(regions) == 1 else "", aliases))
    terms += legacy_terms

    country_codes = dict(LEGACY_COUNTRY_CODES)
    country_codes.update({row["country"]: row["country_code"] for row in catalog})

    def add_place(name: str, place_type: str, code: str, forms: list[str]) -> None:
        key = (code, norm_key(name))
        effective = known_place_type.setdefault(key, place_type)
        bucket = appellations if effective == "appellation" else places
        index(bucket, {appellation_key(form) for form in forms if form}, name)

    seen: set[tuple[str, str]] = set()
    for name, country, region, aliases in terms:
        dedupe = (country, norm_key(name))
        if dedupe in seen:
            continue
        seen.add(dedupe)
        code = country_codes.get(country)
        if code is None:
            continue
        add_place(country, "country", code, [country])
        if region:
            add_place(region, "region", code, [region])
        role, origin_regions = named.get((code, norm_key(name)), (None, []))
        if role == "geography":
            place_type = "region" if not region and not origin_regions else "subregion"
        else:
            place_type = "appellation"
        if norm_key(name) != norm_key(region or country):
            add_place(name, place_type, code, [name, *aliases])
    if ("IT", norm_key("Tuscany")) in known_place_type:
        add_place("Montalcino", "subregion", "IT", ["Montalcino"])

    return {
        "producers": producers,
        "wine_names": wine_names,
        "appellations": appellations,
        "places": places,
    }


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
    expanded_db = DBS / "main/assets/producer-gazetteer-expanded-v1/producers-expanded.sqlite"
    wine_db = DBS / "main/assets/producer-gazetteer-v1/wines.sqlite"
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

    scanner_kb = load_scanner_kb()
    producer_indexes = {
        "lwin_producers": query_keys(producer_db, "SELECT name,key FROM producers"),
        "expanded_producers": query_keys(expanded_db, "SELECT name,key FROM producers"),
        "kb_producers": scanner_kb["producers"],
    }
    wine_indexes = {
        "lwin_wines": query_keys(wine_db, "SELECT id,key FROM wine_names"),
        "kb_wine_names": scanner_kb["wine_names"],
    }

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
        ("expanded_producers", "Expanded producers (Lab)", "producer"),
        ("lwin_wines", "LWIN wine names", "wine_name"),
        ("appellations", "Appellations", "appellation"),
        ("wine_geography", "Regions/subregions", "place"),
        ("kb_producers", "Scanner KB producers", "producer"),
        ("kb_wine_names", "Scanner KB wine names", "wine_name"),
        ("kb_appellations", "Scanner KB appellations", "kb_appellation"),
        ("kb_geography", "Scanner KB regions", "kb_place"),
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
                ids_by_key = wine_indexes[key]
            else:
                fields = ("wine_name", "region", "subregion") if kind.endswith("appellation") else ("country", "region", "subregion")
                values = set().union(*(target_names(record, field) for field in fields))
                matching_keys = {appellation_key(name) for name in values}
                ids_by_key = {
                    "appellation": appellation_ids,
                    "place": place_ids,
                    "kb_appellation": scanner_kb["appellations"],
                    "kb_place": scanner_kb["places"],
                }[kind]
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
        "method": "Exact normalized match of workbook-known targets and accepted alternatives to each database's canonical normalized keys; appellations and regions are matched to canonical names and aliases. Each label is counted once per database. Scanner KB rows reproduce the knowledge base as the runtime assembles it: the fixture snapshot seeding wine-label-kb.sqlite, the bundled producer vocabulary, and the bundled origins supplement (generated editorial / UC Davis AVA catalog merged with the legacy list), with runtime place-type assignment. This scorer-side audit is separate from OCR and its reference values are never provided to the OCR runner.",
        "databases": results,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for item in results:
        print(f"{item['label']}: {item['matched_labels']}/{item['total_labels']} labels; {item['database_records_matched']} distinct records")


if __name__ == "__main__":
    main()
