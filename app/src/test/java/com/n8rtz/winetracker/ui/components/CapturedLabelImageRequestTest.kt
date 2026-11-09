package com.n8rtz.winetracker.ui.components

import android.app.Application
import coil.request.CachePolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapturedLabelImageRequestTest {
    @Test fun requestUsesTheCapturedFileAndDoesNotCachePrivatePhotos() {
        val context = RuntimeEnvironment.getApplication()
        val photo = File(context.cacheDir, "capture.jpg")
        val request = capturedLabelImageRequest(context, photo.absolutePath)
        assertEquals(photo, request.data)
        assertEquals(CachePolicy.DISABLED, request.memoryCachePolicy)
        assertEquals(CachePolicy.DISABLED, request.diskCachePolicy)
        assertEquals(CachePolicy.DISABLED, request.networkCachePolicy)
    }
}
