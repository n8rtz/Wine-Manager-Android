package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataExploration
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.models.SensoryData

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoseCard(
    sensoryData: SensoryData,
    onDataChange: (SensoryData) -> Unit,
    onOpenAromaWheel: () -> Unit,
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nose (Aroma & Bouquet)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(
                        text = "30 pts",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Intensity Slider
            Text("Intensity", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = sensoryData.noseIntensity ?: 0.5f,
                onValueChange = { if (enabled) onDataChange(sensoryData.copy(noseIntensity = it)) },
                valueRange = 0f..1f,
                steps = 3, // Light, Medium-, Medium, Medium+, Pronounced
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Light", style = MaterialTheme.typography.bodySmall)
                Text("Pronounced", style = MaterialTheme.typography.bodySmall)
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(24.dp))

                // Interactive Aroma Wheel Button
                Button(
                    onClick = onOpenAromaWheel,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.DataExploration, contentDescription = "Aroma Wheel")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Aroma Wheel", fontWeight = FontWeight.Bold)
                }
            }

            // Selected Aromas
            if (sensoryData.aromas.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Selected Aromas:", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sensoryData.aromas.forEach { aroma ->
                        InputChip(
                            selected = true,
                            onClick = { 
                                if (enabled) {
                                    val newAromas = sensoryData.aromas.toMutableList()
                                    newAromas.remove(aroma)
                                    onDataChange(sensoryData.copy(aromas = newAromas))
                                }
                            },
                            label = { Text(aroma) }
                        )
                    }
                }
            }
        }
    }
}
