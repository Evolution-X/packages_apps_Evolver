/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.android.settings.R
import com.github.skydoves.colorpicker.compose.*
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextField
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll

private fun toHex(argb: Int, withAlpha: Boolean): String =
    if (withAlpha) String.format("%08X", argb)
    else String.format("%06X", argb and 0xFFFFFF)

/**
 * Skydoves based color picker used by the color preferences.
 * Works on ARGB ints. When [alphaEnabled] is false the result is always fully opaque.
 */
@Composable
fun PreferenceColorPickerDialog(
    title: String?,
    initialColor: Int,
    alphaEnabled: Boolean,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit
) {
    val startArgb = if (alphaEnabled) initialColor else initialColor or 0xFF000000.toInt()
    var selectedArgb by rememberSaveable { mutableStateOf(startArgb) }
    var hex by rememberSaveable { mutableStateOf(toHex(startArgb, alphaEnabled)) }
    var hexError by remember { mutableStateOf(false) }

    // Captured once so the pickers restore their position after recreation.
    val pickerStart = remember { Color(selectedArgb) }
    val controller = rememberColorPickerController()
    val expectedLength = if (alphaEnabled) 8 else 6

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = if (!title.isNullOrEmpty()) {
            { Text(text = title, style = MaterialTheme.typography.headlineSmall) }
        } else {
            null
        },
        text = {
            Column(modifier = Modifier.expressiveVerticalScroll()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(ExpressiveCorners.picker)
                        .background(Color(selectedArgb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, ExpressiveCorners.picker)
                )

                Spacer(Modifier.height(16.dp))

                HsvColorPicker(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(ExpressiveCorners.picker),
                    controller = controller,
                    initialColor = pickerStart,
                    onColorChanged = { envelope ->
                        selectedArgb = envelope.color.toArgb()
                        if (envelope.fromUser) {
                            hex = toHex(selectedArgb, alphaEnabled)
                            hexError = false
                        }
                    }
                )

                Spacer(Modifier.height(12.dp))

                BrightnessSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(ExpressiveCorners.picker),
                    controller = controller,
                    initialColor = pickerStart
                )

                if (alphaEnabled) {
                    Spacer(Modifier.height(12.dp))
                    AlphaSlider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clip(ExpressiveCorners.picker),
                        controller = controller,
                        tileOddColor = Color.White,
                        tileEvenColor = Color.LightGray,
                        initialColor = pickerStart
                    )
                }

                Spacer(Modifier.height(16.dp))

                ExpressiveTextField(
                    value = hex,
                    onValueChange = { input ->
                        val cleaned = input.replace("#", "").uppercase()
                        if (cleaned.length <= expectedLength &&
                            cleaned.all { it in "0123456789ABCDEF" }
                        ) {
                            hex = cleaned
                            hexError = false
                            if (cleaned.length == expectedLength) {
                                try {
                                    val parsed = AndroidColor.parseColor("#$cleaned")
                                    selectedArgb = parsed
                                    controller.selectByColor(Color(parsed), false)
                                } catch (e: IllegalArgumentException) {
                                    hexError = true
                                }
                            }
                        }
                    },
                    label = stringResource(
                        if (alphaEnabled) R.string.color_picker_argb
                        else R.string.color_picker_hex
                    ),
                    leadingIcon = { Text("#") },
                    isError = hexError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
            }
        },
        confirmButton = {
            ExpressiveTextButton(onClick = {
                val out = if (alphaEnabled) selectedArgb
                          else selectedArgb or 0xFF000000.toInt()
                onColorSelected(out)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
