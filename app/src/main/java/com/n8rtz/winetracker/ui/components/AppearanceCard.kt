package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.models.SensoryData

@Composable
fun AppearanceCard(
    sensoryData: SensoryData,
    onDataChange: (SensoryData) -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                // Score placeholder (15 points max)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(
                        text = "15 pts",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // Wine Base Type Segmented Control (White, Red, Rose)
            Text("Wine Type", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val types = listOf("Red", "White", "Rose")
                types.forEach { type ->
                    val selected = (sensoryData.colorType ?: "Red").equals(type, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { 
                            if (enabled) onDataChange(sensoryData.copy(colorType = type, colorHue = null, intensity = 0.5f)) 
                        },
                        label = { Text(type) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Dynamic Color Slider
            Text("Hue & Depth", style = MaterialTheme.typography.labelMedium)
            
            val currentType = sensoryData.colorType ?: "Red"
            val sliderValue = sensoryData.intensity ?: 0.5f

            DynamicColorSlider(
                value = sliderValue,
                onValueChange = { newValue ->
                    if (enabled) onDataChange(sensoryData.copy(intensity = newValue))
                },
                wineType = currentType
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Clarity Segmented Control
            Text("Clarity", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val clarities = listOf("Clear", "Hazy", "Turbid")
                clarities.forEach { clarity ->
                    val selected = sensoryData.clarity == clarity
                    FilterChip(
                        selected = selected,
                        onClick = { if (enabled) onDataChange(sensoryData.copy(clarity = clarity)) },
                        label = { Text(clarity) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
