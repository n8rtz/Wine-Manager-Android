package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HistoryWineCard(
    maker: String,
    name: String,
    vintage: String,
    date: String, // Focus: "MMM dd, yyyy"
    quantity: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 16.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Emphasis: The Date is now the primary header for the record
                Text(
                    text = date.uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color(0xFF722F37) // Bordeaux Red
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Wine Identity
                Text(
                    text = "$maker $name",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF1C1C1E)
                )
                
                Text(
                    text = "Vintage: $vintage",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E8E93)
                )
            }

            // High-density quantity badge
            Surface(
                color = Color(0xFFF8F9FA),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "QTY: $quantity",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF1C1C1E)
                )
            }
        }
    }
}
