package com.n8rtz.winetracker.scanner

import android.content.Context
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldCandidate
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.FillResult
import com.yourapp.wine.fill.UiState
import org.json.JSONObject
import java.io.File

/** Small private recovery record; photos stay in app-private storage and never enter preferences. */
internal class PendingLabelScanStore(context: Context) {
    private val prefs = context.getSharedPreferences("production-label-scan-v1", Context.MODE_PRIVATE)

    data class Active(val requestId: Long, val path: String, val source: String)
    fun active(): Active? = runCatching {
        val json = JSONObject(prefs.getString("active", null) ?: return null)
        Active(json.getLong("id"), json.getString("path"), json.getString("source"))
    }.getOrNull()
    fun saveActive(id: Long, file: File, source: String): Boolean = prefs.edit().putString("active",
        JSONObject().put("id", id).put("path", file.absolutePath).put("source", source).toString()).commit()
    fun clearActive() { prefs.edit().remove("active").commit() }
    fun clearActive(id: Long) { if (active()?.requestId == id) clearActive() }

    fun saveResult(id: Long, report: ScanReport, photoPath: String? = null): Boolean {
        val fields = JSONObject()
        FieldId.entries.forEach { field ->
            val candidate = report.fill.fields.getValue(field)
            fields.put(field.name, JSONObject().put("value", candidate.value).put("state", candidate.state.name)
                .put("reason", candidate.reason))
        }
        val data = JSONObject().put("id", id).put("fields", fields)
            .put("hint", report.fill.overallHint ?: JSONObject.NULL)
            .put("photo", photoPath ?: JSONObject.NULL).toString()
        return prefs.edit().putString("result", data).commit()
    }
    fun result(): LabelScanState.Review? = runCatching {
        val json = JSONObject(prefs.getString("result", null) ?: return null)
        val fields = json.getJSONObject("fields")
        val candidates = FieldId.entries.associateWith { field ->
            val item = fields.getJSONObject(field.name)
            FieldCandidate(field, item.getString("value"), UiState.valueOf(item.getString("state")),
                item.optString("reason"), emptyList())
        }
        val hint = if (json.isNull("hint")) null else json.optString("hint")
        val report = ScanReport.red(hint ?: "").copy(fill = FillResult(candidates, hint))
        // The cache file may be gone (OS pressure) while its record survives; show no photo then.
        val photo = json.optString("photo").takeIf { it.isNotBlank() }
            ?.let { File(it) }?.takeIf { it.isFile }?.absolutePath
        LabelScanState.Review(json.getLong("id"), report, photo)
    }.getOrNull()
    fun clearResult(id: Long) {
        if (result()?.requestId == id) prefs.edit().remove("result").commit()
    }
}
