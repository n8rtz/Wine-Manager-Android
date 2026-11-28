"""Source contracts for the Settings option choosing the default cellar sort."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"


class DefaultCellarSortTests(unittest.TestCase):
    def test_setting_is_persisted_and_used_as_the_starting_sort(self):
        vm = (APP / "viewmodels/WineViewModel.kt").read_text()
        self.assertIn('getSharedPreferences(\n        "wine_manager_settings"', vm)
        self.assertIn('"default_cellar_sort_field"', vm)
        self.assertIn('"default_cellar_sort_ascending"', vm)
        self.assertIn("fun setDefaultCellarSort(field: CellarSortField, ascending: Boolean)", vm)
        self.assertIn("val defaultCellarSort: StateFlow<CellarFilterState>", vm)
        # The cellar starts with the saved default and resets back to it.
        self.assertIn("MutableStateFlow(_defaultCellarSort.value)", vm)
        self.assertIn("_filterState.value = _defaultCellarSort.value", vm)
        self.assertNotIn("_filterState.value = CellarFilterState()\n        _cellarResetKey", vm)

    def test_settings_menu_offers_the_default_sort(self):
        profile = (APP / "ui/screens/ProfileScreen.kt").read_text()
        self.assertIn('text = "Cellar"', profile)
        self.assertIn('Text("Default cellar sort")', profile)
        self.assertIn("showDefaultSortSettings = true", profile)
        self.assertIn("CellarSortField.entries.forEach { field ->", profile)
        self.assertIn("viewModel.setDefaultCellarSort(field, defaultCellarSort.sortAscending)", profile)
        self.assertIn("viewModel.defaultCellarSort.collectAsState()", profile)
        for label in ("Date Added", "Quantity", "Rating", "Price",
                      "Drinking Window (Soonest)", "Drinking Window (Furthest)"):
            self.assertIn(f'-> "{label}"', profile)

    def test_changing_the_default_applies_immediately_to_the_cellar(self):
        vm = (APP / "viewmodels/WineViewModel.kt").read_text()
        self.assertIn(
            "_filterState.value = _filterState.value.copy(sortField = field, sortAscending = ascending)",
            vm,
        )


if __name__ == "__main__":
    unittest.main()
