#!/usr/bin/env python3
"""Refresh source-separated OpenWineMap and X-Wines producer-name candidates.

Fetches GitHub/codeload snapshots only, never executes downloaded content, and
writes X-Wines-derived candidates only to the debug/Lab supplemental index.
The verified primary LWIN database is never modified.
"""
from __future__ import annotations
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sqlite3
import tempfile
import urllib.request
import zipfile

from build import osm_record
from prepare_primary import normalize

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PRIMARY = ROOT / "app/src/main/assets/producer-gazetteer-v1/producers.sqlite"
DEFAULT_TTB = None
DEFAULT_MANIFEST = ROOT / "app/src/main/assets/producer-gazetteer-v1/manifest.json"
DEFAULT_OUT = Path("/tmp/wine-producer-candidate-review")
DEFAULT_REPORT = ROOT / "tools/producer-sources/supplemental-source-refresh.json"
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_HTML_BYTES = 48 * 1024 * 1024
UA = "Wine-Manager-Android supplemental producer data refresh (GitHub API; no auth)"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def request_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": UA})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError("GitHub API response unexpectedly large")
    return json.loads(data)


def repo_snapshot(repo: str, commit: str | None, temp: Path) -> dict:
    metadata = request_json(f"https://api.github.com/repos/{repo}")
    branch = metadata.get("default_branch")
    if not branch:
        raise ValueError(f"No default branch for {repo}")
    if commit is None:
        commit_info = request_json(f"https://api.github.com/repos/{repo}/commits/{branch}")
        commit = commit_info.get("sha")
        commit_date = commit_info.get("commit", {}).get("committer", {}).get("date")
    else:
        commit_info = request_json(f"https://api.github.com/repos/{repo}/commits/{commit}")
        commit = commit_info.get("sha")
        commit_date = commit_info.get("commit", {}).get("committer", {}).get("date")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError(f"Invalid resolved Git commit for {repo}")
    url = f"https://codeload.github.com/{repo}/zip/{commit}"
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=120) as response:
        chunks = []
        total = 0
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            if total > MAX_ARCHIVE_BYTES:
                raise ValueError(f"{repo} archive exceeds {MAX_ARCHIVE_BYTES} bytes")
            chunks.append(block)
    archive = b"".join(chunks)
    path = temp / (repo.rsplit("/", 1)[-1] + ".zip")
    path.write_bytes(archive)
    prefix = repo.rsplit("/", 1)[-1] + "-" + commit + "/"
    with zipfile.ZipFile(path) as zf:
        if not any(name.startswith(prefix) for name in zf.namelist()):
            raise ValueError(f"Codeload archive root does not match {repo}@{commit}")
        if any(PurePosixPath(name).is_absolute() or ".." in PurePosixPath(name).parts for name in zf.namelist()):
            raise ValueError("Unsafe path in source archive")
    license_info = metadata.get("license") or {}
    return dict(repo=repo, branch=branch, commit=commit, commit_date=commit_date,
                archive_sha256=sha(archive), archive_bytes=len(archive),
                archive_url=url, repository_license=license_info.get("spdx_id"), archive_path=path)


