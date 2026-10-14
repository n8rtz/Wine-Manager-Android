package com.yourapp.wine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yourapp.wine.fill.UiState

/** No coordinate conversion: accessible text + color states beside each of the six form fields. */
@Composable
fun ScanFieldStatus(field: ReviewedField, onConfirm: () -> Unit, onRetake: () -> Unit) {
    val state = field.state ?: return
    val color = when (state) {
        UiState.GREEN -> Color(0xff246b45)
        UiState.YELLOW -> Color(0xff996200)
        UiState.RED -> Color(0xffb3261e)
    }
    Column(Modifier.fillMaxWidth().background(color.copy(alpha = .08f)).padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp, 20.dp).background(color))
            Spacer(Modifier.width(8.dp))
            Text(when (state) {
                UiState.GREEN -> "GREEN · Validated"
                UiState.YELLOW -> if (field.confirmed) "YELLOW · Confirmed / editable" else "YELLOW · Confirm this field"
                UiState.RED -> "RED · Retake or enter manually"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = color)
            if (state == UiState.YELLOW && !field.confirmed) TextButton(onClick = onConfirm) { Text("Confirm") }
            if (state == UiState.RED) TextButton(onClick = onRetake) { Text("Retake") }
        }
        Text(field.reason, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(bottom = 4.dp))
    }
}
