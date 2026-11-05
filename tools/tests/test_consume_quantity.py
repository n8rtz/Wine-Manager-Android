"""Host wiring checks for the multi-bottle "Mark as Consumed" selector.

These are source-level contract checks; the Android build and device checks remain in
THINGS_TO_CHECK.md. Run with: python3 tools/tests/test_consume_quantity.py
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
A = ROOT / "app/src/main/java/com/n8rtz/winetracker"
REPOSITORY = A / "data/repository/WineRepository.kt"
VIEWMODEL = A / "viewmodels/WineViewModel.kt"
DETAIL = A / "ui/screens/WineDetailScreen.kt"


class ConsumeQuantityContractTests(unittest.TestCase):
    def test_repository_writes_one_event_for_the_selected_quantity(self):
        repo = REPOSITORY.read_text()
        body = repo[
            repo.index("suspend fun consumeBottle("):repo.index("suspend fun undoConsumeBottle")
        ]
        self.assertIn("quantity: Int = 1", body)
        self.assertIn("val consumedQuantity = quantity.coerceIn(1, bottle.quantity)", body)
        self.assertIn("quantity = consumedQuantity,", body)
        self.assertIn("quantity = bottle.quantity - consumedQuantity,", body)
        self.assertNotIn("quantity = 1,", body)
        self.assertNotIn("quantity - 1", body)
        # UNDO and "Restore to cellar" already use the event's stored quantity.
        self.assertIn("quantity = bottle.quantity + event.quantity,", repo)
        self.assertIn("matchingCellarRow.quantity + event.quantity", repo)
        self.assertIn("quantity = event.quantity,", repo)

    def test_viewmodel_passes_quantity_through(self):
        vm = VIEWMODEL.read_text()
        self.assertIn("quantity: Int = 1,", vm)
        self.assertIn("bottleId, rating, notes, rebuy, consumptionDate, quantity", vm)

    def test_detail_card_has_a_bounded_bottle_selector(self):
        detail = DETAIL.read_text()
        self.assertIn("availableQuantity = bottle.quantity,", detail)
        self.assertIn("availableQuantity: Int,", detail)
        self.assertIn("onConfirm: (Int, Double?, String?, Boolean, Long) -> Unit", detail)
        self.assertIn("val maxQuantity = availableQuantity.coerceAtLeast(1)", detail)
        self.assertIn("val selectedQuantity = selectedCount.coerceIn(1, maxQuantity)", detail)
        self.assertIn("(1..maxQuantity).forEach", detail)
        self.assertIn("ExposedDropdownMenuAnchorType.PrimaryNotEditable", detail)
        self.assertIn("selectedCount = option", detail)
        self.assertIn("quantity = quantity", detail)
        self.assertIn('if (quantity == 1) "bottle" else "bottles"', detail)
        self.assertNotIn('"Consumed 1 bottle of ', detail)


if __name__ == "__main__":
    unittest.main()
