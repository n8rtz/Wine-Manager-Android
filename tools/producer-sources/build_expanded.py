#!/usr/bin/env python3
"""Build the Lab v4 expanded producer index (LWIN primary + supplemental candidates).

The verified primary producers.sqlite is copied verbatim (every LWIN row and ID is
preserved, so the primary linked-wine index keeps joining), then supplemental
producer candidates whose normalized keys are absent from the primary export are
appended from the pinned snapshots already audited for source review:

- X-Wines winery names (CC0-1.0) via the XWines Atlas snapshot,
- TTB wine-permit operating names from the user-supplied FOIA permittee cache,
- OpenStreetMap explicit winery names (ODbL-1.0) via the OpenWineMap snapshot.

Supplemental rows are tagged in producer_sources so runtime telemetry attributes
every suggestion to its source. The output is written only to the Lab benchmark
asset folder app/src/main/assets/producer-gazetteer-expanded-v1/; the primary
database and manifest are never modified, and production/Prod pipelines never
open this index. Candidate identity status stays candidate_not_verified_label_producer:
membership is lexical support only, never proof of a label producer.
"""
from __future__ import annotations

import base64
import collections
import hashlib
import importlib.util
import json
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))

import build as staging  # noqa: E402  (osm_record, ttb_record, blob pins)
from prepare_primary import normalize  # noqa: E402

spec = importlib.util.spec_from_file_location("refresh_open_candidates", TOOLS / "refresh_open_candidates.py")
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)

PRIMARY_DIR = ROOT / "app/src/main/assets/producer-gazetteer-v1"
OUT_DIR = ROOT / "app/src/main/assets/producer-gazetteer-expanded-v1"
REPORT = TOOLS / "supplemental-source-refresh.json"
VERSION = "lab-expanded-producers-v1"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def supplemental_id(name: str, source: str) -> str:
    return hashlib.sha256(json.dumps([name, source], ensure_ascii=False).encode()).hexdigest()[:24]


def fetch_ttb_zip(temp: Path) -> Path:
    """Fetch the pinned user-supplied TTB permittee cache via the GitHub blob API."""
    result = subprocess.run(
        ["gh", "api", f"repos/n8rtz/Wine-Reference-Drop/git/blobs/{staging.TTB_BLOB}"],
        check=True, capture_output=True, text=True,
    )
    data = base64.b64decode(json.loads(result.stdout)["content"])
    if staging.blob(data) != staging.TTB_BLOB:
        raise ValueError("TTB archive blob checksum mismatch")
    path = temp / "ttb-rezip.zip"
    path.write_bytes(data)
    return path


def ttb_names(zip_path: Path) -> list[tuple[str, str]]:
    with zipfile.ZipFile(zip_path) as archive:
        member = next(n for n in archive.namelist() if n.endswith("FRL_All_Permits.json"))
        rows = json.loads(archive.read(member))["Permit Data"]
    kept: dict[str, dict] = {}
    for row in rows:
        record, _ = staging.ttb_record(row)
        if record is None:
            continue
        if record["id"] in kept and kept[record["id"]] != record:
            raise ValueError("Conflicting permit identity")
        kept[record["id"]] = record
    return [(record["name"], record["id"]) for record in kept.values()]


def osm_names(snapshot: dict) -> list[tuple[str, str]]:
    records: dict[str, dict] = {}
    with zipfile.ZipFile(snapshot["archive_path"]) as archive:
        prefix = snapshot["repo"].rsplit("/", 1)[-1] + "-" + snapshot["commit"] + "/data/countries/"
        files = sorted(n for n in archive.namelist() if n.startswith(prefix) and n.endswith(".geojson"))
        if not files:
            raise ValueError("No country GeoJSON inputs in the OSM snapshot")
        for name in files:
            payload = json.loads(archive.read(name))
            country = PurePosixPath(name).stem
            for feature in payload.get("features", []):
                record, _ = staging.osm_record(feature, country)
                if record is not None and record["id"] not in records:
                    records[record["id"]] = record
    return [(record["name"], record["id"]) for record in records.values()]


