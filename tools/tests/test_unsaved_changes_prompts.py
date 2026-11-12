import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
DIALOG = APP / "ui/components/UnsavedChangesDialog.kt"
WINE_EDIT = APP / "ui/screens/WineEditScreen.kt"
ADD_WINE = APP / "ui/screens/AddWineScreen.kt"
NOTE_EDITOR = APP / "ui/screens/TastingNoteEditorScreen.kt"


class UnsavedChangesPromptTests(unittest.TestCase):
    def test_shared_dialog_has_save_discard_cancel(self):
        source = DIALOG.read_text()
        self.assertIn('Text("Unsaved changes")', source)
        self.assertIn('Text("Save")', source)
        self.assertIn('Text("Don\'t save")', source)
        self.assertIn('Text("Cancel")', source)

    def test_edit_wine_prompts_when_dirty(self):
        source = WINE_EDIT.read_text()
        self.assertIn("val isDirty", source)
        self.assertIn("fun saveWine(): Boolean", source)
        self.assertIn("BackHandler(onBack = ::requestBack)", source)
        self.assertIn("IconButton(onClick = ::requestBack)", source)
        self.assertIn("UnsavedChangesDialog(", source)
        # The toolbar save check delegates to the shared save path.
        self.assertIn("IconButton(onClick = { if (saveWine()) onBack() })", source)

    def test_add_wine_prompts_when_draft_changed(self):
        source = ADD_WINE.read_text()
        self.assertIn("currentDraft() != initialDraft", source)
        self.assertIn("BackHandler(onBack = ::requestClose)", source)
        self.assertIn("UnsavedChangesDialog(", source)
        self.assertIn("draftStore.clear()", source)

    def test_note_editor_still_prompts(self):
        self.assertIn("showUnsavedChangesDialog", NOTE_EDITOR.read_text())


if __name__ == "__main__":
    unittest.main()
