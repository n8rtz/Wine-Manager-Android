package com.yourapp.wine.gliner

import com.yourapp.wine.phonecorpus.PhoneCorpusMath

/** One audit dataset only; never mix the curated Lab v7/Prod baselines into these scores. */
internal data class GlinerAuditScore(
    val attempted:Int, val excluded:Int, val failed:Int, val eligible:Int, val complete:Int,
    val oneError:Int, val multiError:Int,
    val fieldCorrect:Map<String,Int>, val fieldTotal:Map<String,Int>,
)

internal fun scoreGlinerAudit(rows:List<GlinerAuditRow>):GlinerAuditScore {
    // Preserve the persisted audit's convention: failed executions and exact curated-image
    // matches do not contribute to field or eligible-label denominators. Unresolved references
    // are excluded per field and disqualify the label from the all-six-fields comparison.
    val scored=rows.filter { it.status=="complete" && !it.exactCorpusMatch }
    val eligible=scored.filter { PhoneCorpusMath.eligible(it.reference.targets) }
    fun errors(row:GlinerAuditRow)=PhoneCorpusMath.fields.count { !PhoneCorpusMath.correct(row.outcomes[it].orEmpty()) }
    val total=PhoneCorpusMath.fields.associateWith { field -> scored.count { it.reference.targets.getValue(field).status!="unresolved" } }
    val correct=PhoneCorpusMath.fields.associateWith { field -> scored.count {
        it.reference.targets.getValue(field).status!="unresolved" && PhoneCorpusMath.correct(it.outcomes[field].orEmpty())
    } }
    return GlinerAuditScore(rows.size,rows.count { it.exactCorpusMatch },rows.count { it.status!="complete" },
        eligible.size,eligible.count { errors(it)==0 },eligible.count { errors(it)==1 },eligible.count { errors(it)>1 },correct,total)
}

internal fun auditFailedFields(row:GlinerAuditRow):List<Pair<String,Map<String,String>>> {
    if(row.status!="complete") return emptyList()
    return PhoneCorpusMath.fields.filter { field ->
        row.reference.targets.getValue(field).status!="unresolved" && !PhoneCorpusMath.correct(row.outcomes[field].orEmpty())
    }.map { field ->
        val target=row.reference.targets.getValue(field)
        field to mapOf("expected" to target.display,"recognized" to row.fields[field].orEmpty(),
            "reference_status" to target.status,"outcome" to row.outcomes[field].orEmpty())
    }
}
