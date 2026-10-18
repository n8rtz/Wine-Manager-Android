package com.yourapp.wine.benchmark

import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.ScanEnvironment
import com.yourapp.wine.fill.FieldId
import java.util.Locale

/** Stable UTF-8 CSV schema, one row per attempted treatment (or explicit image setup failure). */
object BenchmarkCsv {
    val columns = listOf("schema_version", "batch_id", "image_id", "image_label", "input_sha256", "base_sha256",
        "seed", "case_order", "recipe_id", "recipe_description", "contrast", "sharpen", "sauvola_window", "sauvola_k", "stroke_thicken", "shear", "thin", "aspect", "status", "elapsed_ms", "preparation_preview_ms",
        "deadline", "exact_fields", "checked_fields", "raw_name_similarity", "build_commit", "build_branch",
        "working_tree", "device_model", "android_sdk") +
        FieldId.entries.flatMap { field -> val key=field.name.lowercase(Locale.ROOT)
            listOf("expected_$key", "actual_$key", "${key}_state", "${key}_reason") } +
        listOf("raw_ocr", "raw_scores", "engine_pass_timings", "original_width", "original_height", "base_width", "base_height", "original_to_base_homography")
    val header: String get() = encode(columns)

    fun row(batch:String,imageId:String,label:String,inputHash:String,baseHash:String,seed:Int,order:Int,
        recipe:Treatment?,status:String,report:ScanReport?,preparationMs:Long?,expected:Map<FieldId,String>,
        environment:ScanEnvironment):String {
        val score=report?.let { BenchmarkScoring.score(expected,it.fill,it.tokens) }
        val parameters=recipe?.takeUnless { it.id=="baseline" } // Composite pipeline has no single set of filter parameters.
        val values=mutableListOf<Any?>("1",batch,imageId,label,inputHash,baseHash,seed,order,recipe?.id,
            recipe?.description,parameters?.contrast,parameters?.sharpen,parameters?.window,parameters?.k,parameters?.thicken,parameters?.shear,parameters?.thin,parameters?.aspect,status,report?.elapsedMs,preparationMs,report?.deadlineCancelled,
            score?.exactFields,score?.checkedFields,score?.rawNameSimilarity,environment.buildCommit,
            environment.buildBranch,environment.workingTree,environment.model,environment.androidSdk)
        FieldId.entries.forEach { field ->
            val actual=report?.fill?.fields?.get(field)
            values.addAll(listOf(expected[field],actual?.value,actual?.state?.name,actual?.reason))
        }
        values.addAll(listOf(
            report?.tokens?.joinToString("\n") { "${it.engineId}/${it.variantId}: ${it.textRaw}" },
            report?.tokens?.joinToString("\n") { "${it.engineId}/${it.variantId}: ${it.score}; estimated=${it.scoreIsEstimated}" },
            report?.timings?.joinToString("\n") { "${it.engineId}/${it.variantId}: ${it.ms}ms; lines=${it.tokenCount}; deadline=${it.deadlineCancelled}; ${it.detail.orEmpty()}" },
            report?.origW,report?.origH,report?.rectW,report?.rectH,report?.homography?.joinToString(" ")))
        check(values.size==columns.size)
        return encode(values.map { it?.toString().orEmpty() })
    }

    /** RFC4180 quoting + spreadsheet-formula neutralization. Leading apostrophe is deliberate, documented. */
    internal fun encode(values:List<String>):String = values.joinToString(",") { raw ->
        val negativeNumber=Regex("-\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").matches(raw)
        val dangerous=raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || raw.startsWith('\t') || raw.startsWith('\r')
        val safe=if(dangerous && !negativeNumber) "'$raw" else raw
        "\"${safe.replace("\"","\"\"")}\""
    } + "\r\n"
}
