package com.yourapp.wine

import android.app.Application
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import com.n8rtz.winetracker.ui.screens.DevicePhotoLabScreen
import com.n8rtz.winetracker.viewmodels.DevicePhotoLabPhoto
import com.n8rtz.winetracker.viewmodels.DevicePhotoLabState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bug-030: photo and export rows must not share numeric keys in the same LazyColumn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h1800dp")
class DevicePhotoLabKeyRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoAndExportWithSameIndexCanBeComposedAndScrolled() {
        val state = DevicePhotoLabState(
            finished = true,
            photos = listOf(
                DevicePhotoLabPhoto(0, Uri.parse("content://missing/photo/0"), "First"),
                DevicePhotoLabPhoto(1, Uri.parse("content://missing/photo/1"), "Second")
            ),
            exports = listOf(File("first.json"), File("second.json"))
        )
        compose.setContent {
            DevicePhotoLabScreen(state, onSelectPhotos = {}, onStop = {}, onSave = { _, _ -> }, onClose = {})
        }
        val list = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
        compose.onNode(list).performScrollToIndex(5) // Second photo (index 1).
        compose.onNode(list).performScrollToIndex(8) // Second export (also index 1).
        assertTrue(compose.onAllNodes(hasText("Save second.json", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
