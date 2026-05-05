/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
private const val ACTION_BOOTANIM_STYLE_CHANGED =
    "org.evolution.intent.action.BOOTANIM_STYLE_CHANGED"
private const val CUSTOM_BOOTANIMATION_FILE = "/data/misc/bootanim/bootanimation.zip"

// ---------------------------------------------------------------------------
// Fragment
// ---------------------------------------------------------------------------

class BootAnimation : Fragment() {

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
                BootAnimationScreen(context = requireContext())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Root screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BootAnimationScreen(context: android.content.Context) {
    val scope = rememberCoroutineScope()
    val styleNames = stringArrayResource(R.array.themes_boot_animation_entries).toList()

    var selectedIndex by remember {
        mutableIntStateOf(SystemProperties.getInt(BOOTANIMATION_STYLE_KEY, 0))
    }
    var previewDrawable by remember { mutableStateOf<Drawable?>(null) }
    var isLoadingPreview by remember { mutableStateOf(true) }
    // Bounded by BOOT_ANIMATION_FILES.size (currently 14) — no eviction needed
    val thumbnails = remember { mutableStateMapOf<Int, Drawable?>() }

    // Load thumbnails with bounded concurrency
    LaunchedEffect(styleNames) {
        val semaphore = Semaphore(3)
        styleNames.indices.forEach { index ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val thumb = loadThumbnailForStyle(context, index)
                    withContext(Dispatchers.Main) { thumbnails[index] = thumb }
                }
            }
        }
    }

    fun loadPreview(index: Int) {
        scope.launch {
            isLoadingPreview = true
            previewDrawable = null
            previewDrawable = withContext(Dispatchers.IO) { loadDrawableForStyle(context, index) }
            isLoadingPreview = false
        }
    }

    fun applyStyle(index: Int) {
        try {
            SystemProperties.set(BOOTANIMATION_STYLE_KEY, index.toString())
            selectedIndex = index
            loadPreview(index)
            context.sendBroadcast(Intent(ACTION_BOOTANIM_STYLE_CHANGED))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set boot animation style", e)
            Toast.makeText(context, R.string.boot_animation_applied_error, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleCustomPick(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val input: InputStream = context.contentResolver.openInputStream(uri)
                    ?: run { Log.e(TAG, "Could not open stream for $uri"); return@launch }
                val dest = File(CUSTOM_BOOTANIMATION_FILE).also { it.parentFile?.mkdirs() }
                FileOutputStream(dest).use { input.copyTo(it) }
                input.close()
                dest.setReadable(true, false)
                withContext(Dispatchers.Main) {
                    applyStyle(BootAnimationUtils.STYLE_CUSTOM)
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

    // File picker — modern Activity Result API
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { handleCustomPick(it) } }

    // Initial load
    LaunchedEffect(Unit) { loadPreview(selectedIndex) }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Preview card ─────────────────────────────────────────────────
            PreviewCard(
                previewDrawable = previewDrawable,
                isLoading = isLoadingPreview,
                selectedName = styleNames.getOrElse(selectedIndex) {
                    stringResource(R.string.boot_animation_style_unknown)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Style selector list ───────────────────────────────────────────
            StyleSelectorCard(
                styleNames = styleNames,
                selectedIndex = selectedIndex,
                thumbnails = thumbnails,
                onSelect = { index ->
                    if (index == BootAnimationUtils.STYLE_CUSTOM) {
                        fileLauncher.launch(arrayOf("application/zip"))
                    } else {
                        applyStyle(index)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Preview card — clean, no tinted background
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewCard(
    previewDrawable: Drawable?,
    isLoading: Boolean,
    selectedName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Animation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.boot_animation_preview_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = selectedName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Preview viewport — phone-ish 9:16 cropped to a reasonable height
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)        // narrow like a phone screen
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = isLoading to previewDrawable,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                    },
                    label = "preview",
                ) { (loading, drawable) ->
                    when {
                        loading -> LoadingIndicator(modifier = Modifier.size(36.dp))
                        drawable != null -> AnimationPreviewView(
                            drawable = drawable,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Animation,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color.White.copy(alpha = 0.3f),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.boot_animation_no_preview),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Style selector — M3 list inside a card, radio buttons, no thumbnails
// ---------------------------------------------------------------------------

@Composable
private fun StyleSelectorCard(
    styleNames: List<String>,
    selectedIndex: Int,
    thumbnails: Map<Int, Drawable?>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            styleNames.forEachIndexed { index, name ->
                StyleListItem(
                    name = name,
                    isSelected = selectedIndex == index,
                    isCustom = index == BootAnimationUtils.STYLE_CUSTOM,
                    thumbnail = thumbnails[index],
                    onClick = { onSelect(index) },
                )
                if (index < styleNames.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleListItem(
    name: String,
    isSelected: Boolean,
    isCustom: Boolean,
    thumbnail: Drawable?,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.85f,
        label = "itemAlpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading thumbnail or folder icon — 40×40 with rounded corners
        ThumbnailSlot(
            thumbnail = thumbnail,
            isCustom = isCustom,
            isSelected = isSelected,
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Label
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Trailing: radio for unselected, filled check circle for selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            RadioButton(
                selected = false,
                onClick = null, // handled by row
            )
        }
    }
}

@Composable
private fun ThumbnailSlot(
    thumbnail: Drawable?,
    isCustom: Boolean,
    isSelected: Boolean,
) {
    val bgColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isCustom -> Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            thumbnail != null -> AndroidView(
                factory = { ctx ->
                    AndroidImageView(ctx).apply {
                        scaleType = AndroidImageView.ScaleType.CENTER_CROP
                        setImageDrawable(thumbnail)
                    }
                },
                update = { it.setImageDrawable(thumbnail) },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(10.dp)),
            )
            else -> Icon(
                imageVector = Icons.Outlined.Animation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Animation playback view
// ---------------------------------------------------------------------------

@Composable
private fun AnimationPreviewView(
    drawable: Drawable,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(drawable) {
        when (drawable) {
            is AnimatedImageDrawable -> drawable.start()
            is AnimationDrawable -> drawable.start()
        }
        onDispose {
            when (drawable) {
                is AnimatedImageDrawable -> drawable.stop()
                is AnimationDrawable -> drawable.stop()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            AndroidImageView(ctx).apply {
                scaleType = AndroidImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        },
        update = { it.setImageDrawable(drawable) },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// IO helpers
// ---------------------------------------------------------------------------

private fun loadDrawableForStyle(context: android.content.Context, index: Int): Drawable? {
    val zipPath = BootAnimationUtils.BOOT_ANIMATION_FILES.getOrNull(index) ?: return null
    if (!File(zipPath).exists()) {
        Log.w(TAG, "Boot animation zip not found for style $index: $zipPath")
        return null
    }
    return if (BootAnimationUtils.isAnimatedImageStyle(index))
        loadAnimatedImageFromZip(zipPath) ?: loadFramesFromPath(context, zipPath)
    else
        loadFramesFromPath(context, zipPath)
}

private fun loadFramesFromPath(context: android.content.Context, zipPath: String): AnimationDrawable? {
    val original = BootAnimationUtils.getBootAnimationFramesFromPath(context, zipPath)
    if (original == null || original.numberOfFrames == 0) return null
    return AnimationDrawable().apply {
        for (i in 0 until original.numberOfFrames) {
            addFrame(original.getFrame(i)!!, original.getDuration(i).let {
                if (it < 16) 1000 / 60 else it
            })
        }
        isOneShot = false
    }
}

private fun loadAnimatedImageFromZip(zipPath: String?): Drawable? {
    if (zipPath == null) return null
    val file = File(zipPath)
    if (!file.exists()) return null
    return runCatching {
        ZipFile(file).use { zf ->
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
    }.getOrElse { e ->
        Log.e(TAG, "Failed to load animated image from zip", e)
        null
    }
}

private fun loadThumbnailForStyle(context: android.content.Context, index: Int): Drawable? {
    val zipPath = BootAnimationUtils.BOOT_ANIMATION_FILES.getOrNull(index) ?: return null
    val zipFile = File(zipPath)
    if (!zipFile.exists()) return null

    return runCatching {
        ZipFile(zipFile).use { zf ->
            if (BootAnimationUtils.isAnimatedImageStyle(index)) {
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.lowercase()
                    if (!name.contains("/") &&
                        (name.endsWith(".webp") || name.endsWith(".gif"))
                    ) {
                        zf.getInputStream(entry).use { stream ->
                            val bytes = stream.readBytes()
                            val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                            return ImageDecoder.decodeDrawable(src) { dec, _, _ ->
                                dec.setPostProcessor(null)
                            }
                        }
                    }
                }
                return null
            }

            // PNG/JPG frame-based: find a meaningful first frame
            val framePattern = Pattern.compile(
                "part\\d+/.*\\.(png|jpg)$", Pattern.CASE_INSENSITIVE,
            )
            val frameNames = zf.entries().asSequence()
                .map { it.name }
                .filter { framePattern.matcher(it).matches() }
                .sorted()
                .toList()

            for (name in frameNames) {
                val entry = zf.getEntry(name) ?: continue
                val bitmap = zf.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    ?: continue
                if (isMeaningfulFrame(bitmap)) {
                    return BitmapDrawable(context.resources, bitmap)
                }
            }
            null
        }
    }.getOrElse { e ->
        Log.e(TAG, "Failed to load thumbnail for style $index", e)
        null
    }
}

/**
 * Returns true when at least 5% of sampled pixels are non-transparent
 * and not near-black — skips blank/all-black intro frames.
 */
private fun isMeaningfulFrame(bitmap: Bitmap): Boolean {
    val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 10)
    var nonEmpty = 0
    var total = 0
    for (x in 0 until bitmap.width step step) {
        for (y in 0 until bitmap.height step step) {
            val px = bitmap.getPixel(x, y)
            val a = (px shr 24) and 0xff
            val rgb = (px shr 16 and 0xff) + (px shr 8 and 0xff) + (px and 0xff)
            if (a > 20 && rgb > 30) nonEmpty++
            total++
        }
    }
    return total > 0 && nonEmpty.toFloat() / total > 0.05f
}
