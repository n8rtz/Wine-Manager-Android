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

internal data class CorpusState(val busy:Boolean=false,val mode:PhoneCorpusRunner.Mode=PhoneCorpusRunner.Mode.PRODUCTION,
    val message:String="Loading bundled benchmark…",val completed:Int=0,val board:PhoneCorpusScoreboard.Board?=null,val csv:File?=null)
internal class PhoneCorpusViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(CorpusState());val state=mutable.asStateFlow()
    @Volatile private var stopping=false
    private var folder:File?=null
    private val app get()=getApplication<Application>()
    private val root get()=File(app.noBackupFilesDir,"phone-corpus-v1").apply { mkdirs() }
    private val installId get()=app.packageManager.getPackageInfo(app.packageName,0).lastUpdateTime.toString()
    init { load(PhoneCorpusRunner.Mode.PRODUCTION) }
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
            .put("schema_version",1).put("corpus_manifest",JSONObject(app.assets.open("phone-corpus-v1/manifest.json").bufferedReader().use { it.readText() }))
            .put("apk_updated_ms",installId).put("created_ms",System.currentTimeMillis()).put("runtime","phone; fresh OCR")
        atomic(File(dir,"run.json"),meta.toString(2));return dir
    }
    private fun predictions(dir:File)=File(dir,"current").listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.name }.map { JSONObject(it.readText()) }
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
        mutable.update { it.copy(busy=true,mode=mode,completed=0,board=null,csv=null,message="Loading checkpoints…") }
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
                val manifest=JSONObject(app.assets.open("phone-corpus-v1/inputs.json").bufferedReader().use { it.readText() }).getJSONArray("images")
                check(manifest.length()==168)
                val old=predictions(dir).associateBy { it.getString("image") }
                File(dir,"current").mkdirs();File(dir,"attempts").mkdirs()
                for(i in 0 until manifest.length()) {
                    ensureActive();if(stopping) break
                    val input=manifest.getJSONObject(i);val image=input.getString("image");val previous=old[image]
                    val failed=previous?.let { p -> p.optString("status")!="complete" || p.optBoolean("engine_incomplete") ||
                        p.optJSONArray("pipelines")?.let { a -> (0 until a.length()).any { a.getJSONObject(it).optString("status")!="complete" } }==true } ?: false
                    if(if(retryFailures) !failed else previous!=null) continue
                    mutable.update { it.copy(message="Photo ${i+1}/168 · $image\n$mode · keep app open, screen on and phone plugged in.") }
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
    fun export(uri:Uri) {
        if(mutable.value.busy) return
        val file=mutable.value.csv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { app.contentResolver.openOutputStream(uri,"w").use { out -> requireNotNull(out);file.inputStream().use { it.copyTo(out) } }
                mutable.update { it.copy(message="Scored CSV exported.") }
            } catch(e:Exception) { mutable.update { it.copy(message="Export failed: ${e.message}") } }
        }
    }
    fun exportDiagnostics(uri:Uri) {
        if(mutable.value.busy) return
        val dir=folder ?: return
        mutable.update { it.copy(busy=true,message="Exporting raw diagnostic rows…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files=File(dir,"attempts").listFiles().orEmpty().filter { it.extension=="json" }.sortedBy { it.name }
                // Reiterable, one photo at a time: the CSV writer scans keys, then writes rows.
                val records=sequence {
                    for(file in files) {
                        ensureActive()
                        val p=JSONObject(file.readText())
                        val meta=mapOf("build" to p.optString("build"),"mode" to p.optString("mode"),"run_id" to dir.name,
                            "image" to p.optString("image"),"attempt" to p.optString("attempt"))
                        yield(meta+mapOf("row_type" to "image_summary","status" to p.optString("status"),"elapsed_ms" to p.optString("total_elapsed_ms"),"error" to p.optString("error"),"engine_incomplete" to p.optBoolean("engine_incomplete").toString(),"diagnostics" to p.optString("diagnostics")))
                        for(key in listOf("tokens","engine_timings","trace","pipelines")) {
                            val rows=p.optJSONArray(key) ?: continue
                            for(i in 0 until rows.length()) {
                                val row=rows.getJSONObject(i)
                                yield(meta+mapOf("row_type" to key)+row.keys().asSequence().associateWith { row.optString(it) })
                            }
                        }
                    }
                }
                requireNotNull(app.contentResolver.openOutputStream(uri,"w")).bufferedWriter().use { writer ->
                    com.yourapp.wine.textlab.TextLabCsv.write(writer,records)
                }
                mutable.update { it.copy(message="Raw diagnostics CSV exported, including retained earlier attempts.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(e:Exception) { mutable.update { it.copy(message="Diagnostic export failed: ${e.message}") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    private companion object { val lock=Mutex() }
}
