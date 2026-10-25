package com.yourapp.wine.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Candidate chips rendered directly below the corresponding editable field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanFieldChoices(field:ReviewedField,onSelectChoice:(String)->Unit,onDismissChoices:()->Unit,enabled:Boolean=true) {
    if(field.value.isNotBlank() || (!field.clearedByUser && field.edited) || field.choices.isEmpty() || field.intentionallyBlank) return
    Column {
        Text("Possible matches — select only if correct",style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            field.choices.forEach { choice ->
                AssistChip(onClick={onSelectChoice(choice.value)},enabled=enabled,
                    label={Text(choice.value)},modifier=Modifier.widthIn(max=280.dp))
            }
        }
        TextButton(onClick=onDismissChoices,enabled=enabled) { Text("None — leave blank / type below") }
    }
}
