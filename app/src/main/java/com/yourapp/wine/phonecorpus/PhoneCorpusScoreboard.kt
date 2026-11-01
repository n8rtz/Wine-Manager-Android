package com.yourapp.wine.phonecorpus

import android.content.Context
import org.json.JSONObject
import java.io.File
import com.yourapp.wine.textlab.TextLabCsv
import com.yourapp.wine.diagnostics.PipelineFunnel as F

/** Scores only committed predictions. Targets are never supplied to the runner. */
internal class PhoneCorpusScoreboard(
    context:Context,
    private val assetRoot:String="wine-app-labels-v1",
    savedReference:JSONObject?=null,
    savedSelection:JSONObject?=null,
) {
    private val reference=savedReference ?: JSONObject(context.assets.open("$assetRoot/scoring.json").bufferedReader().use { it.readText() })
    private val images=reference.getJSONArray("images")
    private val targets=(0 until images.length()).associate { i -> val im=images.getJSONObject(i);im.getString("image") to im }
    private val includedImages=run {
        val selection=savedSelection ?: JSONObject(context.assets.open("$assetRoot/evaluation-set.json").bufferedReader().use { it.readText() })
        val list=selection.getJSONArray("included_images")
        (0 until list.length()).map { list.getJSONObject(it).getString("image") }.toSet().also {
            check(it.size==selection.optInt("included_count",list.length()) && it.all { id -> id in targets }) { "Curated evaluation set does not match packaged references" }
        }
    }
    data class MatrixCell(val passed:Int,val failed:Int,val notReached:Int=0,val notRecorded:Int=0,val notApplicable:Int=0) {
        val observed get() = passed+failed
        fun label() = "$passed/$failed · NR $notReached · ? $notRecorded · N/A $notApplicable"
    }
    data class MatrixRow(val step:F.Step,val shared:MatrixCell?,val fields:Map<String,MatrixCell>)
    data class Board(val headers:List<String>,val rows:List<List<String>>,val text:String,val csv:List<Map<String,String>>,val details:List<List<String>>,val funnel:List<F.Count>,val matrix:List<MatrixRow>)
    fun build(predictions:List<JSONObject>,mode:PhoneCorpusRunner.Mode):Board {
        val csv=mutableListOf<Map<String,String>>()
        val funnelLabels=mutableListOf<F.Label>()
        val currentByImage=predictions.associateBy { it.getString("image") }
        val names=listOf(mode.pipelineId)
        val acceptedPipelineIds=mode.acceptedPipelineIds
        val rows=mutableListOf<List<String>>()
        val headers=listOf("Pipeline","Attempted","Eligible","Complete","One error","2+ errors","Failed","Correct values","Wrong","Missing","Correct blanks","Unsupported","Unresolved","Deadline / partial")
        fun score(name:String,items:List<Pair<String,JSONObject>>,provenance:Map<String,String> = emptyMap(),displayName:String=name) {
            var eligible=0;var complete=0;var one=0;var multi=0;var failedCount=0;var partial=0;var unresolvedCount=0
            val counts=mutableMapOf<String,Int>()
            for((image,prediction) in items) {
                val source=targets.getValue(image);val targetFields=source.getJSONObject("fields")
                val ts=PhoneCorpusMath.fields.associateWith { f -> val t=targetFields.getJSONObject(f);val a=t.getJSONArray("accepted")
                    PhoneCorpusMath.Target(t.getString("status"),(0 until a.length()).map { a.getString(it) }.toSet(),t.optString("target")) }
                unresolvedCount+=ts.values.count { it.status=="unresolved" }
                val failed=prediction.optString("status")!="complete"
                if(failed) failedCount++
                if(prediction.optBoolean("deadline_reached") || prediction.optBoolean("engine_incomplete")) partial++
                val fields=prediction.optJSONObject("fields") ?: JSONObject()
                val outcomes=PhoneCorpusMath.fields.associateWith { f -> PhoneCorpusMath.outcome(ts.getValue(f),fields.optString(f),failed) }
                if(name==mode.pipelineId) {
                    val label=PhoneCorpusFunnelJson.read(currentByImage.getValue(image),mode.name,mode.pipelineId)
                    val eligible=PhoneCorpusMath.eligible(ts)
                    val errors=outcomes.values.count { !PhoneCorpusMath.correct(it) }
                    fun scored(passed:Boolean)=if(!eligible) F.Gate(F.State.NOT_APPLICABLE,"partly unresolved reference")
                        else F.Gate(if(passed) F.State.PASSED else F.State.BLOCKED,if(passed) "" else if(failed) "execution failed" else "$errors field error(s)")
                    val fieldScores=PhoneCorpusMath.fields.associate { field ->
                        "score.field.$field" to if(ts.getValue(field).status=="unresolved") F.Gate(F.State.NOT_APPLICABLE,"field reference unresolved")
                            else F.Gate(if(PhoneCorpusMath.correct(outcomes.getValue(field))) F.State.PASSED else F.State.BLOCKED,
                                if(PhoneCorpusMath.correct(outcomes.getValue(field))) "" else outcomes.getValue(field))
                    }
                    funnelLabels+=label.copy(gates=label.gates+fieldScores+mapOf("score.complete" to scored(errors==0),"score.at_most_one" to scored(errors<=1)))
                }
                if(PhoneCorpusMath.eligible(ts)) {
                    eligible++;val errors=outcomes.values.count { !PhoneCorpusMath.correct(it) }
                    when(errors) { 0->complete++;1->one++;else->multi++ }
                }
                val formStatus=if(failed) "execution_failed" else if(!PhoneCorpusMath.eligible(ts)) "partly_unresolved" else when(outcomes.values.count { !PhoneCorpusMath.correct(it) }) { 0->"complete";1->"one_error";else->"multiple_errors" }
                for(f in PhoneCorpusMath.fields) {
                    val outcome=outcomes.getValue(f);counts[outcome]=(counts[outcome] ?: 0)+1
                    csv+=linkedMapOf("row_type" to "field","pipeline" to name,"image" to image,"review_number" to source.getInt("review_number").toString(),
                        "form_status" to formStatus,"field" to f,"reference_status" to ts.getValue(f).status,"expected" to ts.getValue(f).display,"recognized" to fields.optString(f),
                        "outcome" to outcome,"attempt" to prediction.optString("attempt"),"error" to prediction.optString("error"),"engine_incomplete" to prediction.optBoolean("engine_incomplete").toString(),"execution_status" to prediction.optString("status"),"elapsed_ms" to prediction.optString("elapsed_ms"),"total_image_elapsed_ms" to prediction.optString("total_image_elapsed_ms"),
                        "fallback_choices" to (prediction.optJSONObject("choices")?.optJSONArray(f)?.toString() ?: "[]"))+provenance
                }
            }
            val row=listOf(displayName,items.size.toString(),eligible.toString(),"$complete/$eligible",one.toString(),multi.toString(),failedCount.toString(),
                (counts["correct_value"] ?: 0).toString(),(counts["wrong_value"] ?: 0).toString(),(counts["missing_value"] ?: 0).toString(),
                (counts["correct_blank"] ?: 0).toString(),(counts["unsupported_fill"] ?: 0).toString(),unresolvedCount.toString(),partial.toString())
            rows+=row
            csv+=mapOf("row_type" to "summary")+headers.zip(row).toMap()+provenance
        }
        for(name in names) {
            val items=predictions.map { p ->
                val ps=p.optJSONArray("pipelines")
                val current=ps?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("pipeline") in acceptedPipelineIds } }
                    ?: JSONObject().put("status","failed").put("fields",JSONObject())
                current.put("engine_incomplete",p.optBoolean("engine_incomplete")).put("attempt",p.optInt("attempt")).put("total_image_elapsed_ms",p.optString("total_elapsed_ms"))
                if(p.optString("status")!="complete") current.put("status","failed")
                if(p.optString("error").isNotBlank()) current.put("error",p.optString("error"))
                p.getString("image") to current
            }
            score(name,items,displayName=mode.title)
        }
        val funnel=F.aggregate(funnelLabels) { step ->
            (!step.labOnly || mode.usesSpatialEvidence) &&
                (!step.productionOnly || !mode.usesSpatialEvidence)
        }
        val latestFunnelLabels=funnelLabels.groupBy { it.image }.values.map { attempts -> attempts.maxBy { it.attempt } }
        fun matrixCell(labels:List<F.Label>,stepId:String, prerequisite:String?=null):MatrixCell? {
            // Funnel denominators are conditional: only labels that passed a true required predecessor
            // enter the next cohort. Unobserved states remain explicit instead of becoming failures.
            val cohort=if(prerequisite==null) labels else labels.filter { it.gates[prerequisite]?.state==F.State.PASSED }
            if(cohort.isEmpty()) return null
            val gates=cohort.map { it.gates[stepId] ?: F.Gate(F.State.NOT_RECORDED) }
            return MatrixCell(gates.count { it.state==F.State.PASSED },gates.count { it.state==F.State.BLOCKED },
                gates.count { it.state==F.State.NOT_REACHED },gates.count { it.state==F.State.NOT_RECORDED },
                gates.count { it.state==F.State.NOT_APPLICABLE })
        }
        val fieldScoreSteps=PhoneCorpusMath.fields.map { field ->
            F.Step("score.field.$field","Scoring only","Reference-correct: $field",
                "This field's final output matches its frozen reference, including an expected blank; unresolved references are omitted.")
        }
        // Requirements view: OCR execution/usable-output gates, the common guarded assignment,
        // the dependent resolver decision gates, and reference scoring. Intermediate evidence
        // observations/hypothesis counts are diagnostics, not universal requirements.
        val matrixSteps=funnel.map { it.step }.filter { step ->
            step.id in setOf("mlkit.started","mlkit.completed","mlkit.mapped","paddle.started","paddle.detected","paddle.regions","paddle.lines","paddle.completed","paddle.mapped","extract.assignment") ||
                step.id.startsWith("producer.") || step.id.startsWith("wine.") || step.id.startsWith("score.")
        }.filterNot { it.id.startsWith("score.") }+fieldScoreSteps+funnel.map { it.step }.filter { it.id=="score.at_most_one" || it.id=="score.complete" }
        val producerSuffixes=matrixSteps.filter { it.id.startsWith("producer.") }.map { it.id.removePrefix("producer.") }.toSet()
        fun groupKey(step:F.Step):String {
            if(step.id.startsWith("final.") && step.field!=null) return "field.populated"
            if(step.id.startsWith("score.field.")) return "field.correct"
            if(step.id.startsWith("producer.")) return "resolver.${step.id.removePrefix("producer.")}"
            if(step.id.startsWith("wine.") && !step.id.startsWith("wine.producer_")) {
                val suffix=step.id.removePrefix("wine.")
                if(suffix in producerSuffixes) return "resolver.$suffix"
            }
            return "step.${step.id}"
        }
        fun groupTitle(key:String,members:List<F.Step>):String = when(key) {
            "field.populated" -> "Final field populated"
            "field.correct" -> "Field correct against reference"
            else -> when(key.removePrefix("resolver.")) {
                "invoked" -> "Resolver invoked"
                "execution" -> "Resolver completed within budget"
                "primary" -> "Verified producer index available"
                "spans" -> "Span-count bound passed"
                "independent" -> "Two-engine + disjoint agreement"
                "unambiguous" -> "One viable identity/name group"
                "separated" -> "Separated from competing candidates"
                "existing" -> "Existing-value protection passed"
                "selected" -> "Name confirmed / suggested"
                "changed" -> "Field value changed"
                else -> {
                    val parts=key.removePrefix("resolver.").split('.')
                    val engine=parts.firstOrNull()
                    val action=parts.drop(1).joinToString(".")
                    val engineName=when(engine) { "mlkit" -> "ML Kit"; "paddle" -> "Paddle Medium"; else -> null }
                    val actionName=when(action) {
                        "eligible" -> "eligible observed span"
                        "lookup" -> "database lookup completed"
                        "retrieved" -> "reference candidates returned"
                        "distance" -> "candidate passed edit distance"
                        else -> null
                    }
                    if(engineName!=null && actionName!=null) "$engineName · $actionName"
                    else members.single().title
                }
            }
        }
        val groupedSteps=linkedMapOf<String,MutableList<F.Step>>()
        matrixSteps.forEach { step -> groupedSteps.getOrPut(groupKey(step)) { mutableListOf() }+=step }
        val matrix=groupedSteps.map { (key,members) ->
            if(members.size==1 && members.single().field==null) {
                val step=members.single()
                val dependency=if(step.id.startsWith("wine.")) "producer.invoked" else null
                MatrixRow(step.copy(branch=when {
                    step.id.startsWith("mlkit.") -> "OCR table · ML Kit"
                    step.id.startsWith("paddle.") -> "OCR table · Paddle"
                    else -> "Post-OCR · ${step.branch}"
                }),matrixCell(latestFunnelLabels,step.id,dependency),emptyMap())
            } else {
                val fieldMembers=members.filter { it.field!=null }.groupBy { it.field!! }
                val fieldCells=fieldMembers.mapNotNull { (field,steps) ->
                    check(steps.size==1) { "Matrix group $key repeats field $field" }
                    val dependency=if(steps.single().id.startsWith("wine.")) "producer.invoked" else null
                    matrixCell(latestFunnelLabels,steps.single().id,dependency)?.let { field to it }
                }.toMap()
                val title=groupTitle(key,members)
                val description=when(key) {
                    "field.populated" -> "A nonblank final value was produced for this field. Coverage is not correctness."
                    "field.correct" -> "The final field matches its frozen reference, including an expected blank. Unresolved references are omitted."
                    else -> members.joinToString("\n") { "${it.field ?: it.branch}: ${it.definition}" }
                }
                MatrixRow(F.Step("matrix.$key","Field stages",title,description),null,fieldCells)
            }
        }
        csv.addAll(funnel.map { it.csv(mode.pipelineId) })
        val text=buildString {
            appendLine("WINE APP LABELS · ${predictions.size}/${includedImages.size} included photos attempted · $mode")
            appendLine("This test uses the retained owner-reviewed workbook photos and selected internet front-label photos. Club des Sommeliers #14, review #67 and the marked-red workbook rows remain excluded; IMG_2732.jpeg remains excluded.")
            appendLine("Unresolved references remain unscorable; explicitly expected blanks are scored as blanks. These development references are not held-out accuracy.")
            appendLine(headers.joinToString(" | "));rows.forEach { appendLine(it.joinToString(" | ")) }
            appendLine("Complete means all six scorable fields correct, including expected blanks. Failures receive no correct-blank credit and count as multi-error on eligible forms.")
            appendLine("This screen scores the selected ${includedImages.size} curated photos.")
            appendLine("Prod v1.0 uses the Add Wine scanner's shared budget; Prod v2.0 uses the guarded evidence path; Prod v4.0 and Lab v5 use the same current spatial pipeline (original readings plus producer and linked-wine resolution over spatial spans) and differ only in producer data: Prod v4.0 uses the verified LWIN producer index only, Lab v5 uses the Lab expanded producer index (the same LWIN subset plus source-tagged X-Wines, TTB and OpenStreetMap winery candidates). Each mode runs and scores only its selected implementation.")
            appendLine()
            appendLine("PIPELINE BOTTLENECK MATRIX · current selected pipeline only")
            appendLine("Three tables: ML Kit OCR, Paddle OCR (sequential in runner), and post-OCR. Post-OCR evidence hypotheses use joint field assignment; only genuine dependencies are conditional. NR, unrecorded, and N/A remain distinct. Scores are correctness gates, including correct expected blanks.")
            appendLine((listOf("Step")+PhoneCorpusMath.fields).joinToString(" | "))
            matrix.forEach { row ->
                val values=if(row.shared!=null) listOf("Shared ${row.shared.passed}/${row.shared.failed}")+List(PhoneCorpusMath.fields.size) { "" }
                    else PhoneCorpusMath.fields.map { field -> row.fields[field]?.let { "${it.passed}/${it.failed}" }.orEmpty() }
                appendLine((listOf(row.step.branch+" · "+row.step.title)+values).joinToString(" | "))
            }
        }
        val details=csv.filter { it["row_type"]=="field" && it["pipeline"] in names }.groupBy { it["pipeline"] to it["image"] }.values.map { group ->
            listOf("Photo #${group.first()["review_number"]} · ${group.first()["pipeline"]} · ${group.first()["form_status"]}")+PhoneCorpusMath.fields.map { field ->
                val r=group.single { it["field"]==field }
                "$field: ${r["recognized"].orEmpty().ifBlank { "(blank)" }} — ${r["outcome"]}\nExpected: ${r["expected"].orEmpty().ifBlank { r["reference_status"].orEmpty() }}"
            }
        }
        return Board(headers,rows,text,csv,details,funnel,matrix)
    }
    companion object {
        fun export(board:Board,file:File,metadata:Map<String,String>) {
            file.bufferedWriter().use { w -> TextLabCsv.write(w,board.csv.asSequence().map { metadata+it }) }
        }
    }
}
