"""Host contract checks for the retired Settings entries.

These are source-level wiring checks; the Android build and device checks remain in
THINGS_TO_CHECK.md. Run with: python3 tools/tests/test_settings_cleanup.py
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
A = ROOT / "app/src/main/java/com/n8rtz/winetracker"
PROFILE = A / "ui/screens/ProfileScreen.kt"
MAIN = A / "MainActivity.kt"
ADD_WINE = A / "ui/screens/AddWineScreen.kt"
WINE_VIEWMODEL = A / "viewmodels/WineViewModel.kt"
DEBUG_MANIFEST = ROOT / "app/src/debug/AndroidManifest.xml"
MAIN_MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


class SettingsCleanupContractTests(unittest.TestCase):
    def test_obsolete_settings_rows_are_gone(self):
        profile = PROFILE.read_text()
        for label in (
            "Import Legacy Tasting Notes (CSV)",
            "Import Legacy History Log (CSV)",
            "Wine images",
            "Review Missing Images (Open Food Facts)",
            "Offline wine image storage",
            "OCR Text Lab — draw a crop",
            "OCR Lab — multi-image tests",
            "Export OCR test data (CSV)",
            "Offline image soft budget",
        ):
            self.assertNotIn(label, profile, label)
        # No new explanatory prose was added while removing them (agent rule 18).
        self.assertIn('Text("Backup and restore, data, app information")', profile)
        self.assertNotIn("legacy imports, wine images", profile)

    def test_settings_rows_that_stay(self):
        profile = PROFILE.read_text()
        for label in (
            "Backup & Restore",
            "Import Data (JSON)",
            "Export Data",
            "Reset Database",
            "Database Lookup",
            "App Information",
            "Large image dataset test",
        ):
            self.assertIn(label, profile, label)
        self.assertIn("com.yourapp.wine.phonecorpus.WineAppLabelsActivity", profile)

    def test_no_launchers_or_plumbing_for_removed_features(self):
        profile = PROFILE.read_text()
        main = MAIN.read_text()
        add_wine = ADD_WINE.read_text()
        wine_vm = WINE_VIEWMODEL.read_text()
        for source in (profile, main, add_wine):
            for stale in (
                "wineImageImportViewModel",
                "importLegacyCsv",
                "OcrLabActivity",
                "OcrTextLabActivity",
                "export_csv",
            ):
                self.assertNotIn(stale, source, stale)
        for stale in ("CsvImportHelper", "LegacyCsvImportType", "importLegacyCsv"):
            self.assertNotIn(stale, wine_vm, stale)

    def test_removed_implementation_and_test_files_are_gone(self):
        removed = (
            "app/src/main/java/com/n8rtz/winetracker/data/importer/CsvImportHelper.kt",
            "app/src/main/java/com/n8rtz/winetracker/viewmodels/WineImageImportViewModel.kt",
            "app/src/main/java/com/n8rtz/winetracker/data/repository/WineImageImportRepository.kt",
            "app/src/main/java/com/n8rtz/winetracker/image/OpenFoodFactsWineImageProvider.kt",
            "app/src/main/java/com/yourapp/wine/textlab/OcrTextLabActivity.kt",
            "app/src/main/java/com/yourapp/wine/textlab/TextLabViewModel.kt",
            "app/src/debug/java/com/yourapp/wine/benchmark/OcrLabActivity.kt",
            "app/src/debug/assets/ocr-lab-v1/samples.json",
            "app/src/androidTest/java/com/n8rtz/winetracker/data/repository/WineImageImportRepositoryTest.kt",
            "app/src/test/java/com/n8rtz/winetracker/image/OpenFoodFactsWineImageProviderTest.kt",
        )
        for relative in removed:
            self.assertFalse((ROOT / relative).exists(), relative)

    def test_debug_manifest_has_no_lab_activities_and_benchmark_stays(self):
        debug = DEBUG_MANIFEST.read_text()
        self.assertNotIn("OcrTextLabActivity", debug)
        self.assertNotIn("OcrLabActivity", debug)
        self.assertIn("com.yourapp.wine.phonecorpus.WineAppLabelsActivity", MAIN_MANIFEST.read_text())

    def test_orphaned_image_search_api_and_dao_query_are_gone(self):
        api = (A / "network/OpenFoodFactsApi.kt").read_text()
        self.assertIn("suspend fun product(", api)
        self.assertNotIn("cgi/search.pl", api)
        self.assertNotIn("OpenFoodFactsResponse", api)
        self.assertNotIn("ProductImages", api)
        self.assertNotIn("ImageMeta", api)
        self.assertNotIn("OpenFoodFactsApi", WINE_VIEWMODEL.read_text())
        self.assertNotIn("getAllWithoutImages", (A / "data/local/WineDaos.kt").read_text())
        self.assertTrue(
            (ROOT / "app/src/main/java/com/yourapp/wine/textlab/TextLabState.kt").exists()
        )

    def test_shared_engine_and_display_paths_survive(self):
        kept = (
            "app/src/main/java/com/yourapp/wine/phonecorpus/WineAppLabelsActivity.kt",
            "app/src/main/java/com/yourapp/wine/phonecorpus/PhoneCorpusRunner.kt",
            "app/src/main/java/com/yourapp/wine/capture/WineScannerServices.kt",
            "app/src/main/java/com/yourapp/wine/textlab/TextLabRunner.kt",
            "app/src/main/java/com/yourapp/wine/textlab/PaddleMediumOcrEngine.kt",
            "app/src/main/java/com/n8rtz/winetracker/image/WineImageFileStore.kt",
            "app/src/main/java/com/n8rtz/winetracker/image/WineImageDisplay.kt",
        )
        for relative in kept:
            self.assertTrue((ROOT / relative).exists(), relative)
        store = (ROOT / "app/src/main/java/com/n8rtz/winetracker/image/WineImageFileStore.kt").read_text()
        self.assertIn("fun clearStoredImages()", store)
        self.assertIn("fun localFile(context: Context, relativePath: String?)", store)
        self.assertNotIn("Open Food Facts", store)


if __name__ == "__main__":
    unittest.main()