def extract_xwines_names(snapshot: dict) -> tuple[list[str], dict]:
    with zipfile.ZipFile(snapshot["archive_path"]) as zf:
        prefix = snapshot["repo"].rsplit("/", 1)[-1] + "-" + snapshot["commit"] + "/"
        members = [n for n in zf.namelist() if n.startswith(prefix) and n.endswith("/index.html")]
        if len(members) != 1:
            raise ValueError(f"Expected one XWines Atlas index.html, found {len(members)}")
        raw = zf.read(members[0])
    if len(raw) > MAX_HTML_BYTES:
        raise ValueError("XWines Atlas payload exceeds the safety limit")
    html = raw.decode("utf-8")
    match = re.search(r"window\.__XWINES_PREBAKED__\s*=\s*", html)
    if not match:
        raise ValueError("No precomputed X-Wines payload found")
    payload, _ = json.JSONDecoder().raw_decode(html, match.end())
    wineries = payload.get("wine_table", {}).get("wineries")
    if not isinstance(wineries, list) or not wineries or any(not isinstance(n, str) or not n.strip() for n in wineries):
        raise ValueError("Malformed X-Wines winery list")
    if payload.get("allWineriesCount") != len(wineries) or payload.get("grand_total_wineries") != len(wineries):
        raise ValueError("X-Wines winery count markers do not match the list")
    total_wines = payload.get("grand_total_wines")
    if not isinstance(total_wines, int) or total_wines < 100_000:
        raise ValueError("X-Wines wine-count marker is unexpectedly small")
    dataset = dict(loaded_at=payload.get("loaded_at"), winery_rows=len(wineries),
                   unique_raw_names=len(set(wineries)), unique_normalized_names=len({normalize(n) for n in wineries}),
                   grand_total_wines=total_wines, grand_total_wineries=payload.get("grand_total_wineries"),
                   countries=len(payload.get("countries", [])), regions=len(payload.get("regions", [])))
    return wineries, dataset


def create_xwines_db(path: Path, names: list[str], primary_keys: set[str]) -> dict:
    if not primary_keys:
        raise ValueError("Primary LWIN index is empty")
    path.unlink(missing_ok=True)
    con = sqlite3.connect(path)
    try:
        con.execute("PRAGMA journal_mode=OFF")
        con.execute("PRAGMA synchronous=OFF")
        con.executescript("""
            CREATE TABLE support(key TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(key,id)) WITHOUT ROWID;
            CREATE TABLE candidates(id TEXT PRIMARY KEY,name TEXT NOT NULL,key TEXT NOT NULL,source_index INTEGER NOT NULL);
            CREATE INDEX candidate_exact ON candidates(key);
            CREATE VIRTUAL TABLE candidate_names USING fts5(id UNINDEXED,key,tokenize='trigram');
        """)
        support_rows = []
        candidate_rows = []
        unique_candidate_keys = set()
        for index, name in enumerate(names):
            key = normalize(name)
            if not key:
                raise ValueError(f"Empty normalized X-Wines name at row {index}")
            source_id = f"xwines:{index}"
            support_rows.append((key, source_id))
            if key not in primary_keys:
                candidate_rows.append((source_id, name.strip(), key, index))
                unique_candidate_keys.add(key)
        con.executemany("INSERT OR IGNORE INTO support VALUES(?,?)", support_rows)
        con.executemany("INSERT INTO candidates VALUES(?,?,?,?)", candidate_rows)
        con.executemany("INSERT INTO candidate_names VALUES(?,?)", [(r[0], r[2]) for r in candidate_rows])
        con.execute("ANALYZE")
        con.commit()
        con.execute("VACUUM")
        counts = dict(source_rows=len(names), support_rows=con.execute("SELECT COUNT(*) FROM support").fetchone()[0],
                      candidates=len(candidate_rows), unique_candidate_keys=len(unique_candidate_keys),
                      candidates_matching_primary=0)
        if con.execute("SELECT COUNT(*) FROM candidates c JOIN (SELECT key FROM candidates GROUP BY key HAVING COUNT(*)>1) d USING(key)").fetchone()[0]:
            counts["duplicate_candidate_rows_by_normalized_key"] = con.execute(
                "SELECT COUNT(*) FROM candidates c JOIN (SELECT key FROM candidates GROUP BY key HAVING COUNT(*)>1) d USING(key)").fetchone()[0]
        return counts
    finally:
        con.close()


