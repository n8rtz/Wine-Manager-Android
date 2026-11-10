"""Source contracts for compact cellar-rack navigation and assignment controls."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"


class CellarRacksUiTests(unittest.TestCase):
    def test_rack_overview_has_no_redundant_heading_or_intro(self):
        source = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertNotIn('Text("Wine racks"', source)
        self.assertNotIn("Group bottles by how you plan to enjoy or age them.", source)
        self.assertIn("LazyVerticalGrid(", source)

    def test_quick_rack_action_opens_multi_select_rack_tab_with_clear_rack_glyph(self):
        source = (APP / "ui/screens/CellarRackViews.kt").read_text()
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("fun RackQuickAddButton(", source)
        self.assertIn("private fun RackGlyph(", source)
        self.assertIn("painterResource(R.drawable.wine_rack_lattice)", source)
        self.assertIn("ColorFilter.tint(color)", source)
        self.assertIn("modifier = Modifier.size(width = 25.dp, height = 27.dp)", source)
        self.assertIn("Arrangement.spacedBy(1.dp)", source)
        self.assertIn("Icons.Default.Add, contentDescription = null", source)
        self.assertIn("contentScale = ContentScale.FillBounds", source)
        self.assertIn(".size(width = 44.dp, height = 40.dp)", source)
        self.assertIn(".clickable(onClick = onOpenRackSelection)", source)
        self.assertIn("contentDescription = if (automaticNewAddition) \"Add wine to another rack\"", source)
        asset = ROOT / "app/src/main/res/drawable-nodpi/wine_rack_lattice.png"
        self.assertTrue(asset.exists())
        data = asset.read_bytes()
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        self.assertEqual(data[25], 6, "rack art should retain transparent checkerboard areas")
        self.assertIn("onOpenRackSelection", source)
        self.assertNotIn("DropdownMenu(expanded = expanded", source)
        self.assertIn("Choose one or more racks", source)
        self.assertIn("selectedAssignmentRacks", source)
        self.assertIn('else "Add to ${selectedAssignmentRacks.size} racks")', source)
        self.assertIn('"Remove from racks"', source)
        self.assertIn("selectCellarTab(1)", screen)
        self.assertIn("updateRackAssignments", screen)

    def test_expanded_card_inline_actions_and_overflow_aroma_chip_opens_note(self):
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        chips = (APP / "ui/components/AromaChips.kt").read_text()
        # Cellar count, add-note and Tasting notes share one inline row.
        row = screen.split('text = "Cellar: ${item.bottle.quantity}"', 1)[1].split("if (formattedAddedDate != null)", 1)[0]
        self.assertIn("Icons.Default.NoteAdd", row)
        self.assertIn('Text("Tasting notes")', row)
        # Aromas come from the most recent note that has aromas.
        self.assertIn(".sortedByDescending { it.note.dateTasted }", screen)
        # The "+N" overflow chip is a button straight to that tasting note.
        self.assertIn("onOverflowClick: (() -> Unit)? = null", chips)
        self.assertIn("onOverflowClick = { onNoteClick(aromaNote.note.noteId) }", screen)

    def test_rack_order_is_persisted_and_long_press_supports_live_drag_reordering(self):
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        views = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertIn('getString("rack_order_json", null)', screen)
        self.assertIn('putString("rack_order_json", org.json.JSONArray(rackNames).toString())', screen)
        self.assertIn('add(toIndex, removeAt(fromIndex))', screen)
        self.assertIn('onMoveRack = moveRack', screen)
        self.assertIn('rackOrder = rackNames', screen)
        self.assertNotIn('names.sortedBy', screen)
        self.assertNotIn('rackNames.sortedBy', screen)
        self.assertIn('onMoveRack: (String, String) -> Unit', views)
        self.assertIn('rememberLazyGridState()', views)
        self.assertIn('.animateItem()', views)
        self.assertNotIn('import androidx.compose.foundation.lazy.grid.animateItem', views)
        self.assertIn('detectDragGesturesAfterLongPress(', views)
        self.assertIn('detectDragGestures(', views)
        self.assertIn('latestOnMoveRack(activeRack, targetRack)', views)
        self.assertIn('import androidx.compose.material.icons.filled.CheckCircle', views)
        self.assertNotIn('import androidx.compose.ui.input.pointer.consume', screen)
        self.assertNotIn('import androidx.compose.ui.input.pointer.consume', views)

    def test_cellar_swipe_changes_between_bottles_and_racks_without_interrupting_rack_detail(self):
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        views = (APP / "ui/screens/CellarRackViews.kt").read_text()
        # A pager gives a smooth, finger-tracking slide between the two pages
        # (and an animated settle on tab taps) instead of a sudden swap.
        self.assertIn("rememberPagerState(initialPage = 0) { 2 }", screen)
        self.assertIn("HorizontalPager(", screen)
        # Both pages stay composed so swipe-away-and-back keeps page state
        # (e.g. an open wine Edit/Delete bar is still open on return).
        self.assertIn("beyondViewportPageCount = 1", screen)
        self.assertIn("pagerState.animateScrollToPage(page)", screen)
        self.assertIn("selectCellarTab(0)", screen)
        self.assertIn("selectCellarTab(1)", screen)
        self.assertNotIn("detectHorizontalDragGestures(", screen)
        self.assertNotIn("if (cellarTab == 0)", screen)
        # Paging must never interrupt an open rack, a rack assignment, or a
        # rack-tile drag-reorder.
        self.assertIn(
            "userScrollEnabled = selectedRack == null && assignmentBottleId == null && !rackDragActive",
            screen,
        )
        self.assertIn("onRackDragActiveChange = { rackDragActive = it }", screen)
        self.assertIn("LaunchedEffect(draggingRack != null) { onRackDragActiveChange(draggingRack != null) }", views)

    def test_rating_sort_uses_each_wines_most_recent_rating(self):
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        # Most recent rating (by date tasted) wins; never the average.
        self.assertIn("notes.maxByOrNull { it.note.dateTasted }?.note?.rating", screen)
        self.assertNotIn("notes.map { it.note.rating }.average()", screen)
        self.assertIn("CellarSortField.RATING -> compareSortValues(aRating, bRating, filterState.sortAscending)", screen)

    def test_long_press_selects_cellar_wine_with_popup_edit_and_confirmed_delete(self):
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        # Long-press selects the wine and opens its popup menu.
        self.assertIn("selectedWineIds = selectedWineIds + item.bottle.bottleId", screen)
        self.assertIn("wineMenuBottleId = item.bottle.bottleId", screen)
        self.assertNotIn("DropdownMenu(", screen)
        # Every card shows a top-left checkbox while selecting; the selected
        # wine's is checked (checkmark), the rest are empty grey boxes.
        self.assertIn("if (wineSelectionMode)", screen)
        self.assertIn("checked = item.bottle.bottleId in selectedWineIds", screen)
        self.assertIn("uncheckedColor = Color.Gray", screen)
        self.assertIn(".align(Alignment.TopStart)", screen)
        # The checkbox is small and tucked up above the wine's name.
        self.assertIn(".offset(x = 3.dp, y = 1.dp)", screen)
        self.assertIn(".size(28.dp)", screen)
        self.assertIn("scaleX = 0.8f; scaleY = 0.8f", screen)
        # Action-bar pill labels never wrap mid-word.
        self.assertIn("maxLines = 1,", screen)
        self.assertIn("softWrap = false", screen)
        # Edit disappears once 2+ wines are selected; Delete stays.
        self.assertIn("if (selectedWineIds.size == 1)", screen)
        self.assertIn("showWineDeleteConfirmation = true", screen)
        # The popup is a single-line, transparent action bar docked at the
        # bottom of the screen (above the main navigation): no solid
        # backdrop, just translucent bordeaux-outlined pills, and the "+"
        # Add menu in the host scaffold hides while it is up.
        self.assertIn("private fun WineActionBarAction(", screen)
        self.assertIn('label = "Edit"', screen)
        self.assertIn("Icons.Default.Edit", screen)
        self.assertIn("Icons.Default.Delete", screen)
        self.assertIn("Alignment.BottomCenter", screen)
        # Animated by hand: AnimatedVisibility picks the ColumnScope
        # overload inside the Column-nested pager and fails to compile.
        self.assertIn("animateFloatAsState(", screen)
        self.assertNotIn("AnimatedVisibility(", screen)
        self.assertIn("color = MaterialTheme.colorScheme.surface,", screen)
        self.assertNotIn("copy(alpha = 0.82f)", screen)
        self.assertNotIn("BorderStroke(\n            1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)", screen)
        self.assertIn("onWineActionBarVisibleChange: (Boolean) -> Unit = {}", screen)
        main = (ROOT / "app/src/main/java/com/n8rtz/winetracker/MainActivity.kt").read_text()
        self.assertIn("onWineActionBarVisibleChange = { wineActionBarVisible = it }", main)
        self.assertIn('currentRoute != "add_wine" && !wineActionBarVisible', main)
        self.assertIn("visible = isMenuExpanded && showAddMenu && !wineActionBarVisible", main)
        # Deleting always asks for confirmation first.
        self.assertIn('Text(if (selectedWineIds.size == 1) "Delete wine?" else "Delete wines?")', screen)
        self.assertIn("selectedWineIds.forEach { bottleId ->", screen)
        self.assertIn("viewModel.deleteWine(bottleId)", screen)
        # ...or everywhere (cellar + notes) when the user opts in.
        self.assertIn('"Also delete tasting notes"', screen)
        self.assertIn("viewModel.deleteWineEverywhere(", screen)
        repo = (APP / "data/repository/WineRepository.kt").read_text()
        self.assertIn("tastingNoteDao.deleteNotesForWine(definitionId, vintage)", repo)
        dao = (APP / "data/local/WineDaos.kt").read_text()
        self.assertIn("suspend fun deleteNotesForWine(definitionId: Long, vintage: String)", dao)
        self.assertLess(screen.index("showWineDeleteConfirmation = true"),
                        screen.index("viewModel.deleteWine(bottleId)"))

    def test_automatic_new_additions_does_not_disable_other_rack_assignments(self):
        source = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertNotIn("enabled = !automaticNewAddition", source)
        self.assertIn('if (automaticNewAddition) "Add wine to another rack" else "Choose wine racks"', source)
        self.assertIn('selectedRack in assignments[it.bottle.bottleId].orEmpty()', source)
        self.assertNotIn("bottle.bottleId !in newAdditions", source)

    def test_custom_rack_tile_creates_and_persists_named_racks(self):
        views = (APP / "ui/screens/CellarRackViews.kt").read_text()
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn('item(key = "add_rack_tile")', views)
        self.assertIn('Text("New Rack"', views)
        self.assertNotIn('Text("Create a custom rack"', views)
        self.assertIn('title = { Text("Create rack") }', views)
        self.assertIn('onCreateRack(candidate)', views)
        self.assertIn('getStringSet("rack_names"', screen)
        self.assertIn('putStringSet("rack_names", rackNames.filterNot { it == NEW_ADDITIONS_RACK }.toSet())', screen)

    def test_rack_tiles_reveal_edit_and_delete_only_after_long_press(self):
        views = (APP / "ui/screens/CellarRackViews.kt").read_text()
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("detectDragGesturesAfterLongPress(", views)
        self.assertIn("if (wasSelectedAtStart && !didMove)", views)
        self.assertIn("selectedRackNamesForDeletion = emptySet()", views)
        self.assertIn("val deletableSelectedRackNames = selectedRackNamesForDeletion - NEW_ADDITIONS_RACK", views)
        self.assertNotIn('contentDescription = "Edit $rack name"', views)
        self.assertIn('if (selectedRackNamesForDeletion.size == 1 && deletableSelectedRackNames.isNotEmpty())', views)
        self.assertIn('Text("Edit")', views)
        self.assertIn('showRackDeleteConfirmation = true', views)
        self.assertIn('title = { Text(if (deletableSelectedRackNames.size == 1) "Delete rack?" else "Delete racks?") }', views)
        self.assertIn('onDeleteRacks(deletableSelectedRackNames)', views)
        self.assertIn('val renameRack: (String, String) -> Unit', screen)
        self.assertIn('val deleteRacks: (Set<String>) -> Unit', screen)

    def test_long_press_selects_wines_for_removal_from_the_open_rack(self):
        views = (APP / "ui/screens/CellarRackViews.kt").read_text()
        card = (APP / "ui/components/WineCard.kt").read_text()
        screen = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn('onLongClick = if (canRemoveFromRack)', views)
        self.assertIn('selectedForRemoval = selectedForRemoval + item.bottle.bottleId', views)
        self.assertIn('Text("Remove from rack")', views)
        self.assertIn('onRemoveFromRack(selectedForRemoval, it)', views)
        self.assertIn('.combinedClickable(onClick = onClick, onLongClick = onLongClick)', card)
        self.assertIn('val removeFromRack: (Set<Long>, String) -> Unit', screen)
        self.assertIn('new_additions_exclusions', screen)
        self.assertIn('excludedNewAdditions = excludedNewAdditionIds', screen)
        self.assertIn('.offset(x = (-7.5).dp, y = (-8).dp)', views)

    def test_quick_rack_action_sits_above_the_expand_caret_on_cellar_cards(self):
        card = (APP / "ui/components/WineCard.kt").read_text()
        cellar = (APP / "ui/screens/CellarScreen.kt").read_text()
        views = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertIn("trailingAction: @Composable (() -> Unit)? = null", card)
        self.assertLess(card.index("trailingAction?.invoke()"), card.index("Icons.Default.ExpandLess"))
        # The rack glyph (25.dp) sits right of the "+" (14.dp + 1.dp gap) in
        # the button's centred row, so its centre is 7.5.dp right of the
        # button centre; the button is shifted left by exactly that so the
        # rack icon is centred over the caret, which is centred in the same
        # WineCard trailing column (Column is CenterHorizontally).
        self.assertIn("horizontalAlignment = Alignment.CenterHorizontally", card)
        self.assertIn('.offset(x = (-7.5).dp, y = (-8).dp)', views)
        self.assertNotIn('.offset(x = (-4).dp, y = (-4).dp)', views)
        self.assertIn("trailingAction = {", cellar)
        self.assertIn("RackQuickAddButton(", cellar)
        # The rating is a bare number (no star) in the trailing column,
        # between the rack button (invoked above it) and the caret (below),
        # and the caret is nudged down to open that space up.
        self.assertNotIn("★", card)
        self.assertIn("val compactMetadata = metadata", card)
        rating_block = card.index("val ratingText =")
        self.assertLess(card.index("trailingAction?.invoke()"), rating_block)
        self.assertLess(rating_block, card.index("Icons.Default.ExpandLess"))
        self.assertIn("text = ratingText,", card)
        self.assertIn("Modifier.size(48.dp).offset(y = 4.dp)", card)
        # The whole rack-button / rating / caret stack is shifted down, the
        # rating text is larger, and its bordered box is filled with the
        # colour assigned to the rating -- an ombre between the colours of
        # the lower and upper bounds for a rating range.
        self.assertIn("Modifier.offset(y = 6.dp)", card)
        self.assertIn("style = MaterialTheme.typography.titleMedium", card)
        # The rating box outline is a neutral black/dark gray.
        self.assertIn(".border(1.5.dp, Color(0xFF333333), ratingShape)", card)
        self.assertNotIn(".border(1.5.dp, MaterialTheme.colorScheme.primary, ratingShape)", card)
        self.assertIn("Brush.horizontalGradient(", card)
        self.assertIn("ratingColor(ratingValues.first())", card)
        self.assertIn("ratingColor(ratingValues.last())", card)
        # The ombre blend is confined to a narrow centre band; each bound's
        # colour stays solid across its own side of the box.
        self.assertIn("0.42f to lowerColor", card)
        self.assertIn("0.58f to upperColor", card)
        sensory = (APP / "ui/components/SensoryEvaluationFields.kt").read_text()
        self.assertIn("internal fun overallScoreBand(score: Int)", sensory)
        # The rating fill uses one flat, stepped colour per band so wines
        # in adjacent bands are easy to tell apart; 98-100 is a near-black
        # green and nothing 85+ is Yellow.
        self.assertIn("internal fun ratingColor(score: Int): Color = when {", sensory)
        for band in (
            "score >= 98 -> Color(0xFF0A2E1F)",
            "score >= 95 -> Color(0xFF146B3A)",
            "score >= 92 -> Color(0xFF1E7A3C)",
            "score >= 89 -> Color(0xFF2E8B47)",
            "score >= 85 -> Color(0xFF55A04A)",
            "score >= 80 -> Color(0xFFA8B93C)",
            "score >= 75 -> Color(0xFFD9B81F)",
            "score >= 70 -> Color(0xFFE39A1E)",
            "score >= 60 -> Color(0xFFD3542E)",
            "else -> Color(0xFF8E2323)",
        ):
            self.assertIn(band, sensory)
        # Stepped, not interpolated: no gradient-stop list or colour lerp.
        self.assertNotIn("ratingColorStops", sensory)
        self.assertNotIn("colorLerp", sensory)
        # Unrated wines show "NR" on a plain grey fill instead of no box.
        self.assertIn('val ratingText = rating?.takeIf { it.isNotBlank() } ?: "NR"', card)
        self.assertIn("Modifier.background(Color(0xFF9E9E9E))", card)
        self.assertIn('contentDescription = if (rated) "Rating $ratingText" else "Not rated"', card)
        self.assertIn("onOpenRackSelection = { startRackAssignment(item.bottle.bottleId) }", cellar)


if __name__ == "__main__":
    unittest.main()
