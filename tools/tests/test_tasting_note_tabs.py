import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
NOTES = APP / "ui/screens/TastingNotesScreen.kt"
MAIN = APP / "MainActivity.kt"


class TastingNoteTabsTests(unittest.TestCase):
    def test_every_wine_has_date_tabs_and_a_plus_tab(self):
        source = NOTES.read_text()
        # Tabs show even for a single-note wine (no size > 1 gate).
        self.assertNotIn("if (orderedGroup.size > 1) {", source)
        self.assertIn(".horizontalScroll(rememberScrollState())", source)
        # Attached tabs with only the top corners rounded: the open tab
        # matches the note body colour (same as Cellar's expanded cards),
        # the others a fainter shade; no underline indicator anywhere.
        self.assertIn("if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)", source)
        self.assertIn("else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.14f)", source)
        self.assertIn("color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)", source)
        self.assertNotIn("ScrollableTabRow(", source)
        self.assertIn("RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)", source)
        self.assertIn("RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 8.dp, bottomEnd = 8.dp)", source)
        # Compact tabs and a narrow "+" tab.
        self.assertIn(".height(36.dp)", source)
        self.assertIn(".size(width = 40.dp, height = 36.dp)", source)
        # Tapping the card expands it rather than opening the detail.
        self.assertIn("onClick = onExpandToggle,", source)
        # "+" tab adds a note for this wine.
        self.assertIn("onAddNote(firstItem.note.definitionId, firstItem.note.vintage)", source)
        self.assertIn('"Add a tasting note for this wine"', source)
        self.assertIn("onAddNote = { definitionId, vintage ->", MAIN.read_text())

    def test_swipe_switches_notes_and_date_is_not_duplicated(self):
        source = NOTES.read_text()
        self.assertIn("detectHorizontalDragGestures(", source)
        self.assertIn("orderedGroup[selectedIndex + 1].note.noteId", source)
        self.assertIn("orderedGroup[selectedIndex - 1].note.noteId", source)
        # The note body no longer repeats the tab's date.
        self.assertNotIn('· $score"', source)


if __name__ == "__main__":
    unittest.main()
