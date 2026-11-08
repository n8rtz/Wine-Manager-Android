package com.yourapp.wine

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.n8rtz.winetracker.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the actual keyed lists against the populated debug cellar, not synthetic list data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainActivityListScrollTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val lazyList = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)

    @Test fun scrollCellarNotesHistoryAndRankings() {
        // Wait for the asynchronous Room-backed debug seed before starting the list checks.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Showing ", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Showing 0 bottles")).fetchSemanticsNodes().isEmpty()
        }
        exerciseList()
        for ((tab, title) in listOf("Notes" to "Tasting Notes", "History" to "Consumption History", "Rankings" to "Rankings")) {
            compose.onNodeWithText(tab).performClick()
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodes(hasText(title)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(lazyList).fetchSemanticsNodes().size == 1
            }
            exerciseList()
        }
    }

    private fun exerciseList() {
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(lazyList).fetchSemanticsNodes().size == 1
        }
        // Every seeded list has more than 50 entries. Jump across it and fling in both cases
        // while Compose measures different keyed rows, then return to the top.
        compose.onNode(lazyList).performScrollToIndex(20)
        compose.onNode(lazyList).performScrollToIndex(50)
        compose.onNode(lazyList).performTouchInput { swipeUp() }
        compose.onNode(lazyList).performScrollToIndex(0)
    }
}
