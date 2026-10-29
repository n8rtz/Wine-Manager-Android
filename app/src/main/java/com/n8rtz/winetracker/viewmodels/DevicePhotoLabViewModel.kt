package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.capture.SelectedLabelPhotoImporter
import com.yourapp.wine.phonecorpus.DevicePhotoLabResult
import com.yourapp.wine.phonecorpus.LabSuiteArchive
import com.yourapp.wine.phonecorpus.PhoneCorpusRunner
import com.yourapp.wine.textlab.evidence.LabPostOcrModules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class DevicePhotoLabPhoto(
    val index:Int,
    val uri:Uri,
    val name:String,
    val localPhoto:File?=null,
    val status:String="Waiting",
    val result:DevicePhotoLabResult?=null,
)

internal data class DevicePhotoLabState(
    val busy:Boolean=false,
    val finished:Boolean=false,
    val message:String="Select multiple photos for a fresh-OCR Lab v5 sanity check.",
    val photos:List<DevicePhotoLabPhoto> = emptyList(),
    val modules:LabPostOcrModules=LabPostOcrModules.none,
    val exports:List<File> = emptyList(),
)

/** Isolated, unscored session: no corpus references, baseline stores, OCR caches or best-build logs. */
internal class DevicePhotoLabViewModel(application:Application):AndroidViewModel(application) {
    private val mutable=MutableStateFlow(DevicePhotoLabState())
    val state=mutable.asStateFlow()
    private val lifetime=viewModelScope.coroutineContext[Job]!!
    private val root=File(application.cacheDir,"device-photo-lab-${UUID.randomUUID()}")
    private var configured=false
    @Volatile private var stopping=false

    fun configure(keys:List<String>) {
        if(configured) return
        configured=true
        mutable.update { it.copy(modules=LabPostOcrModules.fromKeys(keys).withoutRetiredModules()) }
    }

