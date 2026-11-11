"""Source contracts for the collapsible selected-aromas overlay on the wheel."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
DIALOG = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/AromaWheelDialog.kt"


class AromaWheelOverlayTests(unittest.TestCase):
    def test_overlay_collapses_to_one_line_with_a_plus_n_chip(self):
        source = DIALOG.read_text()
        self.assertIn("var expanded by remember { mutableStateOf(false) }", source)
        self.assertIn("orderedIds.take(2)", source)
        self.assertIn('"+${orderedIds.size - 2}"', source)
        self.assertIn('"Show all selected aromas"', source)
        # Tapping the chip expands; "Show less" collapses again.
        self.assertIn("modifier = Modifier.clickable { expanded = true }", source)
        self.assertIn('"Show less"', source)
        self.assertIn("modifier = Modifier.clickable { expanded = false }", source)
        # The expanded view shows every chip at once (no cap, no scroll)
        # and keeps Clear all.
        self.assertIn('"Clear all"', source)
        self.assertNotIn("heightIn(max = 148.dp)", source)
        # Long-pressing a chip and dragging it to a phase chip reassigns it.
        self.assertIn("onDropChipAt = if (readOnly) null else { id, pointerRoot ->", source)
        self.assertIn("moveChipToPhaseAt(id, pointerRoot)", source)
        self.assertIn("moveAromaObservation(", source)
        self.assertIn("phaseChipBounds[phase] = Rect(", source)
        self.assertIn("hoveredPhase == phase", source)
        structure = (ROOT / "app/src/main/java/com/n8rtz/winetracker/data/local/TastingNoteStructure.kt").read_text()
        self.assertIn("fun moveAromaObservation(", structure)

    def test_back_with_unsaved_changes_prompts_to_save_in_both_wheel_dialogs(self):
        source = DIALOG.read_text()
        self.assertEqual(2, source.count("var showUnsavedChangesDialog by remember"))
        self.assertEqual(2, source.count("BackHandler { requestDismiss() }"))
        self.assertEqual(2, source.count("onDismissRequest = ::requestDismiss,"))
        self.assertIn("selected.toSet() != initiallySelectedIds.toSet()", source)
        self.assertIn("observations.toList() != initialObservationSnapshot", source)
        self.assertIn("Unsaved changes", source)
        self.assertIn('Text("Save")', source)
        self.assertIn('Text("Don\'t save")', source)
        self.assertIn("onConfirm(selected.toList())", source)
        self.assertIn("onConfirm(activePhase, observations.toList())", source)

    def test_phase_wheel_can_open_locked_for_viewing_a_note(self):
        source = DIALOG.read_text()
        detail = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/TastingNoteDetailScreen.kt").read_text()
        self.assertIn("readOnly: Boolean = false", source)
        # No header text in the wheel screen, and the whole dialog uses
        # the wheel's grey background (no white top bar).
        self.assertEqual(source.count("color = Color(AromaWheelTheme.backgroundMid)"), 2)
        self.assertNotIn('"${selected.size} selected"', source)
        self.assertIn("locked = readOnly", source)
        self.assertIn("showRemove = !locked", source)
        # Detail screen opens the wheel without entering edit mode.
        self.assertIn("onOpenWheel = { aromaWheelEditMode = false; showAromaWheel = true }", detail)
        self.assertIn("readOnly = !aromaWheelEditMode,", detail)

    def test_chip_deletion_shows_bottom_undo_popup_in_wheel_and_note(self):
        wheel = DIALOG.read_text()
        cloud = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/PhaseAromaCloud.kt").read_text()
        sections = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/TastingPhaseSections.kt").read_text()
        editor = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/TastingNoteEditorScreen.kt").read_text()
        self.assertIn("fun AromaRemovalUndoBar(", cloud)
        self.assertIn('"Undo"', cloud)
        self.assertEqual(2, wheel.count("fun removeChipWithUndo(id: String)"))
        self.assertEqual(2, wheel.count("AromaRemovalUndoBar("))
        self.assertIn("onAromaRemoved = { label, undo ->", editor)
        self.assertIn("snackbarHost = {", editor)

    def test_note_lists_aromas_only_with_the_wheel_not_per_phase(self):
        sections = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/TastingPhaseSections.kt").read_text()
        editor_sections = sections.split("fun SectionedSensoryFields(", 1)[1].split(
            "private fun roundedSteppedCardPath", 1
        )[0]
        self.assertIn("AromaObservationCloud(", editor_sections)
        self.assertNotIn("PhaseAromaCloud(", editor_sections)

    def test_aroma_picked_for_multiple_phases_is_listed_once_in_the_note(self):
        cloud = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/PhaseAromaCloud.kt").read_text()
        sections = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/TastingPhaseSections.kt").read_text()
        # Display dedupes by descriptor (intensity-sorted, so largest wins).
        self.assertIn(".distinctBy { it.descriptorId }", cloud)
        # Resizing/removing the single chip applies across all its phases.
        self.assertIn("TastingPhase.values().fold(normalized) { data, phaseValue ->\n                                                setAromaIntensity(data, id, phaseValue, intensity)", sections)
        self.assertIn("TastingPhase.values().fold(normalized) { data, phaseValue ->\n                                                removeAromaObservation(data, id, phaseValue)", sections)

    def test_note_chips_hide_x_until_long_press_delete_mode(self):
        cloud = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/PhaseAromaCloud.kt").read_text()
        sections = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/TastingPhaseSections.kt").read_text()
        # No X by default; it only renders in delete mode.
        self.assertIn("if (!readOnly && deleteMode) {", cloud)
        # A one-finger long-press on a chip enters delete mode.
        self.assertIn("onLongPress = { setDeleteMode(true) }", cloud)
        # The note sections own the mode and dismiss it on a tap elsewhere
        # (observed on the Main pass, so chip taps arrive consumed).
        self.assertIn("chipDeleteMode = aromaDeleteMode", sections)
        self.assertIn("awaitPointerEvent(PointerEventPass.Main)", sections)
        self.assertIn("aromaDeleteMode = false", sections)

    def test_pinch_resize_is_sensitive_angle_independent_and_shrinks_below_default(self):
        cloud = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/PhaseAromaCloud.kt").read_text()
        structure = (ROOT / "app/src/main/java/com/n8rtz/winetracker/data/local/TastingNoteStructure.kt").read_text()
        # Zoom derives from two-pointer distance, so any pinch angle works.
        self.assertIn("event.calculateZoom()", cloud)
        self.assertIn("PointerEventPass.Initial", cloud)
        # 10sp at intensity 0 up to 32sp at 1 (default 0.5 -> 21sp).
        self.assertIn("(9f + visualAromaIntensity(intensity) * 20f).sp", cloud)
        self.assertIn("* 0.9f", structure)
        # Pinch is handled by the whole cloud: the chip between the two
        # fingers (under their midpoint / nearest to it) is the target, so
        # the fingers do not need to touch the chip itself.
        self.assertIn("val centroid = Offset(", cloud)
        self.assertIn(".firstOrNull { it.value.contains(centroid) }", cloud)
        self.assertIn("Modifier.pointerInput(Unit)", cloud)
        # Order is frozen mid-pinch and only re-sorts on release.
        self.assertIn("var frozenOrder by remember", cloud)
        self.assertIn("frozenOrder = currentObservations.value", cloud)
        self.assertIn("frozenOrder = null", cloud)


if __name__ == "__main__":
    unittest.main()


class ChipHeightTests(unittest.TestCase):
    def test_chip_height_scales_with_pinch_font_size(self):
        cloud = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/components/PhaseAromaCloud.kt").read_text()
        self.assertIn("lineHeight = fontSize * 1.2f", cloud)


class PhaseBarLayoutTests(unittest.TestCase):
    def test_all_four_phases_share_one_line(self):
        source = DIALOG.read_text()
        self.assertIn("Modifier\n                                .weight(1f)", source)
        # Compact custom pills (8.dp label padding) so "Finish" fits whole.
        self.assertIn("style = MaterialTheme.typography.labelLarge,", source)


class ViewOnlyEditPencilTests(unittest.TestCase):
    def test_pencil_switches_view_only_wheel_to_edit_and_saves(self):
        source = DIALOG.read_text()
        self.assertIn("onEditRequest: (() -> Unit)? = null", source)
        self.assertIn("if (readOnly && onEditRequest != null)", source)
        self.assertIn('contentDescription = "Edit aromas"', source)
        detail = (ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/TastingNoteDetailScreen.kt").read_text()
        self.assertIn("readOnly = !aromaWheelEditMode", detail)
        self.assertIn("onEditRequest = { aromaWheelEditMode = true }", detail)
        self.assertIn("viewModel.saveTastingNote(updatedNote)", detail)
