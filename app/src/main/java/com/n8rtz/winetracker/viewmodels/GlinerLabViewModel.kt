package com.n8rtz.winetracker.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.n8rtz.winetracker.BuildConfig
import com.yourapp.wine.gliner.GlinerBundle
import com.yourapp.wine.gliner.GlinerBundleStatus
import com.yourapp.wine.gliner.GlinerLabCorpus
import com.yourapp.wine.gliner.GlinerLabImageResult
import com.yourapp.wine.gliner.GlinerLabRunner
import com.yourapp.wine.gliner.GlinerLabSample
import com.yourapp.wine.gliner.GlinerLaneConfig
import com.yourapp.wine.gliner.GlinerOnnxModel
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import com.yourapp.wine.textlab.TextLabCsv
import com.yourapp.wine.textlab.TextLabWineExtraction
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class GlinerLabRow(
    val index:Int,
    val sample:GlinerLabSample,
    val status:String="Waiting",
    val result:GlinerLabImageResult?=null,
) {
    val displayName:String get() = sample.displayName
}

/** Same scoring semantics as the Lab v7 scoreboard, counted per field for this screen only. */
internal data class GlinerAggregate(
    val attempted:Int=0,
    val eligible:Int=0,
    val complete:Int=0,
    val oneError:Int=0,
    val multiError:Int=0,
    val failed:Int=0,
    val fieldCorrect:Map<String,Int> = emptyMap(),
    val fieldTotal:Map<String,Int> = emptyMap(),
    val stopped:Boolean=false,
) {
    fun text():String =
        "Fully correct $complete/$eligible · one error $oneError · 2+ errors $multiError · failed $failed · attempted $attempted"
}

internal data class GlinerLabState(
    val bundle:GlinerBundleStatus,
    val samples:List<GlinerLabRow> = emptyList(),
    val running:Boolean=false,
    val stopped:Boolean=false,
    val done:Int=0,
    val total:Int=0,
    val current:String="",
    val message:String="",
    val laneConfig:GlinerLaneConfig=GlinerLaneConfig(),
    val tokenizer:String="not loaded",
    val modules:LabPostOcrModules=LabPostOcrModules.none.withIntegrated(),
    val aggregate:GlinerAggregate?=null,
    val exports:List<File> = emptyList(),
    val expanded:Set<String> = emptySet(),
    val modelsReady:Boolean=false,
)

/**
 * Owns one GLiNER lab session: bundle status, the packaged Lab v7 dataset, one model session, one
 * warm post-processing session and the run loop. Nothing is cached across photos except the model,
 * the knowledge base and the producer gazetteer, so a run always reads fresh pixels.
 */
internal class GlinerLabViewModel(application:Application):AndroidViewModel(application) {
    // AndroidViewModel.app is generic over its bound, so keep the concrete reference.
    private val app:Application=application
    private val mutable=MutableStateFlow(GlinerLabState(bundle=GlinerBundle.status(application)))
    val state=mutable.asStateFlow()
    private val workFolder=File(application.cacheDir,"gliner-lab-${UUID.randomUUID()}")
    private var job:Job?=null

    init {
        mutable.update { it.copy(modelsReady=com.yourapp.wine.gliner.GlinerOcr.modelsPresent(application)) }
        refresh()
    }

    fun refresh() {
        val bundle=GlinerBundle.status(app)
        mutable.update { it.copy(bundle=bundle) }
        viewModelScope.launch(Dispatchers.IO) {
            val samples=runCatching { GlinerLabCorpus.load(app) }
                .getOrElse {
                    mutable.update { state -> state.copy(message="Cannot read the packaged Lab v7 dataset: ${it.message}") }
                    return@launch
                }
            mutable.update { state ->
                val known=state.samples.associateBy { it.sample.image }
                state.copy(samples=samples.mapIndexed { index,sample ->
                    known[sample.image]?.copy(index=index) ?: GlinerLabRow(index,sample)
                },total=samples.size)
            }
        }
    }

