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
    def test_cellar_tasting_notes_action_opens_the_matching_notes_group(self):
        cellar = CELLAR.read_text()
        main = MAIN.read_text()
        view_model = (APP / "viewmodels/TastingNoteViewModel.kt").read_text()
        self.assertIn("onTastingNotesClick: (Long, String) -> Unit", cellar)
        self.assertIn('Text("Tasting notes")', cellar)
        self.assertIn("onTastingNotesClick(item.definition.definitionId, item.bottle.vintage)", cellar)
        self.assertIn("tastingNoteViewModel.openWineTastingNotes(definitionId, vintage)", main)
        self.assertIn('navigateToMenu("notes")', main)
        self.assertIn("fun openWineTastingNotes(definitionId: Long, vintage: String)", view_model)
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

    def test_notes_screen_resizes_structured_aromas_and_persists_intensity(self):
        notes = (APP / "ui/screens/TastingNotesScreen.kt").read_text()
        cloud = (APP / "ui/components/PhaseAromaCloud.kt").read_text()
        view_model = (APP / "viewmodels/TastingNoteViewModel.kt").read_text()
        self.assertIn("PhaseAromaCloud(", notes)
        self.assertIn("allowIntensityAdjustment = true", notes)
        self.assertIn("tastingNoteViewModel.updateAromaIntensity(", notes)
        self.assertIn("AromaChips(sensory?.aromas.orEmpty())", notes)
        self.assertIn("allowIntensityAdjustment: Boolean = !readOnly", cloud)
        self.assertIn("pinch to resize", cloud)
        self.assertIn("aromaIntensitySaveMutex.withLock", view_model)
        self.assertIn("setAromaIntensity(currentSensory, descriptorId, phase, intensity)", view_model)

    def test_editor_does_not_duplicate_faults_already_available_in_the_aroma_wheel(self):
        editor = (APP / "ui/screens/TastingNoteEditorScreen.kt").read_text()
        self.assertNotIn("CommonFaultsFields(", editor)
        self.assertIn("PhaseAromaWheelDialog(", editor)
        self.assertIn('"faults", "Faults & Other"', (APP / "aromawheel/AromaTaxonomy.kt").read_text())
        self.assertIn("val flaws: List<String>", (APP / "data/local/WineDatabase.kt").read_text())

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
        self.assertIn("allowIntensityAdjustment: Boolean = !readOnly", cloud)
        self.assertIn("if (!allowIntensityAdjustment) {", cloud)
        self.assertIn("if (!readOnly && deleteMode) {", cloud)


if __name__ == "__main__":
    unittest.main()
