package com.yourapp.wine.textlab

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.wine.capture.SelectedLabelPhotoImporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import com.n8rtz.winetracker.BuildConfig

data class TextLabImage(val id:String,val name:String,val photo:TextPhoto?=null,
    val selection:TextCrop?=TextCrop(0f,0f,1f,1f),val error:String?=null)
data class TextLabSavedRun(val file:File,val modifiedAt:Long,val bytes:Long)
data class TextLabState(val images:List<TextLabImage> = emptyList(),val activeImage:Int=0,
    val savedRuns:List<TextLabSavedRun> = emptyList(),val viewingRunId:String?=null,
    val compareEvidence:Boolean=false,val anchorHybrid:Boolean=false,val repairProducer:Boolean=false,val repairPlaces:Boolean=false,val compareGazetteer:Boolean=false,val residualCandidates:Boolean=false,val enlarge:Boolean=false,val selectedCandidates:Set<String> = setOf("candidate_3"),val detectLines:Boolean=true,
    val busy:Boolean=false,val result:String="",val wineTable:List<List<String>> = emptyList(),val formTable:List<List<String>> = emptyList(),val cropPreview:File?=null,val lastCsv:File?=null,
    val message:String="Choose one or more images. Full images are selected by default.") {
    val current:TextLabImage? get()=images.getOrNull(activeImage)
    val photo:TextPhoto? get()=current?.photo
    val selection:TextCrop? get()=current?.selection
    val cropsReady:Boolean get()=images.all { image -> image.photo?.let { image.selection?.pixels(it.width,it.height)!=null } ?: true }
}
class TextLabViewModel(app:Application):AndroidViewModel(app) {
    private val csvFolder=File(app.noBackupFilesDir,"text-lab-tests")
    private val mutable=MutableStateFlow(TextLabState())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private var cleared=false

