"""Host contract checks for tab re-entry scroll position and one-line search hints.

Run with: python3 tools/tests/test_tab_top_and_search_labels.py
These are source-level checks; the on-device checklist lives in THINGS_TO_CHECK.md.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui"
A = ROOT / "app/src/main/java/com/n8rtz/winetracker"

LIST_SCREENS = {
    "cellar": UI / "screens/CellarScreen.kt",
    "rankings": UI / "screens/RankingsScreen.kt",
    "notes": UI / "screens/TastingNotesScreen.kt",
}

SEARCH_HINT_FILES = (
    # The three list tabs keep their top search row and also use the pop-up control.
    UI / "screens/CellarScreen.kt",
    UI / "screens/RankingsScreen.kt",
    UI / "screens/TastingNotesScreen.kt",
    UI / "components/FloatingSearchFilterControls.kt",
    UI / "screens/TastingNoteEditorScreen.kt",
    UI / "screens/DatabaseLookupScreen.kt",
    UI / "components/AromaWheelDialog.kt",
)

OLD_HINTS = (
    "Search producer or name...",
    "Search Producer, Name, or Vintage",
    "Search producer or wine",
    "Search aromas",
    'Search ${category.label.lowercase()}',
    "Type a name",
)


class TabStartsAtTopTests(unittest.TestCase):
    def test_list_state_is_never_saved_or_restored(self):
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertIn("remember { LazyListState() }", source, name)
            self.assertNotIn("rememberLazyListState()", source, name)
            self.assertNotIn("rememberSaveable", source, name)
            self.assertIn("import androidx.compose.foundation.lazy.LazyListState", source, name)

    def test_each_screen_scrolls_to_top_on_entry(self):
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertRegex(
                source,
                r"LaunchedEffect\(Unit\) \{ \w+ListState\.scrollToItem\(0\) \}",
                name,
            )

    def test_search_and_filter_changes_keep_the_scroll_position(self):
        # The floating search/filter bubbles replaced the fixed rows; changing them no longer
        # scrolls the user back to the top. Only re-entering the tab resets the list.
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertNotIn("LaunchedEffect(filterState, searchQuery)", source, name)
            self.assertNotIn("LaunchedEffect(rankedWines)", source, name)
            self.assertNotIn("LaunchedEffect(listKey)", source, name)
            self.assertIn("FloatingSearchFilterControls(", source, name)

    def test_tab_navigation_itself_is_unchanged(self):
        main = (A / "MainActivity.kt").read_text()
        self.assertIn("restoreState = route != \"profile\"", main)
        self.assertIn("saveState = true", main)


class SearchLabelTests(unittest.TestCase):
    def test_every_search_bar_has_the_single_word_hint(self):
        for path in SEARCH_HINT_FILES:
            self.assertIn(
                'Text("Search", maxLines = 1)', path.read_text(), path.name
            )

    def test_no_long_search_hints_remain(self):
        for path in sorted(UI.rglob("*.kt")):
            source = path.read_text()
            for old in OLD_HINTS:
                self.assertNotIn(old, source, f"{path.name}: {old}")

    def test_no_search_bar_declares_a_multiword_hint(self):
        multiword = re.compile(
            r"(?:placeholder|label)\s*=\s*\{\s*Text\(\"Search[^\"]+\""
        )
        for path in sorted(UI.rglob("*.kt")):
            self.assertIsNone(multiword.search(path.read_text()), path.name)

    def test_database_lookup_search_has_only_one_hint(self):
        source = (UI / "screens/DatabaseLookupScreen.kt").read_text()
        self.assertIn('label = { Text("Search", maxLines = 1) }', source)
        self.assertNotIn('placeholder = { Text("Type a name") }', source)
        self.assertIn("singleLine = true", source)


if __name__ == "__main__":
    unittest.main()
