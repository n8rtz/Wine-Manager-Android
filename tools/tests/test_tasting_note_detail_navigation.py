"""Source-level checks for exact note-row navigation and read-only detail rendering."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
CELLAR = APP / "ui/screens/CellarScreen.kt"
DETAIL = APP / "ui/screens/TastingNoteDetailScreen.kt"
MAIN = APP / "MainActivity.kt"
SENSORY = APP / "ui/components/SensoryEvaluationFields.kt"
SECTIONS = APP / "ui/components/TastingPhaseSections.kt"
CLOUD = APP / "ui/components/PhaseAromaCloud.kt"


class TastingNoteDetailNavigationTests(unittest.TestCase):
    def test_expanded_cellar_note_row_opens_its_own_note_without_a_wine_detail_route(self):
        cellar = CELLAR.read_text()
        main = MAIN.read_text()
        self.assertIn("onNoteClick: (Long) -> Unit", cellar)
        self.assertIn(".clickable { onNoteClick(noteWithDef.note.noteId) }", cellar)
        self.assertIn("onClick = toggleExpanded", cellar)
        self.assertIn('route = "note_detail/{noteId}"', main)
        self.assertNotIn('route = "detail/{bottleId}"', main)

    def test_note_routes_open_read_only_detail_and_edit_is_explicit(self):
        main = MAIN.read_text()
        self.assertIn('navController.navigate("note_detail/$noteId")', main)
        self.assertIn('route = "note_detail/{noteId}"', main)
        self.assertIn("TastingNoteDetailScreen(", main)
        self.assertIn('navController.navigate("note_editor/edit/$noteId")', main)
        self.assertIn('contentDescription = "Edit tasting note"', DETAIL.read_text())

    def test_detail_reuses_editor_layout_without_editing_controls(self):
        detail = DETAIL.read_text()
        for token in (
            'Text("Date tasted")',
            'Text("Tasting notes and impressions")',
            'Text("Value"',
            "OverallScoreSlider(",
            "SectionedSensoryFields(",
            "CommonFaultsFields(",
            "WheelPicker(",
            "readOnly = true",
            "enabled = false",
        ):
            self.assertIn(token, detail)
        self.assertIn('"Overall score"', SENSORY.read_text())
        self.assertIn('Text("Common faults"', SECTIONS.read_text())
        self.assertNotIn("Save Changes", detail)
        self.assertNotIn("Save Tasting Note", detail)
        self.assertNotIn("Delete note", detail)

    def test_shared_sensory_controls_have_a_noninteractive_read_only_mode(self):
        sections = SECTIONS.read_text()
        sensory = SENSORY.read_text()
        cloud = CLOUD.read_text()
        self.assertIn("readOnly: Boolean = false", sections)
        self.assertIn("readOnly = readOnly", sections)
        self.assertIn("enabled = !readOnly", sections)
        self.assertIn("enabled: Boolean = true", sensory)
        self.assertIn("enabled = enabled", sensory)
        self.assertIn("readOnly: Boolean = false", cloud)
        self.assertIn("if (readOnly) {", cloud)
        self.assertIn("if (!readOnly) {", cloud)


if __name__ == "__main__":
    unittest.main()
