import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/producer-sources"))
TOOLS = ROOT / "tools/producer-sources"
MAIN = ROOT / "app/src/main/assets/producer-gazetteer-v1"
REPORT = TOOLS / "supplemental-source-refresh.json"
spec = importlib.util.spec_from_file_location("refresh_open_candidates", TOOLS / "refresh_open_candidates.py")
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


EXPANDED = ROOT / "app/src/main/assets/producer-gazetteer-expanded-v1"


class XWinesCandidateDataTests(unittest.TestCase):
    def test_supplemental_sources_packaged_only_as_lab_expanded_index(self):
        report = json.loads(REPORT.read_text())
        manifest = json.loads((MAIN / "manifest.json").read_text())
        source = report["xwines"]
        self.assertEqual(source["xwines_license"], "CC0-1.0")
        self.assertEqual(source["dataset"]["grand_total_wines"], 100646)
        self.assertEqual(source["dataset"]["winery_rows"], 30510)
        self.assertEqual(source["source_rows"], 30510)
        self.assertEqual(source["candidates"], 19085)
        self.assertEqual(source["unique_candidate_keys"], 18945)
        self.assertEqual(source["new_distinct_names_vs_current_sources"], 18006)
        self.assertEqual(source["role"], "unverified winery/producer/brand name candidate; no country or aliases inferred")
        self.assertIsNone(source["atlas_repo_license"])
        self.assertFalse(report["artifacts"].get("app_manifest_modified", True))
        # The primary verified database and manifest remain supplement-free; supplemental
        # candidates ship only inside the separate Lab expanded producer index.
        self.assertNotIn("support", manifest)
        self.assertNotIn("support_attribution", manifest)
        self.assertEqual(manifest["records"], 33160)
        self.assertEqual(manifest["database_sha256"], refresh.db_hash(MAIN / "producers.sqlite"))
        for root in (MAIN, ROOT / "app/src/debug/assets/producer-gazetteer-v1"):
            for filename in ("ttb-support.sqlite", "osm-support.sqlite", "xwines-candidates.sqlite"):
                self.assertFalse((root / filename).exists())
        expanded_manifest = json.loads((EXPANDED / "manifest.json").read_text())
        self.assertEqual(expanded_manifest["kind"], "lab-expanded-producer-index")
        self.assertTrue(expanded_manifest["lab_only"])
        self.assertEqual(expanded_manifest["primary_records"], 33160)
        self.assertEqual(expanded_manifest["records"], 78632)
        self.assertEqual(expanded_manifest["supplemental_records"], 45472)
        self.assertEqual(expanded_manifest["primary_database_sha256"], manifest["database_sha256"])
        self.assertEqual(expanded_manifest["wine_database_sha256"], manifest["wine_database_sha256"])
        by_id = {entry["id"]: entry for entry in expanded_manifest["sources"]}
        self.assertEqual(by_id["xwines"]["records"], 18945)
        self.assertEqual(by_id["ttb"]["records"], 14849)
        self.assertEqual(by_id["osm"]["records"], 11661)
        self.assertEqual(by_id["approved_brand"]["records"], 17)
        expanded_db = EXPANDED / "producers-expanded.sqlite"
        self.assertEqual(expanded_manifest["database_sha256"], refresh.db_hash(expanded_db))
        import sqlite3
        with sqlite3.connect(expanded_db) as expanded, sqlite3.connect(MAIN / "producers.sqlite") as primary:
            self.assertEqual(expanded.execute("SELECT COUNT(*) FROM producers").fetchone()[0], 78632)
            primary_ids = {row[0] for row in primary.execute("SELECT id FROM producers")}
            expanded_ids = {row[0] for row in expanded.execute("SELECT id FROM producers")}
            self.assertTrue(primary_ids <= expanded_ids)
            tagged = expanded.execute("SELECT source,COUNT(*) FROM producer_sources GROUP BY source").fetchall()
            self.assertEqual(dict(tagged), {"xwines": 18945, "ttb": 14849, "osm": 11661, "approved_brand": 17})
            untagged = expanded.execute(
                "SELECT COUNT(*) FROM producers p LEFT JOIN producer_sources s ON s.id=p.id WHERE s.id IS NULL"
            ).fetchone()[0]
            self.assertEqual(untagged, 33160)

    def test_candidate_database_builder_is_review_fixture_only(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "candidate.sqlite"
            counts = refresh.create_xwines_db(
                output,
                ["Winery Example", "Winery Example", "Other Winery", "Primary Winery"],
                {"primary winery"},
            )
            self.assertEqual((counts["source_rows"], counts["candidates"], counts["unique_candidate_keys"]), (4, 3, 2))
            self.assertTrue(output.is_file())
            self.assertFalse((MAIN / "xwines-candidates.sqlite").exists())

    def test_payload_parser_reads_json_without_executing_archive_code(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "atlas.zip"
            commit = "1" * 40
            payload = {
                "allWineriesCount": 2,
                "grand_total_wines": 100646,
                "grand_total_wineries": 2,
                "loaded_at": "fixture",
                "countries": [],
                "regions": [],
                "wine_table": {"wineries": ["Domaine One", "Estate Two"]},
            }
            html = "<script>window.__XWINES_PREBAKED__=" + json.dumps(payload) + ";window.should_not_run=true;</script>"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(f"Xwines-Atlas-{commit}/index.html", html)
            names, summary = refresh.extract_xwines_names(
                {"archive_path": path, "repo": "JamesOH5/Xwines-Atlas", "commit": commit}
            )
            self.assertEqual(names, ["Domaine One", "Estate Two"])
            self.assertEqual(summary["unique_raw_names"], 2)

    def test_openstreetmap_snapshot_remains_audit_only(self):
        report = json.loads(REPORT.read_text())
        manifest = json.loads((MAIN / "manifest.json").read_text())
        osm = report["osm"]
        self.assertEqual(osm["commit"], "1f4bf605d1938844c275e7ec0ae0e23141b4a61e")
        self.assertGreaterEqual(osm["records"], 15000)
        self.assertGreaterEqual(osm["new_osm_ids"], 0)
        self.assertGreaterEqual(osm["new_exact_lwin_support_keys"], 0)
        self.assertEqual(manifest["records"], 33160)
        self.assertEqual(manifest["database_sha256"], refresh.db_hash(MAIN / "producers.sqlite"))
        self.assertNotIn("support", manifest)


if __name__ == "__main__":
    unittest.main()
