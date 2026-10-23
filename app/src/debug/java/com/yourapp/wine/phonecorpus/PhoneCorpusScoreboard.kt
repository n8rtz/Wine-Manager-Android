package com.yourapp.wine.phonecorpus

import android.content.Context
import org.json.JSONObject
import java.io.File
import com.yourapp.wine.textlab.TextLabCsv

/** Scores only committed predictions. Targets are never supplied to the runner. */
internal class PhoneCorpusScoreboard(context:Context) {
    private val reference=JSONObject(context.assets.open("phone-corpus-v1/scoring.json").bufferedReader().use { it.readText() })
    private val images=reference.getJSONArray("images")
    private val targets=(0 until images.length()).associate { i -> val im=images.getJSONObject(i);im.getString("image") to im }
    data class Board(val headers:List<String>,val rows:List<List<String>>,val text:String,val csv:List<Map<String,String>>,val details:List<List<String>>)
    fun build(predictions:List<JSONObject>,mode:PhoneCorpusRunner.Mode):Board {
        val csv=mutableListOf<Map<String,String>>()
        val names=if(mode==PhoneCorpusRunner.Mode.PRODUCTION) listOf("production-15s") else listOf("lab-combined-60s","lab-guarded-producer","lab-trigram")
        val rows=mutableListOf<List<String>>()
        val headers=listOf("Pipeline","Attempted","Eligible","Complete","One error","2+ errors","Failed","Correct values","Wrong","Missing","Correct blanks","Unsupported","Unresolved","Deadline / partial")
        fun score(name:String,items:List<Pair<String,JSONObject>>) {
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
                        "fallback_choices" to (prediction.optJSONObject("choices")?.optJSONArray(f)?.toString() ?: "[]"))
                }
            }
            val row=listOf(name,items.size.toString(),eligible.toString(),"$complete/$eligible",one.toString(),multi.toString(),failedCount.toString(),
                (counts["correct_value"] ?: 0).toString(),(counts["wrong_value"] ?: 0).toString(),(counts["missing_value"] ?: 0).toString(),
                (counts["correct_blank"] ?: 0).toString(),(counts["unsupported_fill"] ?: 0).toString(),unresolvedCount.toString(),partial.toString())
            rows+=row
            csv+=mapOf("row_type" to "summary")+headers.zip(row).toMap()
        }
        val ids=predictions.map { it.getString("image") }.toSet()
        for(name in names) {
            val items=predictions.map { p ->
                val ps=p.optJSONArray("pipelines")
                val current=ps?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("pipeline")==name } }
                    ?: JSONObject().put("status","failed").put("fields",JSONObject())
                current.put("engine_incomplete",p.optBoolean("engine_incomplete")).put("attempt",p.optInt("attempt")).put("total_image_elapsed_ms",p.optString("total_elapsed_ms"))
                if(p.optString("status")!="complete") current.put("status","failed")
                if(p.optString("error").isNotBlank()) current.put("error",p.optString("error"))
                p.getString("image") to current
            }
            score(name,items)
        }
        val baseline=reference.getJSONObject("baseline")
        fun baselineItems(subset:Set<String>)=subset.sorted().map { im -> im to JSONObject().put("status","complete").put("fields",baseline.getJSONObject(im)) }
        score("Frozen baseline · same attempted photos",baselineItems(ids))
        score("Frozen baseline · full corpus",baselineItems(targets.keys))
        val text=buildString {
            appendLine("PHONE CORPUS · ${predictions.size}/168 photos attempted · $mode")
            appendLine("Blank-aware development reference; 160 fully scorable forms, 8 partly unresolved. Reference judgments are provisional.")
            appendLine("No saved OCR is replayed. Frozen baseline rows are loaded, NOT rerun.")
            appendLine(headers.joinToString(" | "));rows.forEach { appendLine(it.joinToString(" | ")) }
            appendLine("Complete means all six scorable fields correct, including expected blanks. Failures receive no correct-blank credit and count as multi-error on eligible forms.")
            appendLine("Full-run target: at least 128/160 complete and at most 7/160 multi-error. Partial runs do not establish full-corpus acceptance.")
            appendLine("Normal mode uses 15s production scanning. Lab mode uses 60s/engine on full-label crops; do not compare their timings as identical pipelines.")
        }
        val details=csv.filter { it["row_type"]=="field" && it["pipeline"] in names }.groupBy { it["pipeline"] to it["image"] }.values.map { group ->
            listOf("Photo #${group.first()["review_number"]} · ${group.first()["pipeline"]} · ${group.first()["form_status"]}")+PhoneCorpusMath.fields.map { field ->
                val r=group.single { it["field"]==field }
                "$field: ${r["recognized"].orEmpty().ifBlank { "(blank)" }} — ${r["outcome"]}\nExpected: ${r["expected"].orEmpty().ifBlank { r["reference_status"].orEmpty() }}"
            }
        }
        return Board(headers,rows,text,csv,details)
    }
    companion object {
        fun export(board:Board,file:File,metadata:Map<String,String>) {
            file.bufferedWriter().use { w -> TextLabCsv.write(w,board.csv.asSequence().map { metadata+it }) }
        }
    }
}
