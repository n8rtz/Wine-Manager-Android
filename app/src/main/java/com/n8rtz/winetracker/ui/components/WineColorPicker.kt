package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.n8rtz.winetracker.data.local.HsvColor
import com.n8rtz.winetracker.data.local.resolveEffectiveColor
import kotlin.math.roundToInt

private val NeutralSwatch = Color(0xFFE0E0E0)

// Wine colors span burgundy/red through amber/straw, crossing hue zero.
private const val WINE_HUE_START = 330f
private const val WINE_HUE_SPAN = 90f
private const val WINE_HUE_END = 60f

private fun normalizedHue(hue: Float): Float {
    if (hue.isNaN() || hue.isInfinite()) return WINE_HUE_START
    return ((hue % 360f) + 360f) % 360f
}

private fun wineHueAtFraction(fraction: Float): Float =
    (WINE_HUE_START + fraction.coerceIn(0f, 1f) * WINE_HUE_SPAN) % 360f

private fun isWineHue(hue: Float): Boolean {
    if (hue.isNaN() || hue.isInfinite() || hue !in 0f..360f) return false
    val normalized = normalizedHue(hue)
    return normalized >= WINE_HUE_START || normalized <= WINE_HUE_END
}

private fun wineHueFraction(hue: Float): Float {
    val normalized = normalizedHue(hue)
    val offsetFromStart = (normalized - WINE_HUE_START + 360f) % 360f
    if (offsetFromStart <= WINE_HUE_SPAN) return offsetFromStart / WINE_HUE_SPAN

    val distanceToStart = minOf(
        (normalized - WINE_HUE_START + 360f) % 360f,
        (WINE_HUE_START - normalized + 360f) % 360f
    )
    val distanceToEnd = minOf(
        (normalized - WINE_HUE_END + 360f) % 360f,
        (WINE_HUE_END - normalized + 360f) % 360f
    )
    return if (distanceToStart <= distanceToEnd) 0f else 1f
}

@Composable
fun WineColorPicker(
    color: HsvColor?,
    onColorChange: (HsvColor?) -> Unit,
    modifier: Modifier = Modifier
) {
    val current = color ?: HsvColor(0f, 0f, 1f)
    val currentState by rememberUpdatedState(current)
    val currentCallback by rememberUpdatedState(onColorChange)
    var hText by remember { mutableStateOf(current.hue.roundToInt().toString()) }
    var sText by remember { mutableStateOf("%.2f".format(current.saturation)) }
    var vText by remember { mutableStateOf("%.2f".format(current.value)) }

    LaunchedEffect(current) {
        hText = current.hue.roundToInt().toString()
        sText = "%.2f".format(current.saturation)
        vText = "%.2f".format(current.value)
    }

    fun updateHue(hue: Float) {
        if (isWineHue(hue)) currentCallback(currentState.copy(hue = normalizedHue(hue)))
    }

    fun updateSaturationValue(saturation: Float, value: Float) {
        if (saturation in 0f..1f && value in 0f..1f) {
            currentCallback(currentState.copy(saturation = saturation, value = value))
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(current.toArgb()))
            )
            Spacer(Modifier.size(12.dp))
            Text("H", style = MaterialTheme.typography.labelSmall)
        }

        HueStrip(
            hue = current.hue,
            onHueChange = ::updateHue,
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )

        SaturationValueSquare(
            hue = current.hue,
            saturation = current.saturation,
            value = current.value,
            onSaturationValueChange = ::updateSaturationValue,
            modifier = Modifier.fillMaxWidth().height(170.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumericColorField(
                label = "H",
                value = hText,
                onValueChange = { text ->
                    hText = text
                    text.toFloatOrNull()?.let { updateHue(it) }
                },
                modifier = Modifier.weight(1f)
            )
            NumericColorField(
                label = "S",
                value = sText,
                onValueChange = { text ->
                    sText = text
                    text.toFloatOrNull()?.let { updateSaturationValue(it, current.value) }
                },
                modifier = Modifier.weight(1f)
            )
            NumericColorField(
                label = "V",
                value = vText,
                onValueChange = { text ->
                    vText = text
                    text.toFloatOrNull()?.let { updateSaturationValue(current.saturation, it) }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NumericColorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun HueStrip(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val callback by rememberUpdatedState(onHueChange)
    val colors = remember {
        (0..12).map { step ->
            Color(HsvColor(wineHueAtFraction(step / 12f), 1f, 1f).toArgb())
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    callback(wineHueAtFraction(offset.x / size.width.toFloat()))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        callback(wineHueAtFraction(offset.x / size.width.toFloat()))
                    },
                    onDrag = { change, _ ->
                        callback(wineHueAtFraction(change.position.x / size.width.toFloat()))
                        change.consume()
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = Offset(size.width * wineHueFraction(hue), size.height / 2f)
            )
        }
    }
}

@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val callback by rememberUpdatedState(onSaturationValueChange)
    val base = Color(HsvColor(hue, 1f, 1f).toArgb())
    fun setFromOffset(x: Float, y: Float, width: Float, height: Float) {
        val s = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val v = (1f - y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        callback(s, v)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(base)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    setFromOffset(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        setFromOffset(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        setFromOffset(
                            change.position.x,
                            change.position.y,
                            size.width.toFloat(),
                            size.height.toFloat()
                        )
                        change.consume()
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent))
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
            )
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(
                    size.width * saturation.coerceIn(0f, 1f),
                    size.height * (1f - value.coerceIn(0f, 1f))
                )
            )
        }
    }
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
    var draft by remember { mutableStateOf(noteColor ?: definitionColor ?: HsvColor(0f, 0f, 1f)) }
    val effective = resolveEffectiveColor(noteColor, definitionColor)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ColorSwatchButton(
            color = effective,
            description = if (readOnly) "Wine color" else "Choose wine color",
            enabled = !readOnly,
            onClick = {
                draft = noteColor ?: definitionColor ?: HsvColor(0f, 0f, 1f)
                showPicker = true
            }
        )
        if (!readOnly && noteColor != null) {
            IconButton(onClick = { onNoteColorChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = "Clear tasting-note color override")
            }
        }
    }

    if (showPicker && !readOnly) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Color") },
            text = {
                WineColorPicker(
                    color = draft,
                    onColorChange = { draft = it ?: HsvColor(0f, 0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onNoteColorChange(draft)
                    showPicker = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DefaultColorField(
    color: HsvColor?,
    onColorChange: (HsvColor?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(color ?: HsvColor(0f, 0f, 1f)) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ColorSwatchButton(
            color = color,
            description = "Choose default wine color",
            onClick = {
                draft = color ?: HsvColor(0f, 0f, 1f)
                showPicker = true
            }
        )
        if (color != null) {
            IconButton(onClick = { onColorChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = "Clear wine default color")
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Color") },
            text = {
                WineColorPicker(
                    color = draft,
                    onColorChange = { draft = it ?: HsvColor(0f, 0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onColorChange(draft)
                    showPicker = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        )
    }
}
