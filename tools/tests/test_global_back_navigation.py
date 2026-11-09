"""Source contracts for Android back behavior across tabs and cellar racks."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"


class GlobalBackNavigationTests(unittest.TestCase):
    def test_main_scaffold_tracks_menu_history_and_handles_android_back(self):
        source = (APP / "MainActivity.kt").read_text()
        self.assertIn('mutableStateOf(listOf("cellar"))', source)
        self.assertIn("fun navigateToMenu(route: String)", source)
        self.assertIn("BackHandler(enabled = currentRoute != null", source)
        self.assertIn("currentRoute in TOP_LEVEL_MENU_ROUTES", source)
        self.assertIn("menuHistory = menuHistory.dropLast(1)", source)
        self.assertIn('private val TOP_LEVEL_MENU_ROUTES = setOf("cellar", "notes", "history", "rankings", "profile")', source)
        for route in ("notes", "history", "rankings", "profile"):
            self.assertIn(f'navigateToMenu("{route}")', source)

    def test_cellar_back_steps_out_of_rack_navigation(self):
        source = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("BackHandler(enabled = cellarTab == 1)", source)
        self.assertIn("selectedRack = null", source)
        self.assertIn("cellarTab = 0", source)


if __name__ == "__main__":
    unittest.main()
