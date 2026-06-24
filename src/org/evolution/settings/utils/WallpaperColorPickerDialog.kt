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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.skydoves.colorpicker.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .width(340.dp)
            ) {
                Text(
                    text = if (useCustomImage) "Pick Color from Image" else "Pick Color from Wallpaper",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import Image from Gallery")
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (wallpaperBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(
                                color = selectedColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { },
                        label = { Text("Selected Color") },
                        prefix = { Text("#") },
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp)
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
                        text = "Tap on the ${if (useCustomImage) "image" else "wallpaper"} to pick a color",
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
                                text = "Unable to load wallpaper",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Import Image")
                            }
                        }
                    }
                }

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
                            if (selectedColor != Color.Transparent) {
                                onColorSelected(selectedColor)
                            }
                        },
                        enabled = !isLoading && selectedColor != Color.Transparent
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}
