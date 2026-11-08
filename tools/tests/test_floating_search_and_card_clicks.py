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
# AddWineScreen is exempt: camera-first is full-bleed with no top bar (see
# test_add_wine_is_full_bleed_camera_first_with_no_top_bar below).
SCROLLING_TOP_BAR_SCREENS = (
    "ParetoScreen.kt",
    "TastingNoteEditorScreen.kt",
    "WineEditScreen.kt",
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

    def test_bubbles_are_small_round_surfaces_stacked_vertically(self):
        source = CONTROLS.read_text()
        self.assertIn("shape = CircleShape", source)
        self.assertIn("Modifier.size(44.dp)", source)
        self.assertIn("if (searchExpanded) {", source)
        bubbles = source[source.index("} else {"):]
        self.assertIn("Column(", bubbles)
        self.assertIn("verticalArrangement = Arrangement.spacedBy(8.dp)", bubbles)
        self.assertIn("horizontalAlignment = Alignment.End", bubbles)
        self.assertNotIn("horizontalArrangement", bubbles)
        self.assertIn("import androidx.compose.foundation.layout.Column", source)
        # Search sits above the filter button in the stack.
        self.assertLess(bubbles.index('contentDescription = "Search"'), bubbles.index('contentDescription = "Filter & Sort"'))

    def test_all_three_list_screens_use_the_shared_controls(self):
        for name, path in LIST_SCREENS.items():
            source = path.read_text()
            self.assertIn("FloatingSearchFilterControls(", source, name)
            self.assertIn("import com.n8rtz.winetracker.ui.components.FloatingSearchFilterControls", source, name)
            self.assertIn("searchExpanded = searchExpanded", source, name)
            self.assertIn("onOpenSearch = { searchExpanded = true }", source, name)
            self.assertIn("onOpenFilters = { showFilterSheet = true }", source, name)
            self.assertIn("Modifier.align(Alignment.TopCenter)", source, name)
            self.assertIn("if (showCompactControls || searchExpanded)", source, name)
            self.assertIn("contentPadding = PaddingValues(16.dp)", source, name)
            self.assertNotIn("top = 60.dp", source, name)

    def test_original_top_search_row_is_restored_as_scrolling_content(self):
        for name in LIST_SCREENS:
            source = screen_body(name)
            self.assertIn(f'item(key = "{name}_search")', source, name)
            self.assertIn('placeholder = { Text("Search", maxLines = 1) }', source, name)
            self.assertIn("OutlinedTextField(", source, name)
            self.assertIn("Icons.Default.Tune", source, name)
            self.assertIn("Icons.Default.Search", source, name)
            # The row is an item inside the scrolling list, not a fixed Column above it.
            self.assertLess(source.index("LazyColumn("), source.index(f'item(key = "{name}_search")'), name)

    def test_compact_controls_appear_only_after_the_search_row_scrolls_away(self):
        for name in LIST_SCREENS:
            source = screen_body(name)
            self.assertIn("derivedStateOf {", source, name)
            # Cellar has only the search row above the cards; the other two also have a title row.
            threshold = "> 0" if name == "cellar" else "> 1"
            self.assertIn(f"firstVisibleItemIndex {threshold}", source, name)
            self.assertIn("if (showCompactControls || searchExpanded)", source, name)
            self.assertIn("LaunchedEffect(showCompactControls)", source, name)


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
            self.assertNotIn("LaunchedEffect(searchQuery)", source, name)
            self.assertNotIn("LaunchedEffect(filterState)", source, name)
            self.assertIn("onOpenSearch = { searchExpanded = true }", source, name)
            self.assertEqual(source.count("scrollToItem(0)"), 1, name)


class CardClickTests(unittest.TestCase):
    def test_body_click_uses_the_action_supplied_by_its_screen(self):
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

    def test_cellar_body_and_caret_toggle_one_open_card(self):
        source = CELLAR.read_text()
        self.assertIn("var expandedBottleId by remember { mutableStateOf<Long?>(null) }", source)
        self.assertIn("isExpanded = expandedBottleId == item.bottle.bottleId", source)
        self.assertIn("expandedBottleId = if (expandedBottleId == item.bottle.bottleId) null else item.bottle.bottleId", source)
        self.assertIn("onExpandToggle = toggleExpanded", source)
        self.assertIn("onClick = toggleExpanded", source)
        self.assertNotIn("onWineClick", source)
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
    def test_stats_is_one_icon_inline_with_the_search_bar_and_filters(self):
        source = CELLAR.read_text()
        self.assertNotIn('item(key = "cellar_header")', source)
        search_item = source[source.index('item(key = "cellar_search")'):]
        search_item = search_item[: search_item.index("items(filteredInventory")]
        self.assertIn("onClick = { showStatsDialog = true },", search_item)
        self.assertIn("Icons.Default.QueryStats", search_item)
        self.assertNotIn("DonutLarge", search_item)
        self.assertIn("Icons.Default.Tune", search_item)
        self.assertIn("OutlinedTextField(", search_item)
        # The single stats icon is on the left of the search field, with the filter on the right.
        self.assertLess(search_item.index("Icons.Default.QueryStats"), search_item.index("OutlinedTextField("))
        self.assertLess(search_item.index("OutlinedTextField("), search_item.index("Icons.Default.Tune"))
        # A single stats icon: no stats card or second stats button in the row.
        self.assertEqual(search_item.count("showStatsDialog = true"), 1)
        self.assertNotIn("AnalyticsCard(", search_item)
        self.assertIn("Showing $displayedBottleCount", search_item)
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
            self.assertIn("androidx.compose.ui.input.nestedscroll.nestedScroll", source, name)
            # Files without a material3 wildcard import need these two explicitly.
            if "import androidx.compose.material3.*" not in source:
                self.assertIn("import androidx.compose.material3.TopAppBarDefaults", source, name)
                self.assertIn("import androidx.compose.material3.rememberTopAppBarState", source, name)

    def test_add_wine_is_full_bleed_camera_first_with_no_top_bar(self):
        source = (SCREENS / "AddWineScreen.kt").read_text()
        for absent in ("TopAppBar(", "TopAppBarDefaults", "rememberTopAppBarState(",
                       "scrollBehavior", "nestedScroll"):
            self.assertNotIn(absent, source)
        self.assertIn("BottomSheetScaffold(", source)
        self.assertIn("LabelScanCameraPreview(", source)

    def test_no_explanatory_prose_was_added_to_the_new_controls(self):
        source = CONTROLS.read_text()
        for phrase in ("how it works", "Tap to", "Use the", "Scroll to", "Please"):
            self.assertNotIn(phrase, source)


class ComposeImportTests(unittest.TestCase):
    """A screen with explicit layout imports must import every extension it calls.

    This caught the real :app:compileDebugKotlin failure: HistoryScreen used
    Modifier.fillMaxWidth() without importing androidx.compose.foundation.layout.fillMaxWidth.
    """
    GROUPS = {
        "androidx.compose.foundation.layout": (
            "fillMaxWidth", "fillMaxSize", "fillMaxHeight", "wrapContentHeight",
            "wrapContentWidth", "wrapContentSize", "padding", "size", "height",
            "width", "heightIn", "widthIn", "sizeIn", "absolutePadding",
        ),
        "androidx.compose.foundation": ("background", "clickable", "verticalScroll", "border"),
        "androidx.compose.ui.draw": ("clip", "shadow", "rotate"),
        "androidx.compose.ui.focus": ("focusRequester",),
    }

    def test_every_called_compose_extension_is_imported(self):
        for path in sorted(UI.rglob("*.kt")):
            source = path.read_text()
            body = "\n".join(
                line for line in source.splitlines() if not line.startswith("import ")
            )
            # Coil ImageRequest.Builder.size() takes bare-pixel ints (e.g. .size(1024));
            # only Dp-valued .size() calls are the Compose layout extension.
            body = re.sub(r"\.size\(\d+\)", "", body)
            for package, names in self.GROUPS.items():
                wildcard = f"import {package}.*" in source
                for name in names:
                    if not re.search(r"\." + name + r"\(", body):
                        continue
                    if wildcard or f"import {package}.{name}\n" in source:
                        continue
                    if f"{package}.{name}(" in body:
                        continue  # fully-qualified call, no import needed
                    self.fail(f"{path.name}: uses .{name}( without importing {package}.{name}")


if __name__ == "__main__":
    unittest.main()
