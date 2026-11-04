"""W-057 wiring checks: preference literals, Auto Backup rules, secrets and the failure popup.

These are host contract checks over the Kotlin/XML sources; the Android build and device checks
remain in THINGS_TO_CHECK.md. Run with: python3 tools/tests/test_backup_preferences.py
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
IMPORTER = ROOT / "app/src/main/java/com/n8rtz/winetracker/data/importer"
BACKUP = ROOT / "app/src/main/java/com/n8rtz/winetracker/backup"
MAIN = ROOT / "app/src/main/java/com/n8rtz/winetracker/MainActivity.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
PREFS = IMPORTER / "BackupPreferences.kt"
BACKUP_DATA = IMPORTER / "BackupData.kt"
EXPORTER = IMPORTER / "JsonExporter.kt"
IMPORTER_FILE = IMPORTER / "DataImporter.kt"
RULES_XML = ROOT / "app/src/main/res/xml/backup_rules.xml"
EXTRACTION_XML = ROOT / "app/src/main/res/xml/data_extraction_rules.xml"


def literals(source, names):
    found = {}
    for name in names:
        match = re.search(r'const val\s+' + re.escape(name) + r'\s*=\s*"([^"]*)"', source)
        found[name] = match.group(1) if match else None
    return found


class BackupPreferenceContractTests(unittest.TestCase):
    def test_preference_keys_match_the_two_viewmodels(self):
        prefs = PREFS.read_text()
        tasting = (ROOT / "app/src/main/java/com/n8rtz/winetracker/viewmodels/TastingNoteViewModel.kt").read_text()
        images = (ROOT / "app/src/main/java/com/n8rtz/winetracker/viewmodels/WineImageImportViewModel.kt").read_text()
        canonical = literals(prefs, ["REVIEWER_PREFERENCES_FILE", "REVIEWER_NAMES_KEY",
                                     "IMAGE_SETTINGS_FILE", "SOFT_BUDGET_KEY"])
        reviewers = literals(tasting, ["REVIEWER_PREFERENCES_FILE", "REVIEWER_NAMES_KEY"])
        image_settings = literals(images, ["PREFERENCES_FILE", "SOFT_BUDGET_KEY"])
        self.assertEqual("wine_manager_settings", canonical["REVIEWER_PREFERENCES_FILE"])
        self.assertEqual("wine_reviewer_names", canonical["REVIEWER_NAMES_KEY"])
        self.assertEqual("wine_image_import_settings", canonical["IMAGE_SETTINGS_FILE"])
        self.assertEqual("soft_budget_bytes", canonical["SOFT_BUDGET_KEY"])
        self.assertEqual(canonical["REVIEWER_PREFERENCES_FILE"], reviewers["REVIEWER_PREFERENCES_FILE"])
        self.assertEqual(canonical["REVIEWER_NAMES_KEY"], reviewers["REVIEWER_NAMES_KEY"])
        self.assertEqual(canonical["IMAGE_SETTINGS_FILE"], image_settings["PREFERENCES_FILE"])
        self.assertEqual(canonical["SOFT_BUDGET_KEY"], image_settings["SOFT_BUDGET_KEY"])

    def test_fresh_install_reviewer_defaults_are_exported_not_emptied(self):
        """A missing prefs key must export the built-in list, or a restore would clear it."""
        prefs = PREFS.read_text()
        tasting = (ROOT / "app/src/main/java/com/n8rtz/winetracker/viewmodels/TastingNoteViewModel.kt").read_text()
        self.assertIn("?: return DEFAULT_REVIEWER_NAMES", prefs)
        for name in ["Costco Wine Blog", "Decanter", "James Suckling", "Konstantin Baum",
                     "Peter Koff", "Wine Advocate", "Wine Enthusiast", "Wine Spectator"]:
            self.assertIn('"' + name + '"', prefs)
            self.assertIn('"' + name + '"', tasting)
        self.assertIn("DEFAULT_REVIEWER_NAMES", tasting)
        self.assertIn("DEFAULT_SOFT_BUDGET_BYTES = 256L * 1024L * 1024L", prefs)

    def test_optional_preferences_round_trip_without_guessing(self):
        data = BACKUP_DATA.read_text()
        self.assertIn("val reviewerNames: List<String>? = null", data)
        self.assertIn("val imageStorageBudgetBytes: Long? = null", data)
        exporter = EXPORTER.read_text()
        self.assertIn("BackupPreferences.readReviewers(context)", exporter)
        self.assertIn("BackupPreferences.readBudgetBytes(context)", exporter)
        importer = IMPORTER_FILE.read_text()
        self.assertIn('require(bytes in BackupPreferences.MIN_BUDGET_BYTES..BackupPreferences.MAX_BUDGET_BYTES)', importer)
        self.assertIn("if (restoredReviewers != null)", importer)
        self.assertIn("if (restoredBudget != null)", importer)
        # Validation must happen before the destructive transaction.
        self.assertLess(importer.index("restoredBudget"), importer.index("database.withTransaction"))
        # Nothing secret is exported.
        for forbidden in ("githubToken", "webDavPassword", "driveTreeUri", "token", "password"):
            self.assertNotIn(forbidden, data)
            self.assertNotIn(forbidden, exporter)

    def test_auto_backup_rules_exclude_secrets_only_and_are_referenced(self):
        for xml in (RULES_XML, EXTRACTION_XML):
            text = re.sub(r"<!--.*?-->", "", xml.read_text(), flags=re.S)
            self.assertNotIn("<include", text)  # an include would restrict Android's default set
            self.assertIn('domain="sharedpref" path="backup_credentials.xml"', text)
            self.assertIn('domain="file" path="wine-images/"', text)
        self.assertIn("cloud-backup", EXTRACTION_XML.read_text())
        self.assertIn("device-transfer", EXTRACTION_XML.read_text())
        self.assertIn('const val PREFERENCES_FILE = "backup_credentials"', (BACKUP / "BackupCredentialStore.kt").read_text())
        manifest = MANIFEST.read_text()
        self.assertIn("@xml/backup_rules", manifest)
        self.assertIn("@xml/data_extraction_rules", manifest)

    def test_no_new_permissions_and_popup_on_app_open(self):
        manifest = MANIFEST.read_text()
        self.assertNotIn("POST_NOTIFICATIONS", manifest)
        self.assertNotIn("ACCESS_NETWORK_STATE", manifest)
        self.assertNotIn("android.permission.READ_EXTERNAL_STORAGE", manifest)
        main = MAIN.read_text()
        self.assertIn("backupViewModel.onAppOpened()", main)
        self.assertIn("backupState.failureDialog", main)
        self.assertIn('title = { Text("Backup failed") }', main)
        self.assertIn("refreshAfterRestore()", main)
        viewmodel = (BACKUP / "BackupViewModel.kt").read_text()
        self.assertIn("showAppOpenFailureDialog()", viewmodel)
        self.assertIn("POST_NOTIFICATIONS", viewmodel)  # documented as deliberately unused

    def test_all_four_destinations_are_wired(self):
        schedule = (BACKUP / "BackupSchedule.kt").read_text()
        implemented = re.findall(
            r'\b(?:LOCAL|GOOGLE_DRIVE|GITHUB|WEBDAV)\("[^"]+",\s*"[^"]+",\s*(?:true|false),\s*true\)',
            schedule,
        )
        self.assertEqual(4, len(implemented))
        coordinator = (BACKUP / "BackupCoordinator.kt").read_text()
        for cls in ("LocalBackupDestination", "GoogleDriveDestination", "GitHubDestination", "WebDavDestination"):
            self.assertIn(cls, coordinator)
        screen = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/BackupRestoreScreen.kt").read_text()
        self.assertIn("OpenDocumentTree()", screen)
        self.assertIn("showGitHubWarning", screen)
        self.assertIn("loadRestoreBackups(id)", screen)


if __name__ == "__main__":
    unittest.main()