NOTICE = """# Lab expanded producer index — sources and attribution

`producers-expanded.sqlite` is opened ONLY by the benchmark's Lab v4 pipeline.
Production and Prod v1.0/v2.0/v3.0 never read it. Every suggestion it supports is
YELLOW / confirm manually; membership is lexical support, not proof of identity.

## Primary LWIN subset (verbatim)

All rows of the verified primary producer export (see
`../producer-gazetteer-v1/NOTICE.md` and its manifest) are copied unchanged,
including IDs, so the primary linked-wine index keeps joining. Those rows keep
their attested provenance.

## Supplemental candidates (tagged in `producer_sources`)

- **X-Wines** winery names (`source = 'xwines'`). X-Wines dataset, CC0-1.0.
  Citation: de Azambuja, Morais & Filipe (2023), X-Wines,
  https://doi.org/10.3390/bdcc7010020. Names extracted from the JamesOH5/Xwines-Atlas
  pinned snapshot recorded in `manifest.json`; the Atlas repository itself carries
  no license, so only the CC0 dataset's winery name facts are used.
- **TTB wine permits** (`source = 'ttb'`). Operating names of "Wine Producer"
  permit holders from the TTB FOIA List of Permittees
  (https://www.ttb.gov/public-information/foia/list-of-permittees), via the
  user-supplied pinned cache recorded in `manifest.json`. Authenticity/freshness
  of the supplied cache were not independently validated against a fresh export.
- **OpenStreetMap wineries** (`source = 'osm'`). © OpenStreetMap contributors,
  ODbL-1.0; derivative snapshot by OpenHistoryMap/openwinemap at the pinned
  commit in `manifest.json`. Named explicit craft/industrial wineries only.

Supplemental identity status is `candidate_not_verified_label_producer`: filters
remove known non-producer roles but do not establish every surviving record as a
verified grape-wine label producer. No country, region or aliases are inferred
from supplemental records, and source jurisdiction must never fill label origin
fields.
"""


