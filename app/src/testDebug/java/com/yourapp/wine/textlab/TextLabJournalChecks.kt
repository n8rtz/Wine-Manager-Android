package com.yourapp.wine.textlab

import java.io.StringWriter

object TextLabJournalChecks {
    fun run() {
        val specs=PaddleCandidates.all
        check(specs.size==1 && specs.single().id=="candidate_3")
        check(specs.single().detector.name=="PP-OCRv6_medium_det")
        check(specs.single().recognizer.name=="PP-OCRv6_medium_rec")
        check(specs.single().dictionary.entries==18708)
        for(mask in 1..1) {
            val selected=specs.filterIndexed { i,_ -> mask and (1 shl i)!=0 }.map { it.id }.toSet()
            val journal=TextLabJournal(selected,"test-build",true)
            val first=PaddleCandidates.selected(selected).first()
            journal.add(first.id,"recognition",values=mapOf("raw_text" to "Côtes, \"Merlot\"\n次行","region_id" to "1","ctc_posterior" to "0.9"))
            journal.add(first.id,"candidate_summary","timed_out")
            journal.finishPending("not_run_cancelled")
            journal.finishPending("not_run_cancelled") // Idempotent, never overwrite completed partial status.
            check(journal.rows.count { it["stage"]=="candidate_summary" }==selected.size)
            check(journal.rows.all { it["candidate"] in selected })
            check(journal.rows.first { it["stage"]=="candidate_summary" }["status"]=="timed_out")
            val writer=StringWriter();journal.writeCsv(writer)
            val csv=writer.toString()
            check(csv.contains("\"Côtes, \"\"Merlot\"\"\n次行\""))
            check(csv.contains("test_id") && csv.contains(journal.id) && csv.contains(first.recognizer.sha))
            val again=StringWriter();journal.writeCsv(again);check(csv==again.toString())
        }
        val journal=TextLabJournal(setOf("candidate_3"),"test",false)
        journal.add("candidate_3","recognition",values=mapOf("raw_text" to "  =HYPERLINK(\"evil\")"))
        val writer=StringWriter();journal.writeCsv(writer)
        check(writer.toString().contains("'  =HYPERLINK"))
        val folder=java.nio.file.Files.createTempDirectory("text-lab-csv-check-").toFile()
        try {
            val saved=journal.save(folder)
            check(saved.readText(Charsets.UTF_8)==writer.toString())
            journal.finishPending("not_run_cancelled")
            check(journal.save(folder)==saved && saved.readText().contains("candidate_summary"))
            check(folder.listFiles()!!.none { it.extension=="tmp" })
        } finally { folder.deleteRecursively() }
        check(journal.rows.first()["raw_text"]=="  =HYPERLINK(\"evil\")") // Export escaping never changes inference text.
    }
}
