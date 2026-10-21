package com.yourapp.wine.textlab

import java.io.StringWriter

object TextLabBatchChecks {
    fun run() {
        val batch=TextLabBatch(listOf(TextLabImageKey("image-A","same,name.jpg"),TextLabImageKey("image-B","same,name.jpg"),
            TextLabImageKey("image-C","Rhône\n\"2022\".jpg")),setOf("candidate_3"),"test-build",true)
        val a=batch.journals[0];val b=batch.journals[1]
        a.metadata["input_sha256"]="hash-A";a.metadata["crop_x"]="12";a.metadata["original_width"]="3000"
        b.metadata["input_sha256"]="hash-B";b.metadata["crop_x"]="85";b.metadata["original_width"]="640"
        a.add("candidate_3","recognition",values=mapOf("raw_text" to "Merlot","only_a" to "value"))
        a.add("candidate_3","candidate_summary","complete")
        batch.finishImage(0,"complete")
        b.add("shared","error","import_failed",details="Unreadable image")
        batch.finishImage(1,"import_failed")
        batch.finishPending("cancelled");batch.finishPending("cancelled")
        check(batch.journals.all { it.rows.count { row -> row["stage"]=="image_summary" }==1 })
        check(batch.journals.all { it.rows.count { row -> row["stage"]=="candidate_summary" }==1 })
        check(batch.journals.flatMap { it.rows }.none { it["candidate"]=="candidate_2" })
        check(a.rows.first { it["stage"]=="candidate_summary" }["status"]=="complete")
        check(batch.journals[2].rows.last()["status"]=="not_run_cancelled")
        check(batch.journals.map { it.id }.toSet().size==3)
        val out=StringWriter();batch.writeCsv(out);val csv=out.toString()
        check(csv.contains("image_index") && csv.contains("batch_id") && csv.contains(batch.id))
        check(csv.contains("hash-A") && csv.contains("hash-B") && csv.contains("\"same,name.jpg\""))
        check(csv.contains("\"Rhône\n\"\"2022\"\".jpg\""))
        val lines=csv.split("\r\n")
        check(lines.filter { it.contains("\"image-A\"") }.all { it.contains("hash-A") && !it.contains("hash-B") })
        check(lines.filter { it.contains("\"image-B\"") }.all { it.contains("hash-B") && !it.contains("hash-A") })
        check(a.metadata["crop_x"]=="12" && b.metadata["crop_x"]=="85")
        val folder=java.nio.file.Files.createTempDirectory("ocr-batch-check-").toFile()
        try {
            val saved=batch.save(folder)
            check(saved.readText()==csv)
            a.add("shared","checkpoint_test",details="updated-checkpoint")
            check(batch.save(folder).readText().contains("updated-checkpoint"))
            check(folder.listFiles()!!.count { it.extension=="csv" }==1)
            check(folder.listFiles()!!.none { it.extension=="tmp" })
        } finally { folder.deleteRecursively() }
    }
}
