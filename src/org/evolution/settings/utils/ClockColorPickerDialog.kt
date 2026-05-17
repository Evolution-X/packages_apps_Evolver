/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.github.skydoves.colorpicker.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CLOCK_COLOR_PRESETS = listOf(
    Color.White,
    Color.Black,
    Color(0xFF6750A4), // Monet default purple
    Color(0xFFE91E63), // Pink
    Color(0xFF2196F3), // Blue
    Color(0xFF4CAF50), // Green
    Color(0xFFFF9800), // Orange
    Color(0xFF00BCD4), // Cyan
    Color(0xFFFF5722), // Deep Orange
    Color(0xFF9C27B0), // Purple
    Color(0xFF3F51B5), // Indigo
    Color(0xFF009688), // Teal
    Color(0xFFF44336), // Red
    Color(0xFFFFEB3B), // Yellow
    Color(0xFF8BC34A), // Light Green
    Color(0xFF607D8B), // Blue Grey
)

private enum class ClockColorTab { PRESET, WALLPAPER, CUSTOM }

@Composable
fun ClockColorPickerDialog(
    initialColor: String = "FFFFFF",
    onDismiss: () -> Unit,
    onColorSelected: (Color) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedColor by remember {
        mutableStateOf(
            try { Color(android.graphics.Color.parseColor("#$initialColor")) }
            catch (e: Exception) { Color.White }
        )
    }

    var activeTab by remember { mutableStateOf(ClockColorTab.PRESET) }

    val wallpaperController = rememberColorPickerController()
    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isWallpaperLoading by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isWallpaperLoading = true
            coroutineScope.launch {
                val bmp = withContext(Dispatchers.IO) { loadAndScaleBitmap(context, uri) }
                wallpaperBitmap = bmp
                bmp?.let { wallpaperController.setPaletteImageBitmap(it.asImageBitmap()) }
                isWallpaperLoading = false
            }
        }
    }

    LaunchedEffect(activeTab) {
        if (activeTab == ClockColorTab.WALLPAPER && wallpaperBitmap == null) {
            isWallpaperLoading = true
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val wm = WallpaperManager.getInstance(context)
                    val drawable = wm.drawable
                    if (drawable is BitmapDrawable) scaleBitmap(drawable.bitmap) else null
                } catch (e: Exception) { null }
            }
            wallpaperBitmap = bmp
            bmp?.let { wallpaperController.setPaletteImageBitmap(it.asImageBitmap()) }
            isWallpaperLoading = false
        }
    }

    val customController = rememberColorPickerController()

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
                    text = "Clock Color",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selectedColor)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(12.dp)
                        )
                )

                Spacer(Modifier.height(16.dp))

                TabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    ClockColorTab.values().forEach { tab ->
                        Tab(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            text = {
                                Text(
                                    when (tab) {
                                        ClockColorTab.PRESET -> "Preset"
                                        ClockColorTab.WALLPAPER -> "Image"
                                        ClockColorTab.CUSTOM -> "Custom"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                when (activeTab) {
                    ClockColorTab.PRESET -> PresetTab(
                        selected = selectedColor,
                        onSelect = { selectedColor = it }
                    )
                    ClockColorTab.WALLPAPER -> WallpaperTab(
                        bitmap = wallpaperBitmap,
                        isLoading = isWallpaperLoading,
                        controller = wallpaperController,
                        onColorChanged = { selectedColor = it },
                        onImportClick = { imagePickerLauncher.launch("image/*") }
                    )
                    ClockColorTab.CUSTOM -> CustomTab(
                        controller = customController,
                        onColorChanged = { selectedColor = it }
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onColorSelected(selectedColor) }) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun PresetTab(selected: Color, onSelect: (Color) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CLOCK_COLOR_PRESETS) { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (color == Color.White) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WallpaperTab(
    bitmap: Bitmap?,
    isLoading: Boolean,
    controller: ColorPickerController,
    onColorChanged: (Color) -> Unit,
    onImportClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onImportClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Import from Gallery")
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator()
                bitmap != null -> {
                    ImageColorPicker(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        controller = controller,
                        paletteImageBitmap = bitmap.asImageBitmap(),
                        paletteContentScale = PaletteContentScale.FIT,
                        onColorChanged = { envelope -> onColorChanged(envelope.color) }
                    )
                }
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Unable to load wallpaper",
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onImportClick) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import Image")
                    }
                }
            }
        }
        if (bitmap != null && !isLoading) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap the image to pick a color",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun CustomTab(
    controller: ColorPickerController,
    onColorChanged: (Color) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HsvColorPicker(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp)),
            controller = controller,
            onColorChanged = { envelope -> onColorChanged(envelope.color) }
        )
        BrightnessSlider(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp)),
            controller = controller
        )
        AlphaSlider(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp)),
            controller = controller,
            tileOddColor = Color.White,
            tileEvenColor = Color.LightGray
        )
    }
}

private fun scaleBitmap(src: Bitmap, maxSize: Int = 800): Bitmap {
    val scale = minOf(maxSize.toFloat() / src.width, maxSize.toFloat() / src.height, 1f)
    return if (scale < 1f)
        Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    else src
}

private fun loadAndScaleBitmap(context: android.content.Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            scaleBitmap(BitmapFactory.decodeStream(stream) ?: return null)
        }
    } catch (e: Exception) { null }
}
