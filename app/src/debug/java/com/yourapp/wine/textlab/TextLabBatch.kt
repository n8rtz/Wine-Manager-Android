package com.yourapp.wine.textlab

import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

/** Image descriptors contain no expected OCR text. Each image owns its own metadata and row sequence. */
internal data class TextLabImageKey(val id:String,val name:String)
internal class TextLabBatch(images:List<TextLabImageKey>,selected:Set<String>,build:String,detectLines:Boolean,val compareEvidence:Boolean=false,val anchorHybrid:Boolean=false,val repairProducer:Boolean=false) {
    val id:String=UUID.randomUUID().toString()
    val journals=images.mapIndexed { index,image ->
        TextLabJournal(selected,build,detectLines).apply {
            metadata.putAll(mapOf("schema_version" to "2","batch_id" to this@TextLabBatch.id,
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
        appendLine(TextLabWineTable.formText(journals))
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
}
