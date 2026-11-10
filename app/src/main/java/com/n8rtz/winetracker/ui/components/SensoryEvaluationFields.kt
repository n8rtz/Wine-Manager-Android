@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.SensoryData
import kotlin.math.roundToInt

internal data class SensoryTraitControl(
    val title: String,
    val descriptors: List<String>,
    val value: Int,
    val onValueChange: (Int) -> Unit
)

internal fun sensoryTraitControls(
    data: SensoryData,
    onChange: (SensoryData) -> Unit
): List<SensoryTraitControl> = listOf(
        SensoryTraitControl("Sweetness", listOf("Bone dry", "Dry", "Off-dry", "Medium sweet", "Sweet", "Luscious"), data.sweetness) { onChange(data.copy(sweetness = it)) },
        SensoryTraitControl("Aroma", listOf("Very subdued", "Subdued", "Moderate", "Expressive", "Powerful"), data.aroma) { onChange(data.copy(aroma = it)) },
        SensoryTraitControl("Acidity", listOf("Flabby", "Low", "Balanced", "Crisp", "Tart / searing"), data.acidity) { onChange(data.copy(acidity = it)) },
        SensoryTraitControl("Tannins / grip", listOf("Silky", "Low", "Medium", "Grippy", "Harsh"), data.tannins) { onChange(data.copy(tannins = it)) },
        SensoryTraitControl("Body", listOf("Light", "Medium-minus", "Medium", "Medium-plus", "Full"), data.body) { onChange(data.copy(body = it)) },
        SensoryTraitControl("Balance", listOf("Disjointed", "Unbalanced", "Acceptable", "Well-balanced", "Seamless"), data.balance) { onChange(data.copy(balance = it)) },
        SensoryTraitControl("Concentration", listOf("Weak", "Light", "Moderate", "Pronounced", "Overwhelming"), data.intensity) { onChange(data.copy(intensity = it)) },
        SensoryTraitControl("Complexity", listOf("Simple", "Moderate", "Complex", "Highly complex"), data.complexity) { onChange(data.copy(complexity = it)) },
        SensoryTraitControl("Finish", listOf("Short", "Medium-minus", "Medium", "Long", "Lingering"), data.finish) { onChange(data.copy(finish = it)) },
        SensoryTraitControl("Quality", listOf("Poor", "Acceptable", "Good", "Very good", "Outstanding"), data.quality) { onChange(data.copy(quality = it)) }
    )

@Composable
fun SensoryEvaluationFields(
    data: SensoryData,
    onChange: (SensoryData) -> Unit
) {
    val traits = sensoryTraitControls(data, onChange)
    traits.forEach { trait -> SensoryTraitSlider(trait) }

}

internal data class OverallScoreBand(val label: String, val color: Color)

private val overallScoreMarkers = listOf(50, 60, 70, 80, 90, 100)
private val overallScoreGradient = listOf(
    Color(0xFFB94F57),
    Color(0xFFD47941),
    Color(0xFFD3A33D),
    Color(0xFF9A9A42),
    Color(0xFF5F9259),
    Color(0xFF347A59)
)

internal fun overallScoreBand(score: Int): OverallScoreBand = when {
    score >= 98 -> OverallScoreBand("Extraordinary", Color(0xFF27664A))
    score >= 94 -> OverallScoreBand("Outstanding", Color(0xFF347A59))
    score >= 90 -> OverallScoreBand("Excellent", Color(0xFF3E7448))
    score >= 86 -> OverallScoreBand("Very Good", Color(0xFF5C773A))
    score >= 80 -> OverallScoreBand("Good", Color(0xFF6E7028))
    score >= 70 -> OverallScoreBand("Average", Color(0xFF8A6B1E))
    score >= 60 -> OverallScoreBand("Below Average", Color(0xFFA85F31))
    else -> OverallScoreBand("Unacceptable", Color(0xFFA9444D))
}

