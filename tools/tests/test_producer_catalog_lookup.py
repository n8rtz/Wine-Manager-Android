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
        self.assertIn("SELECT id,name,key,country_group FROM producers$countryClause ORDER BY id", source)
        self.assertIn("ORDER BY name,id LIMIT ? OFFSET ?", source)
        self.assertIn("countryMenuExpanded", screen)
        self.assertIn("Load next 50 producers", screen)


if __name__ == "__main__":
    unittest.main()
