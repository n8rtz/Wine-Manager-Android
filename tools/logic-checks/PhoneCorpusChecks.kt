import com.yourapp.wine.phonecorpus.PhoneCorpusMath as M
import com.yourapp.wine.textlab.*
import java.io.File
import java.util.Base64
fun main(args:Array<String>) {
    var checks=0
    fun verify(ok:Boolean) { checks++;check(ok) { "Phone corpus check $checks failed" } }
    fun decode(v:String)=String(Base64.getDecoder().decode(v),Charsets.UTF_8)
    val cases=File(args.single()).readLines().map { it.split('\t') }
    val counts=mutableMapOf<String,Int>();var eligible=0;var complete=0
    for((_,form) in cases.groupBy { it[0] }) {
        val targets=form.associate { it[1] to M.Target(it[2],decode(it[3]).split('|').toSet()) }
        val outcomes=form.map { r ->
            val outcome=M.outcome(targets.getValue(r[1]),decode(r[4]))
            verify(outcome==r[5]);verify(M.normalize(decode(r[4]))==decode(r[6]))
            counts[outcome]=(counts[outcome] ?: 0)+1;outcome
        }
        if(M.eligible(targets)) { eligible++;if(outcomes.all(M::correct)) complete++ }
    }
    verify(cases.size==1008);verify(eligible==160 && complete==26)
    verify(counts["correct_value"]==597 && counts["correct_blank"]==77 && counts["wrong_value"]==100 && counts["unsupported_fill"]==20 && counts["missing_value"]==197)
    val blank=M.Target("expected_blank",emptySet());val unresolved=M.Target("unresolved",emptySet())
    verify(M.outcome(blank,"")=="correct_blank");verify(M.outcome(blank,"Napa")=="unsupported_fill")
    verify(M.outcome(blank,"",true)=="execution_failed");verify(M.outcome(unresolved,"",true)=="execution_failed")
    verify(M.outcome(unresolved,"")=="unscored_blank");verify(M.outcome(unresolved,"Napa")=="unscored_filled")
    verify(M.normalize("Château & Fils")=="chateauetfils");verify(M.normalize("Straße")=="strasse")
    val j=TextLabJournal(setOf("candidate_3"),"test",true)
    j.add("production_combined","form_summary","suggestions",mapOf("producer" to "Estate","producer_state" to "YELLOW"))
    val failed=TextLabJournal(setOf("candidate_3"),"test",true);failed.add("production_combined","form_summary","failed")
    val stats=TextLabStats.rows(listOf(j,failed)).single()
    verify(stats["filled_slots"]=="1" && stats["blank_slots"]=="5" && stats["failed_or_incomplete_previews"]=="1")
    verify(stats["six_fields_filled"]=="0")
    val writer=java.io.StringWriter();TextLabCsv.write(writer,sequenceOf(mapOf("value" to "=SUM(A1)","comma" to "a,b")))
    verify(writer.toString().contains("'=SUM(A1)") && writer.toString().contains("\"a,b\""))
    println("PASS: $checks actual Kotlin scoring/normalization, baseline parity, lab stats and CSV checks. No Android or OCR execution.")
}
