package com.n8rtz.winetracker.scanner

import android.content.Context
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.ui.ReviewedField
import com.yourapp.wine.ui.ScanFormState
import org.json.JSONObject

/** Private, unsaved Add Wine draft. Saving a wine clears it; no photo is stored here. */
internal data class AddWineDraft(
    val maker: String = "", val name: String = "", val vintage: String = "NV", val type: String = "Red",
    val quantity: String = "1", val price: String = "", val windowStart: String = "", val windowEnd: String = "",
    val country: String = "", val region: String = "", val subregion: String = "",
    val edited: Set<FieldId> = emptySet(),
) {
    fun formState(): ScanFormState {
        val values = mapOf(FieldId.PRODUCER to maker, FieldId.WINE_NAME to name, FieldId.VINTAGE to vintage,
            FieldId.COUNTRY to country, FieldId.REGION to region, FieldId.SUBREGION to subregion)
        return ScanFormState(FieldId.entries.associateWith { field ->
            ReviewedField(value = values.getValue(field), edited = field in edited)
        })
    }
}

internal class AddWineDraftStore(context: Context) {
    private val prefs = context.getSharedPreferences("add-wine-draft-v1", Context.MODE_PRIVATE)
    fun load(): AddWineDraft = runCatching {
        val j = JSONObject(prefs.getString("draft", null) ?: return AddWineDraft())
        val edited = j.optJSONArray("edited")
        AddWineDraft(j.optString("maker"), j.optString("name"), j.optString("vintage", "NV"),
            j.optString("type", "Red"), j.optString("quantity", "1"), j.optString("price"),
            j.optString("windowStart"), j.optString("windowEnd"), j.optString("country"),
            j.optString("region"), j.optString("subregion"),
            FieldId.entries.filter { field -> edited != null && (0 until edited.length()).any { edited.optString(it) == field.name } }.toSet())
    }.getOrDefault(AddWineDraft())

    fun save(draft: AddWineDraft, synchronous: Boolean = false): Boolean {
        val j = JSONObject().put("maker", draft.maker).put("name", draft.name).put("vintage", draft.vintage)
            .put("type", draft.type).put("quantity", draft.quantity).put("price", draft.price)
            .put("windowStart", draft.windowStart).put("windowEnd", draft.windowEnd).put("country", draft.country)
            .put("region", draft.region).put("subregion", draft.subregion)
            .put("edited", org.json.JSONArray(draft.edited.map { it.name }))
        val edit = prefs.edit().putString("draft", j.toString())
        return if (synchronous) edit.commit() else { edit.apply(); true }
    }
    fun clear() { prefs.edit().remove("draft").commit() }
}
