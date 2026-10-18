package com.yourapp.wine.textlab

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.wine.capture.SelectedLabelPhotoImporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import com.n8rtz.winetracker.BuildConfig

data class TextLabState(val photo:TextPhoto?=null,val selection:TextCrop?=null,val enlarge:Boolean=false,val selectedCandidates:Set<String> = setOf("candidate_1","candidate_2","candidate_3"),val detectLines:Boolean=true,
    val busy:Boolean=false,val result:String="",val cropPreview:File?=null,val lastCsv:File?=null,
    val message:String="Choose a photo, then select the full label or a text line.")
class TextLabViewModel(app:Application):AndroidViewModel(app) {
    private val csvFolder=File(app.noBackupFilesDir,"text-lab-tests")
    private val mutable=MutableStateFlow(TextLabState(lastCsv=csvFolder.listFiles()?.filter { it.extension=="csv" }?.maxByOrNull { it.lastModified() }))
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private fun clearPreview() { mutable.value.cropPreview?.delete() }
    fun select(crop:TextCrop?) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(selection=crop,result="",cropPreview=null) } } }
    fun enlarge(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(enlarge=value,result="",cropPreview=null) } } }
    fun candidate(id:String,enabled:Boolean) {
        if(mutable.value.busy || PaddleCandidates.all.none { it.id==id }) return
        clearPreview()
        mutable.update { it.copy(selectedCandidates=if(enabled) it.selectedCandidates+id else it.selectedCandidates-id,result="",cropPreview=null) }
    }
    fun detectLines(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(detectLines=value,result="",cropPreview=null) } } }
    fun choose(uri:Uri) {
        if(mutable.value.busy) return
        clearPreview()
        mutable.update { it.copy(busy=true,message="Loading photo…",result="",cropPreview=null) }
        job=viewModelScope.launch {
            var imported:File?=null;var preview:File?=null;var retained=false
            try {
                val photo=withTimeout(8000) {
                    val local=SelectedLabelPhotoImporter(getApplication()).copy(uri).also { imported=it.file }
                    withContext(Dispatchers.Default) {
                        val target=File.createTempFile("ocr-text-source-",".png",getApplication<Application>().cacheDir).also { preview=it }
                        TextLabRunner.preview(local.file,target)
                    }
                }
                val previous=mutable.value.photo
                mutable.update { it.copy(photo=photo,selection=null,message="Drag around the label or text to test. Keep all lettering and flourishes inside the box.") }
                retained=true
                previous?.input?.delete();previous?.preview?.delete()
            } catch(_:TimeoutCancellationException) { mutable.update { it.copy(message="Photo loading timed out. Try again.") } }
            catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { mutable.update { it.copy(message="Could not open this photo. Try another image.") } }
            catch(_:OutOfMemoryError) { mutable.update { it.copy(message="Not enough memory to open this photo. Try a smaller image.") } }
            finally { if(!retained) { imported?.delete();preview?.delete() };mutable.update { it.copy(busy=false) } }
        }
    }
    fun run() {
        val snapshot=mutable.value
        val photo=snapshot.photo ?: return
        val crop=snapshot.selection ?: return
        if(snapshot.busy || snapshot.selectedCandidates.isEmpty() || crop.pixels(photo.width,photo.height)==null) return
        clearPreview()
        val journal=TextLabJournal(snapshot.selectedCandidates,BuildConfig.BUILD_COMMIT,snapshot.detectLines)
        journal.add("shared","test_start","running")
        mutable.update { it.copy(busy=true,result="",cropPreview=null,message="Running selected candidates sequentially…") }
        job=viewModelScope.launch {
            var output:File?=null
            var finalStatus="complete"
            var message="Test finished. Export last test CSV for all selected candidates."
            try {
                withContext(Dispatchers.Default) {
                    val file=File.createTempFile("ocr-text-crop-",".png",getApplication<Application>().cacheDir).also { output=it }
                    TextLabRunner.recognize(getApplication(),photo,crop,snapshot.enlarge,file,journal) {
                        try { journal.save(csvFolder) }
                        catch(error:Exception) { journal.add("shared","checkpoint","failed",details=error.message.orEmpty()) }
                    }
                }
            } catch(cancel:CancellationException) { finalStatus="cancelled";message="Stopped. Partial results and unrun candidates are included in the CSV." }
            catch(error:OutOfMemoryError) { finalStatus="memory_pressure";message="Stopped under memory pressure; partial CSV retained." }
            catch(error:LinkageError) { finalStatus="unavailable";message="Native runtime unavailable; see CSV." }
            catch(error:Exception) {
                finalStatus="failed";message="Test could not finish; see partial CSV."
                journal.add("shared","error","failed",details=error.message.orEmpty().take(2000))
            } finally {
                // The allocating worker/native calls have exited before this snapshot is read or exported.
                journal.finishPending("not_run_$finalStatus")
                journal.add("shared","test_end",finalStatus)
                var csv:File?=null
                withContext(NonCancellable+Dispatchers.IO) {
                    try {
                        csv=journal.save(csvFolder)
                    } catch(_:Exception) { csv=File(csvFolder,"${journal.id}.csv").takeIf { it.isFile }
                        message += " Final CSV save failed; any earlier checkpoint and Copy OCR result remain available." }
                }
                val preview=output?.takeIf { it.length()>0 }
                if(preview==null) output?.delete()
                mutable.update { it.copy(busy=false,result=journal.text(),cropPreview=preview,lastCsv=csv,message=message) }
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
                    requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
                mutable.update { it.copy(message="Exported one CSV containing all selected candidates for that test.") }
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { mutable.update { it.copy(message="CSV export failed. The private saved copy is retained; try again.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun cancel() { job?.cancel();mutable.update { it.copy(message="Stopped.") } }
    override fun onCleared() {
        job?.cancel()
        mutable.value.photo?.let { it.input.delete();it.preview.delete() }
        mutable.value.cropPreview?.delete()
        super.onCleared()
    }
}
