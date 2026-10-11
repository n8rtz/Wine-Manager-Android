package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.models.SensoryData

@Composable
fun FlawsCard(
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
            // Header
            Text(
                text = "Flaws & Faults",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // Flaws Checkboxes in a simple 2-column layout (simulated with row/col)
            val allFlaws = listOf(
                "TCA (Cork Taint)", "Oxidation", 
                "Volatile Acidity", "Brettanomyces", 
                "Reduction", "Heat Damage"
            )
            
            val chunked = allFlaws.chunked(2)
            chunked.forEach { rowFlaws ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowFlaws.forEach { flaw ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Checkbox(
                                checked = sensoryData.flaws.contains(flaw),
                                onCheckedChange = { checked ->
                                    val newFlaws = sensoryData.flaws.toMutableList()
                                    if (checked) {
                                        newFlaws.add(flaw)
                                    } else {
                                        newFlaws.remove(flaw)
                                    }
                                    onDataChange(sensoryData.copy(flaws = newFlaws))
                                },
                                enabled = enabled
                            )
                            Text(text = flaw, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (rowFlaws.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
