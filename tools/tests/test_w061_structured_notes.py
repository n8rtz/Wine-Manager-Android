"""Host source-contract checks for W-061 structured tasting notes.

These are source-level checks; Android/Gradle/device verification remains in
THINGS_TO_CHECK.md and PIPELINE.md.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
UI = APP / "ui"
DATA = APP / "data/local"
DOCS = ROOT / "_refs"
TESTS = ROOT / "app/src/test/java/com/n8rtz/winetracker/data/local"


def read(path):
    return path.read_text()


class W061SourceContractTests(unittest.TestCase):
    def test_new_data_contract_files_exist(self):
        for rel in [
            "TastingNoteStructure.kt",
        ]:
            self.assertTrue((DATA / rel).is_file(), rel)
        for rel in [
            "WineColorPicker.kt",
            "PhaseAromaCloud.kt",
            "TastingPhaseSections.kt",
            "LegacyAromaPhaseSheet.kt",
        ]:
            self.assertTrue((UI / "components" / rel).is_file(), rel)
        self.assertTrue((TESTS / "TastingNoteStructureTest.kt").is_file())

    def test_room_v22_adds_only_nullable_default_color(self):
        db = read(DATA / "WineDatabase.kt")
        self.assertIn("version = 22,", db)
        self.assertIn("val defaultColorHsv: HsvColor? = null", db)
        self.assertIn("val colorHsv: HsvColor? = null", db)
        self.assertIn("val entryTightOpen: Float? = 0.5f", db)
        self.assertIn("val aromaObservations: List<AromaObservation> = emptyList()", db)
        self.assertIn("fun toHsvColor(value: String?): HsvColor?", db)
        self.assertIn("normalizeSensoryData(", db)
        self.assertIn("val MIGRATION_21_22 = object : Migration(21, 22)", db)
        self.assertIn(
            'db.execSQL("ALTER TABLE wine_definitions ADD COLUMN defaultColorHsv TEXT")',
            db,
        )
        main = read(APP / "MainActivity.kt")
        self.assertIn("MIGRATION_21_22", main)

    def test_definition_default_color_is_wired_through_screens(self):
        repo = read(APP / "data/repository/WineRepository.kt")
        vm = read(APP / "viewmodels/WineViewModel.kt")
        add = read(UI / "screens/AddWineScreen.kt")
        detail = read(UI / "screens/WineEditScreen.kt")
        self.assertIn("defaultColorHsv: HsvColor? = null", repo)
        self.assertIn("defaultColorHsv = defaultColorHsv", repo)
        self.assertIn("defaultColorHsv: HsvColor? = null", vm)
        self.assertIn("defaultColorHsv = defaultColorHsv", add)
        self.assertIn("DefaultColorField(", add)
        self.assertIn("defaultColorHsv = defaultColorHsv", detail)
        self.assertIn("DefaultColorField(", detail)

    def test_wine_color_uses_a_swatch_menu_and_curated_palette(self):
        picker = read(UI / "components/WineColorPicker.kt")
        self.assertIn("ColorSwatchButton(", picker)
        self.assertIn("DropdownMenu(", picker)
        self.assertIn("WineColorOptions", picker)
        for family in ('"Red"', '"White"', '"Rosé"'):
            self.assertIn(family, picker)
        self.assertNotIn('Text("Use default")', picker)
        self.assertNotIn('Text("Override")', picker)
        self.assertNotIn('"Blue"', picker)
        self.assertNotIn('"Pink"', picker)
        self.assertNotIn('"Purple"', picker)
        self.assertNotIn("HueStrip(", picker)
        self.assertNotIn("SaturationValueSquare(", picker)
        self.assertNotIn("NumericColorField(", picker)

    def test_editor_uses_sectioned_phase_aware_controls(self):
        editor = read(UI / "screens/TastingNoteEditorScreen.kt")
        self.assertIn("SectionedSensoryFields(", editor)
        self.assertIn("CommonFaultsFields(", editor)
        self.assertIn("PhaseAromaWheelDialog(", editor)
        self.assertIn("LegacyAromaPhaseSheet(", editor)
        self.assertIn("withDerivedAromas(", editor)
        self.assertIn("validateForSave(sensoryData)", editor)
        self.assertNotIn("AromaWheelLauncherCard(", editor)
        self.assertNotIn("SensoryEvaluationFields(", editor)
        self.assertNotIn("        AromaWheelDialog(", editor)
        self.assertNotIn("AromaSelection.normalize", editor)

    def test_phase_wheel_and_cloud_contracts_exist(self):
        wheel = read(UI / "components/AromaWheelDialog.kt")
        cloud = read(UI / "components/PhaseAromaCloud.kt")
        sections = read(UI / "components/TastingPhaseSections.kt")
        legacy = read(UI / "components/LegacyAromaPhaseSheet.kt")
        self.assertIn("fun PhaseAromaWheelDialog(", wheel)
        self.assertIn("TastingPhase.values().forEach", wheel)
        self.assertIn("toggleAromaObservation(", wheel)
        self.assertIn("fun PhaseAromaCloud(", cloud)
        self.assertIn("FlowRow(", cloud)
        self.assertIn("detectTransformGestures", cloud)
        self.assertIn("sortAromaObservationsByIntensity", cloud)
        self.assertNotIn('Text("%.1f".format', cloud)
        self.assertNotIn('"${info.label} %.1f"', sections)
        self.assertNotIn("onReorder", cloud)
        self.assertIn("intensityAfterPinch", read(DATA / "TastingNoteStructure.kt"))
        self.assertIn("fun SectionedSensoryFields(", sections)
        self.assertIn("Tight ↔ Open", sections)
        self.assertIn("StructuredNoteSummary(", sections)
        self.assertIn("fun LegacyAromaPhaseSheet(", legacy)
        self.assertIn("TastingPhase.values().forEach", legacy)

    def test_note_detail_uses_read_only_sectioned_sensory_controls(self):
        detail = read(UI / "screens/TastingNoteDetailScreen.kt")
        self.assertIn("SectionedSensoryFields(", detail)
        self.assertIn("CommonFaultsFields(", detail)
        self.assertIn("readOnly = true", detail)
        self.assertNotIn("noteAromas", detail)
        self.assertNotIn("AromaTaxonomy.describe(aromaId)", detail)

    def test_filter_contract_searches_all_phases_with_optional_phase(self):
        state = read(UI / "screens/TastingNotesFilterState.kt")
        screen = read(UI / "screens/TastingNotesScreen.kt")
        self.assertIn("val aromaQuery: String = \"\"", state)
        self.assertIn("val aromaPhase: TastingPhase? = null", state)
        self.assertIn("matchesAromaQuery(", screen)
        self.assertIn("filterState.aromaPhase", screen)
        self.assertIn("TastingPhase.values().forEach", screen)

    def test_backup_restore_and_pure_contract_are_present(self):
        importer = read(APP / "data/importer/DataImporter.kt")
        models = read(DATA / "TastingNoteModels.kt")
        structure = read(DATA / "TastingNoteStructure.kt")
        self.assertNotIn("SensoryData", models)
        self.assertNotIn("AromaTaxonomy", models)
        self.assertIn("normalizeSensoryData(oldNote.sensoryData)", importer)
        for token in ["enum class TastingPhase", "data class HsvColor", "data class AromaObservation"]:
            self.assertIn(token, models)
        for token in [
            "fun normalizeSensoryData",
            "fun withDerivedAromas",
            "fun addAromaObservation",
            "fun setAromaIntensity",
            "fun reorderAromaChips",
            "fun validateForSave",
            "fun matchesAromaQuery",
        ]:
            self.assertIn(token, structure)

    def test_docs_and_gates_are_updated(self):
        arch = read(DOCS / "ARCH.md")
        changelog = read(DOCS / "CHANGELOG.md")
        checklist = read(DOCS / "THINGS_TO_CHECK.md")
        backlog = read(DOCS / "BACKLOG.md")
        pipeline = read(DOCS / "PIPELINE.md")
        framework = read(DOCS / "major-feature-planning/structured-tasting-notes.md")
        self.assertIn("Room DB (v22)", arch)
        self.assertIn("TastingNoteStructure.kt", arch)
        self.assertIn("WineColorPicker.kt", arch)
        self.assertIn("PhaseAromaCloud.kt", arch)
        self.assertIn("TastingPhaseSections.kt", arch)
        self.assertIn("LegacyAromaPhaseSheet.kt", arch)
        self.assertIn("W-061", changelog)
        self.assertIn("W-061", checklist)
        self.assertIn("W-061", pipeline)
        self.assertIn("Fixed – awaiting device check", backlog)
        self.assertIn("Source implementation written 2026-11-06", framework)
        self.assertNotIn("implementation is not authorized by this documentation task", framework)
        self.assertNotIn("No app code changed", framework)
        self.assertNotIn("none exist for this framework yet", framework)


if __name__ == "__main__":
    unittest.main()
