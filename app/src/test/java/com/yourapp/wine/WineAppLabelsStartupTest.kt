package com.yourapp.wine

import android.app.Application
import com.yourapp.wine.phonecorpus.WineAppLabelsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression: the shared ViewModel is also constructed when the GLiNER activity opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WineAppLabelsStartupTest {
    @Test fun initializesBeforeLoadingTheDynamicCorpusCount() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val selection = app.assets.open("wine-app-labels-v1/evaluation-set.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        val expected = selection.getInt("included_count")

        // Previously construction threw before the lazy selection delegate was initialized.
        val vm = WineAppLabelsViewModel(app)
        val loaded = withTimeout(10_000) {
            vm.state.first { it.total == expected || it.message.startsWith("Cannot load the curated-label test:") }
        }
        assertFalse(loaded.message.startsWith("Cannot load the curated-label test:"))
        assertEquals(expected, loaded.total)
    }
}
