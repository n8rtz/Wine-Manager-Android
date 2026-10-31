package com.yourapp.wine

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.yourapp.wine.gliner.GlinerLabActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real activity, both ViewModels and its first composition, without running OCR. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlinerLabStartupTest {
    @get:Rule val compose = createAndroidComposeRule<GlinerLabActivity>()

    @Test fun screenOpensAndShowsTheScoreboard() {
        compose.onNodeWithText("GLiNER lane lab").assertIsDisplayed()
        compose.onNodeWithTag("gliner_lab_content").performScrollToNode(hasText("Choose images"))
        compose.onNodeWithText("Choose images").assertIsDisplayed()
        compose.onNodeWithTag("gliner_lab_content").performScrollToNode(hasText("Scored results"))
        compose.onNodeWithText("Scored results").assertIsDisplayed()
    }
}