    fun importBundle(uris:List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome=GlinerBundle.import(app,uris)
            val bundle=GlinerBundle.status(app)
            mutable.update { it.copy(bundle=bundle,message=outcome.message) }
        }
    }

    fun clearBundle() {
        GlinerBundle.clear(app)
        val bundle=GlinerBundle.status(app)
        mutable.update { it.copy(bundle=bundle,message="Imported GLiNER files removed") }
    }

    fun setThreshold(delta:Float) {
        mutable.update { state ->
            val value=(state.laneConfig.threshold+delta).coerceIn(0.05f,0.95f)
            state.copy(laneConfig=state.laneConfig.copy(threshold=Math.round(value*100f)/100f))
        }
    }

    fun setMaxWidth(delta:Int) {
        mutable.update { state ->
            state.copy(laneConfig=state.laneConfig.copy(maxWidth=(state.laneConfig.maxWidth+delta).coerceIn(2,24)))
        }
    }

    fun toggleExpanded(image:String) {
        mutable.update { state ->
            state.copy(expanded=if(image in state.expanded) state.expanded-image else state.expanded+image)
        }
    }

    fun stop() {
        job?.cancel(CancellationException("Stopped by user"))
        mutable.update { it.copy(running=false,stopped=true,current="") }
    }

    fun runAll() = launchRun(only=null)

    fun runSingle(image:String) = launchRun(only=image)

    private fun launchRun(only:String?) {
        if(job?.isActive==true) return
        val bundle=mutable.value.bundle
        if(!bundle.ready) {
            mutable.update { it.copy(message="Install the GLiNER-Small INT4 bundle first: ${bundle.summary()}") }
            return
        }
        if(mutable.value.samples.isEmpty()) {
            mutable.update { it.copy(message="The packaged dataset is still loading") }
            return
        }
        val laneConfig=mutable.value.laneConfig
        val modules=mutable.value.modules
        val targets=mutable.value.samples.filter { only==null || it.sample.image==only }
        mutable.update { state ->
            state.copy(running=true,stopped=false,done=0,total=targets.size,current="",aggregate=null,message="",
                samples=state.samples.map { row ->
                    if(row.sample.image in targets.map { it.sample.image }.toSet()) row.copy(status="Waiting",result=null) else row
                })
        }
        job=viewModelScope.launch(Dispatchers.Default) {
            var model:GlinerOnnxModel?=null
            var warm:TextLabWineExtraction.WarmSession?=null
            val results=mutableListOf<GlinerLabImageResult>()
            try {
                workFolder.mkdirs()
                mutable.update { it.copy(message="Loading the GLiNER-Small INT4 graph…") }
                val opened=GlinerOnnxModel.open(app,GlinerBundle.status(app),laneConfig)
                model=opened
                if(!opened.int4) mutable.update { it.copy(message="Warning: this model does not declare MatMulNBits, so it is not the INT4 build") }
                mutable.update { it.copy(tokenizer=opened.tokenizerDescription) }
                val warmSession=TextLabWineExtraction.WarmSession(app)
                warm=warmSession
                targets.forEachIndexed { index,row ->
                    currentCoroutineContext().ensureActive()
                    mutable.update { it.copy(current=row.displayName) }
                    setStatus(row.sample.image,"Running")
                    val result=try {
                        GlinerLabRunner.run(app,row.sample,opened,laneConfig,warmSession,workFolder,modules)
                    } catch(cancel:CancellationException) {
                        setStatus(row.sample.image,"Stopped")
                        throw cancel
                    } catch(error:Exception) {
                        GlinerLabRunner.failedFor(row.sample,error,opened)
                    }
                    results += result
                    mutable.update { state ->
                        state.copy(done=index+1,
                            samples=state.samples.map { existing ->
                                if(existing.sample.image==row.sample.image) existing.copy(status=result.status,result=result) else existing
                            })
                    }
                }
                val aggregate=aggregate(results,stopped=false)
                mutable.update { it.copy(running=false,current="",aggregate=aggregate,message=aggregate.text()) }
            } catch(cancel:CancellationException) {
                val aggregate=aggregate(results,stopped=true)
                mutable.update { it.copy(running=false,stopped=true,current="",aggregate=aggregate,
                    message="Stopped after ${results.size} photos. ${aggregate.text()}") }
            } catch(error:Exception) {
                mutable.update { it.copy(running=false,current="",
                    message="GLiNER lab could not start: ${error.javaClass.simpleName}: ${error.message}") }
            } finally {
                runCatching { warm?.close() }
                runCatching { model?.close() }
            }
        }
    }

    private fun setStatus(image:String,status:String) {
        mutable.update { state ->
            state.copy(samples=state.samples.map { if(it.sample.image==image) it.copy(status=status) else it })
        }
    }

    private fun aggregate(results:List<GlinerLabImageResult>,stopped:Boolean):GlinerAggregate {
        val correct=PhoneCorpusMath.fields.associateWith { field ->
            results.count { result -> result.fieldResults.firstOrNull { it.field==field }?.let { PhoneCorpusMath.correct(it.outcome) }==true }
        }
        val total=PhoneCorpusMath.fields.associateWith { field -> results.size }
        return GlinerAggregate(
            attempted=results.size,
            eligible=results.count { it.eligible },
            complete=results.count { it.complete },
            oneError=results.count { it.status=="complete" && it.eligible && it.errorCount==1 },
            multiError=results.count { it.status=="complete" && it.eligible && it.errorCount>1 },
            failed=results.count { it.status!="complete" },
            fieldCorrect=correct,
            fieldTotal=total,
            stopped=stopped,
        )
    }

    fun saveCsv(uri:Uri) {
        val snapshot=mutable.value
        viewModelScope.launch(Dispatchers.IO) {
            val rows=snapshot.samples.mapNotNull { it.result }.map { result -> csvRow(result,snapshot.laneConfig) }
            if(rows.isEmpty()) {
                mutable.update { it.copy(message="Run the dataset before exporting") }
                return@launch
            }
            val file=File(app.cacheDir,"gliner-lab-${System.currentTimeMillis()}.csv")
            runCatching {
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    TextLabCsv.write(writer,rows.asSequence())
                } ?: error("Cannot open the selected document")
                mutable.update { it.copy(exports=it.exports+file,message="Exported ${rows.size} rows") }
            }.onFailure { error ->
                mutable.update { it.copy(message="Export failed: ${error.javaClass.simpleName}: ${error.message}") }
            }
        }
    }

    private fun csvRow(result:GlinerLabImageResult,config:GlinerLaneConfig):Map<String,String> {
        val base=linkedMapOf(
            "pipeline" to "gliner-lane-v1",
            "build" to BuildConfig.BUILD_COMMIT,
            "image" to result.image,
            "review_number" to result.reviewNumber.toString(),
            "status" to result.status,
            "message" to result.message,
            "eligible" to result.eligible.toString(),
            "field_errors" to result.errorCount.toString(),
            "model" to result.modelSignature,
            "int4_matmul_nbits" to result.int4.toString(),
            "output_shape" to result.outputShape,
            "output_layout" to result.layout,
            "words" to result.wordCount.toString(),
            "spans" to result.spanCount.toString(),
            "threshold" to config.threshold.toString(),
            "max_width" to config.maxWidth.toString(),
            "lane_prompts" to config.prompts.joinToString(" | "),
            "ocr_ms" to result.ocrMs.toString(),
            "gliner_ms" to result.glinerMs.toString(),
            "post_ms" to result.postMs.toString(),
            "total_ms" to result.totalMs.toString(),
            "producer_lane" to result.laneTexts["producer"].orEmpty(),
            "wine_name_lane" to result.laneTexts["wine_name"].orEmpty(),
            "appellation_lane" to result.laneTexts["appellation"].orEmpty(),
            "residual" to result.residualTexts.joinToString(" | "),
            "ocr_lines" to result.ocrLines.joinToString(" | "),
            "notes" to result.notes.replace('\n',' '),
        )
        PhoneCorpusMath.fields.forEach { field ->
            val outcome=result.fieldResults.firstOrNull { it.field==field }
            base["${field}_expected"]=outcome?.expected.orEmpty()
            base["${field}_observed"]=result.fields[field].orEmpty()
            base["${field}_state"]=result.fieldStates[field].orEmpty()
            base["${field}_outcome"]=outcome?.outcome.orEmpty()
        }
        return base
    }

    override fun onCleared() {
        job?.cancel()
        workFolder.deleteRecursively()
        super.onCleared()
    }
}
