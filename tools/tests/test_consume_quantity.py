"""Host contract checks for Cellar stock controls and consumption history wiring.

These source-level checks do not replace Android build/device checks in THINGS_TO_CHECK.md.
Run with: python3 tools/tests/test_consume_quantity.py
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/com/n8rtz/winetracker"
REPOSITORY = APP / "data/repository/WineRepository.kt"
VIEWMODEL = APP / "viewmodels/WineViewModel.kt"
CELLAR = APP / "ui/screens/CellarScreen.kt"
EDIT = APP / "ui/screens/WineEditScreen.kt"
MAIN = APP / "MainActivity.kt"


class CellarStockContractTests(unittest.TestCase):
    def test_consuming_one_bottle_records_an_event_and_decrements_inventory(self):
        repo = REPOSITORY.read_text()
        body = repo[
            repo.index("suspend fun consumeBottle("):repo.index("suspend fun undoConsumeBottle")
        ]
        self.assertIn("quantity: Int = 1", body)
        self.assertIn("val consumedQuantity = quantity.coerceIn(1, bottle.quantity)", body)
        self.assertIn("quantity = consumedQuantity,", body)
        self.assertIn("quantity = bottle.quantity - consumedQuantity,", body)
        self.assertIn("insertEvent(", body)
        self.assertNotIn("quantity = 1,", body)
        # UNDO and "Restore to cellar" restore the event's recorded bottle count.
        self.assertIn("quantity = bottle.quantity + event.quantity,", repo)
        self.assertIn("matchingCellarRow.quantity + event.quantity", repo)
        self.assertIn("quantity = event.quantity,", repo)

    def test_stock_increment_is_atomic_and_uses_current_database_quantity(self):
        repo = REPOSITORY.read_text()
        body = repo[
            repo.index("suspend fun addBottleToStock("):repo.index("suspend fun updateWine(")
        ]
        self.assertIn("database.withTransaction", body)
        self.assertIn("inventoryDao.getBottleById(bottleId)", body)
        self.assertIn("BottleStatus.IN_CELLAR", body)
        self.assertIn("bottle.copy(quantity = bottle.quantity + 1)", body)
        vm = VIEWMODEL.read_text()
        self.assertIn("fun addBottleToStock(bottleId: Long)", vm)
        self.assertIn("repository.addBottleToStock(bottleId)", vm)

    def test_expanded_cellar_card_exposes_stock_add_note_and_long_press_edit(self):
        cellar = CELLAR.read_text()
        self.assertIn('text = "Cellar: ${item.bottle.quantity}"', cellar)
        self.assertIn('contentDescription = "Consume one bottle"', cellar)
        self.assertIn('contentDescription = "Add one bottle"', cellar)
        self.assertIn("quantity = 1", cellar)
        self.assertIn("viewModel.addBottleToStock(item.bottle.bottleId)", cellar)
        self.assertIn('contentDescription = "Add tasting note"', cellar)
        self.assertIn("onAddNote(item.definition.definitionId, item.bottle.vintage)", cellar)
        self.assertNotIn('contentDescription = "Edit wine"', cellar)
        self.assertIn("onLongClick = { onEditWine(item.bottle.bottleId) }", cellar)

    def test_wine_edit_is_separate_from_the_removed_detail_and_consumption_flow(self):
        edit = EDIT.read_text()
        main = MAIN.read_text()
        self.assertIn("fun WineEditScreen(", edit)
        self.assertIn('route = "wine_edit/{bottleId}"', main)
        self.assertNotIn("WineDetailScreen", main)
        self.assertNotIn('route = "detail/{bottleId}"', main)
        self.assertNotIn("Mark as Consumed", edit)
        self.assertNotIn("ConsumeDialog", edit)


if __name__ == "__main__":
    unittest.main()
