package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.HsvColor
import com.n8rtz.winetracker.data.local.resolveEffectiveColor

private val NeutralSwatch = Color(0xFFE0E0E0)

private data class WineColorOption(
    val family: String,
    val name: String,
    val color: HsvColor
)

/** Curated wine-like colors; there are no arbitrary blue, purple, or neon hue choices. */
private val WineColorOptions = listOf(
    WineColorOption("Red", "Deep ruby", HsvColor(350f, 0.78f, 0.43f)),
    WineColorOption("Red", "Ruby", HsvColor(350f, 0.76f, 0.67f)),
    WineColorOption("Red", "Garnet", HsvColor(358f, 0.61f, 0.52f)),
    WineColorOption("Red", "Tawny", HsvColor(20f, 0.48f, 0.57f)),
    WineColorOption("White", "Pale straw", HsvColor(50f, 0.30f, 0.94f)),
    WineColorOption("White", "Lemon", HsvColor(52f, 0.55f, 0.88f)),
    WineColorOption("White", "Gold", HsvColor(43f, 0.65f, 0.76f)),
    WineColorOption("White", "Amber", HsvColor(32f, 0.63f, 0.63f)),
    WineColorOption("Rosé", "Pale salmon", HsvColor(12f, 0.28f, 0.94f)),
    WineColorOption("Rosé", "Salmon", HsvColor(9f, 0.48f, 0.84f)),
    WineColorOption("Rosé", "Copper", HsvColor(18f, 0.56f, 0.68f))
)

/** A compact dropdown palette built only from common red, white, and rosé wine colors. */
@Composable
fun WineColorPicker(
    color: HsvColor?,
    onColorChange: (HsvColor?) -> Unit,
    modifier: Modifier = Modifier,
    includeWineDefault: Boolean = false,
    defaultColor: HsvColor? = null
) {
    Column(modifier = modifier) {
        if (includeWineDefault) {
            DropdownMenuItem(
                text = { Text("Wine default") },
                leadingIcon = { WineColorSwatch(defaultColor) },
                trailingIcon = {
                    if (color == null) Icon(Icons.Default.Check, contentDescription = "Selected")
                },
                onClick = { onColorChange(null) }
            )
            HorizontalDivider()
        }

        WineColorOptions.groupBy { it.family }.forEach { (family, options) ->
            Text(
                text = family,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    leadingIcon = { WineColorSwatch(option.color) },
                    trailingIcon = {
                        if (color == option.color) Icon(Icons.Default.Check, contentDescription = "Selected")
                    },
                    onClick = { onColorChange(option.color) }
                )
            }
        }
    }
}

@Composable
private fun WineColorSwatch(color: HsvColor?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color?.let { Color(it.toArgb()) } ?: NeutralSwatch)
    )
}

@Composable
private fun ColorSwatchButton(
    color: HsvColor?,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color?.let { Color(it.toArgb()) } ?: NeutralSwatch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
    )
}

@Composable
fun NoteColorField(
    definitionColor: HsvColor?,
    noteColor: HsvColor?,
    onNoteColorChange: (HsvColor?) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false
) {
    var showPicker by remember { mutableStateOf(false) }
    val effective = resolveEffectiveColor(noteColor, definitionColor)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            ColorSwatchButton(
                color = effective,
                description = when {
                    readOnly -> "Wine color"
                    noteColor == null && definitionColor != null -> "Choose wine color, currently using wine default"
                    else -> "Choose wine color"
                },
                enabled = !readOnly,
                onClick = { showPicker = true }
            )
            if (!readOnly) {
                DropdownMenu(
                    expanded = showPicker,
                    onDismissRequest = { showPicker = false },
                    modifier = Modifier.widthIn(min = 220.dp)
                ) {
                    WineColorPicker(
                        color = noteColor,
                        defaultColor = definitionColor,
                        includeWineDefault = true,
                        onColorChange = { selected ->
                            onNoteColorChange(selected)
                            showPicker = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DefaultColorField(
    color: HsvColor?,
    onColorChange: (HsvColor?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            ColorSwatchButton(
                color = color,
                description = "Choose default wine color",
                onClick = { showPicker = true }
            )
            DropdownMenu(
                expanded = showPicker,
                onDismissRequest = { showPicker = false },
                modifier = Modifier.widthIn(min = 220.dp)
            ) {
                WineColorPicker(
                    color = color,
                    onColorChange = { selected ->
                        onColorChange(selected)
                        showPicker = false
                    }
                )
            }
        }
        if (color != null) {
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = { onColorChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = "Clear wine color")
            }
        }
    }
}
