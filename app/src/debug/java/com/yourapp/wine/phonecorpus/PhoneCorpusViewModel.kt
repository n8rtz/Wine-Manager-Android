package com.yourapp.wine.phonecorpus

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.textlab.PaddleCandidates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

internal data class CorpusState(val busy:Boolean=false,val mode:PhoneCorpusRunner.Mode=PhoneCorpusRunner.Mode.PRODUCTION_V1,
    val message:String="Loading curated 107-photo benchmark…",val completed:Int=0,val total:Int=PhoneCorpusRunner.ACTIVE_IMAGE_COUNT,
    val board:PhoneCorpusScoreboard.Board?=null,val csv:File?=null,val exportParts:List<File> = emptyList())
internal class PhoneCorpusViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(CorpusState());val state=mutable.asStateFlow()
    @Volatile private var stopping=false
    private var folder:File?=null
    private val app get()=getApplication<Application>()
    private val root get()=File(app.noBackupFilesDir,"phone-corpus-v1").apply { mkdirs() }
    private val installId get()=app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime.toString()
    init { load(PhoneCorpusRunner.Mode.PRODUCTION_V1) }
    private fun atomic(file:File,text:String) {
        val temp=File(file.parentFile,file.name+".tmp")
        temp.writeText(text)
        try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(_:AtomicMoveNotSupportedException) { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun selectFolder(mode:PhoneCorpusRunner.Mode,fresh:Boolean=false):File {
        val prefix="${BuildConfig.BUILD_COMMIT}-$installId-${mode.name}-"
        if(!fresh) root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(prefix) }.maxByOrNull { it.lastModified() }?.let { return it }
        val dir=File(root,prefix+UUID.randomUUID()).apply { mkdirs() }
        val meta=JSONObject().put("build",BuildConfig.BUILD_COMMIT).put("working_tree",BuildConfig.BUILD_WORKING_TREE).put("mode",mode.name)
            .put("pipeline",mode.pipelineId).put("spatial_merging",mode==PhoneCorpusRunner.Mode.LAB)
            .put("schema_version",2).put("pipeline_funnel_version",com.yourapp.wine.diagnostics.PipelineFunnel.VERSION).put("corpus_manifest",JSONObject(app.assets.open("phone-corpus-v1/manifest.json").bufferedReader().use { it.readText() }))
            .put("apk_updated_ms",installId).put("created_ms",System.currentTimeMillis()).put("runtime","phone; fresh OCR")
        atomic(File(dir,"run.json"),meta.toString(2));return dir
    }
    private val includedImageIds by lazy {
        val selection=JSONObject(app.assets.open("phone-corpus-v1/evaluation-set.json").bufferedReader().use { it.readText() }).getJSONArray("included_images")
        (0 until selection.length()).map { selection.getJSONObject(it).getString("image") }.toSet()
    }
    private fun predictions(dir:File)=File(dir,"current").listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.name }
        .map { JSONObject(it.readText()) }.filter { it.getString("image") in includedImageIds }
    private fun refresh(dir:File,mode:PhoneCorpusRunner.Mode) {
        val predictions=predictions(dir)
        val board=PhoneCorpusScoreboard(app).build(predictions,mode)
        val csv=File(dir,"results.csv");val temp=File(dir,"results.csv.tmp")
        PhoneCorpusScoreboard.export(board,temp,mapOf("build" to BuildConfig.BUILD_COMMIT,"run_id" to dir.name,"mode" to mode.name,"device" to android.os.Build.MODEL,"run_metadata" to File(dir,"run.json").readText()))
        Files.move(temp.toPath(),csv.toPath(),StandardCopyOption.REPLACE_EXISTING)
        mutable.update { it.copy(completed=predictions.size,board=board,csv=csv) }
    }
    fun load(mode:PhoneCorpusRunner.Mode,fresh:Boolean=false) {
        if(mutable.value.busy) return
        folder=null
        mutable.update { it.copy(busy=true,mode=mode,completed=0,board=null,csv=null,exportParts=emptyList(),message="Loading checkpoints…") }
        viewModelScope.launch(Dispatchers.Default) {
            try { val dir=selectFolder(mode,fresh);folder=dir;refresh(dir,mode);mutable.update { it.copy(message="Ready. Resume skips all checkpointed photos. Retry failures is explicit.") } }
            catch(e:Exception) { folder=null;mutable.update { it.copy(message="Cannot load benchmark: ${e.message}") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun stop() { stopping=true;mutable.update { if(it.busy) it.copy(message="Stopping after the current image; please wait for native OCR to finish.") else it } }
    fun start(retryFailures:Boolean=false) {
        if(mutable.value.busy) return
        val dir=folder ?: return;val mode=mutable.value.mode
        mutable.update { it.copy(busy=true,message="Checking corpus and both OCR engines…") };stopping=false
        viewModelScope.launch(Dispatchers.Default) {
            var locked=false
            try {
                check(lock.tryLock()) { "Another phone benchmark is still running" };locked=true
                val spec=PaddleCandidates.all.single { it.id=="candidate_3" }
                val assets=app.assets.list("text-lab-paddle-v1").orEmpty().toSet()
                check(listOf(spec.detector.asset,spec.recognizer.asset,spec.dictionary.asset).all { it in assets }) { "Medium model assets missing from APK. Build with the normal model-asset task; benchmark will not silently run ML Kit only." }
                WineScannerServices.get(app).withBenchmarkEngines { engines,_ -> check(setOf("mlkit","paddle-v6-medium").all { id -> engines.any { it.id==id && it.available } }) { "Both ML Kit and Medium must be available" } }
                val packaged=JSONObject(app.assets.open("phone-corpus-v1/inputs.json").bufferedReader().use { it.readText() }).getJSONArray("images")
                check(packaged.length()==PhoneCorpusRunner.PACKAGED_IMAGE_COUNT)
                val selection=JSONObject(app.assets.open("phone-corpus-v1/evaluation-set.json").bufferedReader().use { it.readText() })
                val included=(0 until selection.getJSONArray("included_images").length()).map { selection.getJSONArray("included_images").getJSONObject(it).getString("image") }.toSet()
                check(included.size==PhoneCorpusRunner.ACTIVE_IMAGE_COUNT && selection.optInt("excluded_count")==61)
                val manifest=(0 until packaged.length()).map { packaged.getJSONObject(it) }.filter { it.getString("image") in included }
                check(manifest.size==included.size)
                val old=predictions(dir).associateBy { it.getString("image") }
                File(dir,"current").mkdirs();File(dir,"attempts").mkdirs()
                for(i in manifest.indices) {
                    ensureActive();if(stopping) break
                    val input=manifest[i];val image=input.getString("image");val previous=old[image]
                    val failed=previous?.let { p -> p.optString("status")!="complete" || p.optBoolean("engine_incomplete") ||
                        p.optJSONArray("pipelines")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("status")!="complete" } }==true } ?: false
                    if(if(retryFailures) !failed else previous!=null) continue
                    mutable.update { it.copy(message="Photo ${i+1}/${manifest.size} · $image\n$mode · keep app open, screen on and phone plugged in.") }
                    val result=PhoneCorpusRunner.runImage(app,image,input.getString("sha256"),mode,File(dir,"work"))
                    result.put("attempt",(previous?.optInt("attempt") ?: 0)+1)
                    atomic(File(File(dir,"attempts"),"$image-${System.currentTimeMillis()}.json"),result.toString())
                    // Small checkpoints only; full token traces are retained separately on disk.
                    result.remove("trace");result.remove("tokens");result.remove("diagnostics")
                    atomic(File(File(dir,"current"),"$image.json"),result.toString())
                    refresh(dir,mode)
                    if(result.optBoolean("abort_run")) { stopping=true;break }
                }
                mutable.update { it.copy(message=if(stopping) "Stopped safely. Resume continues unattempted photos." else "Pass finished. Export CSV; failed images can be retried separately.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { mutable.update { it.copy(message="Benchmark stopped: ${e.message}. Saved checkpoints are retained.") } }
            catch(e:OutOfMemoryError) { mutable.update { it.copy(message="Memory pressure: benchmark stopped. Completed checkpoints are retained.") } }
            finally { if(locked) lock.unlock();mutable.update { it.copy(busy=false) } }
        }
    }
    fun saveExportPart(uri:Uri,index:Int) {
        if(mutable.value.busy) return
        val file=mutable.value.exportParts.getOrNull(index) ?: run {
            mutable.update { it.copy(message="Export session expired. Prepare the CSV again, then save its parts.") };return
        }
        mutable.update { it.copy(busy=true,message="Saving ${file.name}…") }
        viewModelScope.launch(Dispatchers.IO) {
            try { app.contentResolver.openOutputStream(uri,"w").use { out -> requireNotNull(out);file.inputStream().use { it.copyTo(out) } }
                mutable.update { it.copy(message="Saved ${file.name}. Save each listed part before uploading.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { mutable.update { it.copy(message="Export failed: ${e.message}") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun prepareExport(raw:Boolean) {
        if(mutable.value.busy) return
        val dir=folder ?: return
        val scored=mutable.value.csv ?: return
        val mode=mutable.value.mode
        val previousParts=mutable.value.exportParts
        val funnelRows=mutable.value.board?.csv.orEmpty().filter { it["row_type"]=="pipeline_step" }
        mutable.update { it.copy(busy=true,exportParts=emptyList(),message="Preparing upload-sized CSV files…") }
        viewModelScope.launch(Dispatchers.IO) {
            val exportDir=File(app.cacheDir,"phone-corpus-exports/${UUID.randomUUID()}")
            try {
                check(exportDir.mkdirs())
                val target=File(exportDir,"wine-${PhoneCorpusRunner.ACTIVE_IMAGE_COUNT}-${if(raw) "raw-" else ""}${mode.name.lowercase()}-${BuildConfig.BUILD_COMMIT}.csv")
                if(raw) {
                    val files=File(dir,"attempts").listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.name }
                    // Reiterable, one photo at a time: the CSV writer scans keys, then writes rows.
                    val records=sequence {
                        for(file in files) {
                            ensureActive()
                            val p=JSONObject(file.readText())
                            val meta=mapOf("build" to p.optString("build"),"mode" to p.optString("mode"),"run_id" to dir.name,
                                "image" to p.optString("image"),"attempt" to p.optString("attempt"))
                            yield(meta+mapOf("row_type" to "image_summary","status" to p.optString("status"),"elapsed_ms" to p.optString("total_elapsed_ms"),"error" to p.optString("error"),"engine_incomplete" to p.optBoolean("engine_incomplete").toString(),"diagnostics" to p.optString("diagnostics"),"pipeline_funnel_error" to p.optString("pipeline_funnel_error")))
                            for(key in listOf("tokens","engine_timings","trace","pipelines","pipeline_funnel")) {
                                val rows=p.optJSONArray(key) ?: continue
                                for(i in 0 until rows.length()) {
                                    val row=rows.getJSONObject(i)
                                    yield(meta+mapOf("row_type" to key)+row.keys().asSequence().associateWith { row.optString(it) })
                                }
                            }
                        }
                        // Same current/latest-attempt aggregate as the screen and scored CSV; old attempts above remain raw evidence only.
                        for(row in funnelRows)
                            yield(mapOf("build" to BuildConfig.BUILD_COMMIT,"mode" to mode.name,"run_id" to dir.name)+row)
                    }
                    target.bufferedWriter(Charsets.UTF_8).use { writer ->
                        com.yourapp.wine.textlab.TextLabCsv.write(writer,records)
                    }
                } else scored.copyTo(target)
                val parts=CsvUploadParts.split(target,exportDir)
                if(parts.size>1) target.delete()
                previousParts.mapNotNull { it.parentFile }.distinct().filter { it.parentFile==exportDir.parentFile }.forEach { it.deleteRecursively() }
                mutable.update { it.copy(exportParts=parts,message="${parts.size} CSV file(s) ready, each at most 25 MB. Tap each Save button below. Complete rows and headers are preserved.") }
            } catch(cancel:CancellationException) { exportDir.deleteRecursively();throw cancel }
            catch(e:Exception) { exportDir.deleteRecursively();mutable.update { it.copy(message="CSV preparation failed: ${e.message}. Original run data is unchanged.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    /** Allows already-saved 35 MB exports from older builds to be split without rerunning OCR. */
    fun splitExistingCsv(uri:Uri) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,exportParts=emptyList(),message="Splitting selected CSV for upload…") }
        viewModelScope.launch(Dispatchers.IO) {
            val exportDir=File(app.cacheDir,"phone-corpus-exports/${UUID.randomUUID()}")
            try {
                check(exportDir.mkdirs())
                val source=File(exportDir,"wine-existing.csv")
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> source.outputStream().use { input.copyTo(it) } }
                val parts=CsvUploadParts.split(source,exportDir)
                if(parts.size>1) source.delete()
                mutable.update { it.copy(exportParts=parts,message="${parts.size} upload-sized CSV file(s) ready. Save every part below. Original file unchanged; no OCR rerun.") }
            } catch(cancel:CancellationException) { exportDir.deleteRecursively();throw cancel }
            catch(e:Exception) { exportDir.deleteRecursively();mutable.update { it.copy(message="Cannot split CSV: ${e.message}. Original file unchanged.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    private companion object { val lock=Mutex() }
}
