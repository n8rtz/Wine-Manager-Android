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

data class TextLabState(val photo:TextPhoto?=null,val selection:TextCrop?=null,val enlarge:Boolean=false,val comparePaddle:Boolean=true,val detectLines:Boolean=true,
    val busy:Boolean=false,val result:String="",val cropPreview:File?=null,
    val message:String="Choose a photo, then select the full label or a text line.")
class TextLabViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(TextLabState())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private fun clearPreview() { mutable.value.cropPreview?.delete() }
    fun select(crop:TextCrop?) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(selection=crop,result="",cropPreview=null) } } }
    fun enlarge(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(enlarge=value,result="",cropPreview=null) } } }
    fun compare(value:Boolean) { if(!mutable.value.busy) { clearPreview();mutable.update { it.copy(comparePaddle=value,result="",cropPreview=null) } } }
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
        if(snapshot.busy || crop.pixels(photo.width,photo.height)==null) return
        clearPreview()
        mutable.update { it.copy(busy=true,result="",cropPreview=null,message="Reading selected crop…") }
        job=viewModelScope.launch {
            var output:File?=null;var retained=false
            try {
                val text=withContext(Dispatchers.Default) {
                    val file=File.createTempFile("ocr-text-crop-",".png",getApplication<Application>().cacheDir).also { output=it }
                    TextLabRunner.recognize(getApplication(),photo,crop,snapshot.enlarge,file,snapshot.comparePaddle,snapshot.detectLines)
                }
                if(text==null) mutable.update { it.copy(message="8-second OCR budget reached. Try again or use a smaller crop.") }
                else {
                    mutable.update { it.copy(result=text,cropPreview=output?.takeIf { file -> file.length()>0 },message="Done. Draw another box to test different lettering.") }
                    retained=mutable.value.cropPreview!=null
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(_:Exception) { mutable.update { it.copy(message="OCR could not finish. Try another crop.") } }
            catch(_:OutOfMemoryError) { mutable.update { it.copy(message="Stopped under memory pressure. Try a smaller crop.") } }
            catch(_:LinkageError) { mutable.update { it.copy(message="Native OCR is unavailable in this build.") } }
            finally { if(!retained) output?.delete();mutable.update { it.copy(busy=false) } }
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
