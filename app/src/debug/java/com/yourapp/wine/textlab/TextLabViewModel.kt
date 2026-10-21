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
data class TextLabState(val images:List<TextLabImage> = emptyList(),val activeImage:Int=0,
    val enlarge:Boolean=false,val selectedCandidates:Set<String> = setOf("candidate_3"),val detectLines:Boolean=true,
    val busy:Boolean=false,val result:String="",val cropPreview:File?=null,val lastCsv:File?=null,
    val message:String="Choose one or more images. Full images are selected by default.") {
    val current:TextLabImage? get()=images.getOrNull(activeImage)
    val photo:TextPhoto? get()=current?.photo
    val selection:TextCrop? get()=current?.selection
    val cropsReady:Boolean get()=images.all { image -> image.photo?.let { image.selection?.pixels(it.width,it.height)!=null } ?: true }
}
class TextLabViewModel(app:Application):AndroidViewModel(app) {
    private val csvFolder=File(app.noBackupFilesDir,"text-lab-tests")
    private val mutable=MutableStateFlow(TextLabState(lastCsv=csvFolder.listFiles()?.filter { it.extension=="csv" }?.maxByOrNull { it.lastModified() }))
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private var cleared=false
    private fun clearPreview() { mutable.value.cropPreview?.delete() }
    private fun dispose(images:List<TextLabImage>) { images.forEach { it.photo?.let { photo -> photo.input.delete();photo.preview.delete() } } }
    fun select(crop:TextCrop?) {
        if(mutable.value.busy) return
        clearPreview()
        mutable.update { state -> state.copy(images=state.images.mapIndexed { index,image ->
            if(index==state.activeImage) image.copy(selection=crop) else image
        },result="",cropPreview=null) }
    }
    fun showImage(index:Int) {
        if(mutable.value.busy || index !in mutable.value.images.indices) return
        clearPreview();mutable.update { it.copy(activeImage=index,cropPreview=null) }
    }
    fun enlarge(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(enlarge=value,result="",cropPreview=null) } } }
    fun candidate(id:String,enabled:Boolean) {
        if(mutable.value.busy || TextLabChoices.all.none { it.id==id }) return
        clearPreview();mutable.update { it.copy(selectedCandidates=if(enabled) it.selectedCandidates+id else it.selectedCandidates-id,result="",cropPreview=null) }
    }
    fun detectLines(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(detectLines=value,result="",cropPreview=null) } } }
    fun choose(uris:List<Uri>) {
        if(mutable.value.busy || uris.isEmpty()) return
        clearPreview()
        val previous=mutable.value.images
        mutable.update { it.copy(busy=true,message="Importing ${uris.size} images…",result="",cropPreview=null) }
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
        val batch=TextLabBatch(snapshot.images.map { TextLabImageKey(it.id,it.name) },snapshot.selectedCandidates,BuildConfig.BUILD_COMMIT,snapshot.detectLines)
        mutable.update { it.copy(busy=true,result="",cropPreview=null,message="Running image batch…") }
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
                    var csv:File?=null
                    var finalSaved=false
                    var summary=""
                    withContext(Dispatchers.IO) {
                        try { csv=batch.save(csvFolder);finalSaved=true }
                        catch(_:Exception) { csv=File(csvFolder,"${batch.id}.csv").takeIf { it.isFile } }
                        summary=batch.summary()
                    }
                    if(cleared) retainedPreview?.delete() else mutable.update { it.copy(busy=false,result=summary,cropPreview=retainedPreview,lastCsv=csv,
                        message="Batch $finalStatus. Export last batch CSV for every image and selected candidate."+(if(!finalSaved) " Final CSV save failed; any earlier checkpoint and Copy OCR result remain available." else "")) }
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
