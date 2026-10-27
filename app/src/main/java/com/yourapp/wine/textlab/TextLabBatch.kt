package com.yourapp.wine.textlab

import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

/** Image descriptors contain no expected OCR text. Each image owns its own metadata and row sequence. */
internal data class TextLabImageKey(val id:String,val name:String)
internal data class TextLabSavedBatch(val id:String,val build:String,val status:String,val journals:List<TextLabJournal>) {
    fun summary():String=buildString {
        appendLine("Saved OCR Text Lab run · $id · $status · ${journals.size} images · build $build")
        appendLine(TextLabStats.text(journals))
        appendLine(TextLabWineTable.formText(journals))
        appendLine(TextLabResidualPreview.text(journals))
        appendLine(TextLabWineTable.text(journals))
        journals.forEach { journal ->
            appendLine("\nImage ${journal.metadata["image_index"]}: ${journal.metadata["image_name"]} · ${journal.metadata["image_summary_status"] ?: "checkpointed"}")
            journal.rows.filter { it["stage"] in setOf("candidate_summary","pass_summary","error","lm_fallback") }.forEach { row ->
                if(length>50_000) { appendLine("\nOn-screen summary truncated. Export the CSV for complete diagnostics.");return@buildString }
                appendLine("${row["candidate"]} · ${row["stage"]} · ${row["status"]}"+
                    (row["pass_id"]?.let { " · $it" } ?: "")+
                    (row["elapsed_ms"]?.let { " · $it ms" } ?: "")+
                    (row["details"]?.takeIf(String::isNotEmpty)?.let { " · $it" } ?: ""))
            }
        }
        appendLine("\nExport this run's CSV for raw OCR text, coordinates, timings and full diagnostics.")
    }
}
/** The benchmark flags are set only by the phone-corpus runner when one shared OCR pass must
 * feed several benchmark contenders for a photo (see PhoneCorpusRunner.runImageAllModes):
 * with [benchmarkAllPipelines] set, [benchmarkEvidence], [benchmarkLwinSpatial] and
 * [spatialPhrases] independently select which contender summaries are appended to each
 * journal (Prod v2.0 evidence, Prod v3.0 spatial with LWIN-only producers, Lab v4 spatial
 * with the expanded producer index). Every other caller keeps the legacy single-extractor
 * behavior. */
internal class TextLabBatch(images:List<TextLabImageKey>,selected:Set<String>,build:String,detectLines:Boolean,val compareEvidence:Boolean=false,val anchorHybrid:Boolean=false,val repairProducer:Boolean=false,val compareGazetteer:Boolean=false,val residualCandidates:Boolean=false,val spatialPhrases:Boolean=false,val benchmarkOnly:Boolean=false,val benchmarkAllPipelines:Boolean=false,val benchmarkEvidence:Boolean=false,val benchmarkLwinSpatial:Boolean=false,val repairPlaces:Boolean=false,val resolverSingleReading:Boolean=false) {
    val id:String=UUID.randomUUID().toString()
    val journals=images.mapIndexed { index,image ->
        TextLabJournal(selected,build,detectLines).apply {
            metadata.putAll(mapOf("spatial_phrases_enabled" to spatialPhrases.toString(),"benchmark_selected_only" to benchmarkOnly.toString(),"benchmark_all_pipelines" to benchmarkAllPipelines.toString(),"benchmark_evidence" to benchmarkEvidence.toString(),"benchmark_lwin_spatial" to benchmarkLwinSpatial.toString(),"residual_enabled" to residualCandidates.toString(),"place_repair_enabled" to repairPlaces.toString(),"resolver_single_reading_enabled" to resolverSingleReading.toString(),"gazetteer_enabled" to compareGazetteer.toString(),"schema_version" to "2","batch_id" to this@TextLabBatch.id,
                "image_id" to image.id,"image_name" to image.name,"image_index" to (index+1).toString(),"image_count" to images.size.toString(),"batch_status" to "running","extraction_mode" to if(compareEvidence && anchorHybrid && repairProducer) "baseline_and_producer_repair_v2" else if(compareEvidence && anchorHybrid) "baseline_and_anchor_hybrid_v1" else if(compareEvidence) "baseline_and_evidence_v1" else "baseline"))
            add("shared","image_queued","queued")
        }
    }
    fun finishImage(index:Int,status:String) {
        val journal=journals[index]
        if(journal.rows.any { it["stage"]=="image_summary" }) return
        journal.finishPending(if(status.startsWith("not_run_")) status else "not_run_$status")
        journal.add("shared","image_summary",status)
    }
    fun finishPending(status:String) {
        journals.indices.forEach { index ->
            if(journals[index].rows.none { it["stage"]=="image_summary" }) finishImage(index,"not_run_$status")
        }
    }
    fun writeCsv(writer:Writer) = TextLabCsv.write(writer,journals.asSequence().flatMap { journal ->
        journal.rows.asSequence().map { journal.metadata+it }
    } + TextLabStats.rows(journals).asSequence().map { stats ->
        mapOf("batch_id" to id,"build" to journals.firstOrNull()?.build.orEmpty())+stats
    })
    fun save(folder:File):File {
        check(folder.isDirectory || folder.mkdirs())
        val temp=File(folder,"$id.tmp");val target=File(folder,"$id.csv")
        try {
            temp.bufferedWriter(Charsets.UTF_8).use(::writeCsv)
            try { Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(_:AtomicMoveNotSupportedException) { Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING) }
            return target
        } finally { temp.delete() }
    }
    fun summary():String=buildString {
        appendLine(TextLabStats.text(journals))
        appendLine(TextLabWineTable.formText(journals))
        appendLine(TextLabResidualPreview.text(journals))
        appendLine(TextLabWineTable.text(journals))
        appendLine("OCR Text Lab · batch $id · ${journals.size} images")
        journals.forEach { journal ->
            appendLine("\nImage ${journal.metadata["image_index"]}: ${journal.metadata["image_name"]}")
            appendLine("Image ID: ${journal.metadata["image_id"]}; test: ${journal.id}")
            journal.rows.filter { it["stage"] in setOf("image_summary","candidate_summary","pass_summary","error","lm_fallback","recognition") }.forEach { row ->
                if(length>50_000) {
                    appendLine("\nOn-screen summary truncated. Export the CSV for the complete batch.")
                    return@buildString
                }
                appendLine("${row["candidate"]} · ${row["stage"]} · ${row["status"]}"+
                    (row["pass_id"]?.let { " · $it" } ?: "")+
                    (row["elapsed_ms"]?.let { " · $it ms" } ?: "")+
                    (row["raw_text"]?.let { " · $it" } ?: "")+
                    (row["details"]?.takeIf(String::isNotEmpty)?.let { " · $it" } ?: ""))
            }
        }
        appendLine("\nExport last batch CSV for complete per-image timings, coordinates, model IDs and diagnostics.")
    }

