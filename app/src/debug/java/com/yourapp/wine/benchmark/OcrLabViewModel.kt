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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class LabState(val busy:Boolean=false,val progress:String="Choose a photo to begin.",
    val results:List<BenchmarkCaseResult> = emptyList(),val base:File?=null,
    val expected:Map<FieldId,String> = emptyMap(),val seed:Int=0,val baseHash:String="",val setup:ScanReport?=null)

class OcrLabViewModel(app:Application):AndroidViewModel(app) {
    private val mutable=MutableStateFlow(LabState())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    private var directory:File?=null

    fun start(uri:Uri,truth:Map<FieldId,String>) {
        if(job?.isActive==true) return
        val expected=truth.mapValues { it.value.trim().take(120) }.filterValues { it.isNotEmpty() }
        mutable.value=LabState(busy=true,progress="Opening photo…",expected=expected,seed=(System.nanoTime() and 0x7fffffff).toInt())
        job=viewModelScope.launch(Dispatchers.Default) {
            var photo:File?=null
            try {
                directory?.deleteRecursively()
                val root=File(getApplication<Application>().cacheDir,"ocr-lab").apply { mkdirs() }
                // Expire process-death leftovers, never other app cache files or a user's original image.
                root.listFiles()?.filter { it.lastModified()<System.currentTimeMillis()-86_400_000 }?.forEach { it.deleteRecursively() }
                val folder=File(root,"run-${java.util.UUID.randomUUID()}").apply { mkdirs() };directory=folder
                val imported=withTimeout(8000) { SelectedLabelPhotoImporter(getApplication()).copy(uri).also { photo=it.file } }
                mutable.update { it.copy(progress="Initializing local engines…") }
                val scanner=withTimeout(8000) { WineScannerServices.get(getApplication()) }
                BenchmarkRunner(scanner).run(imported.file,folder,mutable.value.seed,onBase={ report,file ->
                    val digest=MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input -> val buffer=ByteArray(32768);while(true) {
                        val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)
                    } }
                    mutable.update { it.copy(base=file,setup=report,baseHash=digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 255) }) }
                },onProgress={ message -> mutable.update { it.copy(progress=message) } },onResult={ result ->
                    // Ground truth crosses into evaluation ONLY after recognition/extraction have completed.
                    val scored=result.copy(score=BenchmarkScoring.score(expected,result.report.fill,result.report.tokens))
                    mutable.update { it.copy(results=it.results+scored) }
                })
                mutable.update { it.copy(progress="Finished 30 cases. Inspect raw text as well as field matches.") }
            } catch(cancel:CancellationException) {
                mutable.update { it.copy(progress=if(cancel is TimeoutCancellationException) "Setup timed out; choose again." else "Stopped. Completed cases retained.") }
                throw cancel
            } catch(error:Exception) {
                mutable.update { it.copy(progress="Benchmark stopped (${error.javaClass.simpleName}). Try a closer label photo; completed cases retained.") }
            } catch(error:LinkageError) {
                mutable.update { it.copy(progress="Native scanner unavailable (${error.javaClass.simpleName}).") }
            } catch(_:OutOfMemoryError) {
                mutable.update { it.copy(progress="Benchmark stopped under memory pressure. Completed cases retained; close other apps before retrying.") }
            } finally { photo?.delete();mutable.update { it.copy(busy=false) } }
        }
    }
    fun cancel() { mutable.update { it.copy(progress="Stopping and releasing images…") };job?.cancel() }

    fun export(uri:Uri) {
        if(job?.isActive==true) return
        val snapshot=mutable.value
        mutable.update { it.copy(busy=true,progress="Exporting images and diagnostics…") }
        job=viewModelScope.launch(Dispatchers.IO) {
            try {
                requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri,"wt")).use { output ->
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("results.txt"));zip.write(BenchmarkExport.text(snapshot).toByteArray());zip.closeEntry()
                        val images=listOfNotNull(snapshot.base)+snapshot.results.flatMap { it.previews }
                        images.filter { it.isFile }.distinct().forEach { file ->
                            ensureActive()
                            val entry=if(file==snapshot.base) "base.png" else "${file.parentFile!!.name}/${file.name}"
                            zip.putNextEntry(ZipEntry(entry));file.inputStream().use { it.copyTo(zip) };zip.closeEntry()
                        }
                    }
                }
                mutable.update { it.copy(progress="ZIP exported. It includes processed label images, expected answers and OCR/device diagnostics.") }
            } catch(cancel:CancellationException) {
                mutable.update { it.copy(progress="Export stopped; an incomplete ZIP may need deleting.") }
                throw cancel
            }
            catch(error:Exception) { mutable.update { it.copy(progress="Export failed (${error.javaClass.simpleName}); partial output may need deleting.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    override fun onCleared() {
        val running=job;running?.cancel()
        CoroutineScope(Dispatchers.IO).launch { running?.join();directory?.deleteRecursively() }
        super.onCleared()
    }
}

object BenchmarkExport {
    fun text(state:LabState):String=buildString {
        appendLine("WINE OCR LAB v1 — debug experiment, not normal scan acceptance")
        appendLine("Includes label images/text and user-entered evaluation answers. No original file/URI/EXIF GPS exported.")
        appendLine("seed=${state.seed} base_png_sha256=${state.baseHash} completed_cases=${state.results.size}/30")
        appendLine("All cases share base.png and original-to-base homography. Setup is warmup, NOT ranked.")
        appendLine("8s target per case including decode, treatment, preview encoding, OCR and extraction; synchronous native calls can overrun. Deadline cases are excluded from ranking.")
        appendLine("Baseline runs V0/V1/conditional V2; other recipes run one variant with the SAME available engines. Repeated ML Kit passes are not independent votes.")
        appendLine("Default order: baseline first, others shuffled by recorded seed; heat/warmup can bias timing. Repeat on several labels.")
        appendLine("Previews are the actual prepared images before ML Kit's grayscale NV21 adapter. PNG preview storage is capped at 64 MiB; missing previews may reflect that cap or early failure.")
        appendLine("Rank only COMPLETE cases by raw name best-span similarity, then exact checked fields. Not a universal winner or full-label CER.")
        appendLine("Blank expectations are UNSCORED, not expected-absent. Exact fields may reflect KB corrections; raw-name score is separately reported.")
        state.expected.forEach { (field,value) -> appendLine("expected_$field=${value.replace('\n',' ')}") }
        state.setup?.let { appendLine("\n[Unranked crop/localization setup]");appendLine(ScanDiagnosticFormatter.format(it,AndroidScanEnvironment.current(),0)) }
        state.results.forEachIndexed { index,result ->
            appendLine("\n[Case ${index+1}: ${result.recipe.id}]")
            appendLine(result.recipe.description)
            appendLine("status=${result.status} preparation_and_preview_ms=${result.preparationMs} exact_fields=${result.score?.exactFields}/${result.score?.checkedFields} raw_name_best_span_similarity=${result.score?.rawNameSimilarity}")
            appendLine(ScanDiagnosticFormatter.format(result.report,AndroidScanEnvironment.current(),index+1L))
        }
    }
}
