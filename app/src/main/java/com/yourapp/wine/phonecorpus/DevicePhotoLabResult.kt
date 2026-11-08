package com.yourapp.wine.phonecorpus

import org.json.JSONObject

/** Values actually selected by Lab, not an expected/reference row or a guessed replacement. */
internal data class DevicePhotoLabResult(
    val fields:Map<String,String>,
    val error:String="",
    val incompleteOcr:Boolean=false,
    val ocrLines:List<String> = emptyList(),
) {
    companion object {
        val fieldLabels=linkedMapOf("producer" to "Producer","wine_name" to "Wine name",
            "country" to "Country","region" to "Region","subregion" to "Subregion","vintage" to "Vintage")

        fun fromOutput(output:JSONObject):DevicePhotoLabResult {
            val pipelines=output.optJSONArray("pipelines")
            val selected=pipelines?.let { array -> (0 until array.length()).map { array.getJSONObject(it) }
                .firstOrNull { it.optString("pipeline")==PhoneCorpusRunner.Mode.LAB.pipelineId } }
            val assigned=selected?.optJSONObject("fields")
            val fields=fieldLabels.keys.associateWith { key ->
                if(assigned==null || assigned.isNull(key)) "" else assigned.optString(key)
            }
            val failed=output.optString("status")!="complete" || selected?.optString("status")!="complete" || assigned==null
            val error=if(failed) output.optString("error").ifBlank {
                selected?.optString("error").orEmpty().ifBlank { "Lab did not complete extraction" }
            } else ""
            return DevicePhotoLabResult(fields,error,output.optBoolean("engine_incomplete"))
        }

        /**
         * Raw OCR for the audit screen: every recognition row in reading
         * order with its relative type size (box height as a multiple of the
         * median line height on this photo). Boxes are normalized, so the
         * ratio is independent of camera distance and resolution.
         */
        fun ocrDisplayLines(rows:List<Map<String,String>>):List<String> {
            data class Line(val top:Double,val left:Double,val height:Double,val engine:String,val text:String,val confidence:String)
            val lines=rows.filter { it["stage"]=="recognition" }.mapNotNull { row ->
                val text=row["raw_text"].orEmpty().trim()
                val box=(row["base_box_normalized"] ?: row["original_box_normalized"])
                    ?.split(' ')?.mapNotNull { it.toDoubleOrNull() }
                if(text.isEmpty() || box==null || box.size!=4) null else Line(
                    top=box[1],left=box[0],height=box[3]-box[1],
                    engine=row["candidate"].orEmpty().ifBlank { "ocr" },
                    text=text,
                    confidence=row["mlkit_confidence"] ?: row["confidence"] ?: row["paddle_confidence"] ?: "")
            }
            if(lines.isEmpty()) return emptyList()
            val heights=lines.map { it.height }.filter { it>0.0 }.sorted()
            val median=if(heights.isEmpty()) 0.0 else heights[heights.size/2]
            return lines.sortedWith(compareBy({ it.top },{ it.left })).map { line ->
                val size=if(median>0.0) " · %.1f× median size".format(line.height/median) else ""
                val conf=line.confidence.toFloatOrNull()?.let { " · conf %.2f".format(it) } ?: ""
                "[${line.engine}] ${line.text}$size$conf"
            }
        }
    }
}