    companion object {
        private val viewStages=setOf("image_summary","candidate_summary","pass_summary","error","lm_fallback","recognition","field_summary","form_summary","evidence_summary","gazetteer_summary","spatial_evidence_summary","spatial_lwin_evidence_summary","residual_summary")

        /** Rebuilds a read-only in-app view from a checkpoint or completed batch CSV. */
        fun readSaved(file:File):TextLabSavedBatch {
            require(file.isFile && file.extension.equals("csv",ignoreCase=true)) { "Saved OCR Text Lab CSV not found" }
            var headers:List<String>?=null
            var batchId=file.nameWithoutExtension
            var build="unknown"
            var status="checkpointed"
            val journals=linkedMapOf<String,TextLabJournal>()
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                TextLabCsv.read(reader) { cells ->
                    if(headers==null) {
                        headers=cells.mapIndexed { index,value -> if(index==0) value.removePrefix("\uFEFF") else value }
                    } else {
                        val row=headers.orEmpty().mapIndexedNotNull { index,key -> key.takeIf(String::isNotEmpty)?.let { it to cells.getOrElse(index) { "" } } }.toMap()
                        val testId=row["test_id"].orEmpty()
                        if(testId.isNotEmpty()) {
                            batchId=row["batch_id"]?.takeIf(String::isNotEmpty) ?: batchId
                            build=row["build"]?.takeIf(String::isNotEmpty) ?: build
                            status=row["batch_status"]?.takeIf(String::isNotEmpty) ?: status
                            val journal=journals.getOrPut(testId) {
                                val selected=row["selected_candidates"].orEmpty().split(Regex("\\s+")).map(String::trim).filter(String::isNotEmpty).toSet()
                                TextLabJournal(selected,build,row["detection_enabled"]=="true")
                            }
                            journal.metadata.putAll(row.filterKeys { it in setOf("batch_id","image_id","image_name","image_index","image_count","batch_status") })
                            val stage=row["stage"].orEmpty()
                            if(stage in viewStages) {
                                journal.rows+=row
                                if(stage=="image_summary") journal.metadata["image_summary_status"]=row["status"].orEmpty()
                            }
                        }
                    }
                }
            }
            require(journals.isNotEmpty()) { "No per-image OCR rows were found in this saved CSV" }
            val ordered=journals.values.sortedWith(compareBy({ it.metadata["image_index"]?.toIntOrNull() ?: Int.MAX_VALUE },{ it.metadata["image_name"].orEmpty() }))
            return TextLabSavedBatch(batchId,build,status,ordered)
        }
    }
}
