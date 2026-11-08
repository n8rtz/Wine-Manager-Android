package com.yourapp.wine

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.n8rtz.winetracker.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launch the actual app and wait for the debug seed to populate its real Room-backed cellar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainActivityStartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainScreenOpensWithPopulatedCellar() {
        compose.onNodeWithText("Cellar").assertIsDisplayed()
        // The debug seed is already bundled in this project; wait for its background import and
        // Room's inventory flow rather than passing while the initial empty list is displayed.
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodes(hasText("Showing ", substring = true)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("Showing 0 bottles")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Total Bottles").assertIsDisplayed()
    }
}
