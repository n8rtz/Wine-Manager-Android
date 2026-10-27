import hashlib
import json
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / "app/src/main/assets/producer-gazetteer-v1"


class ProducerCatalogLookupTests(unittest.TestCase):
    def test_main_app_asset_is_the_verified_full_producer_catalog(self):
        manifest = json.loads((ASSET / "manifest.json").read_text())
        database = ASSET / "producers.sqlite"
        self.assertTrue(manifest["primary_verified"])
        self.assertEqual(manifest["records"], 33160)
        self.assertEqual(hashlib.sha256(database.read_bytes()).hexdigest(), manifest["database_sha256"])
        with sqlite3.connect(database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM producers").fetchone()[0], 33160)
            self.assertEqual(connection.execute("SELECT COUNT(DISTINCT country_group) FROM producers").fetchone()[0], 59)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM names").fetchone()[0], 33160)

    def test_lookup_searches_the_full_scoped_catalog_and_browses_in_pages(self):
        source = (ROOT / "app/src/main/java/com/yourapp/wine/kb/ProducerCatalogDatabaseLookup.kt").read_text()
        screen = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/DatabaseLookupScreen.kt").read_text()
        self.assertIn("SELECT p.id,p.name,p.key,p.country_group$sourceColumn FROM $from ORDER BY p.id", source)
        self.assertIn("ORDER BY p.name,p.id LIMIT ? OFFSET ?", source)
        self.assertIn("countryMenuExpanded", screen)
        self.assertIn("Load next 50 producers", screen)

    def test_producer_lookup_searches_every_producer_database(self):
        # Owner model (2026-10-26, .agents/instructions.md #17): ONE Producer lookup
        # searches all packaged producer databases — the expanded index is their
        # union — and the page lists each contributing database with its count.
        expanded = ROOT / "app/src/main/assets/producer-gazetteer-expanded-v1"
        manifest = json.loads((expanded / "manifest.json").read_text())
        self.assertEqual(manifest["kind"], "lab-expanded-producer-index")
        self.assertTrue(manifest["lab_only"])
        self.assertEqual(manifest["records"], 78615)
        database = expanded / "producers-expanded.sqlite"
        self.assertEqual(hashlib.sha256(database.read_bytes()).hexdigest(), manifest["database_sha256"])
        with sqlite3.connect(database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM producers").fetchone()[0], 78615)
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM producer_sources").fetchone()[0],
                manifest["supplemental_records"],
            )
            per_source = dict(connection.execute(
                "SELECT COALESCE(s.source,'lwin'),COUNT(*) FROM producers p "
                "LEFT JOIN producer_sources s ON s.id=p.id GROUP BY COALESCE(s.source,'lwin')"
            ).fetchall())
            self.assertEqual(per_source, {"lwin": 33160, "xwines": 18945, "ttb": 14849, "osm": 11661})
        source = (ROOT / "app/src/main/java/com/yourapp/wine/kb/ProducerCatalogDatabaseLookup.kt").read_text()
        screen = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/DatabaseLookupScreen.kt").read_text()
        categories = (ROOT / "app/src/main/java/com/yourapp/wine/kb/DatabaseNameLookup.kt").read_text()
        kb = (ROOT / "app/src/main/java/com/yourapp/wine/kb/SqliteWineKnowledgeBase.kt").read_text()
        # The selector only chooses Producer / Wine name / Appellation.
        self.assertIn('PRODUCER("Producer"), WINE_NAME("Wine name"), APPELLATION("Appellation")', categories)
        self.assertNotIn("EXPANDED_PRODUCER", categories)
        self.assertIn('EXPANDED("producer-gazetteer-expanded-v1","producers-expanded.sqlite")', source)
        self.assertIn("producers p LEFT JOIN producer_sources s ON s.id=p.id", source)
        self.assertIn("MAX_DATABASE_BYTES=64L*1024*1024", source)
        # Producer searches the union index; the page lists each database + count.
        self.assertIn("ProducerCatalogDatabaseLookup.Catalog.EXPANDED", screen)
        self.assertIn("Databases searched", screen)
        self.assertIn("wineNameCount()", screen)
        self.assertIn("No country recorded", screen)
        self.assertNotIn("All sources", screen)
        self.assertIn("fun wineNameCount()", kb)
        self.assertNotIn("appellationCount", kb)
        self.assertNotIn("EXPANDED_PRODUCER", kb)

    def test_wine_and_appellation_lookup_reach_the_real_databases(self):
        # Wine name must search the packaged LWIN wine index (101,326 names), not
        # only the scanner KB's 5 fixture cuvees; Appellation must search the
        # supplemented origins view the scanner uses, not the 9-appellation fixture.
        manifest = json.loads((ASSET / "manifest.json").read_text())
        wines = ASSET / "wines.sqlite"
        self.assertEqual(hashlib.sha256(wines.read_bytes()).hexdigest(), manifest["wine_database_sha256"])
        with sqlite3.connect(wines) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM wine_names").fetchone()[0], manifest["wine_names"])
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM wine_links").fetchone()[0], manifest["wine_links"])
            self.assertEqual(manifest["wine_names"], 101326)
        wine_lookup = (ROOT / "app/src/main/java/com/yourapp/wine/kb/WineIndexDatabaseLookup.kt").read_text()
        screen = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/DatabaseLookupScreen.kt").read_text()
        ranking = (ROOT / "app/src/main/java/com/yourapp/wine/kb/DatabaseNameLookup.kt").read_text()
        catalog = (ROOT / "app/src/main/java/com/yourapp/wine/kb/ProducerCatalogDatabaseLookup.kt").read_text()
        self.assertIn('manifest.getString("wine_database_sha256")', wine_lookup)
        self.assertIn("SELECT id,name,key FROM wine_names ORDER BY id", wine_lookup)
        self.assertIn("WineIndexDatabaseLookup.open(context)", screen)
        self.assertIn("SupplementedWineKnowledgeBase(base)", screen)
        self.assertIn("fun appellationCandidates(places: List<Place>)", ranking)
        self.assertIn("identity = candidate.identity", ranking)
        # Exact word match inside a longer name is edit distance 0 (Koonunga report).
        self.assertIn("private fun wordDistance(", ranking)
        checks = (ROOT / "tools/logic-checks/DatabaseNameLookupChecks.kt").read_text()
        self.assertIn("exactWordMatchesRankFirstWithZeroDistance", checks)
        self.assertIn("fun producerNames(ids: Collection<String>)", catalog)
        self.assertIn("LWIN wine names:", screen)
        self.assertIn("Bundled origins appellations:", screen)
        # Superseded searches are cancelled by Compose ("The coroutine scope left
        # the composition"); that control flow must never render as a lookup error.
        self.assertGreaterEqual(screen.count("catch (failure: CancellationException)"), 2)
        self.assertIn("Database lookup failed:", screen)  # real failures still surface
        fixture = (ROOT / "app/src/main/java/com/yourapp/wine/kb/FixtureKnowledgeBase.kt").read_text()
        self.assertEqual(fixture.count('"appellation"'), 9)  # the bypassed fixture, for the record


if __name__ == "__main__":
    unittest.main()
