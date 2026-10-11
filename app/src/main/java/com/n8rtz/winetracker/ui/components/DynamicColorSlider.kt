package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

fun interpolateColor(colors: List<Color>, t: Float): Color {
    if (colors.isEmpty()) return Color.Transparent
    if (colors.size == 1) return colors.first()
    
    val scaledT = t.coerceIn(0f, 1f) * (colors.size - 1)
    val index = scaledT.toInt()
    val fract = scaledT - index
    
    if (index >= colors.size - 1) return colors.last()
    
    val startColor = colors[index]
    val endColor = colors[index + 1]
    
    return lerp(startColor, endColor, fract)
}

@Composable
fun DynamicColorSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    wineType: String,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList() // Optional labels to draw under the track
) {
    // Define Gradients based on AWS standards
    val redColors = listOf(
        Color(0xFF5A2C55), // Purple
        Color(0xFF8B0000), // Ruby
        Color(0xFF800020), // Garnet
        Color(0xFF8C4004), // Tawny
        Color(0xFF5C3317)  // Brown
    )
    val whiteColors = listOf(
        Color(0xFFE4F0A3), // Lemon-Green
        Color(0xFFFDE910), // Lemon
        Color(0xFFFFD700), // Gold
        Color(0xFFFFAE42), // Amber
        Color(0xFF8B4513)  // Brown
    )
    val roseColors = listOf(
        Color(0xFFFFC0CB), // Pink
        Color(0xFFFA8072), // Salmon
        Color(0xFFFFA500), // Orange
        Color(0xFFD2B48C)  // Onion Skin
    )

    val currentColors = when (wineType.lowercase()) {
        "white" -> whiteColors
        "sparkling" -> whiteColors
        "rose" -> roseColors
        "rosé" -> roseColors
        else -> redColors // Default to red
    }

    // Default labels if none provided but matches standard steps
    val displayLabels = if (labels.isEmpty() && currentColors.size == redColors.size && wineType.lowercase() != "white") {
        listOf("Purple", "Ruby", "Garnet", "Tawny", "Brown")
    } else if (labels.isEmpty() && currentColors.size == whiteColors.size && (wineType.lowercase() == "white" || wineType.lowercase() == "sparkling")) {
        listOf("Lemon-Green", "Lemon", "Gold", "Amber", "Brown")
    } else if (labels.isEmpty() && currentColors.size == roseColors.size) {
        listOf("Pink", "Salmon", "Orange", "Onion Skin")
    } else {
        labels
    }

    val thumbColor = interpolateColor(currentColors, value)
    
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(80.dp).padding(vertical = 16.dp)) {
        val width = maxWidth.value
        
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentColors) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val newValue = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(newValue)
                    }
                }
        ) {
            val trackHeight = 16.dp.toPx()
            val thumbRadius = 14.dp.toPx()
            val cy = size.height / 2f
            
            // Draw Gradient Track
            val brush = Brush.horizontalGradient(colors = currentColors)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(0f, cy - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            )
            
            // Draw Thumb
            val thumbX = (value * size.width).coerceIn(thumbRadius, size.width - thumbRadius)
            
            // Draw thumb shadow/outer ring
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(thumbX, cy)
            )
            // Draw thumb inner color
            drawCircle(
                color = thumbColor,
                radius = thumbRadius - 2.dp.toPx(),
                center = Offset(thumbX, cy)
            )
            // Draw thumb border
            drawCircle(
                color = Color.LightGray,
                radius = thumbRadius,
                center = Offset(thumbX, cy),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        
        // Draw Labels underneath
        if (displayLabels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .offset(y = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                displayLabels.forEachIndexed { index, label ->
                    val weight = if (index == 0 || index == displayLabels.lastIndex) 0.5f else 1f
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(weight),
                        textAlign = if (index == 0) androidx.compose.ui.text.style.TextAlign.Start else if (index == displayLabels.lastIndex) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
