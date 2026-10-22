package com.yourapp.wine

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourapp.wine.capture.BitmapWorkspace
import com.yourapp.wine.capture.CaptureDecoder
import com.yourapp.wine.capture.LabelPhotoTooLargeException
import com.yourapp.wine.capture.SelectedLabelPhotoImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.RandomAccessFile

/** Real granted content-URI byte-copy/decoder tests. No ML Kit inference or native OpenCV execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SelectedLabelPhotoImporterTest {
    // AndroidX caches paths by authority across provider instances. Robolectric gives
    // each test a different cacheDir while retaining the same app authority; discard
    // only this test fixture's authority, never broaden the app's FileProvider roots.
    @Before @After fun clearTestProviderPathCache() {
        val strategies=ReflectionHelpers.getStaticField<MutableMap<String,Any>>(FileProvider::class.java,"sCache")
        synchronized(strategies) { strategies.remove("${context.packageName}.provider") }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private fun privateCopies() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("wine-selected-label-") }.toSet()
    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

    @Test fun copiesOriginalBytesThenUsesTheBoundedDecoderWithoutChangingTheSource() = runBlocking {
        val source = File.createTempFile("source-label-", ".png", context.cacheDir)
        val expected = javaClass.classLoader!!.getResourceAsStream("labels/barolo.png")!!.use { it.readBytes() }
        source.writeBytes(expected)
        var privateCopy: File? = null
        try {
            val imported = SelectedLabelPhotoImporter(context).copy(uri(source))
            privateCopy = imported.file
            assertNotEquals(source.canonicalPath, imported.file.canonicalPath)
            assertArrayEquals(expected, imported.file.readBytes())
            assertArrayEquals(expected, source.readBytes())
            assertEquals(expected.size.toLong(), imported.bytes)
            val workspace = BitmapWorkspace()
            try {
                val decoded = CaptureDecoder().decode(imported.file, workspace)
                assertTrue(decoded.origW > 0 && decoded.origH > 0)
                assertTrue(maxOf(decoded.bitmap.width, decoded.bitmap.height) <= 1920)
                assertTrue(workspace.peakCount <= 2)
            } finally { workspace.close() }
            assertEquals(0, workspace.aliveCount)
        } finally { privateCopy?.delete(); source.delete() }
    }

    @Test fun anUnreadableUriDoesNotLeaveACacheFile() = runBlocking {
        val before = privateCopies()
        try {
            SelectedLabelPhotoImporter(context).copy(Uri.parse("content://missing.scanner.provider/photo"))
            fail("Must reject unreadable photo")
        } catch (_: Exception) {
            assertEquals(before, privateCopies())
        }
    }

    @Test fun arbitraryFileUrisAreRejectedWithoutDeletingTheOriginal() = runBlocking {
        val original = File.createTempFile("do-not-delete-", ".png", context.cacheDir)
        original.writeText("original user bytes")
        val before = privateCopies()
        try {
            try {
                SelectedLabelPhotoImporter(context).copy(Uri.fromFile(original))
                fail("Picker must supply a content URI")
            } catch (_: IllegalArgumentException) {
                assertEquals("original user bytes", original.readText())
                assertEquals(before, privateCopies())
            }
        } finally { original.delete() }
    }

    @Test fun oversizedInputsAreRejectedAndOnlyThePrivateCopyIsRemoved() = runBlocking {
        val source = File.createTempFile("oversized-source-", ".png", context.cacheDir)
        RandomAccessFile(source, "rw").use { it.setLength(SelectedLabelPhotoImporter.MAX_INPUT_BYTES + 1) }
        val before = privateCopies()
        try {
            try {
                SelectedLabelPhotoImporter(context).copy(uri(source))
                fail("Must enforce the compressed-photo byte limit")
            } catch (_: LabelPhotoTooLargeException) {
                assertTrue(source.exists())
                assertEquals(SelectedLabelPhotoImporter.MAX_INPUT_BYTES + 1, source.length())
                assertEquals(before, privateCopies())
            }
        } finally { source.delete() }
    }
}
