package com.yourapp.wine.gliner

import android.content.Context
import com.yourapp.wine.phonecorpus.PhoneCorpusMath
import org.json.JSONObject
import java.io.File

/** One packaged workbook label: the same 124-image set Lab v7 is measured on. */
internal data class GlinerLabSample(
    val image:String,
    val reviewNumber:Int,
    val sourceFilename:String,
    val sha256:String,
    val included:Boolean,
    val targets:Map<String,PhoneCorpusMath.Target>,
) {
    val displayName:String get() = "#$reviewNumber · $sourceFilename"
    fun target(field:String):PhoneCorpusMath.Target =
        targets[field] ?: PhoneCorpusMath.Target("unresolved", emptySet(), "")
}

/**
 * Read-only view of the Lab v7 sample dataset (`assets/wine-app-labels-v1`).
 *
 * The Gliner lab never copies targets into the runner: the reference fields are loaded here and
 * used only after the pipeline has produced its own predictions. Scores are computed with the same
 * `PhoneCorpusMath` normalizer and target semantics as the existing scoreboard, so the numbers on
 * this screen mean exactly what Lab v7's numbers mean.
 */
internal object GlinerLabCorpus {
    const val ASSET_ROOT="wine-app-labels-v1"
    val fields:List<String> = PhoneCorpusMath.fields

    fun load(context:Context):List<GlinerLabSample> {
        val inputs=JSONObject(context.assets.open("$ASSET_ROOT/inputs.json").bufferedReader().use { it.readText() })
        val scoring=JSONObject(context.assets.open("$ASSET_ROOT/scoring.json").bufferedReader().use { it.readText() })
        val evaluation=JSONObject(context.assets.open("$ASSET_ROOT/evaluation-set.json").bufferedReader().use { it.readText() })
        val included=(0 until evaluation.getJSONArray("included_images").length())
            .map { evaluation.getJSONArray("included_images").getJSONObject(it).getString("image") }
            .toSet()
        val targets=linkedMapOf<String,JSONObject>()
        val scored=scoring.getJSONArray("images")
        for(index in 0 until scored.length()) {
            val entry=scored.getJSONObject(index)
            targets[entry.getString("image")]=entry
        }
        val images=inputs.getJSONArray("images")
        return (0 until images.length()).map { index ->
            val entry=images.getJSONObject(index)
            val image=entry.getString("image")
            val fields=targets[image]?.optJSONObject("fields") ?: JSONObject()
            val parsed=linkedMapOf<String,PhoneCorpusMath.Target>()
            fields.keys().forEach { field ->
                val target=fields.optJSONObject(field) ?: return@forEach
                val accepted=target.optJSONArray("accepted")
                parsed[field]=PhoneCorpusMath.Target(
                    status=target.optString("status","unresolved"),
                    accepted=(0 until (accepted?.length() ?: 0)).mapNotNull { accepted?.optString(it) }.toSet(),
                    display=target.optString("target",""),
                )
            }
            GlinerLabSample(image,entry.optInt("review_number",index+1),
                entry.optString("source_filename",image),entry.optString("sha256",""),
                image in included,parsed)
        }.sortedBy { it.reviewNumber }
    }

    fun copyTo(context:Context,sample:GlinerLabSample,target:File) {
        target.parentFile?.mkdirs()
        context.assets.open("$ASSET_ROOT/images/${sample.image}").use { input ->
            target.outputStream().use { output -> input.copyTo(output,65536) }
        }
    }

    fun byteCount(context:Context,sample:GlinerLabSample):Long = runCatching {
        context.assets.openFd("$ASSET_ROOT/images/${sample.image}").use { it.length }
    }.recoverCatching {
        // Compressed assets have no file descriptor; count the stream instead of showing 0 bytes.
        context.assets.open("$ASSET_ROOT/images/${sample.image}").use { input ->
            val buffer=ByteArray(65_536)
            var total=0L
            while(true) {
                val read=input.read(buffer)
                if(read<0) break
                total+=read
            }
            total
        }
    }.getOrDefault(0L)
}
