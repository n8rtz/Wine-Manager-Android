"""Host contract: cancelling "Change setup" keeps the already-selected Drive folder.

Run with: python3 tools/tests/test_drive_folder_change_setup.py
On-device checks live in THINGS_TO_CHECK.md.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
B = ROOT / "app/src/main/java/com/n8rtz/winetracker/backup"
UI = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens"

VIEW_MODEL = B / "BackupViewModel.kt"
DESTINATION = B / "GoogleDriveDestination.kt"
STORE = B / "BackupCredentialStore.kt"
SCREEN = UI / "BackupRestoreScreen.kt"


class DriveChangeSetupTests(unittest.TestCase):
    def test_cancel_is_a_no_op_that_reconfirms_the_saved_folder(self):
        source = VIEW_MODEL.read_text()
        match = re.search(
            r"fun onDriveFolderPicked\(uri: Uri\?\) \{(.*?)\n    \}\n", source, re.S
        )
        self.assertIsNotNone(match)
        body = match.group(1)
        self.assertIn("if (uri == null || uri.toString().isBlank())", body)
        cancel, _, confirmed = body.partition("viewModelScope.launch")
        self.assertIn("refresh()", cancel)
        self.assertIn("setupMessage = null", cancel)
        self.assertIn("setupInProgress = false", cancel)
        self.assertNotIn("persistTreeUri", cancel)
        self.assertNotIn("clearConfig", cancel)
        self.assertNotIn("saveDriveTreeUri", cancel)
        self.assertIn("GoogleDriveDestination.persistTreeUri(getApplication(), uri)", confirmed)
        self.assertIn("BackupDestinationId.GOOGLE_DRIVE", confirmed)

    def test_no_misleading_no_folder_message(self):
        self.assertNotIn("No Drive folder was selected.", VIEW_MODEL.read_text())

    def test_cancel_never_clears_the_drive_config(self):
        self.assertNotIn(
            "clearConfig(BackupDestinationId.GOOGLE_DRIVE)", VIEW_MODEL.read_text()
        )

    def test_persist_rejects_blank_result_before_touching_the_store(self):
        source = DESTINATION.read_text()
        guard = source.index('require(uri.toString().isNotBlank()) { "Invalid Drive folder result." }')
        grant = source.index("takePersistableUriPermission")
        save = source.index("saveDriveTreeUri(uri.toString())")
        self.assertLess(guard, grant)
        self.assertLess(grant, save)

    def test_credential_store_ignores_blank_but_disconnect_still_clears(self):
        source = STORE.read_text()
        self.assertIn("if (treeUri.isBlank()) return", source)
        self.assertIn("put(KEY_DRIVE_TREE_URI, treeUri)", source)
        self.assertIn("BackupDestinationId.GOOGLE_DRIVE -> listOf(KEY_DRIVE_TREE_URI)", source)

    def test_picker_and_setup_rows_are_unchanged(self):
        source = SCREEN.read_text()
        self.assertIn("ActivityResultContracts.OpenDocumentTree()", source)
        self.assertIn("driveFolderLauncher.launch(null)", source)
        self.assertIn("if (configured) \"Change setup\" else \"Set up…\"", source)
        self.assertIn('Text("Disconnect")', source)

    def test_cancel_keeps_the_folder_mirror(self):
        """Mirror of the store + picker result rules: only a real selection or Disconnect changes it."""
        saved = {"folder": "content://drive/tree/old"}

        def save(value):
            if not value or value.isspace():
                return
            saved["folder"] = value

        def on_result(uri):
            if uri is None or uri.isspace() or not uri:
                return
            save(uri)

        def disconnect():
            saved.pop("folder", None)

        on_result(None)
        self.assertEqual(saved["folder"], "content://drive/tree/old")
        on_result("")
        self.assertEqual(saved["folder"], "content://drive/tree/old")
        on_result("content://drive/tree/new")
        self.assertEqual(saved["folder"], "content://drive/tree/new")
        on_result(None)
        self.assertEqual(saved["folder"], "content://drive/tree/new")
        disconnect()
        self.assertNotIn("folder", saved)


if __name__ == "__main__":
    unittest.main()
