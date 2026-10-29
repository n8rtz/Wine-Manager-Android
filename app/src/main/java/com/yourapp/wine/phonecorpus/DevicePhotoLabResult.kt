package com.yourapp.wine.phonecorpus

import org.json.JSONObject

/** Values actually selected by Lab v5, not an expected/reference row or a guessed replacement. */
internal data class DevicePhotoLabResult(
    val fields:Map<String,String>,
    val error:String="",
    val incompleteOcr:Boolean=false,
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
                selected?.optString("error").orEmpty().ifBlank { "Lab v5 did not complete extraction" }
            } else ""
            return DevicePhotoLabResult(fields,error,output.optBoolean("engine_incomplete"))
        }
    }
}
