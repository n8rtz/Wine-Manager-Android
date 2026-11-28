import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
EDITOR = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/TastingNoteEditorScreen.kt"


class TastingNoteUnsavedChangesTests(unittest.TestCase):
    def test_back_with_unsaved_changes_prompts_to_save(self):
        source = EDITOR.read_text()
        # Both back paths route through the guard.
        self.assertIn("BackHandler { requestDismiss() }", source)
        self.assertIn("IconButton(onClick = ::requestDismiss)", source)
        # Dirty tracking snapshots every editable field once loaded.
        self.assertIn("private data class TastingNoteFormSnapshot(", source)
        self.assertIn("initialSnapshot != currentSnapshot()", source)
        self.assertIn("if (loaded && initialSnapshot == null) initialSnapshot = currentSnapshot()", source)
        # The prompt offers Save / Don't save / Cancel, and Save reuses the
        # same save routine as the Save button.
        self.assertIn('"Unsaved changes"', source)
        self.assertIn('Text("Save")', source)
        self.assertIn('Text("Don\'t save")', source)
        self.assertIn("fun performSave() {", source)
        self.assertIn("onClick = { performSave() },", source)
        self.assertIn("enabled = canSave", source)


if __name__ == "__main__":
    unittest.main()
