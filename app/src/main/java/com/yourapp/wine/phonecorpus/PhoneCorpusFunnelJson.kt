package com.yourapp.wine.phonecorpus

import com.yourapp.wine.diagnostics.PipelineFunnel as F
import org.json.JSONArray
import org.json.JSONObject

/** Compact checkpoints keep the funnel after large original traces have been removed. */
internal object PhoneCorpusFunnelJson {
    fun write(output: JSONObject, label: F.Label) {
        output.put("pipeline_funnel_version", F.VERSION)
        output.put("pipeline_funnel", JSONArray(F.steps.map { step ->
            val gate = label.gates[step.id] ?: F.Gate(F.State.NOT_RECORDED, "Stage telemetry absent")
            JSONObject().put("step_id", step.id).put("step_status", gate.state.name).put("step_reason", gate.reason)
        }))
    }
    fun read(output: JSONObject, mode: String, pipelineId: String): F.Label {
        val image = output.getString("image")
        val attempt = output.optInt("attempt", 1)
        val array = output.optJSONArray("pipeline_funnel")
        val ownerMode = PhoneCorpusRunner.Mode.fromPipelineId(pipelineId)
            ?: PhoneCorpusRunner.Mode.entries.firstOrNull { it.name == mode }
        val acceptedPipelineIds = ownerMode?.acceptedPipelineIds ?: setOf(pipelineId)
        if (array != null && output.optInt("pipeline_funnel_version") == F.VERSION) {
            val gates = buildMap {
                for (i in 0 until array.length()) {
                    val row = array.getJSONObject(i)
                    val state = F.State.entries.firstOrNull { it.name == row.optString("step_status") } ?: F.State.NOT_RECORDED
                    put(row.getString("step_id"), F.Gate(state, row.optString("step_reason")))
                }
            }
            return F.Label(image, attempt, gates)
        }
        // Legacy checkpoints can establish final fields, but cannot invent absent resolver/engine telemetry.
        val rows = output.optJSONArray("trace")?.let { trace -> (0 until trace.length()).map { i ->
            val row = trace.getJSONObject(i);row.keys().asSequence().associateWith { row.optString(it) }
        } }.orEmpty()
        val pipelines = output.optJSONArray("pipelines")
        val current = pipelines?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("pipeline") in acceptedPipelineIds } }
        val fields = current?.optJSONObject("fields")?.let { f -> PhoneCorpusMath.fields.associateWith { f.optString(it) } }
        val status = if (output.optString("status") != "complete") "failed" else current?.optString("status")
        return PhoneCorpusPipelineDiagnostics.capture(image, attempt, mode, rows, emptyMap(), fields, status, fresh = false)
    }
}
