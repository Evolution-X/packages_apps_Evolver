/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.evolution.settings.utils

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.settings.R
import com.github.skydoves.colorpicker.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressivePrimaryAction
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextField
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WallpaperColorPickerDialog(
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    val context = LocalContext.current
    val controller = rememberColorPickerController()
    val coroutineScope = rememberCoroutineScope()
    
    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedColor by remember { mutableStateOf(Color.Transparent) }
    var hexInput by remember { mutableStateOf("") }
    var useCustomImage by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val originalBitmap = BitmapFactory.decodeStream(stream)
                            val maxSize = 800
                            val scale = minOf(
                                maxSize.toFloat() / originalBitmap.width,
                                maxSize.toFloat() / originalBitmap.height,
                                1f
                            )
                            if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    originalBitmap,
                                    (originalBitmap.width * scale).toInt(),
                                    (originalBitmap.height * scale).toInt(),
                                    true
                                )
                            } else {
                                originalBitmap
                            }
                        }
                    }
                    wallpaperBitmap = bitmap
                    useCustomImage = true
                    isLoading = false
                    
                    bitmap?.let { bmp ->
                        controller.setPaletteImageBitmap(bmp.asImageBitmap())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!useCustomImage) {
            coroutineScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        val wallpaperManager = WallpaperManager.getInstance(context)
                        val drawable = wallpaperManager.drawable
                        if (drawable is BitmapDrawable) {
                            val originalBitmap = drawable.bitmap
                            val maxSize = 800
                            val scale = minOf(
                                maxSize.toFloat() / originalBitmap.width,
                                maxSize.toFloat() / originalBitmap.height,
                                1f
                            )
                            if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    originalBitmap,
                                    (originalBitmap.width * scale).toInt(),
                                    (originalBitmap.height * scale).toInt(),
                                    true
                                )
                            } else {
                                originalBitmap
                            }
                        } else {
                            null
                        }
                    }
                    wallpaperBitmap = bitmap
                    isLoading = false
                    
                    bitmap?.let {
                        controller.setPaletteImageBitmap(it.asImageBitmap())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    isLoading = false
                }
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (useCustomImage) R.string.color_picker_title_image
                    else R.string.color_picker_title_wallpaper
                ),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(modifier = Modifier.expressiveVerticalScroll()) {
                ExpressivePrimaryAction(
                    text = stringResource(R.string.color_picker_import_gallery),
                    icon = Icons.Default.Image,
                    onClick = { imagePickerLauncher.launch("image/*") },
                    inset = false
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else if (wallpaperBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(
                                color = selectedColor,
                                shape = ExpressiveCorners.picker
                            )
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    ExpressiveTextField(
                        value = hexInput,
                        onValueChange = { },
                        label = stringResource(R.string.color_picker_selected_color),
                        prefix = "#",
                        readOnly = true,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = ExpressiveCorners.preview
                            )
                    ) {
                        ImageColorPicker(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            controller = controller,
                            paletteImageBitmap = wallpaperBitmap!!.asImageBitmap(),
                            paletteContentScale = PaletteContentScale.FIT,
                            onColorChanged = { colorEnvelope ->
                                selectedColor = colorEnvelope.color
                                hexInput = colorEnvelope.hexCode.takeLast(6)
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (useCustomImage) R.string.color_picker_hint_image
                            else R.string.color_picker_hint_wallpaper
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.color_picker_wallpaper_error),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            ExpressivePrimaryAction(
                                text = stringResource(R.string.color_picker_import_image),
                                icon = Icons.Default.Image,
                                onClick = { imagePickerLauncher.launch("image/*") },
                                inset = false
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = {
                    if (selectedColor != Color.Transparent) {
                        onColorSelected(selectedColor)
                    }
                },
                enabled = !isLoading && selectedColor != Color.Transparent
            ) {
                Text(stringResource(R.string.color_picker_apply))
            }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
