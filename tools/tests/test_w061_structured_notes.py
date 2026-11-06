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

    def test_wine_color_picker_uses_hsv_controls(self):
        picker = read(UI / "components/WineColorPicker.kt")
        self.assertIn("HueStrip(", picker)
        self.assertIn("SaturationValueSquare(", picker)
        self.assertNotIn("NumericColorField", picker)
        self.assertNotIn("OutlinedTextField", picker)
        self.assertEqual(picker.count("Icons.Default.Check"), 2)
        self.assertEqual(picker.count('contentDescription = "Done"'), 2)
        self.assertEqual(picker.count('contentDescription = "Cancel"'), 2)
        self.assertNotIn('Text("Done")', picker)
        self.assertNotIn('Text("Cancel")', picker)
        self.assertIn("WINE_HUE_START = 330f", picker)
        self.assertIn("WINE_HUE_SPAN = 90f", picker)
        self.assertIn("WINE_HUE_END = 60f", picker)
        self.assertIn("wineHueAtFraction", picker)
        self.assertIn("wineHueFraction(hue)", picker)
        self.assertIn("isWineHue(hue)", picker)
        self.assertIn("hue !in 0f..360f", picker)
        self.assertNotIn("it * 60f", picker)
        self.assertIn("AlertDialog(", picker)
        self.assertIn("resolveEffectiveColor", picker)
        self.assertNotIn("WineColorOptions", picker)
        self.assertNotIn("DropdownMenu(", picker)
        self.assertNotIn('Text("Use default")', picker)
        self.assertNotIn('Text("Override")', picker)

    def test_wine_color_picker_shows_live_swatch_and_wine_glass(self):
        picker = read(UI / "components/WineColorPicker.kt")
        self.assertIn("WineGlassPreview(", picker)
        self.assertIn("modifier = Modifier.width(64.dp).height(206.dp)", picker)
        self.assertIn("swatchSize = 56.dp", picker)
        self.assertIn("val top = pointer.y.roundToInt() - swatchPx - gapPx", picker)
        self.assertIn("val bowlSides = Path().apply", picker)
        self.assertIn("val footSize = Size", picker)
        self.assertEqual(
            picker.count("FloatingColorPreviewSwatch(color, previewPosition, controlSize)"),
            2,
        )
        self.assertIn("onSizeChanged { controlSize = it }", picker)
        self.assertIn("previewEpoch++", picker)
        self.assertNotIn("Spacer(", picker)
        self.assertNotIn(".size(40.dp)", picker)

    def test_aromas_can_be_assigned_to_multiple_phases(self):
        legacy = read(UI / "components/LegacyAromaPhaseSheet.kt")
        editor = read(UI / "screens/TastingNoteEditorScreen.kt")
        model_test = read(TESTS / "TastingNoteStructureTest.kt")
        self.assertIn("Map<String, Set<TastingPhase>>", legacy)
        self.assertIn("phase in assignments[id].orEmpty()", legacy)
        self.assertIn(
            "allAssigned = remaining.all { assignments[it].orEmpty().isNotEmpty() }",
            legacy,
        )
        self.assertIn(".filter { it in assignments.getValue(id) }", editor)
        self.assertIn("sameDescriptorCanBeAssignedToMultiplePhasesIndependently", model_test)

    def test_editor_uses_sectioned_phase_aware_controls(self):
        editor = read(UI / "screens/TastingNoteEditorScreen.kt")
        sections = read(UI / "components/TastingPhaseSections.kt")
        self.assertIn("SectionedSensoryFields(", editor)
        self.assertIn("CommonFaultsFields(", editor)
        self.assertIn("PhaseAromaWheelDialog(", editor)
        self.assertIn("LegacyAromaPhaseSheet(", editor)
        self.assertIn("withDerivedAromas(", editor)
        self.assertIn("validateForSave(sensoryData)", editor)
        self.assertIn("AromaWheelLauncherCard(", sections)
        self.assertIn("selectedAromaIds", sections)
        self.assertIn("sortAromaObservationsByIntensity", sections)
        self.assertNotIn("AromaWheelLauncherButton", sections)
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
        launcher = wheel.split("fun AromaWheelLauncherCard(", 1)[1].split("/** Drag positions", 1)[0]
        self.assertIn("AromaWheelGlyph(", launcher)
        self.assertIn("AromaSummaryChipRow(", launcher)
        self.assertNotIn("ReorderableAromaChipRow(", launcher)
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
        self.assertIn('"Entry",', sections)
        self.assertIn('"Tight",', sections)
        self.assertIn('"Open",', sections)
        self.assertIn('"Entry (Tight ↔ Open): %.1f"', sections)
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

    def test_aroma_phase_has_the_short_display_label(self):
        models = read(DATA / "TastingNoteModels.kt")
        fields = read(UI / "components/SensoryEvaluationFields.kt")
        sections = read(UI / "components/TastingPhaseSections.kt")
        self.assertIn('AROMA_BEFORE_TASTING("Aroma")', models)
        self.assertIn('AROMA_BEFORE_TASTING -> "Visual / Aroma"', models)
        self.assertNotIn('"Aroma (before tasting)"', models)
        self.assertIn('SensoryTraitControl("Aroma"', fields)
        self.assertIn('"Aroma" -> listOf(', fields)
        self.assertIn('TastingPhase.AROMA_BEFORE_TASTING -> listOf("Aroma")', sections)
        self.assertNotIn('SensoryTraitControl("Aroma / bouquet"', fields)

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