def create_osm_db(path: Path, snapshot: dict) -> dict:
    path.unlink(missing_ok=True)
    records = {}
    excluded = collections.Counter()
    input_count = 0
    file_hashes = {}
    with zipfile.ZipFile(snapshot["archive_path"]) as zf:
        prefix = snapshot["repo"].rsplit("/", 1)[-1] + "-" + snapshot["commit"] + "/data/countries/"
        files = sorted(n for n in zf.namelist() if n.startswith(prefix) and n.endswith(".geojson"))
        if not files:
            raise ValueError("No country GeoJSON inputs in the OSM snapshot")
        for name in files:
            raw = zf.read(name)
            file_hashes[PurePosixPath(name).name] = sha(raw)
            country = PurePosixPath(name).stem
            payload = json.loads(raw)
            for feature in payload.get("features", []):
                input_count += 1
                record, reason = osm_record(feature, country)
                if reason:
                    excluded[reason] += 1
                elif record["id"] in records:
                    excluded["duplicate_osm_id"] += 1
                else:
                    records[record["id"]] = record
    path.touch()
    con = sqlite3.connect(path)
    try:
        con.execute("PRAGMA journal_mode=OFF")
        con.execute("PRAGMA synchronous=OFF")
        con.execute("CREATE TABLE support(key TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(key,id)) WITHOUT ROWID")
        rows = sorted((normalize(row["name"]), row["id"]) for row in records.values() if normalize(row["name"]))
        con.executemany("INSERT OR IGNORE INTO support VALUES(?,?)", rows)
        con.commit()
        con.execute("VACUUM")
        return dict(input_features=input_count, records=len(records), support_rows=len(rows),
                    distinct_name_keys=len({r[0] for r in rows}), excluded=dict(excluded), input_files=file_hashes)
    finally:
        con.close()


def primary_keys(path: Path) -> set[str]:
    with sqlite3.connect(path) as con:
        return {row[0] for row in con.execute("SELECT DISTINCT key FROM producers")}


def support_keys(path: Path | None) -> set[str]:
    if not path or not path.exists():
        return set()
    with sqlite3.connect(path) as con:
        return {row[0] for row in con.execute("SELECT DISTINCT key FROM support")}


def db_hash(path: Path) -> str:
    return sha(path.read_bytes())


