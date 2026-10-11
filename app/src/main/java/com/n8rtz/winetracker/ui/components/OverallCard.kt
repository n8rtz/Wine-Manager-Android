package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n8rtz.winetracker.data.models.SensoryData

@Composable
fun OverallCard(
    sensoryData: SensoryData,
    onDataChange: (SensoryData) -> Unit,
    notes: String,
    onNotesChange: (String) -> Unit,
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
            // Big Score Display
            val score = sensoryData.overallScore ?: calculateBaseScore(sensoryData)
            
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (enabled) {
                    IconButton(onClick = { onDataChange(sensoryData.copy(overallScore = (score - 1).coerceAtLeast(50))) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease Score")
                    }
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = if (enabled) 0.dp else 16.dp)) {
                    Text(
                        text = "$score",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = getQualityDescriptor(score),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (enabled) {
                    IconButton(onClick = { onDataChange(sensoryData.copy(overallScore = (score + 1).coerceAtMost(100))) }) {
                        Icon(Icons.Default.Add, contentDescription = "Increase Score")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = notes,
                onValueChange = onNotesChange,
                label = { Text("Tasting Notes") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                maxLines = 10,
                readOnly = !enabled
            )
        }
    }
}

// Basic auto-calculation based loosely on slider values (mock logic for now)
fun calculateBaseScore(data: SensoryData): Int {
    val base = 50
    val appearancePts = ((data.intensity ?: 0.5f) * 15).toInt()
    val nosePts = ((data.noseIntensity ?: 0.5f) * 30).toInt()
    val palatePts = (((data.body ?: 0.5f) + (data.tannin ?: 0.5f)) / 2f * 40).toInt()
    val finishPts = ((data.finishLength ?: 0.5f) * 15).toInt()
    
    return (base + appearancePts * 0.3 + nosePts * 0.3 + palatePts * 0.4 + finishPts * 0.3).toInt().coerceIn(50, 100)
}

fun getQualityDescriptor(score: Int): String {
    return when {
        score >= 95 -> "Classic / Extraordinary"
        score >= 90 -> "Outstanding"
        score >= 85 -> "Very Good"
        score >= 80 -> "Good"
        score >= 70 -> "Average"
        else -> "Below Average"
    }
}
