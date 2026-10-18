package com.yourapp.wine.benchmark

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.wine.capture.*
import com.yourapp.wine.diagnostics.*
import com.yourapp.wine.fill.FieldId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LabLabel(val id:String=UUID.randomUUID().toString(),val uri:Uri,val label:String,
    val expected:Map<FieldId,String> = emptyMap(),val reviewed:Boolean=false,val presetKey:String?=null)
data class LabelRun(val id:String,val label:String,val expected:Map<FieldId,String>,val seed:Int,
    val inputHash:String="",val baseHash:String="",val base:File?=null,val setup:ScanReport?=null,
    val results:List<BenchmarkCaseResult> = emptyList(),val status:String="queued")
data class LabState(val busy:Boolean=false,val progress:String="Choose up to 10 label photos, then select a cellar wine or enter each label's actual details.",
    val labels:List<LabLabel> = emptyList(),val runs:List<LabelRun> = emptyList(),val batchId:String="",val savedRows:Int=0,val historyReady:Boolean=false)
data class CellarSearchState(val loading:Boolean=false,val wines:List<CellarWineChoice> = emptyList(),val error:String?=null)
private class HistoryWriteFailure(cause:Exception):Exception(cause)

class OcrLabViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(LabState())
    val state=mutable.asStateFlow()
    private val cellarMutable=MutableStateFlow(CellarSearchState())
    val cellarSearch=cellarMutable.asStateFlow()
    private var cellarJob:Job?=null
    fun searchCellar(query:String) {
        cellarJob?.cancel()
        cellarMutable.value=CellarSearchState(loading=true)
        cellarJob=viewModelScope.launch {
            try {
                delay(250)
                val choices=CellarWineSearch.find(getApplication(),query)
                ensureActive()
                cellarMutable.value=CellarSearchState(wines=choices)
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { cellarMutable.value=CellarSearchState(error="Could not read the cellar. Try again, or enter the details manually.") }
        }
    }
    fun closeCellarSearch() { cellarJob?.cancel();cellarMutable.value=CellarSearchState() }
    fun useCellarWine(labelId:String,wine:CellarWineChoice) {
        if(mutable.value.busy) return
        mutable.update { state -> state.copy(labels=state.labels.map { label ->
            if(label.id!=labelId) label else label.copy(label=wine.title.take(120),expected=wine.expected(),reviewed=false)
        }) }
        saveBundledChanges()
        closeCellarSearch()
    }
    private var job:Job?=null
    private var directory:File?=null
    private val history=BenchmarkHistory(File(app.noBackupFilesDir,"ocr-lab-csv-v1"))
    init { viewModelScope.launch { refreshCount() } }
    private suspend fun refreshCount() { val count=history.count();mutable.update { it.copy(savedRows=count,historyReady=true) } }

    private fun saveBundledChanges() = BundledLabSamples.save(getApplication(),mutable.value.labels)
    fun loadBundledSamples() {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,progress="Loading bundled test photos…") }
        job=viewModelScope.launch {
            try {
                val samples=BundledLabSamples.load(getApplication())
                mutable.update { it.copy(labels=samples,progress="Bundled photos and expected details loaded. Review once; corrections and confirmations are remembered.") }
            } catch(cancel:CancellationException) {
                mutable.update { it.copy(progress="Loading cancelled. Previous queue retained.") };throw cancel
            } catch(_:Exception) {
                mutable.update { it.copy(progress="Could not load bundled photos. Previous queue retained; try again or choose photos manually.") }
            } finally { mutable.update { it.copy(busy=false) } }
        }
    }

    fun choose(uris:List<Uri>) {
        if(mutable.value.busy || uris.isEmpty()) return
        val unique=uris.distinct()
        if(unique.size>10) { mutable.update { it.copy(progress="Select at most 10 photos per batch. No selection was changed.") };return }
        mutable.update { it.copy(labels=unique.mapIndexed { index,uri -> LabLabel(uri=uri,label="Label ${index+1}") },
            progress="Select a cellar wine or enter details for each photo, then mark it reviewed. Previous CSV history is retained.") }
    }
    fun edit(id:String,field:FieldId?,value:String) {
        if(mutable.value.busy) return
        mutable.update { state -> state.copy(labels=state.labels.map { if(it.id!=id) it else if(field==null)
            it.copy(label=value.take(120),reviewed=false) else it.copy(expected=it.expected+(field to value.take(120)),reviewed=false) }) }
        saveBundledChanges()
    }
    fun review(id:String,value:Boolean) {
        if(!mutable.value.busy) mutable.update { state -> state.copy(labels=state.labels.map { if(it.id==id) it.copy(reviewed=value) else it }) }
        if(!mutable.value.busy) saveBundledChanges()
    }
    private fun updateRun(id:String,change:(LabelRun)->LabelRun) { mutable.update { it.copy(runs=it.runs.map { run -> if(run.id==id) change(run) else run }) } }

    fun start() {
        if(job?.isActive==true || mutable.value.labels.isEmpty() || mutable.value.labels.any { !it.reviewed }) return
        val queue=mutable.value.labels.map { it.copy(expected=it.expected.mapValues { entry -> entry.value.trim() }.filterValues(String::isNotBlank)) }
        val batch="${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val runs=queue.map { LabelRun(it.id,it.label,it.expected,(System.nanoTime() and 0x7fffffff).toInt()) }
        mutable.update { it.copy(busy=true,batchId=batch,runs=runs,progress="Starting multi-label batch…") }
        job=viewModelScope.launch(Dispatchers.Default) {
            try {
                directory?.deleteRecursively()
                val root=File(getApplication<Application>().cacheDir,"ocr-lab").apply { mkdirs() }
                root.listFiles()?.filter { it.lastModified()<System.currentTimeMillis()-86_400_000 }?.forEach { it.deleteRecursively() }
                val folder=File(root,batch).apply { mkdirs() };directory=folder
                val scanner=withTimeout(8000) { WineScannerServices.get(getApplication()) }
                for((imageIndex,label) in queue.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    var photo:File?=null
                    try {
                        updateRun(label.id) { it.copy(status="running") }
                        mutable.update { it.copy(progress="Image ${imageIndex+1}/${queue.size}: opening ${label.label}…") }
                        val imported=withTimeout(8000) { SelectedLabelPhotoImporter(getApplication()).copy(label.uri).also { photo=it.file } }
                        val inputHash=hash(imported.file)
                        updateRun(label.id) { it.copy(inputHash=inputHash) }
                        val imageFolder=File(folder,label.id).apply { mkdirs() }
                        val seed=runs.first { it.id==label.id }.seed
                        BenchmarkRunner(scanner).run(imported.file,imageFolder,seed,onBase={ report,file ->
                            val baseHash=hash(file)
                            updateRun(label.id) { it.copy(base=file,setup=report,baseHash=baseHash) }
                        },onProgress={ message -> mutable.update { it.copy(progress="Image ${imageIndex+1}/${queue.size} · ${label.label}: $message") } },onResult={ result ->
                            // Freeze per-image truth before the run; evaluate only AFTER recognition/extraction.
                            val scored=result.copy(score=BenchmarkScoring.score(label.expected,result.report.fill,result.report.tokens))
                            updateRun(label.id) { it.copy(results=it.results+scored) }
                            val run=mutable.value.runs.first { it.id==label.id }
                            saveRow(batch,run,run.results.size,scored)
                        },previewBudgetBytes=64L*1024*1024/queue.size)
                        updateRun(label.id) { it.copy(status="finished") }
                    } catch(error:HistoryWriteFailure) { throw error }
                    catch(timeout:TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        updateRun(label.id) { it.copy(status="setup timed out") }
                        saveRow(batch,mutable.value.runs.first { it.id==label.id },0,null)
                    } catch(cancel:CancellationException) {
                        updateRun(label.id) { it.copy(status="stopped") };throw cancel
                    } catch(error:Exception) {
                        updateRun(label.id) { it.copy(status="setup/run failure: ${error.javaClass.simpleName}") }
                        saveRow(batch,mutable.value.runs.first { it.id==label.id },0,null)
                    } finally { photo?.delete() }
                }
                mutable.update { it.copy(progress="Batch finished. Results saved locally; CSV includes every label's expected and recognized fields.") }
            } catch(cancel:CancellationException) {
                mutable.update { it.copy(progress="Stopped. Completed CSV rows are retained; unfinished labels are not marked complete.") };throw cancel
            } catch(_:HistoryWriteFailure) {
                mutable.update { it.copy(progress="Stopped: CSV history could not be saved (storage full or unavailable). Export saved history, then clear it. Latest unsaved result remains visible.") }
            } catch(error:Exception) {
                mutable.update { it.copy(progress="Batch stopped (${error.javaClass.simpleName}). Completed CSV rows retained.") }
            } catch(error:LinkageError) {
                mutable.update { it.copy(progress="Native scanner unavailable (${error.javaClass.simpleName}).") }
            } catch(_:OutOfMemoryError) {
                mutable.update { it.copy(progress="Stopped under memory pressure. Completed CSV rows retained; try fewer photos.") }
            } finally { mutable.update { it.copy(busy=false,runs=it.runs.map { run ->
                if(run.status=="running") run.copy(status="interrupted") else run
            }) } }
        }
    }
    private suspend fun saveRow(batch:String,run:LabelRun,order:Int,result:BenchmarkCaseResult?) {
        try {
            val row=BenchmarkCsv.row(batch,run.id,run.label,run.inputHash,run.baseHash,run.seed,order,
                result?.recipe,result?.status ?: run.status,result?.report,result?.preparationMs,run.expected,AndroidScanEnvironment.current())
            history.save("$batch-${run.id}-${order.toString().padStart(3,'0')}",row)
            refreshCount()
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) { throw HistoryWriteFailure(error) }
    }
    fun cancel() { mutable.update { it.copy(progress="Stopping and releasing images…") };job?.cancel() }

    fun export(uri:Uri,csv:Boolean=false) {
        if(job?.isActive==true) return
        val snapshot=mutable.value
        mutable.update { it.copy(busy=true,progress="Exporting…") }
        job=viewModelScope.launch(Dispatchers.IO) {
            try {
                requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).use { output ->
                    if(csv) history.export(output) else ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("results.csv"))
                        zip.write(BenchmarkCsv.header.toByteArray())
                        snapshot.runs.forEach { run ->
                            run.results.forEachIndexed { index,result ->
                                zip.write(BenchmarkCsv.row(snapshot.batchId,run.id,run.label,run.inputHash,run.baseHash,run.seed,index+1,
                                    result.recipe,result.status,result.report,result.preparationMs,run.expected,AndroidScanEnvironment.current()).toByteArray())
                            }
                            if(run.results.isEmpty()) zip.write(BenchmarkCsv.row(snapshot.batchId,run.id,run.label,run.inputHash,
                                run.baseHash,run.seed,0,null,run.status,null,null,run.expected,AndroidScanEnvironment.current()).toByteArray())
                        }
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("results.txt"));zip.write(BenchmarkExport.text(snapshot).toByteArray());zip.closeEntry()
                        snapshot.runs.forEach { run ->
                            val images=listOfNotNull(run.base)+run.results.flatMap { it.previews }
                            images.filter { it.isFile }.distinct().forEach { file ->
                                ensureActive()
                                val entry=if(file==run.base) "${run.id}/base.png" else "${run.id}/${file.parentFile!!.name}/${file.name}"
                                zip.putNextEntry(ZipEntry(entry));file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
                            }
                        }
                    }
                }
                mutable.update { it.copy(progress=if(csv) "All saved test rows exported as CSV (no images)." else "Current batch exported: CSV, images and diagnostics.") }
            } catch(cancel:CancellationException) {
                mutable.update { it.copy(progress="Export stopped; an incomplete destination file may need deleting.") };throw cancel
            } catch(error:Exception) { mutable.update { it.copy(progress="Export failed (${error.javaClass.simpleName}); partial output may need deleting.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun clearHistory() {
        if(job?.isActive==true) return
        mutable.update { it.copy(busy=true) }
        job=viewModelScope.launch {
            try { history.clear();refreshCount();mutable.update { it.copy(progress="Saved CSV history deleted. Cellar data and original photos unchanged.") } }
            catch(error:Exception) { mutable.update { it.copy(progress="Could not clear history (${error.javaClass.simpleName}).") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    override fun onCleared() {
        val running=job;running?.cancel()
        // Only transient image previews are deleted. Structured CSV history survives this screen and app restarts.
        CoroutineScope(Dispatchers.IO).launch { running?.join();directory?.deleteRecursively() }
        super.onCleared()
    }
    private suspend fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer=ByteArray(32768);while(true) {
            currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
