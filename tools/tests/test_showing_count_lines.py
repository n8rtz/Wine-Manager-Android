import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"


class ShowingCountLineTests(unittest.TestCase):
    def test_cellar_count_display_setting_is_persisted(self):
        view_model = (APP / "viewmodels/WineViewModel.kt").read_text()
        self.assertIn("cellar_count_shows_bottles", view_model)
        self.assertIn("fun setCellarCountShowsBottles(", view_model)
        profile = (APP / "ui/screens/ProfileScreen.kt").read_text()
        self.assertIn('"Cellar count display"', profile)
        self.assertIn("showCountDisplaySettings", profile)
        self.assertIn('listOf(true to "Bottles", false to "Wines")', profile)

    def test_cellar_line_switches_between_bottles_and_wines(self):
        cellar = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("val displayedWineCount = remember(filteredInventory)", cellar)
        self.assertIn("if (cellarCountShowsBottles)", cellar)
        self.assertIn('"wine" else "wines"', cellar)

    def test_notes_line_shows_wines_and_note_count_below_search(self):
        notes = (APP / "ui/screens/TastingNotesScreen.kt").read_text()
        self.assertIn('"Showing ${groupedHistory.size} "', notes)
        self.assertIn('"(${filteredHistory.size} tasting "', notes)
        search_item = notes.index('item(key = "notes_search")')
        count_line = notes.index('"Showing ${groupedHistory.size} "')
        search_field = notes.index("OutlinedTextField(", search_item)
        self.assertLess(search_field, count_line)


if __name__ == "__main__":
    unittest.main()
