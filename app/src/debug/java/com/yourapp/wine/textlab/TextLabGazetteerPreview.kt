package com.yourapp.wine.textlab

import android.os.SystemClock
import com.yourapp.wine.fill.*
import com.yourapp.wine.gazetteer.TrigramGazetteer
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

/** Separate default-off pre-parser route; never mutates baseline readings, rows or intake fields. */
internal object TextLabGazetteerPreview {
    fun geography(e:TrigramGazetteer.Entity):Map<FieldId,String> {
        if(e.country=="United States") {
            if(e.region.isBlank() || e.region==e.country) return mapOf(FieldId.COUNTRY to "",FieldId.REGION to "",FieldId.SUBREGION to "")
            // App convention: state in Country, valley in Region; California alone adds no narrower origin.
            val parts=e.path.dropWhile { it!=e.region }.drop(1)
            return mapOf(FieldId.COUNTRY to e.region,FieldId.REGION to parts.firstOrNull().orEmpty(),
                FieldId.SUBREGION to if(parts.size>1) parts.last() else "")
        }
        val region=e.region.takeUnless { it==e.country }.orEmpty()
        return mapOf(FieldId.COUNTRY to e.country,FieldId.REGION to region,
            FieldId.SUBREGION to if(region.isNotBlank() && e.name!=region) e.name else "")
    }
    fun append(journal:TextLabJournal,resolver:TrigramGazetteer?,engine:FillEngine?,year:Int,setupError:String="") {
        if(journal.rows.any { it["stage"]=="gazetteer_summary" }) return
        val values=mutableMapOf("post_processing_version" to TrigramGazetteer.VERSION,
            "preview_policy" to "raw OCR -> trigram role tags -> rules on unclaimed lines; debug-only; confirm all suggestions")
        try {
            check(resolver!=null && engine!=null) { setupError.ifBlank { "Gazetteer or parser unavailable" } }
            val deadline=SystemClock.elapsedRealtime()+60000L
            fun budget() { check(SystemClock.elapsedRealtime()<deadline) { "Gazetteer preview deadline reached" } }
            val records=TextLabFormPreview.records(journal)
            require(records.size<=128) { "Gazetteer observation cap exceeded; no partial role assignment" }
            val matches=records.map { resolver.resolve(it.textRaw,::budget) }
            matches.forEachIndexed { i,r ->
                val hit=r.accepted;val record=records[i]
                journal.add("gazetteer","gazetteer_resolution",if(hit==null) "abstained" else "suggestion",mapOf(
                    "raw_ocr_input" to r.raw,"source_observation" to i.toString(),"source_engine" to record.engineId,"source_variant" to record.variantId,
                    "original_box" to record.bboxOrig.toString(),"rectified_box" to record.bboxRect.toString(),
                    "category" to if(hit==null) "UNRESOLVED" else hit.entity.level.uppercase(java.util.Locale.ROOT), "entity_level" to hit?.entity?.level.orEmpty(),
                    "canonical_name" to hit?.entity?.name.orEmpty(),"appellation_id" to (hit?.entity?.id?.toString() ?: ""),
                    "official_code" to hit?.entity?.officialCode.orEmpty(),"region_path" to hit?.entity?.regionPath.orEmpty(),
                    "entity_source" to hit?.entity?.source.orEmpty(),"bm25_rank" to (hit?.bm25?.toString() ?: ""),
                    "confidence_score" to (hit?.similarity?.toString() ?: ""),"confidence_kind" to "uncalibrated lexical similarity; NOT probability",
                    "alternatives" to JSONArray(r.alternatives.map { h -> JSONObject().put("id",h.entity.id).put("name",h.entity.name).put("similarity",h.similarity).put("bm25",h.bm25) }).toString()),r.reason)
            }
            // Tagged observations are removed BEFORE producer/grape rules, never rewritten in the journal.
            val remaining=records.filterIndexed { i,_ -> matches[i].accepted==null }
            budget()
            val fill=CellarFieldPresentation.apply(engine.fill(remaining,year,ConfidenceContext(labelFound=false)))
            FieldId.entries.forEach { f ->
                val key=f.name.lowercase();val v=fill.fields.getValue(f)
                values[key]=if(v.state==UiState.RED) "" else v.value
                values["${key}_state"]=if(values[key].isNullOrBlank()) "RED" else "YELLOW"
                values["${key}_reason"]=v.reason+"; experimental gazetteer pre-parser; confirm"
            }
            val entities=matches.mapNotNull { it.accepted?.entity }.distinctBy { it.id }
            val deepest=entities.maxByOrNull { it.path.size }
            val compatible=deepest!=null && entities.all { it.country==deepest.country && it.path==deepest.path.take(it.path.size) }
            if(deepest!=null) {
                val geo=if(compatible) geography(deepest) else mapOf(FieldId.COUNTRY to "",FieldId.REGION to "",FieldId.SUBREGION to "")
                // A contradictory residual-rule geography is not silently overwritten by the new catalogue.
                val conflict=!compatible || geo.any { (f,v) -> val prior=values[f.name.lowercase()].orEmpty();prior.isNotBlank() && TrigramGazetteer.normalize(prior)!=TrigramGazetteer.normalize(v) }
                geo.forEach { (f,v) ->
                    val key=f.name.lowercase();values[key]=if(conflict) "" else v
                    values["${key}_state"]=if(values[key].isNullOrBlank()) "RED" else "YELLOW"
                    values["${key}_reason"]=if(conflict) "Conflicting geography; manual review required" else "Gazetteer hierarchy suggestion; source claims not independently verified; confirm"
                }
                values["gazetteer_conflict"]=conflict.toString()
                values["gazetteer_region_path"]=deepest.regionPath
                // Metadata stays separate: appellations need not be the wine/cuvee name.
            }
            budget()
            journal.add("gazetteer","gazetteer_summary","suggestions",values,"Raw readings retained; BM25 is not confidence. No production form updates.")
        } catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            // Never publish a partially assigned form after a timeout, malformed DB or unsupported tokenizer.
            FieldId.entries.forEach { val key=it.name.lowercase();values[key]="";values["${key}_state"]="RED" }
            journal.add("gazetteer","gazetteer_summary","failed",values,"${error.javaClass.simpleName}: ${error.message}")
        }
    }
}
