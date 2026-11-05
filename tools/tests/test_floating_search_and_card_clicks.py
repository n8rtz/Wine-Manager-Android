"""Host contract checks for floating search/filter bubbles, card click targets and scrolling headers.

Run with: python3 tools/tests/test_floating_search_and_card_clicks.py
These are source-level checks; the on-device checklist lives in THINGS_TO_CHECK.md.
"""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
UI = ROOT / "app/src/main/java/com/n8rtz/winetracker/ui"
COMPONENTS = UI / "components"
SCREENS = UI / "screens"

CELLAR = SCREENS / "CellarScreen.kt"
RANKINGS = SCREENS / "RankingsScreen.kt"
NOTES = SCREENS / "TastingNotesScreen.kt"
HISTORY = SCREENS / "HistoryScreen.kt"
WINE_CARD = COMPONENTS / "WineCard.kt"
CONTROLS = COMPONENTS / "FloatingSearchFilterControls.kt"

LIST_SCREENS = {"cellar": CELLAR, "rankings": RANKINGS, "notes": NOTES}

# The part of each screen above its filter sheet / card composable.
BODY_END = {
    "cellar": "fun FilterSheet(",
    "rankings": "fun RankingsFilterSheet(",
    "notes": "fun CompactNoteItem(",
}

# Screens that keep a pinned back/action bar; those bars must scroll away instead of freezing.
SCROLLING_TOP_BAR_SCREENS = (
    "ParetoScreen.kt",
    "TastingNoteEditorScreen.kt",
    "AddWineScreen.kt",
    "WineDetailScreen.kt",
    "AppInfoScreen.kt",
    "DatabaseLookupScreen.kt",
    "ProfileScreen.kt",
    "BackupRestoreScreen.kt",
)


def screen_body(name):
    return LIST_SCREENS[name].read_text().split(BODY_END[name])[0]


class FloatingControlsTests(unittest.TestCase):
    def test_controls_file_exists_with_both_bubbles_and_a_popup_field(self):
        source = CONTROLS.read_text()
        self.assertIn("fun FloatingSearchFilterControls(", source)
        self.assertIn('contentDescription = "Search"', source)
        self.assertIn('contentDescription = "Filter & Sort"', source)
        self.assertIn("Alignment.TopEnd", source)
        self.assertIn('placeholder = { Text("Search", maxLines = 1) }', source)
        self.assertIn("onOpenFilters", source)
        self.assertIn("onCloseSearch", source)
        self.assertNotIn("scrollToItem", source)

    def test_bubbles_are_small_round_surfaces_not_a_full_width_row(self):
        source = CONTROLS.read_text()
        self.assertIn("shape = CircleShape", source)
        self.assertIn("Modifier.size(44.dp)", source)
        self.assertIn("if (searchExpanded) {", source)

    def test_all_three_list_screens_use_the_shared_controls(self):
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertIn("FloatingSearchFilterControls(", source, name)
            self.assertIn("searchExpanded = searchExpanded", source, name)
            self.assertIn("onOpenSearch = { searchExpanded = true }", source, name)
            self.assertIn("onOpenFilters = { showFilterSheet = true }", source, name)
            self.assertIn("Modifier.align(Alignment.TopCenter)", source, name)
            self.assertIn("top = 60.dp", source, name)

    def test_no_fixed_full_width_search_row_remains_on_the_list_screens(self):
        for name in LIST_SCREENS:
            source = screen_body(name)
            self.assertNotIn('Text("Search", maxLines = 1)', source, name)
            self.assertNotIn("OutlinedTextField(", source, name)
            self.assertNotIn("Icons.Default.Tune", source, name)


class ScrollPositionTests(unittest.TestCase):
    def test_search_and_filter_changes_never_jump_the_list_to_the_top(self):
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertNotIn("LaunchedEffect(filterState, searchQuery)", source, name)
            self.assertNotIn("LaunchedEffect(listKey)", source, name)
            self.assertNotIn("LaunchedEffect(rankedWines)", source, name)
            self.assertNotIn("lastRankedHash", source, name)
            self.assertNotIn("lastListKey", source, name)
            matches = re.findall(r"LaunchedEffect\([^)]*\) \{[^{}]*scrollToItem\(0\)", source)
            self.assertEqual(len(matches), 1, (name, matches))
            self.assertIn("LaunchedEffect(Unit)", matches[0], name)

    def test_opening_search_does_not_change_the_list_state(self):
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertNotIn("LaunchedEffect(searchExpanded)", source, name)
            self.assertIn("onOpenSearch = { searchExpanded = true }", source, name)
            self.assertEqual(source.count("scrollToItem(0)"), 1, name)


