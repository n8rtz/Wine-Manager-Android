package com.yourapp.wine.phonecorpus

import android.content.Context
import com.yourapp.wine.diagnostics.PipelineFunnel as F
import com.yourapp.wine.textlab.TextLabCsv
import java.io.File

/** Reads a scored Phone Corpus CSV as a durable result table; it never launches OCR or edits references. */
internal object PhoneCorpusCsvTable {
    private const val MAX_BYTES=64L*1024*1024
    private const val MAX_ROWS=200_000
    private val scoreHeaders=listOf("Pipeline","Attempted","Eligible","Complete","One error","2+ errors","Failed",
        "Correct values","Wrong","Missing","Correct blanks","Unsupported","Unresolved","Deadline / partial")
    data class Imported(val mode:PhoneCorpusRunner.Mode,val board:PhoneCorpusScoreboard.Board,
        val imageCount:Int,val sourceBuild:String,val sourceRunId:String)

    fun read(context:Context,file:File):Imported {
        require(file.isFile && file.length() in 1..MAX_BYTES) { "Choose a scored Phone Corpus CSV no larger than 64 MB" }
        var headers:List<String>?=null
        val records=mutableListOf<Map<String,String>>()
        TextLabCsv.read(file.reader(Charsets.UTF_8)) { cells ->
            if(headers==null) {
                val names=cells.mapIndexed { index,value -> if(index==0) value.removePrefix("\uFEFF") else value }
                require(names.toSet().size==names.size && names.containsAll(listOf("row_type","pipeline"))) { "CSV is missing its Phone Corpus result columns" }
                headers=names
            } else if(cells.size==headers!!.size) {
                if(cells.any(String::isNotEmpty)) records+=headers!!.zip(cells).toMap()
                require(records.size<=MAX_ROWS) { "CSV contains too many rows" }
            } else throw IllegalArgumentException("CSV row has a different number of columns than its header")
        }
        val columns=headers ?: throw IllegalArgumentException("CSV is empty")
        val required=listOf("image","field","recognized","outcome","reference_status","execution_status","attempt")
        require(columns.containsAll(required)) { "Select a scored Phone Corpus results CSV, not a raw diagnostics export" }
        val included=readIncludedImages(context)
        val modeCounts=PhoneCorpusRunner.Mode.entries.associateWith { mode ->
            records.count { it["row_type"]=="field" && it["pipeline"]==mode.pipelineId }
        }
        val mode=modeCounts.maxByOrNull { it.value }?.key
            ?: throw IllegalArgumentException("CSV has no scored field rows for a supported Phone Corpus pipeline")
        require(modeCounts.values.count { it>0 }==1) { "Import one pipeline's scored table at a time" }
        val selectedFields=records.filter { it["row_type"]=="field" && it["pipeline"]==mode.pipelineId }
        require(selectedFields.none { it["image"].orEmpty() !in included }) {
            "This file includes photos outside the active 107-label set. Those review-excluded labels are not imported."
        }
        val byImage=selectedFields.groupBy { it["image"].orEmpty() }
        require(byImage.isNotEmpty()) { "No included photo results were found" }
        for((image,rows) in byImage) {
            require(image.matches(Regex("[0-9a-f]{40}\\.jpg"))) { "Invalid photo identifier in CSV" }
            val fields=rows.map { it["field"].orEmpty() }
            require(fields.size==PhoneCorpusMath.fields.size && fields.toSet()==PhoneCorpusMath.fields.toSet()) {
                "Each imported photo must have exactly one row for each of the six fields ($image)"
            }
        }
        val currentSummaries=records.filter { it["row_type"]=="summary" && it["Pipeline"]==mode.pipelineId }
        require(currentSummaries.size==1 && currentSummaries.single().keys.containsAll(scoreHeaders)) {
            "Exactly one complete scored summary row is required for ${mode.pipelineId}"
        }
        val summaries=records.filter { it["row_type"]=="summary" && it.keys.containsAll(scoreHeaders) }
        val summaryRows=summaries.map { row -> scoreHeaders.map { row[it].orEmpty() } }
        val stepRows=records.filter { it["row_type"]=="pipeline_step" && it["pipeline"]==mode.pipelineId }
        require(stepRows.map { it["step_id"].orEmpty() }.toSet().size==stepRows.size) {
            "Duplicate aggregate pipeline_step rows make the matrix ambiguous"
        }
        val matrix=matrix(stepRows,selectedFields,mode)
        val details=byImage.entries.sortedBy { entry -> entry.value.first()["review_number"]?.toIntOrNull() ?: Int.MAX_VALUE }
            .map { (image,rows) ->
                val first=rows.first()
                listOf("Photo #${first["review_number"].orEmpty()} · ${mode.pipelineId} · ${first["form_status"].orEmpty()}" )+
                    PhoneCorpusMath.fields.map { field ->
                        val row=rows.single { it["field"]==field }
                        "$field: ${row["recognized"].orEmpty().ifBlank { "(blank)" }} — ${row["outcome"].orEmpty()}\n"+
                            "Expected: ${row["expected"].orEmpty().ifBlank { row["reference_status"].orEmpty() }}"
                    }
            }
        val sourceBuild=selectedFields.firstNotNullOfOrNull { it["build"]?.takeIf(String::isNotBlank) } ?: "unknown"
        val runId=selectedFields.firstNotNullOfOrNull { it["run_id"]?.takeIf(String::isNotBlank) } ?: "unknown"
        val text=buildString {
            appendLine("IMPORTED CURATED PHONE CORPUS · ${byImage.size}/${PhoneCorpusRunner.ACTIVE_IMAGE_COUNT} included photos · ${mode.name}")
            appendLine("Imported scored table from build $sourceBuild, run $runId. This is saved result data; OCR was not rerun.")
            appendLine(scoreHeaders.joinToString(" | "));summaryRows.forEach { appendLine(it.joinToString(" | ")) }
            appendLine();appendLine("PIPELINE BOTTLENECK MATRIX · imported aggregate steps")
            appendLine((listOf("Step")+PhoneCorpusMath.fields).joinToString(" | "))
            matrix.forEach { row ->
                val values=if(row.shared!=null) listOf("Shared ${cellLabel(row.shared)}")+List(PhoneCorpusMath.fields.size) { "" }
                    else PhoneCorpusMath.fields.map { field -> row.fields[field]?.let(::cellLabel).orEmpty() }
                appendLine((listOf(row.step.branch+" · "+row.step.title)+values).joinToString(" | "))
            }
        }
        val csv=records
        val board=PhoneCorpusScoreboard.Board(scoreHeaders,summaryRows,text,csv,details,emptyList(),matrix)
        return Imported(mode,board,byImage.size,sourceBuild,runId)
    }

