/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemProperties
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.utils.BootAnimationUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Enumeration
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import android.widget.ImageView as AndroidImageView

private const val TAG = "BootAnimationSettings"
private const val BOOTANIMATION_STYLE_KEY = "persist.sys.bootanimation_style"
private const val CUSTOM_BOOTANIMATION_FILE = "/data/misc/bootanim/bootanimation.zip"
private const val REQUEST_CODE_PICK_ZIP = 1001

class BootAnimation : Fragment() {

    private var pendingStyleIndex: Int = -1
    private var onFilePicked: ((Uri) -> Unit)? = null

    @SuppressLint("QueryPermissionsNeeded")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.themes_boot_animation_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                BootAnimationContent(
                    context = requireContext(),
                    onPickFile = { callback ->
                        onFilePicked = callback
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                        }
                        startActivityForResult(intent, REQUEST_CODE_PICK_ZIP)
                    },
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_ZIP
            && resultCode == Activity.RESULT_OK
            && data?.data != null
        ) {
            onFilePicked?.invoke(data.data!!)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BootAnimationContent(
    context: android.content.Context,
    onPickFile: (callback: (Uri) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val styleNames = stringArrayResource(R.array.themes_boot_animation_entries).toList()

    var selectedIndex by remember {
        mutableIntStateOf(SystemProperties.getInt(BOOTANIMATION_STYLE_KEY, 0))
    }
    var previewDrawable by remember { mutableStateOf<Drawable?>(null) }
    var isLoadingPreview by remember { mutableStateOf(true) }
    var dominantColor by remember { mutableStateOf<Color?>(null) }
    val thumbnails = remember { mutableStateMapOf<Int, Drawable?>() }

    // Load thumbnails for all styles in parallel
    LaunchedEffect(styleNames) {
        styleNames.indices.forEach { index: Int ->
            launch(Dispatchers.IO) {
                val thumb = loadThumbnailForStyle(context, index)
                withContext(Dispatchers.Main) {
                    thumbnails[index] = thumb
                }
            }
        }
    }

    fun applyStyle(index: Int) {
        SystemProperties.set(BOOTANIMATION_STYLE_KEY, index.toString())
        selectedIndex = index
    }

    fun loadPreview(index: Int) {
        scope.launch {
            isLoadingPreview = true
            previewDrawable = null
            dominantColor = null
            val drawable = withContext(Dispatchers.IO) {
                loadDrawableForStyle(context, index)
            }
            previewDrawable = drawable
            dominantColor = drawable?.let { extractDominantColor(it) }
            isLoadingPreview = false
        }
    }

    fun handleCustomPick(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val input: InputStream? = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    Log.e(TAG, "Could not open stream for $uri")
                    return@launch
                }
                val dest = File(CUSTOM_BOOTANIMATION_FILE)
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    var len: Int
                    while (input.read(buf).also { len = it } > 0) out.write(buf, 0, len)
                }
                input.close()
                dest.setReadable(true, false)
                withContext(Dispatchers.Main) {
                    applyStyle(BootAnimationUtils.STYLE_CUSTOM)
                    loadPreview(BootAnimationUtils.STYLE_CUSTOM)
                    Toast.makeText(context, R.string.boot_animation_applied, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying custom boot animation", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.boot_animation_applied_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Initial preview load
    LaunchedEffect(Unit) {
        loadPreview(selectedIndex)
    }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Preview card with dynamic background color from palette
            val cardColor by animateColorAsState(
                targetValue = dominantColor?.copy(alpha = 0.15f)
                    ?: MaterialTheme.colorScheme.surfaceBright,
                label = "cardColor",
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cardColor,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.themes_boot_animation_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (selectedIndex in styleNames.indices)
                                    styleNames[selectedIndex]
                                else
                                    stringResource(R.string.boot_animation_style_unknown),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Animation preview area — 4:3 ratio for a compact but visible preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isLoadingPreview) {
                            LoadingIndicator()
                        } else if (previewDrawable != null) {
                            AnimationPreviewView(
                                drawable = previewDrawable!!,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.boot_animation_no_preview),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Style picker label
            Text(
                text = stringResource(R.string.themes_boot_animation_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )

            // Horizontal style picker
            // The last tile (Custom) triggers the file picker directly
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(styleNames) { index: Int, name: String ->
                    if (index == BootAnimationUtils.STYLE_CUSTOM) {
                        StyleTile(
                            name = name,
                            index = index,
                            isSelected = selectedIndex == index,
                            isCustom = true,
                            thumbnail = null,
                            onClick = {
                                onPickFile { uri -> handleCustomPick(uri) }
                            },
                        )
                    } else {
                        StyleTile(
                            name = name,
                            index = index,
                            isSelected = selectedIndex == index,
                            isCustom = false,
                            thumbnail = thumbnails[index],
                            onClick = {
                                applyStyle(index)
                                loadPreview(index)
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StyleTile(
    name: String,
    index: Int,
    isSelected: Boolean,
    isCustom: Boolean,
    thumbnail: Drawable?,
    onClick: () -> Unit,
) {
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.5.dp else 0.dp,
        label = "border",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "container",
    )

    Surface(
        modifier = Modifier
            .width(90.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = if (isSelected)
            BorderStroke(borderWidth, MaterialTheme.colorScheme.primary)
        else null,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Show thumbnail if available, folder icon for custom
                if (thumbnail != null && !isCustom) {
                    AndroidView(
                        factory = { ctx ->
                            AndroidImageView(ctx).apply {
                                scaleType = AndroidImageView.ScaleType.FIT_CENTER
                                setImageDrawable(thumbnail)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (isCustom) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Selection checkmark overlay
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AnimationPreviewView(
    drawable: Drawable,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(drawable) {
        if (drawable is AnimatedImageDrawable) drawable.start()
        else if (drawable is AnimationDrawable) drawable.start()
        onDispose {
            if (drawable is AnimatedImageDrawable) drawable.stop()
            else if (drawable is AnimationDrawable) drawable.stop()
        }
    }

    AndroidView(
        factory = { ctx ->
            AndroidImageView(ctx).apply {
                scaleType = AndroidImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        },
        update = { view ->
            view.setImageDrawable(drawable)
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Preview loading helpers
// ---------------------------------------------------------------------------

private fun loadDrawableForStyle(context: android.content.Context, index: Int): Drawable? {
    return if (BootAnimationUtils.isAnimatedImageStyle(index)) {
        loadAnimatedImageFromZip(BootAnimationUtils.getSelectedBootAnimation()) ?: loadFrames(context)
    } else {
        loadFrames(context)
    }
}

private fun loadFrames(context: android.content.Context): AnimationDrawable? {
    val original = BootAnimationUtils.getBootAnimationFrames(context)
    if (original == null || original.numberOfFrames == 0) return null
    val fixed = AnimationDrawable()
    for (i in 0 until original.numberOfFrames) {
        var duration = original.getDuration(i)
        if (duration < 16) duration = 1000 / 60
        fixed.addFrame(original.getFrame(i)!!, duration)
    }
    fixed.isOneShot = false
    return fixed
}

private fun loadAnimatedImageFromZip(zipPath: String?): Drawable? {
    if (zipPath == null) return null
    val zipFile = File(zipPath)
    if (!zipFile.exists()) return null
    return try {
        ZipFile(zipFile).use { zf ->
            val entries: Enumeration<out ZipEntry> = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.lowercase()
                if (!name.contains("/") && (name.endsWith(".webp") || name.endsWith(".gif"))) {
                    zf.getInputStream(entry).use { stream ->
                        val bytes = stream.readBytes()
                        val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                        return ImageDecoder.decodeDrawable(src) { decoder, _, _ ->
                            decoder.setPostProcessor(null)
                        }
                    }
                }
            }
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load animated image from zip", e)
        null
    }
}

private fun loadThumbnailForStyle(context: android.content.Context, index: Int): Drawable? {
    val zipPath = BootAnimationUtils.BOOT_ANIMATION_FILES.getOrNull(index) ?: return null
    val zipFile = File(zipPath)
    if (!zipFile.exists()) return null

    return try {
        ZipFile(zipFile).use { zf ->
            if (BootAnimationUtils.isAnimatedImageStyle(index)) {
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.lowercase()
                    if (!name.contains("/") &&
                        (name.endsWith(".webp") || name.endsWith(".gif"))) {
                        zf.getInputStream(entry).use { stream ->
                            val bytes = stream.readBytes()
                            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                            return ImageDecoder.decodeDrawable(src) { decoder, _, _ ->
                                decoder.setPostProcessor(null)
                            }
                        }
                    }
                }
                return null
            }

            val framePattern = Pattern.compile(
                "part\\d+/.*\\.(png|jpg)$", Pattern.CASE_INSENSITIVE)
            val frameNames = zf.entries().asSequence()
                .map { it.name }
                .filter { framePattern.matcher(it).matches() }
                .sorted()
                .toList()

            // Find first frame with meaningful content (not mostly black/transparent)
            for (name in frameNames) {
                val entry = zf.getEntry(name) ?: continue
                val bitmap = zf.getInputStream(entry).use {
                    BitmapFactory.decodeStream(it)
                } ?: continue
                if (isMeaningfulFrame(bitmap)) {
                    return BitmapDrawable(context.resources, bitmap)
                }
            }
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load thumbnail for style $index", e)
        null
    }
}

private fun isMeaningfulFrame(bitmap: Bitmap): Boolean {
    val sampleStep = maxOf(1, minOf(bitmap.width, bitmap.height) / 10)
    var nonEmptyPixels = 0
    var totalSampled = 0
    for (x in 0 until bitmap.width step sampleStep) {
        for (y in 0 until bitmap.height step sampleStep) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel shr 24) and 0xff
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff
            if (alpha > 20 && (r + g + b) > 30) nonEmptyPixels++
            totalSampled++
        }
    }
    // At least 5% of sampled pixels must be non-empty
    return totalSampled > 0 && (nonEmptyPixels.toFloat() / totalSampled) > 0.05f
}

private fun extractDominantColor(drawable: Drawable): Color? {
    return try {
        val bitmap = when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            is AnimationDrawable -> (drawable.getFrame(0) as? BitmapDrawable)?.bitmap
            else -> null
        } ?: return null

        // Sample a grid of pixels and average the non-black/non-transparent ones
        val sampleStep = maxOf(1, minOf(bitmap.width, bitmap.height) / 20)
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        var count = 0
        for (x in 0 until bitmap.width step sampleStep) {
            for (y in 0 until bitmap.height step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel shr 24) and 0xff
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                // Skip transparent or near-black pixels
                if (alpha > 20 && (r + g + b) > 60) {
                    rSum += r
                    gSum += g
                    bSum += b
                    count++
                }
            }
        }
        if (count == 0) return null
        Color(
            red = (rSum / count).toInt().coerceIn(0, 255) / 255f,
            green = (gSum / count).toInt().coerceIn(0, 255) / 255f,
            blue = (bSum / count).toInt().coerceIn(0, 255) / 255f,
        )
    } catch (e: Exception) {
        null
    }
}
