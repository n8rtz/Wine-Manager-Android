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
        self.assertIn("Diamond lattice and circular bottle openings", source)
        self.assertIn("drawCircle(", source)
        self.assertIn("Continuous diagonal runs form one full diamond lattice", source)
        self.assertIn("drawLine(color, point(slash[0].x", source)
        self.assertIn("modifier = Modifier.size(30.dp)", source)
        self.assertIn("val ringStroke = unit * .02f", source)
        self.assertIn("drawCircle(backgroundColor", source)
        self.assertIn("onOpenRackSelection", source)
        self.assertNotIn("DropdownMenu(expanded = expanded", source)
        self.assertIn("Choose one or more racks", source)
        self.assertIn("selectedAssignmentRacks", source)
        self.assertIn('else "Add to ${selectedAssignmentRacks.size} racks")', source)
        self.assertIn('"Remove from racks"', source)
        self.assertIn("cellarTab = 1", screen)
        self.assertIn("updateRackAssignments", screen)

    def test_automatic_new_additions_does_not_disable_other_rack_assignments(self):
        source = (APP / "ui/screens/CellarRackViews.kt").read_text()
        self.assertNotIn("enabled = !automaticNewAddition", source)
        self.assertIn('if (automaticNewAddition) "Add wine to another rack" else "Choose wine racks"', source)
        self.assertIn('selectedRack in assignments[it.bottle.bottleId].orEmpty()', source)
        self.assertNotIn("bottle.bottleId !in newAdditions", source)

    def test_quick_rack_action_sits_above_the_expand_caret_on_cellar_cards(self):
        card = (APP / "ui/components/WineCard.kt").read_text()
        cellar = (APP / "ui/screens/CellarScreen.kt").read_text()
        self.assertIn("trailingAction: @Composable (() -> Unit)? = null", card)
        self.assertLess(card.index("trailingAction?.invoke()"), card.index("Icons.Default.ExpandLess"))
        self.assertIn("trailingAction = {", cellar)
        self.assertIn("RackQuickAddButton(", cellar)
        self.assertIn("onOpenRackSelection = { startRackAssignment(item.bottle.bottleId) }", cellar)


if __name__ == "__main__":
    unittest.main()
