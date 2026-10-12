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
fun PalateCard(
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Palate (Taste & Texture)",
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
                        text = "40 pts",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Structural Micro-Sliders
            StructuralSlider("Sweetness", "Bone Dry", "Sweet", sensoryData.sweetness ?: 0.2f, enabled = enabled) { 
                onDataChange(sensoryData.copy(sweetness = it)) 
            }
            StructuralSlider("Acidity", "Low", "High", sensoryData.acidity ?: 0.5f, enabled = enabled) { 
                onDataChange(sensoryData.copy(acidity = it)) 
            }
            StructuralSlider("Tannin", "Low", "High", sensoryData.tannin ?: 0.5f, enabled = enabled) { 
                onDataChange(sensoryData.copy(tannin = it)) 
            }
            StructuralSlider("Alcohol", "Low", "High", sensoryData.alcohol ?: 0.5f, enabled = enabled) { 
                onDataChange(sensoryData.copy(alcohol = it)) 
            }
            StructuralSlider("Body", "Light", "Full", sensoryData.body ?: 0.5f, enabled = enabled) { 
                onDataChange(sensoryData.copy(body = it)) 
            }
        }
    }
}

@Composable
fun StructuralSlider(
    label: String,
    leftLabel: String,
    rightLabel: String,
    value: Float,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = { if (enabled) onValueChange(it) },
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..1f,
            steps = 3 // 5 stops total
        )
        Row(modifier = Modifier.fillMaxWidth().offset(y = (-8).dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(leftLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rightLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
