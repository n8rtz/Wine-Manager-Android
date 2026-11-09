package com.n8rtz.winetracker.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.n8rtz.winetracker.data.local.HsvColor
import com.n8rtz.winetracker.data.local.resolveEffectiveColor
import kotlinx.coroutines.delay
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
    fun updateHue(hue: Float) {
        if (isWineHue(hue)) currentCallback(currentState.copy(hue = normalizedHue(hue)))
    }

    fun updateSaturationValue(saturation: Float, value: Float) {
        if (saturation in 0f..1f && value in 0f..1f) {
            currentCallback(currentState.copy(saturation = saturation, value = value))
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WineGlassPreview(
                color = current,
                modifier = Modifier.width(80.dp).height(200.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HueStrip(
                    hue = current.hue,
                    color = current,
                    onHueChange = ::updateHue,
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                SaturationValueSquare(
                    hue = current.hue,
                    saturation = current.saturation,
                    value = current.value,
                    color = current,
                    onSaturationValueChange = ::updateSaturationValue,
                    modifier = Modifier.fillMaxWidth().height(170.dp)
                )
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(
    draft: HsvColor,
    onDraftChange: (HsvColor) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                WineColorPicker(
                    color = draft,
                    onColorChange = { onDraftChange(it ?: HsvColor(0f, 0f, 1f)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                    IconButton(onClick = onConfirm) {
                        Icon(Icons.Default.Check, contentDescription = "Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun WineGlassPreview(color: HsvColor, modifier: Modifier = Modifier) {
    val wine = Color(color.toArgb())
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val top = height * 0.07f
        val bowlBottom = height * 0.60f
        val bowlHeight = bowlBottom - top
        // Bordeaux bowl: narrower rim, broad rounded shoulders, and a rounded lower taper.
        val rimLeft = width * 0.15f
        val rimRight = width * 0.85f
        val bowlLeft = width * 0.035f
        val bowlRight = width * 0.965f
        val shoulderY = top + bowlHeight * 0.52f
        val upperControlY = top + bowlHeight * 0.18f
        val upperShoulderControlY = top + bowlHeight * 0.36f
        val lowerControlY = top + bowlHeight * 0.86f
        val bottomHalfWidth = width * 0.17f
        val strokeWidth = 2.dp.toPx()
        val rimHeight = 5.dp.toPx()

        val bowlFill = Path().apply {
            moveTo(rimLeft, top)
            cubicTo(
                rimLeft - width * 0.015f,
                upperControlY,
                bowlLeft,
                upperShoulderControlY,
                bowlLeft,
                shoulderY
            )
            cubicTo(bowlLeft, lowerControlY, centerX - bottomHalfWidth, bowlBottom, centerX, bowlBottom)
            cubicTo(centerX + bottomHalfWidth, bowlBottom, bowlRight, lowerControlY, bowlRight, shoulderY)
            cubicTo(
                bowlRight,
                upperShoulderControlY,
                rimRight + width * 0.015f,
                upperControlY,
                rimRight,
                top
            )
            close()
        }
        drawPath(bowlFill, Color.White.copy(alpha = 0.25f))

        val liquidTop = top + bowlHeight * 0.52f
        val liquidHalfWidth = width * 0.42f
        val liquidLeft = centerX - liquidHalfWidth
        val liquidRight = centerX + liquidHalfWidth
        val liquidBottom = bowlBottom - bowlHeight * 0.07f
        val lowerLiquidControlY = liquidTop + (liquidBottom - liquidTop) * 0.45f
        val liquid = Path().apply {
            moveTo(liquidLeft, liquidTop)
            quadraticTo(centerX, liquidTop + strokeWidth * 0.7f, liquidRight, liquidTop)
            cubicTo(
                liquidRight - width * 0.02f,
                lowerLiquidControlY,
                centerX + width * 0.16f,
                liquidBottom,
                centerX,
                liquidBottom
            )
            cubicTo(
                centerX - width * 0.16f,
                liquidBottom,
                liquidLeft + width * 0.02f,
                lowerLiquidControlY,
                liquidLeft,
                liquidTop
            )
            close()
        }
        drawPath(liquid, wine)

        val bowlSides = Path().apply {
            moveTo(rimLeft, top)
            cubicTo(
                rimLeft - width * 0.015f,
                upperControlY,
                bowlLeft,
                upperShoulderControlY,
                bowlLeft,
                shoulderY
            )
            cubicTo(bowlLeft, lowerControlY, centerX - bottomHalfWidth, bowlBottom, centerX, bowlBottom)
            moveTo(centerX, bowlBottom)
            cubicTo(centerX + bottomHalfWidth, bowlBottom, bowlRight, lowerControlY, bowlRight, shoulderY)
            cubicTo(
                bowlRight,
                upperShoulderControlY,
                rimRight + width * 0.015f,
                upperControlY,
                rimRight,
                top
            )
        }
        drawPath(bowlSides, outline, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        drawOval(
            color = outline,
            topLeft = Offset(rimLeft, top - rimHeight / 2f),
            size = Size(rimRight - rimLeft, rimHeight),
            style = Stroke(width = strokeWidth)
        )
        val stemEndY = height * 0.90f
        drawLine(
            color = outline,
            start = Offset(centerX, bowlBottom),
            end = Offset(centerX, stemEndY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        val footSize = Size(width * 0.68f, height * 0.04f)
        drawOval(
            color = outline,
            topLeft = Offset(centerX - footSize.width / 2f, stemEndY),
            size = footSize,
            style = Stroke(width = strokeWidth)
        )
        drawLine(
            color = Color.White.copy(alpha = 0.8f),
            start = Offset(bowlLeft + width * 0.16f, top + height * 0.10f),
            end = Offset(bowlLeft + width * 0.18f, top + height * 0.25f),
            strokeWidth = strokeWidth * 0.75f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun FloatingColorPreviewSwatch(
    color: HsvColor,
    position: Offset?,
    controlSize: IntSize,
    fixedY: Float? = null
) {
    val pointer = position ?: return
    if (controlSize.width <= 0) return

    val density = LocalDensity.current
    val swatchSize = 56.dp
    val swatchPx = with(density) { swatchSize.roundToPx() }
    val gapPx = with(density) { 28.dp.roundToPx() }
    val left = (pointer.x.roundToInt() - swatchPx / 2)
        .coerceIn(0, (controlSize.width - swatchPx).coerceAtLeast(0))
    val top = (fixedY ?: pointer.y).roundToInt() - swatchPx - gapPx

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(left, top),
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(color.toArgb()),
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.size(swatchSize)
        ) {}
    }
}

@Composable
private fun HueStrip(
    hue: Float,
    color: HsvColor,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val callback by rememberUpdatedState(onHueChange)
    var previewPosition by remember { mutableStateOf<Offset?>(null) }
    var previewEpoch by remember { mutableStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var controlSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(previewEpoch, isDragging) {
        if (previewPosition != null && !isDragging) {
            delay(700)
            previewPosition = null
        }
    }
    val colors = remember {
        (0..12).map { step ->
            Color(HsvColor(wineHueAtFraction(step / 12f), 1f, 1f).toArgb())
        }
    }
    Box(
        modifier = modifier
            .onSizeChanged { controlSize = it }
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    callback(wineHueAtFraction(offset.x / size.width.toFloat()))
                    previewPosition = offset
                    previewEpoch++
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        previewPosition = offset
                        callback(wineHueAtFraction(offset.x / size.width.toFloat()))
                    },
                    onDrag = { change, _ ->
                        previewPosition = change.position
                        callback(wineHueAtFraction(change.position.x / size.width.toFloat()))
                        change.consume()
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
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
        FloatingColorPreviewSwatch(
            color,
            previewPosition,
            controlSize,
            fixedY = controlSize.height / 2f
        )
    }
}

@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    color: HsvColor,
    onSaturationValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val callback by rememberUpdatedState(onSaturationValueChange)
    var previewPosition by remember { mutableStateOf<Offset?>(null) }
    var previewEpoch by remember { mutableStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var controlSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(previewEpoch, isDragging) {
        if (previewPosition != null && !isDragging) {
            delay(700)
            previewPosition = null
        }
    }
    val base = Color(HsvColor(hue, 1f, 1f).toArgb())
    fun setFromOffset(x: Float, y: Float, width: Float, height: Float) {
        val s = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val v = (1f - y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        callback(s, v)
    }
    Box(
        modifier = modifier
            .onSizeChanged { controlSize = it }
            .clip(RoundedCornerShape(8.dp))
            .background(base)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    previewPosition = offset
                    previewEpoch++
                    setFromOffset(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        previewPosition = offset
                        setFromOffset(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                    },
                    onDrag = { change, _ ->
                        previewPosition = change.position
                        setFromOffset(
                            change.position.x,
                            change.position.y,
                            size.width.toFloat(),
                            size.height.toFloat()
                        )
                        change.consume()
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
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
        FloatingColorPreviewSwatch(color, previewPosition, controlSize)
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
    readOnly: Boolean = false,
    inline: Boolean = false
) {
    var showPicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(noteColor ?: definitionColor ?: HsvColor(0f, 0f, 1f)) }
    val effective = resolveEffectiveColor(noteColor, definitionColor)

    Row(
        modifier = if (inline) modifier else modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (inline) 6.dp else 8.dp)
    ) {
        Text("Color", style = MaterialTheme.typography.bodyMedium)
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
            if (!inline) Spacer(Modifier.weight(1f))
            IconButton(onClick = { onNoteColorChange(null) }) {
                Icon(Icons.Default.Close, contentDescription = "Clear tasting-note color override")
            }
        }
    }

    if (showPicker && !readOnly) {
        ColorPickerDialog(
            draft = draft,
            onDraftChange = { draft = it },
            onConfirm = {
                onNoteColorChange(draft)
                showPicker = false
            },
            onDismiss = { showPicker = false }
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
        ColorPickerDialog(
            draft = draft,
            onDraftChange = { draft = it },
            onConfirm = {
                onColorChange(draft)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}