class CardClickTests(unittest.TestCase):
    def test_body_click_only_navigates(self):
        source = WINE_CARD.read_text()
        self.assertIn(".clickable(onClick = onClick)", source)
        self.assertNotIn("if (details != null && isExpanded == null)", source)
        self.assertIn("onExpandToggle: (() -> Unit)? = null", source)
        self.assertNotIn("expansionResetKey", source)

    def test_caret_has_a_generous_own_click_target(self):
        source = WINE_CARD.read_text()
        carat = source[source.index("if (details != null) {"):]
        carat = carat[: carat.index("AnimatedVisibility")]
        self.assertIn(".size(48.dp)", carat)
        self.assertIn(".clip(CircleShape)", carat)
        self.assertIn("contentAlignment = Alignment.Center", carat)
        self.assertIn("onExpandToggle.invoke()", carat)
        self.assertIn("Icons.Default.ExpandLess", carat)
        self.assertIn("Icons.Default.ExpandMore", carat)

    def test_cellar_hoists_one_open_card(self):
        source = CELLAR.read_text()
        self.assertIn("var expandedBottleId by remember { mutableStateOf<Long?>(null) }", source)
        self.assertIn("isExpanded = expandedBottleId == item.bottle.bottleId", source)
        self.assertIn("expandedBottleId = if (expandedBottleId == item.bottle.bottleId) null else item.bottle.bottleId", source)
        self.assertIn("onClick = { onWineClick(item.bottle.bottleId) }", source)
        self.assertIn("expandedBottleId = null", source)

    def test_notes_hoists_one_open_group_and_body_opens_the_latest_note(self):
        source = NOTES.read_text()
        self.assertIn("var expandedGroupKey by remember { mutableStateOf<Pair<Long, String>?>(null) }", source)
        self.assertIn("expandedGroupKey = if (expandedGroupKey == groupKey) null else groupKey", source)
        self.assertIn("onExpandToggle = onExpandToggle", source)
        self.assertIn("onClick = { onNoteClick(latestNoteId) }", source)
        self.assertIn("group.maxByOrNull { it.note.dateTasted }", source)
        self.assertIn(".clickable { onNoteClick(item.note.noteId) }", source)

    def test_rankings_body_stays_a_no_op(self):
        source = RANKINGS.read_text()
        self.assertIn("onClick = {}", source)
        self.assertNotIn("onExpandToggle", source)


class StatsAndHeaderTests(unittest.TestCase):
    def test_stats_are_in_a_dialog_behind_a_scrolling_header_icon(self):
        source = CELLAR.read_text()
        self.assertIn('item(key = "cellar_header")', source)
        self.assertIn("IconButton(onClick = { showStatsDialog = true })", source)
        self.assertIn("if (showStatsDialog) {", source)
        self.assertIn("AlertDialog(", source)
        self.assertIn('title = { Text("Cellar Stats") }', source)
        for label in ("Total Bottles", "Unique Wines", "Est. Value"):
            self.assertIn(f'AnalyticsCard("{label}"', source)
        # The old fixed three-card row (outside a dialog) is gone.
        self.assertNotIn("modifier = Modifier.fillMaxWidth().padding(16.dp),\n            horizontalArrangement = Arrangement.spacedBy(12.dp)", source)

    def test_titles_live_inside_the_scrolling_lists(self):
        for key, path, title in (
            ("rankings", RANKINGS, "Rankings"),
            ("notes", NOTES, "Tasting Notes"),
            ("history", HISTORY, "Consumption History"),
        ):
            source = path.read_text()
            self.assertIn(f'item(key = "{key}_header")', source, key)
            self.assertIn(f'"{title}"', source, key)
            self.assertNotIn("CenterAlignedTopAppBar(", source, key)

    def test_pinned_top_bars_scroll_away_instead_of_staying_frozen(self):
        for name in SCROLLING_TOP_BAR_SCREENS:
            source = (SCREENS / name).read_text()
            self.assertIn("TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())", source, name)
            self.assertIn("Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)", source, name)
            self.assertIn("scrollBehavior = scrollBehavior", source, name)

    def test_no_explanatory_prose_was_added_to_the_new_controls(self):
        source = CONTROLS.read_text()
        for phrase in ("how it works", "Tap to", "Use the", "Scroll to", "Please"):
            self.assertNotIn(phrase, source)


if __name__ == "__main__":
    unittest.main()
