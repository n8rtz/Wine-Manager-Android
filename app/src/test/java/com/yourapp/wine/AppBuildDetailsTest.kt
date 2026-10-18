package com.yourapp.wine

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.n8rtz.winetracker.ui.screens.AppBuildDetails
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Build-info UI only; no database, native scanner or Android emulator is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppBuildDetailsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun commitAndLocalChangesAreVisibleAlongsideExistingMetadata() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AppBuildDetails("12", "example.app", "test-version", 55, "test-branch", "abcdef123456", "dirty")
                }
            }
        }
        compose.onNodeWithText("Installed build / commit").assertIsDisplayed()
        compose.onNodeWithText("abcdef123456").assertIsDisplayed()
        compose.onNodeWithText("Includes uncommitted local changes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("test-branch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Database version").performScrollTo().assertIsDisplayed()
    }

    @Test fun unavailableGitMetadataIsNotDisplayedAsAValidCommit() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AppBuildDetails("Unavailable", "example.app", branch = "", commit = "unknown", workingTree = "unknown")
                }
            }
        }
        compose.onNodeWithText("Unavailable (Git metadata not found)").performScrollTo().assertIsDisplayed()
    }
}
