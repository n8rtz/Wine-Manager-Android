#!/usr/bin/env python3
"""Measure exact database coverage of workbook-known names; never run by OCR.

Covers every lookup database the scanners consult: the LWIN producer/wine
SQLite asset, the Lab v5 expanded producer index (LWIN subset plus X-Wines,
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
EXPECTED_LABELS = 124

APOSTROPHE_ACUTE_RE = re.compile(r"\b([dlcnst]|qu)\u00b4(?=[aeiouyh])", re.IGNORECASE)
SPACING_ACCENTS_RE = re.compile(r"[\u0060\u00b4\u00a8\u00b8\u00af\u02c6\u02dc\u02d8-\u02dd]")
COMBINING_MARKS_RE = re.compile(r"[\u0300-\u036f\u1ab0-\u1aff\u1dc0-\u1dff\u20d0-\u20ff\ufe20-\ufe2f]+")
LETTER_FOLDS = {
    "\u00df": "ss", "\u1e9e": "SS", "\u00e6": "ae", "\u00c6": "AE",
    "\u0153": "oe", "\u0152": "OE", "\u00f8": "o", "\u00d8": "O",
    "\u0142": "l", "\u0141": "L", "\u0111": "d", "\u0110": "D",
    "\u00f0": "d", "\u00d0": "D", "\u00fe": "th", "\u00de": "TH",
    "\u0131": "i", "\u0130": "I", "\u0133": "ij", "\u0132": "IJ",
}


def scrub_accents(value: str) -> str:
    if not value or value.isascii():
        return value.replace("`", "")
    step1 = APOSTROPHE_ACUTE_RE.sub(r"\1'", value)
    step2 = SPACING_ACCENTS_RE.sub("", step1)
    folded = "".join(LETTER_FOLDS.get(ch, ch) for ch in step2)
    decomposed = unicodedata.normalize("NFD", folded)
    stripped = COMBINING_MARKS_RE.sub("", decomposed)
    return unicodedata.normalize("NFC", stripped)


def producer_key(value: str) -> str:
    value = unicodedata.normalize("NFKD", scrub_accents(value).lower().replace("’", "'"))
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    return " ".join("".join(ch if ch.isalnum() else " " for ch in value).split())


def appellation_key(value: str) -> str:
    value = unicodedata.normalize("NFD", scrub_accents(value).lower())
    value = "".join(ch for ch in value if not unicodedata.combining(ch))
    words = " ".join("".join(ch if ch.isalnum() else " " for ch in value).split()).split()
    replacements = {"st": "saint", "ste": "sainte", "mt": "mount"}
    return " ".join(replacements.get(word, word) for word in words)


def norm_key(value: str) -> str:
    """Mirror of TextNorm.normalize: scrub accents, NFKD, strip combining marks, lowercase."""
    value = unicodedata.normalize("NFKD", scrub_accents(value))
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
        clean_id = scrub_accents(record_id)
        for form in forms:
            if form:
                bucket.setdefault(form, set()).add(clean_id)

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

    grapes: dict[str, set[str]] = {}
    for block in re.findall(r'Source = """(.*?)"""', (kb_dir / "BundledGrapes.kt").read_text(), re.S):
        for line in block.splitlines():
            line = line.strip()
            if not line:
                continue
            name, _, alias_text = line.partition("|")
            forms = {name.strip(), *(a.strip() for a in alias_text.split(";") if a.strip())}
            index(grapes, {producer_key(form) for form in forms if form}, f"grape:{name.strip()}")

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
        "grapes": grapes,
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
    if len(records) != EXPECTED_LABELS:
        raise ValueError(f"Expected {EXPECTED_LABELS} workbook labels, got {len(records)}")

    producer_db = DBS / "main/assets/producer-gazetteer-v1/producers.sqlite"
    expanded_db = DBS / "main/assets/producer-gazetteer-expanded-v1/producers-expanded.sqlite"
    wine_db = DBS / "main/assets/producer-gazetteer-v1/wines.sqlite"
    appellation_db = DBS / "debug/assets/gazetteer-v1/wine_gazetteer.sqlite"

    def query_keys(path: Path, sql: str) -> dict[str, set[str]]:
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        try:
            result: dict[str, set[str]] = {}
            for name, key in conn.execute(sql):
                result.setdefault(producer_key(str(key)), set()).add(scrub_accents(str(name)))
            return result
        finally:
            conn.close()

    scanner_kb = load_scanner_kb()
    producer_indexes = {
        "lwin_producers": query_keys(producer_db, "SELECT name,key FROM producers"),
        "expanded_producers": query_keys(expanded_db, "SELECT name,key FROM producers"),
        "approved_brands": query_keys(expanded_db, "SELECT p.name,p.key FROM producers p JOIN producer_sources s ON s.id=p.id WHERE s.source='approved_brand'"),
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
        ("approved_brands", "Owner-approved wine brands (Lab subset)", "producer"),
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

    def merge_indexes(tagged: list[tuple[str, dict[str, set[str]]]]) -> dict[str, set[str]]:
        merged: dict[str, set[str]] = {}
        for prefix, index in tagged:
            for key, record_ids in index.items():
                bucket = merged.setdefault(key, set())
                for record_id in record_ids:
                    bucket.add(f"{prefix}:{record_id}")
        return merged

    combined_producers = merge_indexes([
        ("lwin_producers", producer_indexes["lwin_producers"]),
        ("expanded_producers", producer_indexes["expanded_producers"]),
        ("kb_producers", producer_indexes["kb_producers"]),
    ])
    combined_wine_index = merge_indexes([
        ("lwin_wines", wine_indexes["lwin_wines"]),
        ("kb_wine_names", wine_indexes["kb_wine_names"]),
    ])
    combined_geography = merge_indexes([
        ("appellation", appellation_ids),
        ("place", place_ids),
        ("kb_appellation", scanner_kb["appellations"]),
        ("kb_place", scanner_kb["places"]),
    ])
    combined_grapes = merge_indexes([
        ("kb_grapes", scanner_kb["grapes"]),
    ])

    parameter_definitions = [
        ("producer", "Producer"),
        ("wine_name", "Wine name"),
        ("country", "Country"),
        ("region", "Region"),
        ("subregion", "Subregion"),
    ]
    parameters = []
    for field, label in parameter_definitions:
        known_labels = 0
        expected_blank_labels = 0
        unresolved_labels = 0
        matched_labels: set[int] = set()
        matched_values: set[str] = set()
        matched_records: set[str] = set()
        wine_index_only_labels: set[int] = set()
        appellation_or_varietal_labels: set[int] = set()

        for record in records:
            status = record.get(f"{field}_status")
            if status == "expected_blank":
                expected_blank_labels += 1
                continue
            if status == "unresolved":
                unresolved_labels += 1
                continue
            if status != "expected_value":
                continue
            known_labels += 1
            values = target_names(record, field)
            review_id = int(record["review_number"])

            if field == "producer":
                found = {producer_key(name) for name in values} & set(combined_producers)
                if found:
                    matched_labels.add(review_id)
                    matched_values.update(found)
                    for key in found:
                        matched_records.update(combined_producers[key])
            elif field == "wine_name":
                prod_keys = {producer_key(name) for name in values}
                app_keys = {appellation_key(name) for name in values}
                found_wine = prod_keys & set(combined_wine_index)
                found_geo = app_keys & set(combined_geography)
                found_grape = prod_keys & set(combined_grapes)
                if found_wine or found_geo or found_grape:
                    matched_labels.add(review_id)
                    matched_values.update(found_wine | found_geo | found_grape)
                    if found_wine:
                        wine_index_only_labels.add(review_id)
                    else:
                        appellation_or_varietal_labels.add(review_id)
                    for key in found_wine:
                        matched_records.update(combined_wine_index[key])
                    for key in found_geo:
                        matched_records.update(combined_geography[key])
                    for key in found_grape:
                        matched_records.update(combined_grapes[key])
            else:
                found = {appellation_key(name) for name in values} & set(combined_geography)
                if found:
                    matched_labels.add(review_id)
                    matched_values.update(found)
                    for key in found:
                        matched_records.update(combined_geography[key])

        entry = {
            "id": field,
            "label": label,
            "matched_labels": len(matched_labels),
            "total_labels": known_labels,
            "dataset_labels": len(records),
            "expected_blank_labels": expected_blank_labels,
            "unresolved_labels": unresolved_labels,
            "unique_known_values_matched": len(matched_values),
            "database_records_matched": len(matched_records),
        }
        if field == "wine_name":
            entry["wine_index_matched_labels"] = len(wine_index_only_labels)
            entry["appellation_or_varietal_matched_labels"] = len(appellation_or_varietal_labels)
        parameters.append(entry)

    output = {
        "schema_version": 2,
        "dataset_count": len(records),
        "source_workbook": "tools/evaluation/wine-app-labels-review-v1/reference-data-scrubbed-updated.xlsx",
        "source_workbook_sha256": doc["source_workbook_sha256"],
        "method": "Exact normalized match of workbook-known targets and accepted alternatives across all packaged lookup databases consulted by the app. Parameter totals count labels with a known expected value for each parameter (excluding expected blanks and unresolved targets) matched in any consulted database for that parameter: producers across LWIN, Expanded Lab, and Scanner KB; wine names across LWIN wine names, Scanner KB cuvees, appellation/place databases for appellation-named wines, and bundled grape varietals; country, region, and subregion across packaged geography and appellation databases. Source-level database rows remain recorded under databases for provenance auditing. This scorer-side audit is separate from OCR and its reference values are never provided to the OCR runner.",
        "parameters": parameters,
        "databases": results,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    for item in parameters:
        print(f"Parameter {item['label']}: {item['matched_labels']}/{item['total_labels']} known labels ({item['database_records_matched']} distinct records)")
    for item in results:
        print(f"{item['label']}: {item['matched_labels']}/{item['total_labels']} labels; {item['database_records_matched']} distinct records")


if __name__ == "__main__":
    main()
