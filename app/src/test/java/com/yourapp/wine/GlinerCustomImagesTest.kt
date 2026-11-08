package com.yourapp.wine

import android.app.Application
import android.net.Uri
import com.yourapp.wine.gliner.GlinerCustomImages
import com.yourapp.wine.gliner.GlinerLabCorpus
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class GlinerCustomImagesTest {
    private val app:Application get() = RuntimeEnvironment.getApplication()

    @Before fun clearCopies() { GlinerCustomImages.reset(app) }
    @After fun removeCopies() { GlinerCustomImages.reset(app) }

    @Test fun multipleImportsCopyDeduplicateAndNeverUseTargets() {
        val curated=GlinerLabCorpus.load(app).take(2)
        val originals=curated.mapIndexed { i,sample -> File(app.cacheDir,"gliner-import-test-$i.jpg").also {
            app.assets.open("${GlinerLabCorpus.ASSET_ROOT}/images/${sample.image}").use { source ->
                it.outputStream().use { output -> source.copyTo(output) }
            }
        } }
        val invalid=File(app.cacheDir,"gliner-import-invalid.txt").apply { writeText("not an image") }
        try {
            val imported=GlinerCustomImages.import(app,originals.map { Uri.fromFile(it) }+
                listOf(Uri.fromFile(originals.first()),Uri.fromFile(invalid)),emptyList())
            assertEquals(2,imported.added)
            assertEquals(1,imported.duplicates)
            assertEquals(1,imported.errors.size)
            assertTrue(imported.samples.all { it.isCustom && it.matchesCorpus && it.targets.isEmpty() })
            assertTrue(imported.samples.all { sample -> !PhoneCorpusMath.eligible(PhoneCorpusMath.fields.associateWith { sample.target(it) }) })
            assertEquals(imported.samples.map { it.image },GlinerCustomImages.load(app).map { it.image })
            val work=File(app.cacheDir,"gliner-import-work.img")
            GlinerLabCorpus.copyTo(app,imported.samples.first(),work)
            assertTrue(work.readBytes().contentEquals(originals.first().readBytes()))
            work.delete()
            GlinerCustomImages.remove(app,imported.samples,imported.samples.map { it.image }.toSet())
            assertTrue(GlinerCustomImages.load(app).isEmpty())
            assertTrue(originals.all { it.exists() })
            assertFalse(GlinerCustomImages.file(app,imported.samples.first().image).exists())
        } finally { originals.forEach { it.delete() };invalid.delete() }
    }

    @Test fun invalidIdsCannotEscapePrivateStorage() {
        try {
            GlinerCustomImages.file(app,"../../outside.jpg")
            throw AssertionError("Path traversal was accepted")
        } catch(_:IllegalArgumentException) { }
    }
}
