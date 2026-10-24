#!/usr/bin/env python3
"""Explicit maintainer-only refresh. Fetch pinned CC0 metadata, never geometry/models or user images.
Uses the existing gh connection. Not called by Gradle or the Android application.
"""
import concurrent.futures, json, subprocess
from pathlib import Path
REPO = "UCDavisLibrary/ava"
COMMIT = "a00174df3809f4edcea101f4489961d8b97e4ff9"
AS_OF = "2026-10-18"
ROOT = Path(__file__).resolve().parents[1]
def get(path):
    return json.loads(subprocess.check_output(["gh", "api", "-H", "Accept: application/vnd.github.raw+json", f"repos/{REPO}/contents/{path}?ref={COMMIT}"]))
def read(item):
    document = get(item["path"])
    rows = []
    for feature in document["features"]:
        p = feature["properties"]
        if p.get("removed") or p.get("valid_end") or (p.get("valid_start") or "") > AS_OF or (p.get("created") or "") > AS_OF:
            continue
        rows.append({k: p.get(k) for k in ("name", "aka", "state", "within", "created", "valid_start", "valid_end", "cfr_index")})
    return {"file": item["path"], "properties": rows}
items = [item for item in get("avas") if item["name"].endswith(".geojson")]
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    rows = list(pool.map(read, items))
path = ROOT / "data/wine-knowledge/sources/ucd-ava-properties.json"
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps({"repository": REPO, "commit": COMMIT, "as_of": AS_OF, "license": "CC0-1.0", "records": rows}, ensure_ascii=False, indent=2)+"\n")
print(f"Saved {len(rows)} source files / {sum(len(r['properties']) for r in rows)} active metadata records (no geometry)")
