package com.yourapp.wine.gliner

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * GLiNER-Small (uni-encoder span architecture) on ONNX Runtime.
 *
 * The graph contract is the export produced by `GLiNER.export_to_onnx()`: word-level `input_ids`
 * with the `ent_token / label / sep_token` prompt in front of the label text, `words_mask` mapping
 * subword tokens to 1-based word indices (0 for prompt and continuation subtokens), `text_lengths`,
 * and an enumerated `span_idx` / `span_mask` over every word span up to `max_width`.
 *
 * Because the export has changed shape between GLiNER releases, the output layout is detected from
 * the real tensor shape and reported back to the screen instead of being assumed:
 *  - `[1, spans, classes]`        span-scored logits (the gliner_small-v2.1 export)
 *  - `[1, words, classes, 3]`     token start / end / inside logits
 *  - `[1, classes, spans]`        transposed span logits
 * Anything else fails closed with the shape and the expected span/class counts in the message.
 */
class GlinerOnnxModel private constructor(
    private val session:OrtSession,
    private val tokenizer:GlinerTokenizer,
    private val config:GlinerConfig,
    private val laneConfig:GlinerLaneConfig,
    private val outputName:String,
    private val outputType:OnnxJavaType,
    private val inputTypes:Map<String,OnnxJavaType>,
    val signature:String,
    val int4:Boolean,
) : AutoCloseable {

    val inputSignature:String = session.inputNames.sorted().joinToString(", ")
    val tokenizerDescription:String = tokenizer.describe()

    /** Runs one label through the full prompt → span → lane pipeline. */
    suspend fun label(document:GlinerDocument,deadlineAtMs:Long):GlinerLabelResult = withContext(Dispatchers.Default) {
        val started=SystemClock.elapsedRealtime()
        val limited=document.limited(minOf(laneConfig.maxWords,config.maxLen))
        val words=limited.words
        require(words.isNotEmpty()) { "No usable words in the OCR document" }
        if(SystemClock.elapsedRealtime()>=deadlineAtMs) error("GLiNER deadline reached before inference")

        val promptIds=promptTokens(tokenizer,config,laneConfig.prompts)
        val textIds=words.map { tokenizer.encodeWord(it.normalized) }
        val maxWidth=minOf(laneConfig.maxWidth,config.maxWidth)
        val spans=spanIndex(words.size,maxWidth)
        val classCount=laneConfig.prompts.size

        val ids=mutableListOf<Int>()
        val wordsMask=mutableListOf<Int>()
        val clsId=tokenizer.tokenId("[CLS]") ?: tokenizer.tokenId("<s>")
        val sepId=tokenizer.tokenId("[SEP]") ?: tokenizer.tokenId("</s>")
        if(clsId!=null) { ids += clsId;wordsMask += 0 }
        promptIds.forEach { ids += it;wordsMask += 0 }
        textIds.forEachIndexed { index,pieces ->
            pieces.forEachIndexed { sub,token ->
                ids += token
                wordsMask += if(sub==0) index+1 else 0
            }
        }
        if(sepId!=null) { ids += sepId;wordsMask += 0 }

        val length=ids.size
        val spanCount=spans.size/2
        val inputs=linkedMapOf<String,OnnxTensor>()
        try {
            inputTypes.forEach { (name,type) ->
                when(name) {
                    "input_ids" -> inputs[name]=longTensor(name,type,ids.map(Int::toLong).toLongArray(),longArrayOf(1,length.toLong()))
                    "attention_mask" -> inputs[name]=longTensor(name,type,LongArray(length) { 1L },longArrayOf(1,length.toLong()))
                    "words_mask" -> inputs[name]=longTensor(name,type,wordsMask.map(Int::toLong).toLongArray(),longArrayOf(1,length.toLong()))
                    "text_lengths" -> inputs[name]=longTensor(name,type,longArrayOf(words.size.toLong()),longArrayOf(1,1))
                    "span_idx" -> inputs[name]=longTensor(name,type,spans.map(Int::toLong).toLongArray(),longArrayOf(1,spanCount.toLong(),2))
                    "span_mask" -> inputs[name]=longTensor(name,type,
                        LongArray(spanCount) { if(spans[it*2+1]<words.size) 1L else 0L },
                        longArrayOf(1,spanCount.toLong()))
                    else -> error("Unsupported GLiNER graph input '$name'. Expected input_ids, attention_mask, words_mask, text_lengths, span_idx, span_mask")
                }
            }
            if(SystemClock.elapsedRealtime()>=deadlineAtMs) error("GLiNER deadline reached before inference")
            session.run(inputs).use { output ->
                // ORT 1.21 exposes positional access only; the graph is a single-output export.
                val tensor=output[0] as? OnnxTensor
                    ?: error("GLiNER output '$outputName' is not a tensor")
                val shape=tensor.info.shape
                val buffer=tensor.getFloatBuffer()
                    ?: error("GLiNER output tensor holds ${tensor.info.type}, which is not float32 logits")
                val flat=FloatArray(buffer.remaining())
                buffer.get(flat)
                val decoded=decode(flat,shape,classCount,words.size,spanCount,spans,limited,promptIds.take(12))
                val entities=withGeometry(decoded.candidates,limited)
                val elapsed=SystemClock.elapsedRealtime()-started
                val lanes=GlinerLane.ordered.map { lane ->
                    GlinerLaneGroup(lane,laneConfig.promptFor(lane),entities.filter { it.lane==lane },decoded.rejected)
                }
                GlinerLabelResult(
                    document=limited,
                    lanes=lanes,
                    residualLines=residual(limited,entities),
                    entities=entities,
                    modelSignature=signature,
                    int4=int4,
                    inputSignature=inputSignature,
                    outputShape=shape.contentToString(),
                    layout=decoded.layout,
                    wordCount=words.size,
                    spanCount=spanCount,
                    elapsedMs=elapsed,
                    config=laneConfig,
                    probes=decoded.probes,
                    promptIds=promptIds,
                    unknownTokens=ids.count { it==tokenizer.unknownId },
                )
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun longTensor(name:String,type:OnnxJavaType,values:LongArray,shape:LongArray):OnnxTensor {
        val env=OrtEnvironment.getEnvironment()
        return when(type) {
            OnnxJavaType.INT64 -> {
                val buffer=ByteBuffer.allocateDirect(values.size*8).order(ByteOrder.nativeOrder())
                val longs=buffer.asLongBuffer();longs.put(values);longs.rewind()
                OnnxTensor.createTensor(env,longs,shape)
            }
            OnnxJavaType.INT32 -> {
                val narrowed=IntArray(values.size) { values[it].toInt() }
                val buffer=ByteBuffer.allocateDirect(narrowed.size*4).order(ByteOrder.nativeOrder())
                val ints=buffer.asIntBuffer();ints.put(narrowed);ints.rewind()
                OnnxTensor.createTensor(env,ints,shape)
            }
            OnnxJavaType.BOOL -> {
                val buffer=ByteBuffer.allocateDirect(values.size).order(ByteOrder.nativeOrder())
                values.forEach { buffer.put(if(it!=0L) 1 else 0) }
                buffer.rewind()
                OnnxTensor.createTensor(env,buffer,shape,OnnxJavaType.BOOL)
            }
            OnnxJavaType.FLOAT -> {
                val floats=FloatArray(values.size) { values[it].toFloat() }
                val buffer=ByteBuffer.allocateDirect(floats.size*4).order(ByteOrder.nativeOrder())
                val view=buffer.asFloatBuffer();view.put(floats);view.rewind()
                OnnxTensor.createTensor(env,view,shape)
            }
            else -> error("GLiNER input '$name' declares unsupported tensor type $type")
        }
    }

    /**
     * The prompt: `ent_token, label words, ent_token, label words, …, sep_token`.
     *
     * The markers must be the ids `tokenizer.json` gives them (`<<ENT>>` -> 128002), not their
     * SentencePiece pieces: the exported graph counts the lane classes from those marker ids, and a
     * text-tokenized marker makes it emit a `[1, words, max_width, 0]` tensor with no classes at
     * all. Labels are tokenized as whole phrases; the sequence is wrapped in CLS/SEP once.
     */
    private fun promptTokens(tokenizer:GlinerTokenizer,config:GlinerConfig,prompts:List<String>):List<Int> {
        val entId=tokenizer.tokenId(config.entToken)
            ?: error("Prompt marker '${config.entToken}' from gliner_config.json is not in tokenizer.json")
        val sepId=tokenizer.tokenId(config.sepToken)
            ?: error("Prompt marker '${config.sepToken}' from gliner_config.json is not in tokenizer.json")
        val ids=mutableListOf<Int>()
        prompts.forEach { prompt ->
            ids += entId
            ids += tokenizer.encodeWord(prompt)
        }
        ids += sepId
        return ids
    }

    /** First ids of the encoded prompt, for the failure report when a graph rejects the feed. */
    fun promptPreview():List<Int> = promptTokens(tokenizer,config,laneConfig.prompts).take(24)

    /**
     * Span grid: every start position with every width 1..maxWidth, inclusive end index, the end
     * clipped at the last word. The exported graph reshapes the grid to (words, maxWidth, ...), so
     * the list has to be exactly `words * maxWidth` long; the spans that run past the last word are
     * kept in the grid and skipped by [decode], which is what the model was exported against.
     */
    private fun spanIndex(words:Int,maxWidth:Int):IntArray {
        val spans=ArrayList<Int>(words*maxWidth*2)
        for(start in 0 until words) {
            for(offset in 0 until maxWidth) {
                spans += start
                spans += start+offset
            }
        }
        return spans.toIntArray()
    }

    private data class Candidate(val lane:GlinerLane,val start:Int,val end:Int,val score:Float)

    private data class Decoded(
        val candidates:List<GlinerEntity>,
        val rejected:Int,
        val layout:String,
        val probes:List<String> = emptyList(),
    )

    /**
     * Turns raw logits into accepted, non-overlapping entities. `flat` prevents one word from
     * belonging to two lanes, which is what keeps a producer name out of the wine-name lane.
     */
    private fun decode(
        buffer:FloatArray,
        shape:LongArray,
        classCount:Int,
        words:Int,
        spanCount:Int,
        spans:IntArray,
        document:GlinerDocument?=null,
        promptIdsPreview:List<Int> = emptyList(),
    ):Decoded {
        val dims=shape.map { it.toInt() }
        val values=buffer
        val maxWidth=minOf(laneConfig.maxWidth,config.maxWidth)
        val candidates=mutableListOf<Candidate>()
        val scored=mutableListOf<Candidate>()
        val layout:String
        when {
            dims.size==2 && dims[0]==spanCount && dims[1]==classCount -> {
                layout="span logits [spans, classes]"
                for(span in 0 until spanCount) for(label in 0 until classCount) {
                    val probability=sigmoid(values[span*classCount+label])
                    if(probability>=laneConfig.threshold) candidates += Candidate(GlinerLane.ordered[label],spans[span*2],spans[span*2+1],probability)
                }
            }
            dims.size==3 && dims[0]==1 && dims[1]==spanCount && dims[2]==classCount -> {
                layout="span logits [1, spans, classes]"
                for(span in 0 until spanCount) for(label in 0 until classCount) {
                    val probability=sigmoid(values[span*classCount+label])
                    if(probability>=laneConfig.threshold) candidates += Candidate(GlinerLane.ordered[label],spans[span*2],spans[span*2+1],probability)
                }
            }
            dims.size==3 && dims[0]==1 && dims[1]==classCount && dims[2]==spanCount -> {
                layout="span logits [1, classes, spans]"
                for(label in 0 until classCount) for(span in 0 until spanCount) {
                    val probability=sigmoid(values[label*spanCount+span])
                    if(probability>=laneConfig.threshold) candidates += Candidate(GlinerLane.ordered[label],spans[span*2],spans[span*2+1],probability)
                }
            }
            dims.size==4 && dims[0]==1 && dims[1]==spanCount && dims[2]==classCount && dims[3]==1 -> {
                layout="span logits [1, spans, classes, 1]"
                val stride=classCount*maxOf(1,dims[3])
                for(span in 0 until spanCount) for(label in 0 until classCount) {
                    val probability=sigmoid(values[span*stride+label])
                    if(probability>=laneConfig.threshold) candidates += Candidate(GlinerLane.ordered[label],spans[span*2],spans[span*2+1],probability)
                }
            }
            dims.size==4 && dims[0]==1 && dims[1]==words && dims[2]==maxWidth && dims[3]==classCount -> {
                // What gliner 0.2.29 actually exports: one logit per (start, width, lane).
                layout="span logits [1, words, max_width, classes]"
                for(label in 0 until classCount) {
                    val lane=GlinerLane.ordered[label]
                    for(start in 0 until words) {
                        for(width in 1..maxWidth) {
                            val end=start+width-1
                            if(end>=words) continue
                            val probability=sigmoid(values[((start*maxWidth)+width-1)*classCount+label])
                            val candidate=Candidate(lane,start,end,probability)
                            scored += candidate
                            if(probability>=laneConfig.threshold) candidates += candidate
                        }
                    }
                }
            }
            dims.size==4 && dims[0]==1 && dims[1]==words && dims[2]==classCount && dims[3]==3 -> {
                layout="token start/end/inside logits [1, words, classes, 3]"
                for(label in 0 until classCount) {
                    val lane=GlinerLane.ordered[label]
                    for(start in 0 until words) {
                        val startProbability=sigmoid(values[(start*classCount+label)*3])
                        if(startProbability<laneConfig.threshold) continue
                        for(width in 1..maxWidth) {
                            val end=start+width-1
                            if(end>=words) break
                            val endProbability=sigmoid(values[(end*classCount+label)*3+1])
                            if(endProbability<laneConfig.threshold) continue
                            val inside=sigmoid(values[(end*classCount+label)*3+2])
                            val score=(Math.sqrt((startProbability*endProbability).toDouble())*(0.5+0.5*inside))
                                .toFloat().coerceIn(0f,1f)
                            if(score>=laneConfig.threshold) candidates += Candidate(lane,start,end,score)
                        }
                    }
                }
            }
            dims.lastOrNull()==0 -> error(
                "GLiNER produced zero lane classes ${shape.contentToString()}: the prompt markers were not "+
                    "tokenized with the ent/sep ids from gliner_config.json. Prompt ids were "+
                    "$promptIdsPreview.")
            else -> error("Unrecognized GLiNER output shape ${shape.contentToString()}; expected "+
                "$spanCount spans and $classCount lane classes over $words words")
        }
        val probes=GlinerLane.ordered.mapNotNull { lane ->
            scored.filter { it.lane==lane }.maxByOrNull { it.score }?.let { best ->
                val text=document?.words?.drop(best.start)?.take(best.end-best.start+1)
                    ?.joinToString(" ") { it.normalized }
                "%s %.3f %s".format(java.util.Locale.ROOT,lane.shortName,best.score,text.orEmpty())
            }
        }
        val accepted=mutableListOf<GlinerEntity>()
        val used=BooleanArray(words)
        var rejected=0
        candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.start }.thenBy { it.end })
            .forEach { candidate ->
                // The span grid is unclipped, so a candidate can end past the last word; the model
                // masks those spans out, and any that still score are dropped here.
                if(candidate.end>=words || candidate.start<0) { rejected++;return@forEach }
                if((candidate.start..candidate.end).any { used[it] }) { rejected++;return@forEach }
                for(index in candidate.start..candidate.end) used[index]=true
                accepted += GlinerEntity(candidate.lane,candidate.start,candidate.end,"",candidate.score,
                    RectF(),emptyList(),laneConfig.promptFor(candidate.lane))
            }
        return Decoded(accepted,rejected,layout,probes)
    }

    private fun sigmoid(value:Float):Float {
        val v=value.toDouble()
        val p=when {
            v>=0 -> 1.0/(1.0+exp(-v))
            else -> { val e=exp(v);e/(1.0+e) }
        }
        return p.toFloat().coerceIn(0f,1f)
    }

    private fun residual(document:GlinerDocument,entities:List<GlinerEntity>):List<GlinerResidualLine> {
        val covered=BooleanArray(document.words.size)
        entities.forEach { entity -> for(index in entity.wordStart..entity.wordEnd) if(index in covered.indices) covered[index]=true }
        return document.words.indices.groupBy { document.words[it].lineIndex }.mapNotNull { (line,indices) ->
            val kept=indices.filterNot { covered[it] }
            if(kept.isEmpty()) null else GlinerResidualLine(line,kept.joinToString(" ") { document.words[it].text },
                GlinerDocument.union(kept.map { document.words[it].box }),kept.size)
        }
    }

    override fun close() { runCatching { session.close() } }

    companion object {
        /** Opens the bundle once. Throws with an actionable message when a file is missing or wrong. */
        suspend fun open(context:Context,status:GlinerBundleStatus,laneConfig:GlinerLaneConfig):GlinerOnnxModel =
            withContext(Dispatchers.Default) {
                val modelFile=status.model ?: error(status.summary())
                val tokenizerFile=status.tokenizer ?: error("tokenizer.json is missing from the GLiNER bundle")
                val tokenizer=runCatching { GlinerTokenizer.parse(tokenizerFile.readText()) }
                    .getOrElse { throw IllegalStateException("tokenizer.json is not a Hugging Face fast-tokenizer file: ${it.message}",it) }
                val config=status.config?.let { file -> runCatching { GlinerConfig.parse(file.readText()) }.getOrNull() }
                    ?: GlinerConfig("[ENT]","[SEP]",12,384,-1,"missing-config",org.json.JSONObject())
                val session=OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    OrtEnvironment.getEnvironment().createSession(modelFile.path,options)
                }
                try {
                    val types=linkedMapOf<String,OnnxJavaType>()
                    session.inputInfo.forEach { (name,value) ->
                        val info=value.info as? TensorInfo ?: error("GLiNER input '$name' is not a tensor")
                        types[name]=info.type
                    }
                    check("input_ids" in types && "attention_mask" in types) {
                        "GLiNER graph needs input_ids and attention_mask; found ${types.keys.sorted()}"
                    }
                    check(session.outputNames.isNotEmpty()) { "GLiNER graph declares no outputs" }
                    val outputName=session.outputNames.first()
                    val outputInfo=session.outputInfo.getValue(outputName).info as? TensorInfo
                        ?: error("GLiNER output is not a tensor")
                    check(outputInfo.type==OnnxJavaType.FLOAT) {
                        "GLiNER output must be FLOAT32 logits; got ${outputInfo.type}. "+
                            "INT4 weight quantization keeps float32 activations, so a half-precision export is not supported."
                    }
                    val prefs=context.getSharedPreferences("gliner-lab-bundle-v1",Context.MODE_PRIVATE)
                    GlinerOnnxModel(session,tokenizer,config,laneConfig,outputName,outputInfo.type,types,
                        signature="${modelFile.name}:${GlinerBundle.sha256(prefs,modelFile).take(12)}",
                        int4=status.int4)
                } catch(error:Throwable) {
                    runCatching { session.close() }
                    throw IllegalStateException("GLiNER graph rejected: ${error.message}",error)
                }
            }

        /**
         * Resolves a decoded span back to text and geometry. Kept separate from tensor work so the
         * same mapping is used by the screen preview and by post-processing records.
         */
        fun withGeometry(entities:List<GlinerEntity>,document:GlinerDocument):List<GlinerEntity> =
            entities.mapNotNull { entity ->
                if(entity.wordStart !in document.words.indices || entity.wordEnd !in document.words.indices) return@mapNotNull null
                val words=document.words.subList(entity.wordStart,entity.wordEnd+1)
                GlinerEntity(entity.lane,entity.wordStart,entity.wordEnd,
                    words.joinToString(" ") { it.text }.trim(),entity.score,
                    GlinerDocument.union(words.map { it.box }),
                    words.map { it.lineIndex }.distinct().sorted(),entity.prompt)
            }.filter { it.text.isNotBlank() }
    }
}
