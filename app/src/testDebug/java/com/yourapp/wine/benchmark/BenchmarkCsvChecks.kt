package com.yourapp.wine.benchmark

import com.yourapp.wine.LogicChecks
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.ScanEnvironment
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files

object BenchmarkCsvChecks {
    fun csvCarriesPerImageTruthAndEscapesSpreadsheets() {
        val tokens=listOf(LogicChecks.token("BLACKSTONE",.9f),LogicChecks.token("California",.9f,y=.8f))
        val report=ScanReport.red("").copy(tokens=tokens,fill=CellarFieldPresentation.apply(FillEngine(FixtureKnowledgeBase()).fill(tokens,2026)))
        val environment=ScanEnvironment(buildCommit="test-commit")
        val expected=mapOf(FieldId.PRODUCER to "Maker, \"quoted\"\nsecond line",FieldId.WINE_NAME to "=not a formula",FieldId.REGION to "North Coast",FieldId.SUBREGION to "AVA")
        val row=BenchmarkCsv.row("batch1","image1","Label 1","sha1","base1",7,1,Treatment("raw"),"complete",report,4,expected,environment)
        val cells=parse(row)
        check(cells.size==BenchmarkCsv.columns.size)
        val data=BenchmarkCsv.columns.zip(cells).toMap()
        check(data["expected_producer"]==expected[FieldId.PRODUCER])
        check(data["expected_wine_name"]=="'=not a formula")
        check(data["expected_region"]=="North Coast" && data["expected_subregion"]=="AVA")
        check(data["actual_producer"]=="Blackstone" && data["actual_wine_name"]=="")
        check(data["raw_ocr"]!!.contains("BLACKSTONE") && data["build_commit"]=="test-commit")
        val other=BenchmarkCsv.row("batch1","image2","Other","sha2","base2",8,0,null,"setup failed",null,null,
            mapOf(FieldId.WINE_NAME to "Different wine"),environment)
        val otherData=BenchmarkCsv.columns.zip(parse(other)).toMap()
        check(otherData["image_id"]=="image2" && otherData["expected_wine_name"]=="Different wine")
        check(otherData["actual_wine_name"]=="" && otherData["status"]=="setup failed")
        check(parse(BenchmarkCsv.encode(listOf("-0.12","-2+3")))==listOf("-0.12","'-2+3"))
        check(parse(BenchmarkCsv.encode(listOf(" +SUM(1,2)","@test","normal","a\rb")))==listOf("' +SUM(1,2)","'@test","normal","a\rb"))
    }
    fun historySurvivesNewInstancesAndExportsAllBatches()=runBlocking {
        val folder=Files.createTempDirectory("ocr-history-test").toFile()
        try {
            BenchmarkHistory(folder).save("batch1-image1-001",BenchmarkCsv.encode(listOf("first")))
            BenchmarkHistory(folder).save("batch2-image2-001",BenchmarkCsv.encode(listOf("second")))
            // Idempotent durable replacement, not a duplicate row on retry.
            BenchmarkHistory(folder).save("batch1-image1-001",BenchmarkCsv.encode(listOf("updated")))
            java.io.File(folder,"interrupted.pending").writeText("never export incomplete writes")
            val reopened=BenchmarkHistory(folder)
            check(reopened.count()==2)
            val all=ByteArrayOutputStream();reopened.export(all)
            check(all.toString("UTF-8").startsWith(BenchmarkCsv.header))
            check("updated" in all.toString("UTF-8") && "second" in all.toString("UTF-8") && "incomplete" !in all.toString("UTF-8"))
            val single=ByteArrayOutputStream();reopened.export(single,"batch1")
            check("second" !in single.toString("UTF-8"))
            reopened.clear();check(reopened.count()==0)
        } finally { folder.deleteRecursively() }
    }
    private fun parse(row:String):List<String> {
        val result=mutableListOf<String>();var index=0
        while(index<row.length && row[index]!='\r') {
            check(row[index++]=='"');val cell=StringBuilder()
            while(true) {
                val c=row[index++]
                if(c!='"') cell.append(c)
                else if(index<row.length && row[index]=='"') { cell.append('"');index++ }
                else break
            }
            result+=cell.toString()
            if(row[index]==',') index++ else break
        }
        return result
    }
}
