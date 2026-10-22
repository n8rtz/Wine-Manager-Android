package com.yourapp.wine

import android.graphics.PointF
import android.graphics.RectF
import com.yourapp.wine.fill.*
import com.yourapp.wine.kb.FixtureKnowledgeBase
import com.yourapp.wine.merge.TextNorm
import com.yourapp.wine.ocr.TokenRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class FixtureFieldsTest {
    @Test fun threePrintedLabelFixturesValidateGreen() {
        for (fixture in listOf("margaux", "barolo", "napa")) {
            val json = JSONObject(javaClass.classLoader!!.getResourceAsStream("labels/$fixture.json")!!.bufferedReader().use { it.readText() })
            val expectedTokens = json.getJSONArray("expected_tokens")
            val records = (0 until expectedTokens.length()).map { i ->
                val token = expectedTokens.getJSONObject(i)
                fun box(key: String): RectF = token.getJSONArray(key).let { a -> RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(),
                    a.getDouble(2).toFloat(), a.getDouble(3).toFloat()) }
                val rect = box("bbox_rect")
                val original = box("bbox_orig")
                TokenRecord(token.getString("text"), TextNorm.normalize(token.getString("text")), token.getDouble("score").toFloat(),
                    original, rect, listOf(PointF(original.left * 840, original.top * 1120),
                        PointF(original.right * 840, original.top * 1120), PointF(original.right * 840, original.bottom * 1120),
                        PointF(original.left * 840, original.bottom * 1120)),
                    token.getString("engine_id"), token.getString("variant_id"))
            }
            val result = FillEngine(FixtureKnowledgeBase()).fill(records, 2026)
            val expected = json.getJSONObject("expected")
            FieldId.entries.forEach { field ->
                val actual = result.fields.getValue(field)
                assertEquals("$fixture $field: ${actual.reason}", expected.getJSONObject(field.name).getString("value"), actual.value)
                assertEquals("$fixture $field: ${actual.reason}", UiState.GREEN, actual.state)
            }
        }
    }
}