    private fun matrix(stepRows:List<Map<String,String>>,fieldRows:List<Map<String,String>>,
        mode:PhoneCorpusRunner.Mode):List<PhoneCorpusScoreboard.MatrixRow> {
        val attempted=fieldRows.mapNotNull { it["image"] }.distinct().size
        val imported=stepRows.associateBy { it["step_id"].orEmpty() }
        fun count(id:String)=imported[id]?.let { row ->
            PhoneCorpusScoreboard.MatrixCell(row.int("labels_passed"),row.int("labels_blocked"),
                row.int("labels_not_reached"),row.int("labels_not_recorded"),row.int("labels_not_applicable"))
        } ?: PhoneCorpusScoreboard.MatrixCell(0,0,notRecorded=attempted)
        fun visible(step:F.Step)=(!step.labOnly || mode.usesSpatialEvidence) &&
            (!step.productionOnly || !mode.usesSpatialEvidence)
        val mainSteps=F.steps.filter { step ->
            visible(step) && !step.id.startsWith("score.") &&
                (step.id in setOf("mlkit.started","mlkit.completed","mlkit.mapped","paddle.started","paddle.detected","paddle.regions","paddle.lines","paddle.completed","paddle.mapped","extract.assignment") ||
                    step.id.startsWith("producer.") || step.id.startsWith("wine."))
        }
        val rows=mainSteps.map { step ->
            val branch=when {
                step.id.startsWith("mlkit.") -> "OCR table · ML Kit"
                step.id.startsWith("paddle.") -> "OCR table · Paddle"
                else -> "Post-OCR · ${step.branch}"
            }
            PhoneCorpusScoreboard.MatrixRow(step.copy(branch=branch),count(step.id),emptyMap())
        }.toMutableList()
        val knownStepIds=F.steps.map { it.id }.toSet()
        stepRows.filter { it["step_id"].orEmpty() !in knownStepIds }.forEach { row ->
            val id=row["step_id"].orEmpty()
            val step=F.Step(id,row["step_branch"].orEmpty(),row["step"].orEmpty(),row["step_definition"].orEmpty())
            rows+=PhoneCorpusScoreboard.MatrixRow(step,count(id),emptyMap())
        }
        val byField=fieldRows.groupBy { it["field"].orEmpty() }
        val fieldCells=PhoneCorpusMath.fields.associateWith { field ->
            val items=byField[field].orEmpty()
            val notApplicable=items.count { it["reference_status"]=="unresolved" }
            val passed=items.count { it["outcome"] in setOf("correct_value","correct_blank") }
            val failed=(items.size-notApplicable-passed).coerceAtLeast(0)
            PhoneCorpusScoreboard.MatrixCell(passed,failed,notApplicable=notApplicable)
        }
        rows+=PhoneCorpusScoreboard.MatrixRow(
            F.Step("matrix.field.correct","Field stages","Field correct against reference",
                "The imported field outcome matches the saved result row, including correct expected blanks; unresolved references are N/A."),
            null,fieldCells)
        for(id in listOf("score.at_most_one","score.complete")) {
            val step=F.steps.firstOrNull { it.id==id } ?: continue
            val grouped=fieldRows.groupBy { it["image"].orEmpty() }.values
            var passed=0;var failed=0;var notApplicable=0
            for(photo in grouped) {
                val eligible=photo.none { it["reference_status"]=="unresolved" }
                if(!eligible) { notApplicable++;continue }
                val errors=photo.count { it["outcome"] !in setOf("correct_value","correct_blank") }
                val succeeds=if(id=="score.complete") errors==0 else errors<=1
                if(succeeds) passed++ else failed++
            }
            rows+=PhoneCorpusScoreboard.MatrixRow(step,PhoneCorpusScoreboard.MatrixCell(passed,failed,notApplicable=notApplicable),emptyMap())
        }
        return rows
    }

    private fun Map<String,String>.int(key:String):Int {
        val value=this[key]?.toIntOrNull() ?: 0
        require(value>=0) { "Negative pipeline step count in imported CSV" }
        return value
    }
    private fun cellLabel(cell:PhoneCorpusScoreboard.MatrixCell)=
        "${cell.passed}/${cell.failed} · NR ${cell.notReached} · ? ${cell.notRecorded} · N/A ${cell.notApplicable}"

    private fun readIncludedImages(context:Context):Set<String> {
        val selection=org.json.JSONObject(context.assets.open("phone-corpus-v1/evaluation-set.json").bufferedReader().use { it.readText() })
            .getJSONArray("included_images")
        return (0 until selection.length()).map { selection.getJSONObject(it).getString("image") }.toSet().also {
            require(it.size==PhoneCorpusRunner.ACTIVE_IMAGE_COUNT) { "Packaged included-photo set is invalid" }
        }
    }
}