def main() -> None:
    primary_db = PRIMARY_DIR / "producers.sqlite"
    primary_manifest = json.loads((PRIMARY_DIR / "manifest.json").read_text())
    report = json.loads(REPORT.read_text())
    if refresh.db_hash(primary_db) != primary_manifest["database_sha256"]:
        raise ValueError("Primary database hash does not match its manifest")

    with tempfile.TemporaryDirectory(prefix="wine-expanded-build-") as temporary:
        temp = Path(temporary)
        osm_snapshot = refresh.repo_snapshot("openhistorymap/openwinemap", report["osm"]["commit"], temp)
        if osm_snapshot["archive_sha256"] != report["osm"]["archive_sha256"]:
            raise ValueError("OSM snapshot differs from the audited refresh report")
        xwines_snapshot = refresh.repo_snapshot("JamesOH5/Xwines-Atlas", report["xwines"]["atlas_commit"], temp)
        if xwines_snapshot["archive_sha256"] != report["xwines"]["atlas_archive_sha256"]:
            raise ValueError("X-Wines snapshot differs from the audited refresh report")
        xwines_names_list, dataset = refresh.extract_xwines_names(xwines_snapshot)
        ttb = ttb_names(fetch_ttb_zip(temp))
        osm = osm_names(osm_snapshot)

        with sqlite3.connect(primary_db) as con:
            primary_rows = con.execute("SELECT id,name,key,country_group,lwin7 FROM producers").fetchall()
        primary_keys = {row[2] for row in primary_rows}
        primary_ids = {row[0] for row in primary_rows}
        if len(primary_rows) != primary_manifest["records"]:
            raise ValueError("Primary row count differs from its manifest")

        # Precedence on key collisions between supplements: X-Wines (label-facing
        # winery/brand names), then TTB operating names, then OSM mapped names.
        additions: list[tuple[str, str, str, str]] = []  # id, name, key, source
        seen_keys = set(primary_keys)
        counts: dict[str, dict[str, int]] = {}

        def add_candidates(source: str, names: list[tuple[str, str]]) -> None:
            added = 0
            for name, _source_record in names:
                display = name.strip()
                key = normalize(display)
                if not key or key in seen_keys:
                    continue
                seen_keys.add(key)
                additions.append((supplemental_id(display, source), display, key, source))
                added += 1
            counts[source] = {"source_records": len(names), "added": added}

        add_candidates("xwines", [(name, "") for name in xwines_names_list])
        if counts["xwines"]["added"] != report["xwines"]["unique_candidate_keys"]:
            raise ValueError("X-Wines candidate count differs from the audited refresh report")
        add_candidates("ttb", ttb)
        add_candidates("osm", osm)

        new_ids = [row[0] for row in additions]
        if len(set(new_ids)) != len(new_ids) or primary_ids & set(new_ids):
            raise ValueError("Supplemental producer ID collision")

        OUT_DIR.mkdir(parents=True, exist_ok=True)
        out_db = OUT_DIR / "producers-expanded.sqlite"
        staging_db = temp / "producers-expanded.sqlite"
        shutil.copyfile(primary_db, staging_db)
        con = sqlite3.connect(staging_db)
        try:
            con.execute("PRAGMA journal_mode=OFF")
            con.execute("PRAGMA synchronous=OFF")
            con.execute("CREATE TABLE producer_sources(id TEXT PRIMARY KEY,source TEXT NOT NULL)")
            con.executemany(
                "INSERT INTO producers VALUES(?,?,?,?,?)",
                [(pid, name, key, "", "[]") for pid, name, key, _ in additions],
            )
            con.executemany("INSERT INTO names VALUES(?,?)", [(pid, key) for pid, _, key, _ in additions])
            con.executemany(
                "INSERT INTO producer_sources VALUES(?,?)", [(pid, source) for pid, _, _, source in additions]
            )
            con.commit()
            con.execute("ANALYZE")
            con.commit()
            con.execute("VACUUM")
            # Integrity: primary subset byte-identical rows, FTS reachable, counts sane.
            subset = con.execute(
                "SELECT id,name,key,country_group,lwin7 FROM producers WHERE id IN "
                "(SELECT id FROM producers EXCEPT SELECT id FROM producer_sources)"
            ).fetchall()
            if sorted(map(tuple, subset)) != sorted(map(tuple, primary_rows)):
                raise ValueError("Primary subset changed while building the expanded index")
            total = con.execute("SELECT COUNT(*) FROM producers").fetchone()[0]
            if total != len(primary_rows) + len(additions):
                raise ValueError("Expanded row count mismatch")
            if not con.execute("SELECT id FROM names WHERE names MATCH '\"win\"' LIMIT 1").fetchone():
                raise ValueError("Expanded FTS smoke query returned nothing")
            tagged = con.execute("SELECT COUNT(*) FROM producer_sources").fetchone()[0]
            if tagged != len(additions):
                raise ValueError("producer_sources count mismatch")
        finally:
            con.close()
        shutil.copyfile(staging_db, out_db)

    database_sha = refresh.db_hash(out_db)
    per_source = collections.Counter(source for *_rest, source in additions)
    manifest = {
        "schema": 1,
        "kind": "lab-expanded-producer-index",
        "lab_only": True,
        "version": VERSION,
        "records": len(primary_rows) + len(additions),
        "database_sha256": database_sha,
        "wine_database_sha256": primary_manifest["wine_database_sha256"],
        "wine_names": primary_manifest["wine_names"],
        "wine_links": primary_manifest["wine_links"],
        "primary_database_sha256": primary_manifest["database_sha256"],
        "primary_records": len(primary_rows),
        "supplemental_records": len(additions),
        "sources": [
            {"id": "lwin", "records": len(primary_rows),
             "note": "Verified primary producer export copied verbatim; see ../producer-gazetteer-v1/manifest.json"},
            {"id": "xwines", "records": per_source["xwines"], "source_records": counts["xwines"]["source_records"],
             "license": "CC0-1.0", "atlas_repo": "JamesOH5/Xwines-Atlas",
             "atlas_commit": report["xwines"]["atlas_commit"],
             "atlas_archive_sha256": report["xwines"]["atlas_archive_sha256"],
             "xwines_repo": report["xwines"]["xwines_repo"], "xwines_commit": report["xwines"]["xwines_commit"],
             "dataset": dataset,
             "citation": "de Azambuja, Morais & Filipe (2023), X-Wines, https://doi.org/10.3390/bdcc7010020"},
            {"id": "ttb", "records": per_source["ttb"], "source_records": counts["ttb"]["source_records"],
             "license_status": "Supplied archive claims public-domain/CC0; authenticity not independently validated",
             "input_git_blob": staging.TTB_BLOB, "input_repo": "n8rtz/Wine-Reference-Drop",
             "source_url": "https://www.ttb.gov/public-information/foia/list-of-permittees"},
            {"id": "osm", "records": per_source["osm"], "source_records": counts["osm"]["source_records"],
             "license": "ODbL-1.0", "attribution": "© OpenStreetMap contributors",
             "repo": "openhistorymap/openwinemap", "commit": report["osm"]["commit"],
             "archive_sha256": report["osm"]["archive_sha256"]},
        ],
        "identity_status": "candidate_not_verified_label_producer",
        "built_by": "tools/producer-sources/build_expanded.py",
    }
    (OUT_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (OUT_DIR / "NOTICE.md").write_text(NOTICE)
    print(json.dumps({"out": str(out_db), "bytes": out_db.stat().st_size, "records": manifest["records"],
                      "supplemental": dict(per_source), "database_sha256": database_sha}, indent=2))


if __name__ == "__main__":
    main()
