@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.SensoryData
import kotlin.math.roundToInt

private data class SensoryTraitControl(
    val title: String,
    val descriptors: List<String>,
    val value: Int,
    val onValueChange: (Int) -> Unit
)

@Composable
fun SensoryEvaluationFields(
    data: SensoryData,
    onChange: (SensoryData) -> Unit
) {
    val traits = listOf(
        SensoryTraitControl("Sweetness", listOf("Bone dry", "Dry", "Off-dry", "Medium sweet", "Sweet", "Luscious"), data.sweetness) { onChange(data.copy(sweetness = it)) },
        SensoryTraitControl("Aroma / bouquet", listOf("Very subdued", "Subdued", "Moderate", "Expressive", "Powerful"), data.aroma) { onChange(data.copy(aroma = it)) },
        SensoryTraitControl("Acidity", listOf("Flabby", "Low", "Balanced", "Crisp", "Tart / searing"), data.acidity) { onChange(data.copy(acidity = it)) },
        SensoryTraitControl("Tannins / grip", listOf("Silky", "Low", "Medium", "Grippy", "Harsh"), data.tannins) { onChange(data.copy(tannins = it)) },
        SensoryTraitControl("Body", listOf("Light", "Medium-minus", "Medium", "Medium-plus", "Full"), data.body) { onChange(data.copy(body = it)) },
        SensoryTraitControl("Balance", listOf("Disjointed", "Unbalanced", "Acceptable", "Well-balanced", "Seamless"), data.balance) { onChange(data.copy(balance = it)) },
        SensoryTraitControl("Concentration", listOf("Weak", "Light", "Moderate", "Pronounced", "Overwhelming"), data.intensity) { onChange(data.copy(intensity = it)) },
        SensoryTraitControl("Complexity", listOf("Simple", "Moderate", "Complex", "Highly complex"), data.complexity) { onChange(data.copy(complexity = it)) },
        SensoryTraitControl("Finish", listOf("Short", "Medium-minus", "Medium", "Long", "Lingering"), data.finish) { onChange(data.copy(finish = it)) },
        SensoryTraitControl("Quality", listOf("Poor", "Acceptable", "Good", "Very good", "Outstanding"), data.quality) { onChange(data.copy(quality = it)) }
    )
    traits.forEach { trait -> SensoryTraitSlider(trait) }

    Spacer(Modifier.height(12.dp))
    AromaDescriptorSection(
        selectedIds = data.aromas,
        onChange = { updated -> onChange(data.copy(aromas = updated)) }
    )

    Spacer(Modifier.height(12.dp))
    Text("Common faults", style = MaterialTheme.typography.titleSmall)
    val flaws = listOf("Cork taint (TCA)", "Oxidation", "Volatile acidity", "Brettanomyces", "Reduction / sulfur", "Heat damage")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        flaws.forEach { flaw ->
            val selected = flaw in data.flaws
            FilterChip(
                selected = selected,
                onClick = {
                    val updated = if (selected) data.flaws - flaw else data.flaws + flaw
                    onChange(data.copy(flaws = updated))
                },
                label = { Text(flaw) }
            )
        }
    }
}

private data class OverallScoreBand(val label: String, val color: Color)

private val overallScoreSliderInset = 10.dp
private val overallScoreMarkerLabelWidth = 24.dp
private val overallScoreMarkers = listOf(50, 60, 70, 80, 90, 100)
private val overallScoreGradient = listOf(
    Color(0xFFB94F57),
    Color(0xFFD47941),
    Color(0xFFD3A33D),
    Color(0xFF9A9A42),
    Color(0xFF5F9259),
    Color(0xFF347A59)
)

private fun overallScoreBand(score: Int): OverallScoreBand = when {
    score >= 98 -> OverallScoreBand("Extraordinary", Color(0xFF27664A))
    score >= 94 -> OverallScoreBand("Outstanding", Color(0xFF347A59))
    score >= 90 -> OverallScoreBand("Excellent", Color(0xFF3E7448))
    score >= 86 -> OverallScoreBand("Very Good", Color(0xFF5C773A))
    score >= 80 -> OverallScoreBand("Good", Color(0xFF6E7028))
    score >= 70 -> OverallScoreBand("Average", Color(0xFF8A6B1E))
    score >= 60 -> OverallScoreBand("Below Average", Color(0xFFA85F31))
    else -> OverallScoreBand("Unacceptable", Color(0xFFA9444D))
}

