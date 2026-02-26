package org.evolution.settings.utils

import android.graphics.Color as GraphicsColor
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.evolution.settings.utils.ThemeStyle
import org.evolution.settings.utils.toArgb
import kotlin.math.*

@Composable
fun StylePickerDialog(
    currentStyle: ThemeStyle,
    onDismiss: () -> Unit,
    onStyleSelected: (ThemeStyle) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(320.dp)
            ) {
                Text(
                    text = "Theme Style",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeStyle.values().forEach { style ->
                        val isSelected = style == currentStyle
                        Surface(
                            onClick = { onStyleSelected(style) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                MaterialTheme.colorScheme.surface,
                            border = if (isSelected) 
                                null 
                            else 
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = style.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    var selectedColor by remember { mutableStateOf(initialColor) }
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0.5f) }
    var brightness by remember { mutableStateOf(0.5f) }
    var hexInput by remember { mutableStateOf("") }
    var hexError by remember { mutableStateOf(false) }

    LaunchedEffect(initialColor) {
        val hsv = FloatArray(3)
        GraphicsColor.colorToHSV(initialColor.toArgb(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        selectedColor = Color.hsv(hue, saturation, brightness)
        hexInput = String.format("%06X", 0xFFFFFF and selectedColor.toArgb())
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(320.dp)
            ) {
                Text(
                    text = "Choose Seed Color",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(selectedColor)
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val cleaned = input.replace("#", "").uppercase()
                        if (cleaned.length <= 6 && cleaned.all { it in "0123456789ABCDEF" }) {
                            hexInput = cleaned
                            hexError = false
                            
                            if (cleaned.length == 6) {
                                try {
                                    val color = Color(GraphicsColor.parseColor("#$cleaned"))
                                    selectedColor = color
                                    
                                    val hsv = FloatArray(3)
                                    GraphicsColor.colorToHSV(color.toArgb(), hsv)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    brightness = hsv[2]
                                } catch (e: Exception) {
                                    hexError = true
                                }
                            }
                        }
                    },
                    label = { Text("HEX Color") },
                    prefix = { Text("#") },
                    isError = hexError,
                    supportingText = if (hexError) {
                        { Text("Invalid hex color") }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Hue",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HuePicker(
                    hue = hue,
                    onHueChange = { newHue ->
                        hue = newHue
                        selectedColor = Color.hsv(hue, saturation, brightness)
                        hexInput = String.format("%06X", 0xFFFFFF and selectedColor.toArgb())
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Saturation",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ColorSlider(
                    value = saturation,
                    onValueChange = { newSaturation ->
                        saturation = newSaturation
                        selectedColor = Color.hsv(hue, saturation, brightness)
                        hexInput = String.format("%06X", 0xFFFFFF and selectedColor.toArgb())
                    },
                    startColor = Color.hsv(hue, 0f, brightness),
                    endColor = Color.hsv(hue, 1f, brightness)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Brightness",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ColorSlider(
                    value = brightness,
                    onValueChange = { newValue ->
                        brightness = newValue
                        selectedColor = Color.hsv(hue, saturation, brightness)
                        hexInput = String.format("%06X", 0xFFFFFF and selectedColor.toArgb())
                    },
                    startColor = Color.hsv(hue, saturation, 0f),
                    endColor = Color.hsv(hue, saturation, 1f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { 
                            if (!hexError && hexInput.length == 6) {
                                onColorSelected(selectedColor)
                            }
                        },
                        enabled = !hexError && hexInput.length == 6
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun HuePicker(hue: Float, onHueChange: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val newHue = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                        onHueChange(newHue)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newHue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                        onHueChange(newHue)
                    }
                }
        ) {
            val gradient = Brush.horizontalGradient(
                colors = listOf(
                    Color.hsv(0f, 1f, 1f),
                    Color.hsv(60f, 1f, 1f),
                    Color.hsv(120f, 1f, 1f),
                    Color.hsv(180f, 1f, 1f),
                    Color.hsv(240f, 1f, 1f),
                    Color.hsv(300f, 1f, 1f),
                    Color.hsv(360f, 1f, 1f)
                )
            )
            drawRect(gradient)

            val selectorX = (hue / 360f) * size.width
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = Offset(selectorX, size.height / 2),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

@Composable
private fun ColorSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    startColor: Color,
    endColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val newValue = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(newValue)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newValue = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(newValue)
                    }
                }
        ) {
            val gradient = Brush.horizontalGradient(
                colors = listOf(startColor, endColor)
            )
            drawRect(gradient)

            val selectorX = value * size.width
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = Offset(selectorX, size.height / 2),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
