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


class XWinesCandidateDataTests(unittest.TestCase):
    def test_unverified_sources_are_audit_only_and_not_packaged(self):
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
        self.assertNotIn("support", manifest)
        self.assertNotIn("support_attribution", manifest)
        for root in (MAIN, ROOT / "app/src/debug/assets/producer-gazetteer-v1"):
            for filename in ("ttb-support.sqlite", "osm-support.sqlite", "xwines-candidates.sqlite"):
                self.assertFalse((root / filename).exists())

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
