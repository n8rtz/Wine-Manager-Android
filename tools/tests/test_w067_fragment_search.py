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

    def test_suggestions_are_wired_into_every_wine_search(self):
        matcher = (APP / "data/local/WineSearch.kt").read_text()
        self.assertIn("fun topWineSearchSuggestions(", matcher)
        for relative in [
            "ui/screens/CellarScreen.kt",
            "ui/screens/TastingNotesScreen.kt",
            "ui/screens/RankingsScreen.kt",
        ]:
            source = (APP / relative).read_text()
            self.assertIn("SearchSuggestionList(", source, relative)
            self.assertIn("onSuggestionPick = { query ->", source, relative)
        # Rankings suggestions come from the unfiltered rated wines.
        view_model = (APP / "viewmodels/RankingsViewModel.kt").read_text()
        self.assertIn("val rankingsSearchSuggestions", view_model)
        self.assertIn("topWineSearchSuggestions(", view_model)

    def test_cellar_suggestions_are_wired(self):
        cellar = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("val searchSuggestions = remember", cellar)
        self.assertIn("SearchSuggestionList(", cellar)
        self.assertIn("onSuggestionPick = { query ->", cellar)
        controls = (APP / "ui/components/FloatingSearchFilterControls.kt").read_text()
        self.assertIn("fun SearchSuggestionList(", controls)
        self.assertIn("suggestions: List<Pair<String, String>> = emptyList()", controls)


if __name__ == "__main__":
    unittest.main()
