import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"


class W067FragmentSearchTests(unittest.TestCase):
    def test_shared_matcher_exists_with_tiers(self):
        source = (APP / "data/local/WineSearch.kt").read_text()
        self.assertIn("fun normalizeSearchText(", source)
        self.assertIn("fun scoreWineSearch(", source)
        self.assertIn("fun matchesWineSearch(", source)
        self.assertIn("class WineSearchDocument", source)
        self.assertIn("fun WineDefinition.searchDocument(", source)
        # Tiers: exact, prefix, substring, fuzzy (long tokens only).
        self.assertIn("word == token -> 0", source)
        self.assertIn("word.startsWith(token) -> 1", source)
        self.assertIn("word.contains(token) -> 2", source)
        self.assertIn("editDistanceAtMostOne(token, word) -> 3", source)

    def test_kotlin_unit_tests_cover_the_store_case(self):
        test = (ROOT / "app/src/test/java/com/n8rtz/winetracker/data/local/WineSearchTest.kt").read_text()
        self.assertIn('"Kirkland Chablis"', test)
        self.assertIn('"Chablis Kirkland"', test)

    def test_all_call_sites_use_the_shared_matcher(self):
        for relative in [
            "ui/screens/CellarScreen.kt",
            "ui/screens/TastingNotesScreen.kt",
            "viewmodels/RankingsViewModel.kt",
            "ui/screens/TastingNoteEditorScreen.kt",
            "ui/screens/AddWineScreen.kt",
        ]:
            source = (APP / relative).read_text()
            self.assertTrue(
                "scoreWineSearch(" in source or "matchesWineSearch(" in source,
                relative,
            )
        # No single-phrase substring search remains in the main screens.
        for relative in [
            "ui/screens/CellarScreen.kt",
            "ui/screens/TastingNotesScreen.kt",
            "viewmodels/RankingsViewModel.kt",
        ]:
            self.assertNotIn("maker.contains(searchQuery", (APP / relative).read_text())
            self.assertNotIn("maker.contains(query", (APP / relative).read_text())

    def test_suggestion_dropdowns_are_removed(self):
        # The dropdown added no information (names/vintages were cut
        # off) and covered the cards, so it was removed everywhere.
        for relative in [
            "data/local/WineSearch.kt",
            "ui/components/FloatingSearchFilterControls.kt",
            "ui/screens/CellarScreen.kt",
            "ui/screens/TastingNotesScreen.kt",
            "ui/screens/RankingsScreen.kt",
            "viewmodels/RankingsViewModel.kt",
        ]:
            source = (APP / relative).read_text()
            self.assertNotIn("SearchSuggestionList", source, relative)
            self.assertNotIn("topWineSearchSuggestions", source, relative)
            self.assertNotIn("rankingsSearchSuggestions", source, relative)

    def test_cellar_search_has_a_one_tap_clear(self):
        cellar = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn('Icon(Icons.Default.Clear, contentDescription = "Clear search")', cellar)

if __name__ == "__main__":
    unittest.main()


class ExpandingSearchBarTests(unittest.TestCase):
    def test_search_fields_expand_to_full_width_on_focus(self):
        for relative in [
            "ui/screens/CellarScreen.kt",
            "ui/screens/TastingNotesScreen.kt",
            "ui/screens/RankingsScreen.kt",
        ]:
            source = (APP / relative).read_text()
            self.assertIn("var searchFieldFocused by remember", source, relative)
            self.assertIn(".onFocusChanged { searchFieldFocused = it.isFocused }", source, relative)
            # The neighbouring icon buttons animate away while focused.
            self.assertIn("visible = !searchFieldFocused", source, relative)
            self.assertIn("shrinkHorizontally()", source, relative)
            self.assertIn("expandHorizontally()", source, relative)
