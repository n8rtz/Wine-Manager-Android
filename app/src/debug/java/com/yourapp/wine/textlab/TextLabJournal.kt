package com.yourapp.wine.textlab

import java.io.Writer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID

/** One immutable row per observation; summary rows exist even for failure/cancellation/no detections.
 * Worker-confined during inference. UI/export reads only after the worker has exited.
 */
internal class TextLabJournal(val selected:Set<String>,val build:String,val detectLines:Boolean) {
    val id:String=UUID.randomUUID().toString()
    val metadata=linkedMapOf("schema_version" to "1","test_id" to id,"build" to build,
        "started_at_ms" to System.currentTimeMillis().toString(),"selected_candidates" to selected.sorted().joinToString(" "),
        "mode" to if(detectLines) "det_line_rec" else "manual_line_rec",
        "candidate_budget_ms" to TextLabBudgets.OCR_MS.toString(),"mlkit_budget_ms" to TextLabBudgets.OCR_MS.toString(),"session_policy" to "fresh per candidate; file/OS caches may be warm",
        "runtime" to "ONNX Runtime Android 1.21.1 CPU; intra=2 inter=1",
        "detection_enabled" to detectLines.toString(),"detector_max_edge" to "960","recognizer_max_width" to "1024",
        "max_lines" to "32","max_components" to "4096","max_contour_candidates" to "128")
    val rows=mutableListOf<Map<String,String>>()
    fun add(candidate:String,stage:String,status:String="complete",values:Map<String,String> = emptyMap(),details:String="") {
        val spec=PaddleCandidates.all.firstOrNull { it.id==candidate }
        val models=if(candidate==IndraSpec.ID) IndraSpec.metadata else if(spec==null) emptyMap() else mapOf("detector" to spec.detector.name,"detector_sha256" to spec.detector.sha,
            "detector_bytes" to spec.detector.bytes.toString(),"recognizer_bytes" to spec.recognizer.bytes.toString(),
            "recognizer" to spec.recognizer.name,"recognizer_sha256" to spec.recognizer.sha,"dictionary_sha256" to spec.dictionary.sha,
            "dictionary_entries" to spec.dictionary.entries.toString(),"db_threshold" to spec.threshold.toString(),
            "db_box_threshold" to spec.boxThreshold.toString(),"db_unclip" to spec.unclip.toString())
        rows += models+values+mapOf("sequence" to (rows.size+1).toString(),"candidate" to candidate,
            "candidate_order" to TextLabChoices.selected(selected).indexOfFirst { it.id==candidate }.let { if(it<0) "" else (it+1).toString() },
            "stage" to stage,"status" to status,"event_at_ms" to System.currentTimeMillis().toString(),"details" to details)
    }
    fun finishPending(status:String) {
        for(candidate in TextLabChoices.selected(selected)) {
            if(rows.none { it["candidate"]==candidate.id && it["stage"]=="candidate_summary" })
                add(candidate.id,"candidate_summary",status,details="No completed candidate summary; inspect earlier rows for partial results.")
        }
    }
    fun text():String=buildString {
        appendLine("OCR Text Lab · build $build · test $id")
        metadata.forEach { (key,value) -> appendLine("$key: $value") }
        PaddleCandidates.selected(selected).forEach { spec ->
            appendLine("\n${spec.title}\nDetector SHA-256: ${spec.detector.sha}\nRecognizer SHA-256: ${spec.recognizer.sha}")
        }
        rows.filter { it["stage"] !in setOf("line_start","line_report") }.forEach { row ->
            appendLine("\n${row["candidate"]} · ${row["stage"]} · ${row["status"]}")
            row.filterKeys { it !in setOf("candidate","stage","status","details","detector_sha256","recognizer_sha256","dictionary_sha256","detector","recognizer","dictionary_entries","detector_bytes","recognizer_bytes") }.forEach { (key,value) -> appendLine("$key: $value") }
            if(!row["details"].isNullOrBlank()) appendLine(row["details"])
        }
    }
    fun save(folder:File):File {
        check(folder.isDirectory || folder.mkdirs())
        val temp=File(folder,"$id.tmp");val target=File(folder,"$id.csv")
        try {
            temp.bufferedWriter(Charsets.UTF_8).use(::writeCsv)
            try {
                Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            } catch(_:AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally { temp.delete() }
    }
    fun writeCsv(writer:Writer) = TextLabCsv.write(writer,rows.asSequence().map { metadata+it })
}

/** Shared CSV encoder: metadata is attached to each row, never taken from the last batch image. */
internal object TextLabCsv {
    fun write(writer:Writer,records:Sequence<Map<String,String>>) {
        val headers=linkedSetOf<String>()
        records.forEach { headers.addAll(it.keys) }
        fun cell(value:String):String {
            val safe=if(value.trimStart().firstOrNull() in listOf('=','+','-','@') || value.startsWith('\t') || value.startsWith('\r')) "'$value" else value
            return "\""+safe.replace("\"","\"\"")+"\""
        }
        writer.append(headers.joinToString(",",transform=::cell)).append("\r\n")
        records.forEach { record ->
            writer.append(headers.joinToString(",") { cell(record[it].orEmpty()) }).append("\r\n")
        }
    }

}

/** Experimental Text Lab only; never changes the normal scanner deadline. */
internal object TextLabBudgets { const val OCR_MS = 60_000L }