@Composable
fun OverallScoreSlider(
    score: Float,
    scoreSelected: Boolean = true,
    onScoreChange: (Float) -> Unit
) {
    val sliderScore = score.coerceIn(50f, 100f)
    val displayedScore = sliderScore.roundToInt()
    val band = overallScoreBand(displayedScore)
    val indicatorColor = if (scoreSelected) band.color else MaterialTheme.colorScheme.primary

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Overall score",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (scoreSelected) band.label else "Choose a score",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scoreSelected) band.color else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = indicatorColor.copy(alpha = 0.13f),
                contentColor = indicatorColor
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        if (scoreSelected) displayedScore.toString() else "—",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("/100", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackHeight = 8.dp.toPx()
                val inset = overallScoreSliderInset.toPx()
                val trackWidth = (size.width - inset * 2).coerceAtLeast(0f)
                val top = (size.height - trackHeight) / 2f
                val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                drawRoundRect(
                    brush = Brush.horizontalGradient(overallScoreGradient),
                    topLeft = Offset(inset, top),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = radius
                )
                overallScoreMarkers.forEach { marker ->
                    val fraction = (marker - 50) / 50f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = 1.5.dp.toPx(),
                        center = Offset(inset + trackWidth * fraction, top + trackHeight / 2f)
                    )
                }
            }
            Slider(
                value = sliderScore,
                onValueChange = { onScoreChange(it.coerceIn(50f, 100f)) },
                valueRange = 50f..100f,
                steps = 49,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = indicatorColor,
                    activeTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                )
            )
        }

        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            val markerTrackWidth = (maxWidth - overallScoreSliderInset * 2).coerceAtLeast(0.dp)
            overallScoreMarkers.forEach { marker ->
                val fraction = (marker - 50) / 50f
                val labelX = when (marker) {
                    overallScoreMarkers.first() -> overallScoreSliderInset
                    overallScoreMarkers.last() ->
                        overallScoreSliderInset + markerTrackWidth - overallScoreMarkerLabelWidth
                    else -> overallScoreSliderInset + markerTrackWidth * fraction -
                        overallScoreMarkerLabelWidth / 2f
                }
                val labelAlignment = when (marker) {
                    overallScoreMarkers.first() -> TextAlign.Start
                    overallScoreMarkers.last() -> TextAlign.End
                    else -> TextAlign.Center
                }
                Text(
                    text = marker.toString(),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = labelX)
                        .width(overallScoreMarkerLabelWidth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = labelAlignment,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SensoryTraitSlider(trait: SensoryTraitControl) {
    val maxValue = (trait.descriptors.size - 1).coerceAtLeast(1)
    val value = trait.value.coerceIn(0, maxValue)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(trait.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(trait.descriptors[value], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                trait.descriptors.first(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                trait.descriptors.last(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        GradientSensorySlider(
            value = value.toFloat(),
            valueRange = 0f..maxValue.toFloat(),
            steps = (trait.descriptors.size - 2).coerceAtLeast(0),
            gradientColors = gradientColorsFor(trait.title),
            onValueChange = { trait.onValueChange(it.roundToInt().coerceIn(0, maxValue)) }
        )
    }
}

private fun gradientColorsFor(title: String): List<Color> = when (title) {
    "Aroma / bouquet" -> listOf(Color(0xFFF6DCE6), Color(0xFFA25D89), Color(0xFF4B1F55))
    "Acidity" -> listOf(Color(0xFFD7EFC5), Color(0xFF63A875), Color(0xFF246B5B))
    "Tannins / grip" -> listOf(Color(0xFFF1D0D3), Color(0xFFA65E75), Color(0xFF532342))
    "Body" -> listOf(Color(0xFFF6DFC9), Color(0xFFC9826B), Color(0xFF722F37))
    "Balance" -> listOf(Color(0xFFFFF1B8), Color(0xFFD8867C), Color(0xFF722F37))
    "Concentration" -> listOf(Color(0xFFD7EFC5), Color(0xFF5F9F82), Color(0xFF4B1F78))
    "Complexity" -> listOf(Color(0xFFFFE5A3), Color(0xFFBE77A1), Color(0xFF4B1F55))
    "Finish" -> listOf(Color(0xFFF6DFC9), Color(0xFFC77D67), Color(0xFF722F37))
    "Quality" -> listOf(Color(0xFFF7D7B8), Color(0xFFC75B6A), Color(0xFF722F37))
    else -> listOf(Color(0xFFFFE7A3), Color(0xFFC75B6A), Color(0xFF722F37))
}

@Composable
private fun GradientSensorySlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    gradientColors: List<Color>,
    onValueChange: (Float) -> Unit
) {
    val progress = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = MaterialTheme.colorScheme.primary
    val brush = Brush.horizontalGradient(gradientColors)

    Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val trackHeight = 4.dp.toPx()
            val inset = 10.dp.toPx()
            val trackWidth = (size.width - inset * 2).coerceAtLeast(0f)
            val top = (size.height - trackHeight) / 2f
            val origin = Offset(inset, top)
            val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            drawRoundRect(
                color = inactiveColor,
                topLeft = origin,
                size = Size(trackWidth, trackHeight),
                cornerRadius = radius
            )
            val activeWidth = trackWidth * progress
            if (activeWidth > 0f) {
                drawRoundRect(
                    brush = brush,
                    topLeft = origin,
                    size = Size(activeWidth, trackHeight),
                    cornerRadius = radius
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        )
    }
}

