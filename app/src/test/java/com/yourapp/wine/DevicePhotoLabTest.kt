package com.yourapp.wine

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.n8rtz.winetracker.viewmodels.DevicePhotoLabViewModel
import com.yourapp.wine.phonecorpus.DevicePhotoLabResult
import com.yourapp.wine.phonecorpus.PhoneCorpusRunner
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** No native OCR: selected values, source-file guards, and unreadable multi-photo sessions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class DevicePhotoLabTest {
    @get:Rule val temporary=TemporaryFolder()

    private fun output(fields:JSONObject=JSONObject()):JSONObject = JSONObject().put("status","complete")
        .put("pipelines",JSONArray().put(JSONObject().put("pipeline",PhoneCorpusRunner.Mode.LAB.pipelineId)
            .put("status","complete").put("fields",fields)))

    @Test fun rendersExactlyAssignedValuesAndPreservesBlanks() {
        val result=DevicePhotoLabResult.fromOutput(output(JSONObject().put("producer","Independent estate")
            .put("vintage","2021").put("country",JSONObject.NULL)))
        assertEquals("Independent estate",result.fields["producer"])
        assertEquals("2021",result.fields["vintage"])
        assertEquals("",result.fields["country"])
        assertEquals("",result.fields["wine_name"])
        assertEquals(6,result.fields.size)
        assertEquals("",result.error)
    }

    @Test fun neverSubstitutesADifferentPipelineOrReferenceValue() {
        val wrong=output(JSONObject().put("wine_name","Wrong pipeline"))
        wrong.getJSONArray("pipelines").getJSONObject(0).put("pipeline",PhoneCorpusRunner.Mode.PRODUCTION_V4.pipelineId)
        wrong.put("expected",JSONObject().put("wine_name","Reference must not enter predictions"))
        val result=DevicePhotoLabResult.fromOutput(wrong)
        assertTrue(result.fields.values.all { it.isEmpty() })
        assertTrue(result.error.isNotEmpty())
    }

    @Test fun displaysFailureAndIncompleteOcrWithoutDroppingPartialAssignments() {
        val data=output(JSONObject().put("region","Assigned region")).put("engine_incomplete",true)
        data.getJSONArray("pipelines").getJSONObject(0).put("status","failed").put("error","OCR unavailable")
        val result=DevicePhotoLabResult.fromOutput(data)
        assertEquals("Assigned region",result.fields["region"])
        assertEquals("OCR unavailable",result.error)
        assertTrue(result.incompleteOcr)
    }

    @Test fun deviceFileIsReadAndVerifiedWithoutOpeningACorpusPhoto() = runBlocking {
        val source=temporary.newFile("original.img").apply { writeText("original bytes") }
        val work=temporary.newFolder("work")
        val result=PhoneCorpusRunner.runImageAllModes(RuntimeEnvironment.getApplication(),"0".repeat(40)+".jpg",
            "deliberately wrong hash",setOf(PhoneCorpusRunner.Mode.LAB),work,emptyList(),sourceFile=source)
            .getValue(PhoneCorpusRunner.Mode.LAB)
        assertEquals("device_photo",result.getString("input_source"))
        assertEquals("failed",result.getString("status"))
        assertTrue(result.getString("error").contains("Photo checksum mismatch"))
        assertEquals("original bytes",source.readText())
        assertFalse(File(work,"input.jpg").exists())
    }

    @Test fun deviceFileCannotEnterFrozenProductionComparators() = runBlocking {
        val source=temporary.newFile("source.img")
        try {
            PhoneCorpusRunner.runImageAllModes(RuntimeEnvironment.getApplication(),"0".repeat(40)+".jpg","hash",
                setOf(PhoneCorpusRunner.Mode.PRODUCTION_V4),temporary.newFolder(),emptyList(),sourceFile=source)
            fail("Device source must be Lab-only")
        } catch(expected:IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("fresh OCR"))
        }
    }

    @Test fun unreadablePhotosRemainSeparateResultsAndPickerCancelDoesNotClearThem() = runBlocking {
        val vm=DevicePhotoLabViewModel(RuntimeEnvironment.getApplication())
        val store=ViewModelStore().apply { put("device-photos",vm) }
        try {
            vm.configure(LabPostOcrModules.bestBuild.enabledKeys())
            val first=Uri.parse("content://missing.photo.provider/one")
            val second=Uri.parse("content://missing.photo.provider/two")
            vm.runPhotos(listOf(first,second,first))
            val finished=withTimeout(10000) { vm.state.first { it.finished && !it.busy } }
            assertEquals(2,finished.photos.size)
            assertEquals(listOf(first,second),finished.photos.map { it.uri })
            assertTrue(finished.photos.all { it.status=="Failed" && it.result?.error?.isNotBlank()==true })
            assertEquals(LabPostOcrModules.bestBuild,finished.modules)
            assertTrue(finished.exports.any { it.name.endsWith("-manifest.json") })
            assertTrue(finished.exports.any { it.name.endsWith("-part-1.json") })
            vm.runPhotos(emptyList())
            assertEquals(finished,vm.state.value)
        } finally { store.clear() }
    }
}
