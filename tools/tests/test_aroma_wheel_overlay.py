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
        self.assertIn("onDropChipAt = { id, pointerRoot ->", source)
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


if __name__ == "__main__":
    unittest.main()
