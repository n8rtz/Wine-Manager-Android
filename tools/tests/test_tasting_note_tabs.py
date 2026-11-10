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
        # the others a darker shade; no underline indicator anywhere.
        self.assertIn("if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)", source)
        self.assertIn("color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)", source)
        self.assertNotIn("ScrollableTabRow(", source)
        self.assertIn("RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)", source)
        self.assertIn("RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = 8.dp, bottomEnd = 8.dp)", source)
        # Compact tabs (trimmed vertical padding only, full-size bold
        # text) and a narrow "+" tab; closed tabs are the darker shade.
        self.assertIn(".height(32.dp)", source)
        self.assertIn(".size(width = 40.dp, height = 32.dp)", source)
        self.assertIn("style = MaterialTheme.typography.labelLarge,\n                                    fontWeight = FontWeight.Bold,", source)
        self.assertIn("else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)", source)
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


class TastingNoteStateAndRatingTests(unittest.TestCase):
    def test_notes_state_survives_detail_navigation(self):
        source = NOTES.read_text()
        self.assertIn("val notesListState = rememberLazyListState()", source)
        self.assertNotIn("scrollToItem(0)", source.split("val notesListState")[0][-200:])
        self.assertIn("var searchQuery by rememberSaveable", source)
        self.assertIn("var selectedNoteId by rememberSaveable(orderedGroup.first().note.noteId)", source)

    def test_rating_is_a_prominent_colour_badge(self):
        source = NOTES.read_text()
        self.assertIn("ratingColor(ratingValue)", source)
        self.assertIn("MaterialTheme.typography.headlineSmall", source)
        self.assertIn('"MY RATING"', source)
        self.assertIn('ratingValue?.toString() ?: "NR"', source)


class TabAndChipColourTests(unittest.TestCase):
    def test_unselected_tabs_are_clearly_darker(self):
        source = NOTES.read_text()
        # Open tab matches the body; closed tabs use a much darker shade.
        self.assertIn("if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)", source)
        self.assertEqual(source.count("MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)"), 2)

    def test_aroma_chips_use_full_wheel_colours(self):
        theme = (ROOT / "app/src/main/java/com/n8rtz/winetracker/aromawheel/AromaWheelTheme.kt").read_text()
        self.assertIn("fun groupSelectedColor(groupId: String?)", theme)
        for rel in [
            "ui/components/AromaChips.kt",
            "ui/components/PhaseAromaCloud.kt",
            "ui/components/AromaWheelDialog.kt",
        ]:
            source = (APP / rel).read_text()
            # Fill and border are the wheel's selected-descriptor colour
            # (the background the wheel screen chips use); text is white,
            # bold, and the chips carry the wheel chips' coloured dot.
            self.assertIn("AromaWheelTheme.groupSelectedColor(", source, rel)
            self.assertIn("contentColor = Color.White", source, rel)
            self.assertIn("FontWeight.Bold", source, rel)
            self.assertIn(".background(Color(AromaWheelTheme.groupColor(", source, rel)
            self.assertNotIn("groupTint(", source, rel)
            self.assertNotIn("groupInk(", source, rel)
            self.assertNotIn("copy(alpha = 0.16f)", source, rel)