/**
 * Continuous colour for a 50-100 rating, used to fill the WineCard rating
 * box. Most rated wines land between 80 and 95, so that stretch of the
 * scale gets the most stops: it runs Yellow-green (80) through clear green
 * (85 -- anything 85+ is decent, so Yellow, which reads as mediocre, is
 * gone by then), then simply gets progressively more green through the
 * 90s: a strong green at 90 deepening step by step to a rich, dark green
 * at 95 and above. Below 80 the scale runs back through Yellow and amber
 * to orange-red and deep red, so weaker wines are just as easy to tell
 * apart.
 */
private val ratingColorStops: List<Pair<Int, Color>> = listOf(
    50 to Color(0xFFB03A3A),
    60 to Color(0xFFD3542E),
    70 to Color(0xFFE39A1E),
    75 to Color(0xFFD9B81F),
    80 to Color(0xFFA8B93C),
    82 to Color(0xFF86B04A),
    85 to Color(0xFF55A04A),
    88 to Color(0xFF439F4E),
    90 to Color(0xFF2E8B47),
    92 to Color(0xFF227A41),
    95 to Color(0xFF146B3A),
    100 to Color(0xFF0E5230)
)

internal fun ratingColor(score: Int): Color {
    val clamped = score.coerceIn(
        ratingColorStops.first().first,
        ratingColorStops.last().first
    )
    val lower = ratingColorStops.last { it.first <= clamped }
    val upper = ratingColorStops.first { it.first >= clamped }
    if (lower.first == upper.first) return lower.second
    val fraction = (clamped - lower.first).toFloat() / (upper.first - lower.first)
    return colorLerp(lower.second, upper.second, fraction)
}

@Composable
fun OverallScoreSlider(
    score: Float,
    scoreSelected: Boolean = true,
    enabled: Boolean = true,
    onScoreChange: (Float) -> Unit
) {
    // Reserve a font-scaled label width at both endpoints. The explicit thumb
    // width gives Material's gesture range exactly the same inset as our track.
    val markerLabelWidth = with(LocalDensity.current) { 32.sp.toDp() }
    val sliderInset = markerLabelWidth / 2
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
                val inset = sliderInset.toPx()
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
                enabled = enabled,
                valueRange = 50f..100f,
                steps = 49,
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    Box(
                        modifier = Modifier.width(markerLabelWidth).height(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.width(4.dp).height(40.dp)
                            .background(indicatorColor, RoundedCornerShape(2.dp)))
                    }
                },
                // The track must occupy the available width even though the
                // gradient is drawn by the Canvas behind it. An empty slot
                // collapses the measured travel range and pins the thumb.
                track = {
                    Spacer(Modifier.fillMaxWidth().height(8.dp))
                },
                colors = SliderDefaults.colors(
                    thumbColor = indicatorColor,
                    disabledThumbColor = indicatorColor,
                    activeTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledActiveTrackColor = Color.Transparent,
                    disabledActiveTickColor = Color.Transparent,
                    disabledInactiveTrackColor = Color.Transparent,
                    disabledInactiveTickColor = Color.Transparent
                )
            )
        }

        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val markerTrackWidth = (maxWidth - markerLabelWidth).coerceAtLeast(0.dp)
            overallScoreMarkers.forEach { marker ->
                val fraction = (marker - 50) / 50f
                Text(
                    text = marker.toString(),
                    modifier = Modifier
                        .offset(x = markerTrackWidth * fraction)
                        .width(markerLabelWidth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun SensoryTraitSlider(trait: SensoryTraitControl, enabled: Boolean = true) {
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
            enabled = enabled,
            onValueChange = { trait.onValueChange(it.roundToInt().coerceIn(0, maxValue)) }
        )
    }
}

internal fun gradientColorsFor(title: String): List<Color> = when (title) {
    "Aroma" -> listOf(Color(0xFFF6DCE6), Color(0xFFA25D89), Color(0xFF4B1F55))
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
internal fun GradientSensorySlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    gradientColors: List<Color>,
    enabled: Boolean = true,
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
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                disabledThumbColor = thumbColor,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent
            )
        )
    }
}