def refresh(out: Path, manifest_path: Path, primary: Path, ttb: Path | None,
            osm_commit: str | None = None, xwines_commit: str | None = None,
            report_path: Path = DEFAULT_REPORT) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    if not primary.exists() or not manifest_path.exists():
        raise ValueError("Verified primary LWIN database and manifest are required")
    if out.resolve().is_relative_to((ROOT / "app/src").resolve()):
        raise ValueError("Unverified source-review databases cannot be written into packaged app assets")
    packaged_manifest = json.loads(manifest_path.read_text())
    if packaged_manifest.get("support") or packaged_manifest.get("support_attribution"):
        raise ValueError("Remove unverified support sources from the packaged manifest before source review")
    with tempfile.TemporaryDirectory(prefix="wine-source-refresh-") as temporary:
        temp = Path(temporary)
        osm_source = repo_snapshot("openhistorymap/openwinemap", osm_commit, temp)
        xwines_source = repo_snapshot("JamesOH5/Xwines-Atlas", xwines_commit, temp)
        xwines_metadata = request_json("https://api.github.com/repos/rogerioxavier/X-Wines")
        license_id = (xwines_metadata.get("license") or {}).get("spdx_id")
        if license_id != "CC0-1.0":
            raise ValueError(f"Expected X-Wines CC0-1.0 upstream license, got {license_id!r}")
        xwines_commit_info = request_json(f"https://api.github.com/repos/rogerioxavier/X-Wines/commits/{xwines_metadata['default_branch']}")
        xwines_source_commit = xwines_commit_info.get("sha")
        if not isinstance(xwines_source_commit, str) or not re.fullmatch(r"[0-9a-f]{40}", xwines_source_commit):
            raise ValueError("Invalid X-Wines source repository commit")
        names, dataset = extract_xwines_names(xwines_source)
        lwin_keys = primary_keys(primary)
        old_osm = out / "osm-support.sqlite"
        old_osm_keys = support_keys(old_osm)
        ttb_keys = support_keys(ttb)
        new_osm_db = temp / "osm-support.sqlite"
        new_osm_counts = create_osm_db(new_osm_db, osm_source)
        xwines_db = temp / "xwines-candidates.sqlite"
        xwines_counts = create_xwines_db(xwines_db, names, lwin_keys)
        xwines_keys = {normalize(n) for n in names}
        new_osm_keys = support_keys(new_osm_db)
        all_existing = lwin_keys | ttb_keys | new_osm_keys
        source_not_yet_covered = len(xwines_keys - all_existing)
        if old_osm.exists():
            with sqlite3.connect(old_osm) as con:
                old_id_to_key = {row[1]: row[0] for row in con.execute("SELECT key,id FROM support")}
        else:
            old_id_to_key = {}
        with sqlite3.connect(new_osm_db) as con:
            new_id_to_key = {row[1]: row[0] for row in con.execute("SELECT key,id FROM support")}
        osm_new_ids = set(new_id_to_key) - set(old_id_to_key)
        osm_removed_ids = set(old_id_to_key) - set(new_id_to_key)
        osm_renamed_ids = {item for item in set(old_id_to_key) & set(new_id_to_key) if old_id_to_key[item] != new_id_to_key[item]}
        lwin_old_osm = lwin_keys & old_osm_keys
        lwin_new_osm = lwin_keys & new_osm_keys
        result = dict(schema_version=1, generated_for="offline_source_review_only",
            osm=dict(repo=osm_source["repo"], commit=osm_source["commit"], commit_date=osm_source["commit_date"],
                     archive_sha256=osm_source["archive_sha256"], archive_bytes=osm_source["archive_bytes"],
                     url=osm_source["archive_url"], license="ODbL-1.0", **new_osm_counts,
                     prior_records=len(old_id_to_key), new_osm_ids=len(osm_new_ids), removed_osm_ids=len(osm_removed_ids),
                     renamed_osm_ids=len(osm_renamed_ids), exact_lwin_support_keys_before=len(lwin_old_osm),
                     exact_lwin_support_keys_after=len(lwin_new_osm), new_exact_lwin_support_keys=len(lwin_new_osm-lwin_old_osm)),
            xwines=dict(atlas_repo=xwines_source["repo"], atlas_commit=xwines_source["commit"],
                     atlas_commit_date=xwines_source["commit_date"], atlas_archive_sha256=xwines_source["archive_sha256"],
                     atlas_archive_bytes=xwines_source["archive_bytes"], atlas_url=xwines_source["archive_url"],
                     atlas_repo_license=xwines_source["repository_license"],
                     xwines_repo="rogerioxavier/X-Wines", xwines_commit=xwines_source_commit,
                     xwines_license=license_id, dataset=dataset, **xwines_counts,
                     new_distinct_names_vs_primary=len(xwines_keys-lwin_keys),
                     new_distinct_names_vs_current_sources=source_not_yet_covered,
                     role="unverified winery/producer/brand name candidate; no country or aliases inferred",
                     citation="de Azambuja, Morais & Filipe (2023), X-Wines, https://doi.org/10.3390/bdcc7010020"))
        osm_destination = out / "osm-support.sqlite"
        xwines_destination = out / "xwines-candidates.sqlite"
        os.replace(new_osm_db, osm_destination)
        os.replace(xwines_db, xwines_destination)
        result["artifacts"] = {
            "osm_review_database_sha256": db_hash(osm_destination),
            "xwines_review_database_sha256": db_hash(xwines_destination),
            "review_output_directory": str(out.resolve()),
            "app_manifest_modified": False,
        }
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--primary", type=Path, default=DEFAULT_PRIMARY)
    parser.add_argument("--ttb-support", type=Path, default=DEFAULT_TTB)
    parser.add_argument("--osm-commit")
    parser.add_argument("--xwines-commit")
    args = parser.parse_args()
    report = refresh(args.out, args.manifest, args.primary, args.ttb_support,
                     args.osm_commit, args.xwines_commit)
    print(json.dumps(report, ensure_ascii=False, indent=2))