    fun runPhotos(uris:List<Uri>) {
        if(mutable.value.busy || uris.isEmpty()) return
        val chosen=uris.distinct()
        val modules=mutable.value.modules
        stopping=false
        mutable.update { it.copy(busy=true,finished=false,exports=emptyList(),message="Preparing ${chosen.size} photos…",
            photos=chosen.mapIndexed { index,uri -> DevicePhotoLabPhoto(index,uri,"Photo ${index+1}") }) }
        viewModelScope.launch(Dispatchers.IO) {
            var warm:PhoneCorpusRunner.WarmSession?=null
            try {
                root.deleteRecursively();check(root.mkdirs()) { "Cannot create private photo session" }
                val importer=SelectedLabelPhotoImporter(getApplication())
                for((index,uri) in chosen.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if(stopping) break
                    val folder=File(root,"photo-${index+1}").apply { check(mkdirs()) }
                    updatePhoto(index) { it.copy(status="Running fresh OCR") }
                    mutable.update { it.copy(message="Running photo ${index+1}/${chosen.size} · Lab v5 · fresh OCR") }
                    Log.d("WineTracker","Device-photo Lab v5: ${index+1}/${chosen.size}, modules=${modules.signatureKey()}")
                    try {
                        val name=displayName(uri,index)
                        updatePhoto(index) { it.copy(name=name) }
                        val imported=importer.copy(uri)
                        val local=File(folder,"original.img")
                        try { check(imported.file.renameTo(local)) { "Cannot retain private photo copy" } }
                        finally { imported.file.delete() }
                        updatePhoto(index) { it.copy(localPhoto=local) }
                        currentCoroutineContext().ensureActive()
                        val hash=PhoneCorpusRunner.sha(local)
                        // Reuse the normal knowledge sources, never standardized-test references or saved predictions.
                        if(warm==null) warm=PhoneCorpusRunner.WarmSession(getApplication())
                        val output=PhoneCorpusRunner.runImageAllModes(
                            getApplication(),hash.take(40)+".jpg",hash,setOf(PhoneCorpusRunner.Mode.LAB),
                            File(folder,"work"),listOf(folder),cachedOcr=null,warmSession=warm,labModules=modules,
                            sourceFile=local,
                            onFreshOcr={ metadata,rows ->
                                File(folder,"ocr.json").writeText(JSONObject().put("metadata",JSONObject(metadata))
                                    .put("rows",JSONArray(rows.map { JSONObject(it) })).toString())
                            },
                        ).getValue(PhoneCorpusRunner.Mode.LAB)
                        output.put("source_filename",name).put("photo_index",index+1)
                        File(folder,"result.json").writeText(output.toString())
                        val result=DevicePhotoLabResult.fromOutput(output)
                        updatePhoto(index) { it.copy(status=if(result.error.isEmpty()) "Finished" else "Failed",result=result) }
                        if(output.optBoolean("abort_run")) { stopping=true;break }
                    } catch(cancel:CancellationException) { throw cancel }
                    catch(error:Exception) {
                        val message="${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        File(folder,"result.json").writeText(JSONObject().put("status","failed").put("error",message)
                            .put("photo_index",index+1).put("source_filename",mutable.value.photos[index].name).toString())
                        updatePhoto(index) { it.copy(status="Failed",result=DevicePhotoLabResult(
                            DevicePhotoLabResult.fieldLabels.keys.associateWith { "" },message)) }
                        Log.d("WineTracker","Device-photo Lab v5 failed: ${index+1}, ${error.javaClass.simpleName}")
                    }
                }
                mutable.update { state -> state.copy(photos=state.photos.map {
                    if(it.status=="Waiting") it.copy(status="Not run — stopped") else it
                },message=if(stopping) "Stopped. Completed results are shown below." else "Finished. Review each photo and its assigned parameters.") }
                // Retain decisions and shared OCR using the same uploadable, lossless JSON normalization.
                val exports=LabSuiteArchive.write(File(root,"device-photo-lab.json"),diagnosticRows(modules))
                mutable.update { it.copy(exports=exports) }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                mutable.update { state -> state.copy(message="Session error: ${error.message}. Completed photo results remain below.",
                    photos=state.photos.map { photo ->
                        if(photo.status=="Waiting" || photo.status=="Running fresh OCR") photo.copy(status="Not run — session error") else photo
                    }) }
                Log.d("WineTracker","Device-photo Lab session error: ${error.javaClass.simpleName}")
            } finally {
                runCatching { warm?.close() }
                mutable.update { it.copy(busy=false,finished=true) }
                Log.d("WineTracker","Device-photo Lab v5 session finished")
            }
        }
    }

    fun stop() { stopping=true;mutable.update { it.copy(message="Stopping after the current photo…") } }

    fun saveExport(uri:Uri,index:Int) {
        if(mutable.value.busy) return
        val file=mutable.value.exports.getOrNull(index) ?: return
        mutable.update { it.copy(busy=true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stream=getApplication<Application>().contentResolver.openOutputStream(uri)
                    ?: error("Cannot open save destination")
                stream.use { output -> file.inputStream().use { it.copyTo(output) } }
                mutable.update { it.copy(message="Saved ${file.name}") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { mutable.update { it.copy(message="Save failed: ${error.message}") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }

    private fun updatePhoto(index:Int,transform:(DevicePhotoLabPhoto)->DevicePhotoLabPhoto) {
        mutable.update { state -> state.copy(photos=state.photos.map { if(it.index==index) transform(it) else it }) }
    }

    private fun displayName(uri:Uri,index:Int):String = runCatching {
        getApplication<Application>().contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Photo ${index+1}"

    private fun diagnosticRows(modules:LabPostOcrModules):Sequence<Map<String,String>> = sequence {
        val run=mapOf("run_id" to root.name,"build_commit" to BuildConfig.BUILD_COMMIT,"lab_modules" to modules.signatureKey(),
            "input_source" to "device_photo","reference_status" to "unscored")
        for(photo in mutable.value.photos) {
            val folder=File(root,"photo-${photo.index+1}")
            var identity=run+mapOf("photo_index" to (photo.index+1).toString(),"source_filename" to photo.name)
            val resultFile=File(folder,"result.json")
            if(resultFile.isFile) {
                val output=JSONObject(resultFile.readText())
                if(output.has("image")) identity=identity+mapOf("image" to output.getString("image"),
                    "image_sha256" to output.getString("image_sha256"))
                // Individual records allow deduplication and bounded JSON parts, never one huge escaped trace.
                val trace=output.optJSONArray("trace")
                output.remove("trace")
                yield(identity+mapOf("row_type" to "device_photo_result","diagnostic_json" to output.toString()))
                if(trace!=null) for(index in 0 until trace.length()) yield(identity+mapOf("row_type" to "decision_record",
                    "record_index" to index.toString(),"diagnostic_json" to trace.getJSONObject(index).toString()))
            } else yield(identity+mapOf("row_type" to "device_photo_result","execution_status" to photo.status))
            val ocrFile=File(folder,"ocr.json")
            if(ocrFile.isFile) {
                val ocr=JSONObject(ocrFile.readText())
                yield(identity+mapOf("row_type" to "ocr_metadata","diagnostic_json" to ocr.getJSONObject("metadata").toString()))
                val rows=ocr.getJSONArray("rows")
                for(index in 0 until rows.length()) yield(identity+mapOf("row_type" to "shared_ocr",
                    "record_index" to index.toString(),"diagnostic_json" to rows.getJSONObject(index).toString()))
            }
        }
    }

    override fun onCleared() {
        // Wait for cancelled IO/native work and saves before deleting copies; originals are never modified.
        lifetime.invokeOnCompletion { root.deleteRecursively() }
        super.onCleared()
    }
}
