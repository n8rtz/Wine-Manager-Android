import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
MAIN = APP / "MainActivity.kt"
CARD = APP / "ui/components/WineCard.kt"
NOTES = APP / "ui/screens/TastingNotesScreen.kt"


class MainSwipeTests(unittest.TestCase):
    def test_swipe_navigates_between_main_screens_in_bottom_bar_order(self):
        source = MAIN.read_text()
        self.assertIn(
            'private val TOP_LEVEL_MENU_ORDER = listOf("cellar", "notes", "history", "rankings", "profile")',
            source,
        )
        # Initial-pass detection: the swipe is seen before children, so
        # no card/list/tab-row can veto it wherever it starts; crossing
        # the threshold quickly claims (consumes) the gesture.
        self.assertIn("awaitPointerEvent(PointerEventPass.Initial)", source)
        self.assertIn(".then(tabSwipeModifier)", source)
        self.assertIn("change.consume()", source)
        self.assertIn("change.uptimeMillis - downTimeMs <= 450L", source)
        self.assertNotIn("downConsumed", source)
        # Inner pagers keep priority while they can move in the swipe
        # direction; at their edge the swipe spills over (gate reported
        # by the pager screens).
        self.assertIn("internal object InnerPagerSwipeGate", source)
        self.assertIn("pagerCanMove", source)
        self.assertIn("PAGER_SWIPE_ROUTES = setOf(\"cellar\", \"history\")", source)
        for screen in ("CellarScreen.kt", "HistoryScreen.kt"):
            src = (APP / "ui/screens" / screen).read_text()
            self.assertIn("InnerPagerSwipeGate.blocksSwipeToNext = pagerState.currentPage < 1", src, screen)
            self.assertIn("InnerPagerSwipeGate.blocksSwipeToPrevious = pagerState.currentPage > 0", src, screen)
            self.assertIn("InnerPagerSwipeGate.reset()", src, screen)
        # Main-tab switches animate with a shared-axis transition
        # (quick fade-out, fade-in + 1/3-width slide) rather than a
        # full-width slide, which kept both heavy screens rendering
        # for the whole animation and looked laggy.
        self.assertIn("slideInHorizontally", source)
        self.assertIn("slideOutHorizontally", source)
        self.assertIn("FastOutSlowInEasing", source)
        self.assertIn("fadeOut(animationSpec = tween(90))", source)

class CardTextTests(unittest.TestCase):
    def test_wine_card_never_truncates_text(self):
        source = CARD.read_text()
        card_body = source.split("fun WineCard(", 1)[1].split("private fun wineTypeColor", 1)[0]
        self.assertNotIn("TextOverflow.Ellipsis", card_body)
        self.assertNotIn("maxLines = 2", card_body)

    def test_wine_card_bottom_row_is_just_the_date(self):
        source = CARD.read_text()
        self.assertIn("if (!dateAdded.isNullOrBlank())", source)
        bottom_row = source.split("if (!dateAdded.isNullOrBlank())", 1)[1].split("AnimatedVisibility", 1)[0]
        self.assertIn("text = dateAdded", bottom_row)

    def test_notes_card_passes_most_recent_note_date_mmddyyyy(self):
        source = NOTES.read_text()
        self.assertIn('SimpleDateFormat("MM/dd/yyyy"', source)
        self.assertIn("dateAdded = mostRecentNoteDate?.let { bottomDateFormat.format(Date(it)) }", source)
        self.assertIn("group.maxByOrNull { it.note.dateTasted }?.note?.dateTasted", source)


class NotesSearchPerformanceTests(unittest.TestCase):
    def test_search_documents_and_scores_are_precomputed(self):
        source = NOTES.read_text()
        self.assertIn("val notesSearchDocuments = remember(history, searchQuery.isBlank())", source)
        self.assertIn("val notesSearchScores = remember(history, searchQuery, notesSearchDocuments)", source)
        # The sort comparator reuses the precomputed scores instead of
        # re-scoring every comparison.
        comparator = source.split("}.sortedWith { a, b ->", 1)[1].split("val priceA", 1)[0]
        self.assertIn("notesSearchScores[a.note.noteId]", comparator)
        self.assertNotIn("scoreWineSearch(", comparator)


if __name__ == "__main__":
    unittest.main()
