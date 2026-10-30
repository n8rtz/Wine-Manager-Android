package com.yourapp.wine.benchmark

import android.content.Context
import com.yourapp.wine.fill.FieldId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Android resources/FileProvider persistence checks; run with testDebugUnitTest, not the host harness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class BundledLabSamplesTest {
    @Test fun originalsReviewAndEditsSurviveReload() = runBlocking {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("ocr-lab-bundled-review-v1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val initial=BundledLabSamples.load(context)
            assertEquals(3,initial.size)
            assertTrue(initial.all { !it.reviewed && it.uri.scheme=="content" })
            for(label in initial) {
                val digest=MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(label.uri)!!.use { input ->
                    val buffer=ByteArray(32768)
                    while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) }
                }
                assertEquals(label.presetKey,digest.digest().joinToString("") { "%02x".format(it) })
            }
            val changed=initial.first().copy(label="My reviewed sample",reviewed=true,
                expected=initial.first().expected+(FieldId.REGION to "")+(FieldId.VINTAGE to "2020"))
            BundledLabSamples.save(context,listOf(changed))
            File(context.cacheDir,"ocr-lab-bundled-v1/${changed.presetKey}.jpg").delete()
            val reloaded=BundledLabSamples.load(context)
            val restored=reloaded.single { it.presetKey==changed.presetKey }
            assertEquals(changed.expected,restored.expected)
            assertEquals("My reviewed sample",restored.label)
            assertTrue(restored.reviewed)
            assertNotEquals(changed.id,restored.id)
            assertTrue(reloaded.filter { it.presetKey!=changed.presetKey }.none { it.reviewed })
            BundledLabSamples.save(context,listOf(restored.copy(reviewed=false)))
            assertFalse(BundledLabSamples.load(context).single { it.presetKey==changed.presetKey }.reviewed)
        } finally { prefs.edit().clear().commit() }
    }
}