    private fun savedRuns():List<TextLabSavedRun> = csvFolder.listFiles()?.asSequence()
        ?.filter { it.isFile && it.extension.equals("csv",ignoreCase=true) }
        ?.sortedByDescending { it.lastModified() }
        ?.map { TextLabSavedRun(it,it.lastModified(),it.length()) }?.toList().orEmpty()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val runs=savedRuns()
            mutable.update { it.copy(savedRuns=runs,lastCsv=runs.firstOrNull()?.file) }
            runs.firstOrNull()?.let { latest ->
                runCatching { TextLabBatch.readSaved(latest.file) }.onSuccess { saved ->
                    if(!cleared) mutable.update { it.copy(result=saved.summary(),wineTable=TextLabWineTable.rows(saved.journals),
                        formTable=TextLabWineTable.formRows(saved.journals),viewingRunId=saved.id,
                        message="Restored the latest saved run. Past runs remain available below until their app data is cleared.") }
                }.onFailure { error ->
                    if(!cleared) mutable.update { it.copy(message="Saved run ${latest.file.name} is retained, but could not be opened: ${error.message ?: error.javaClass.simpleName}") }
                }
            }
        }
    }

    fun openSavedRun(file:File) {
        if(mutable.value.busy || !file.isFile) return
        clearPreview()
        mutable.update { it.copy(busy=true,message="Opening saved OCR run…",cropPreview=null) }
        job=viewModelScope.launch {
            try {
                val saved=withContext(Dispatchers.IO) { TextLabBatch.readSaved(file) }
                if(!cleared) mutable.update { it.copy(busy=false,result=saved.summary(),
                    wineTable=TextLabWineTable.rows(saved.journals),formTable=TextLabWineTable.formRows(saved.journals),
                    lastCsv=file,viewingRunId=saved.id,message="Viewing saved run ${file.name}. No OCR was rerun; export downloads this saved CSV.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                if(!cleared) mutable.update { it.copy(busy=false,message="Could not open ${file.name}: ${error.message ?: error.javaClass.simpleName}") }
            } finally {
                if(!cleared) mutable.update { it.copy(busy=false) }
            }
        }
    }
    private fun clearPreview() { mutable.value.cropPreview?.delete() }
    private fun dispose(images:List<TextLabImage>) { images.forEach { it.photo?.let { photo -> photo.input.delete();photo.preview.delete() } } }
    fun select(crop:TextCrop?) {
        if(mutable.value.busy) return
        clearPreview()
        mutable.update { state -> state.copy(images=state.images.mapIndexed { index,image ->
            if(index==state.activeImage) image.copy(selection=crop) else image
        },result="",formTable=emptyList(),wineTable=emptyList(),cropPreview=null) }
    }
    fun showImage(index:Int) {
        if(mutable.value.busy || index !in mutable.value.images.indices) return
        clearPreview();mutable.update { it.copy(activeImage=index,cropPreview=null) }
    }
    fun enlarge(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(enlarge=value,result="",formTable=emptyList(),wineTable=emptyList(),cropPreview=null) } } }
    fun candidate(id:String,enabled:Boolean) {
        if(mutable.value.busy || TextLabChoices.all.none { it.id==id }) return
        clearPreview();mutable.update { it.copy(selectedCandidates=if(enabled) it.selectedCandidates+id else it.selectedCandidates-id,result="",formTable=emptyList(),wineTable=emptyList(),cropPreview=null) }
    }
    fun compareEvidence(value:Boolean) {
        if(!mutable.value.busy) mutable.update { it.copy(compareEvidence=value,result="",formTable=emptyList(),wineTable=emptyList()) }
    }
    fun anchorHybrid(value:Boolean) {
        if(!mutable.value.busy) mutable.update { it.copy(anchorHybrid=value,result="",formTable=emptyList(),wineTable=emptyList()) }
    }
    fun residualCandidates(value:Boolean) {
        if(!mutable.value.busy) mutable.update { it.copy(residualCandidates=value,result="",formTable=emptyList(),wineTable=emptyList()) }
    }
    fun compareGazetteer(value:Boolean) {
        if(!mutable.value.busy) mutable.update { it.copy(compareGazetteer=value,result="",formTable=emptyList(),wineTable=emptyList()) }
    }
    fun repairProducer(value:Boolean) {
        if(!mutable.value.busy) mutable.update { it.copy(repairProducer=value,result="",formTable=emptyList(),wineTable=emptyList()) }
    }
    fun repairPlaces(value:Boolean) {
        if(!mutable.value.busy) mutable.update { it.copy(repairPlaces=value,result="",formTable=emptyList(),wineTable=emptyList()) }
    }
    fun detectLines(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(detectLines=value,result="",formTable=emptyList(),wineTable=emptyList(),cropPreview=null) } } }
    fun choose(uris:List<Uri>) {
        if(mutable.value.busy || uris.isEmpty()) return
        clearPreview()
        val previous=mutable.value.images
        mutable.update { it.copy(busy=true,message="Importing ${uris.size} images…",result="",formTable=emptyList(),wineTable=emptyList(),cropPreview=null) }
        job=viewModelScope.launch {
            val loaded=mutableListOf<TextLabImage>()
            try {
                for((index,uri) in uris.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(message="Importing image ${index+1}/${uris.size}…") }
                    var input:File?=null;var preview:File?=null;var retained=false
                    var name="Image ${index+1}"
                    val id=UUID.randomUUID().toString()
                    try {
                        val photo=withTimeout(8000) {
                            name=withContext(Dispatchers.IO) {
                                runCatching {
                                    getApplication<Application>().contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                                        if(cursor.moveToFirst()) cursor.getString(0)?.substringAfterLast('/')?.substringAfterLast('\\')?.take(200) else null
                                    }
                                }.getOrNull()?.takeIf { it.isNotBlank() } ?: name
                            }
                            val local=SelectedLabelPhotoImporter(getApplication()).copy(uri).also { input=it.file }
                            withContext(Dispatchers.Default) {
                                val target=File.createTempFile("ocr-text-source-",".png",getApplication<Application>().cacheDir).also { preview=it }
                                TextLabRunner.preview(local.file,target)
                            }
                        }
                        loaded += TextLabImage(id,name,photo)
                        retained=true
                    } catch(_:TimeoutCancellationException) { loaded += TextLabImage(id,name,error="Image import timed out") }
                    catch(cancel:CancellationException) { throw cancel }
                    catch(error:Exception) { loaded += TextLabImage(id,name,error="Image import failed: ${error.javaClass.simpleName}") }
                    finally { if(!retained) { input?.delete();preview?.delete() } }
                }
            } catch(_:CancellationException) {
                mutable.update { it.copy(message="Import stopped. Unimported images will be recorded as failures if you run the batch.") }
            } catch(_:OutOfMemoryError) {
                mutable.update { it.copy(message="Import stopped under memory pressure.") }
            } finally {
                while(loaded.size<uris.size) loaded += TextLabImage(UUID.randomUUID().toString(),"Image ${loaded.size+1}",error="Import not completed")
                dispose(previous)
                if(cleared) dispose(loaded) else mutable.update { it.copy(images=loaded.toList(),activeImage=0,busy=false,
                    message="${loaded.count { image -> image.photo!=null }}/${loaded.size} images loaded. Review crops with Previous/Next, then run the batch. Failed imports remain in the CSV.") }
            }
        }
    }
    fun run() {
        val snapshot=mutable.value
        if(snapshot.busy || snapshot.images.isEmpty() || snapshot.selectedCandidates.isEmpty() || !snapshot.cropsReady) return
        clearPreview()
        val batch=TextLabBatch(snapshot.images.map { TextLabImageKey(it.id,it.name) },snapshot.selectedCandidates,BuildConfig.BUILD_COMMIT,snapshot.detectLines,snapshot.compareEvidence,snapshot.anchorHybrid,snapshot.repairProducer,snapshot.compareGazetteer,snapshot.residualCandidates,repairPlaces=snapshot.repairPlaces)
        mutable.update { it.copy(busy=true,result="",formTable=emptyList(),wineTable=emptyList(),cropPreview=null,message="Running image batch…") }
        job=viewModelScope.launch {
            var finalStatus="complete"
            var retainedPreview:File?=null
            fun checkpoint() {
                try { batch.save(csvFolder) }
                catch(error:Exception) { batch.journals.first().add("shared","checkpoint","failed",details=error.message.orEmpty().take(1000)) }
            }
            try {
                withContext(Dispatchers.Default) {
                    checkpoint() // Includes every queued image even if the process stops before the first test.
                    for((index,image) in snapshot.images.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        mutable.update { it.copy(message="Image ${index+1}/${snapshot.images.size}: ${image.name} — running selected candidates…") }
                        val journal=batch.journals[index]
                        var imageStatus="complete"
                        var output:File?=null
                        try {
                            val photo=image.photo
                            if(photo==null) {
                                imageStatus="import_failed"
                                journal.add("shared","error",imageStatus,details=image.error ?: "Image unavailable")
                            } else {
                                journal.add("shared","test_start","running")
                                val file=File.createTempFile("ocr-text-crop-",".png",getApplication<Application>().cacheDir).also { output=it }
                                TextLabRunner.recognize(getApplication(),photo,requireNotNull(image.selection),snapshot.enlarge,file,journal) {
                                    checkpoint()
                                }
                            }
                        } catch(cancel:CancellationException) { imageStatus="cancelled";throw cancel }
                        catch(error:OutOfMemoryError) { imageStatus="memory_pressure";throw error }
                        catch(error:LinkageError) { imageStatus="unavailable";journal.add("shared","error",imageStatus,details=error.message.orEmpty()) }
                        catch(error:Exception) { imageStatus="failed";journal.add("shared","error",imageStatus,details=error.message.orEmpty().take(2000)) }
                        finally {
                            batch.finishImage(index,imageStatus)
                            checkpoint()
                            if(index==snapshot.activeImage && output?.length()?.let { it>0 }==true) retainedPreview=output else output?.delete()
                        }
                    }
                }
            } catch(_:CancellationException) { finalStatus="cancelled" }
            catch(_:OutOfMemoryError) { finalStatus="memory_pressure" }
            catch(_:Exception) { finalStatus="failed" }
            finally {
                // Keep the UI reset inside NonCancellable too: returning from IO to a cancelled
                // parent otherwise throws before busy is cleared after Stop.
                withContext(NonCancellable) {
                    batch.finishPending(finalStatus)
                    batch.journals.forEach { it.metadata["batch_status"]=finalStatus }
                    withContext(Dispatchers.Default) {
                        try { TextLabWineExtraction.append(getApplication(),batch,finalStatus=="memory_pressure") }
                        catch(_:OutOfMemoryError) { batch.journals.first().add("shared","field_extraction","memory_pressure") }
                        catch(error:Exception) { batch.journals.first().add("shared","field_extraction","failed",details=error.message.orEmpty()) }
                    }
                    var csv:File?=null
                    var finalSaved=false
                    var summary=""
                    var history:List<TextLabSavedRun> = emptyList()
                    withContext(Dispatchers.IO) {
                        try { csv=batch.save(csvFolder);finalSaved=true }
                        catch(_:Exception) { csv=File(csvFolder,"${batch.id}.csv").takeIf { it.isFile } }
                        history=savedRuns()
                        summary=batch.summary()
                    }
                    if(cleared) retainedPreview?.delete() else mutable.update { it.copy(busy=false,result=summary,wineTable=TextLabWineTable.rows(batch.journals),formTable=TextLabWineTable.formRows(batch.journals),cropPreview=retainedPreview,lastCsv=csv,savedRuns=history,viewingRunId=batch.id,
                        message="Batch $finalStatus. This run is saved in past runs; export its CSV for every image and selected candidate."+(if(!finalSaved) " Final CSV save failed; any earlier checkpoint and Copy OCR result remain available." else "")) }
                }
            }
        }
    }
    fun exportCsv(uri:Uri) {
        val file=mutable.value.lastCsv ?: return
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,message="Exporting CSV…") }
        job=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).use { output -> file.inputStream().use { it.copyTo(output) } }
                }
                mutable.update { it.copy(message="Exported one CSV containing all images and selected candidates from the last batch.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { mutable.update { it.copy(message="CSV export failed. The private saved copy is retained; try again.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun cancel() { job?.cancel();mutable.update { it.copy(message="Stopping; preserving partial batch results…") } }
    override fun onCleared() {
        cleared=true
        val images=mutable.value.images;val preview=mutable.value.cropPreview
        val active=job
        active?.cancel()
        if(active==null) { dispose(images);preview?.delete() }
        else active.invokeOnCompletion { dispose(images);preview?.delete() }
        super.onCleared()
    }
}
