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
        self.assertIn("ScrollableTabRow(", source)
        # Coloured tabs make boundaries visible; selected stands out.
        self.assertIn("MaterialTheme.colorScheme.primaryContainer", source)
        self.assertIn("MaterialTheme.colorScheme.surfaceVariant,\n                                    RoundedCornerShape(8.dp)", source)
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
