import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
HISTORY = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui/screens/HistoryScreen.kt"


class HistoryTabsTests(unittest.TestCase):
    def test_history_has_consumed_and_added_tabs_like_cellar(self):
        source = HISTORY.read_text()
        # Two tabs across the top, Consumed default on the left.
        self.assertIn("TabRow(selectedTabIndex = historyTab)", source)
        self.assertIn('Tab(selected = historyTab == 0, onClick = { selectHistoryTab(0) }, text = { Text("Consumed") })', source)
        self.assertIn('Tab(selected = historyTab == 1, onClick = { selectHistoryTab(1) }, text = { Text("Added") })', source)
        # Same navigation as Cellar: swipeable pager, animated tab taps,
        # off-screen page kept composed, Back returns to the first tab.
        self.assertIn("rememberPagerState(initialPage = 0) { 2 }", source)
        self.assertIn("HorizontalPager(", source)
        self.assertIn("beyondViewportPageCount = 1", source)
        self.assertIn("pagerState.animateScrollToPage(page)", source)
        self.assertIn("BackHandler(enabled = historyTab == 1) { selectHistoryTab(0) }", source)

    def test_no_excess_top_padding_and_no_duplicate_titles(self):
        source = HISTORY.read_text()
        self.assertIn("Scaffold(contentWindowInsets = WindowInsets(0.dp))", source)
        self.assertNotIn("Consumption History", source)
        self.assertNotIn("Added to Cellar", source)

    def test_added_tab_lists_wines_by_date_added_newest_first(self):
        source = HISTORY.read_text()
        self.assertIn("viewModel.activeInventory", source)
        self.assertIn("val addedWines = inventory.sortedByDescending {", source)
        self.assertIn("addedParseFormat.parse(raw)?.time", source)
        self.assertIn('"No wines added yet"', source)


if __name__ == "__main__":
    unittest.main()
