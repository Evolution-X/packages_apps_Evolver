/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Animation
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.miscellaneous.ExpressiveChoiceRow
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.ExpressiveGroup
import org.evolution.settings.fragments.miscellaneous.expressiveVerticalScroll
import org.evolution.settings.utils.BootAnimationUtils
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import android.widget.ImageView as AndroidImageView

private const val TAG = "BootAnimationSettings"
private const val ACTION_BOOTANIM_STYLE_CHANGED =
    "org.evolution.intent.action.BOOTANIM_STYLE_CHANGED"
private const val MAX_CUSTOM_BOOTANIMATION_BYTES = 128L * 1024L * 1024L
private const val THUMBNAIL_MAX_SIZE = 256
private const val THUMBNAIL_MAX_TRIES = 24
private const val THUMBNAIL_CACHE_DIR = "bootanim_thumbs"
private val DESC_SPLIT = Regex("\\s+")
private val FRAME_ENTRY = Regex("[^/]+/.+\\.(png|jpg)", RegexOption.IGNORE_CASE)

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
    val styles = remember { BootAnimationUtils.getStyleNames(context) }

    var selectedName by remember { mutableStateOf(BootAnimationUtils.getEffectiveName()) }
    var customInstalled by remember {
        mutableStateOf(File(BootAnimationUtils.CUSTOM_PATH).isFile)
    }
    var previewDrawable by remember { mutableStateOf<Drawable?>(null) }
    var isLoadingPreview by remember { mutableStateOf(true) }
    // One entry per style — no eviction needed
    val thumbnails = remember { mutableStateMapOf<String, Drawable?>() }

    // Load thumbnails with bounded concurrency
    LaunchedEffect(styles) {
        val semaphore = Semaphore(3)
        styles.forEach { name ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val thumb = loadThumbnailForStyle(context, name)
                    withContext(Dispatchers.Main) { thumbnails[name] = thumb }
                }
            }
        }
    }

    val previewJob = remember { arrayOfNulls<Job>(1) }
    fun loadPreview(name: String) {
        previewJob[0]?.cancel()
        previewJob[0] = scope.launch {
            isLoadingPreview = true
            previewDrawable = null
            previewDrawable = withContext(Dispatchers.IO) { loadDrawableForStyle(context, name) }
            isLoadingPreview = false
        }
    }

    fun applyStyle(name: String) {
        try {
            SystemProperties.set(BootAnimationUtils.PROP_NAME, name)
            selectedName = name
            loadPreview(name)
            context.sendBroadcast(Intent(ACTION_BOOTANIM_STYLE_CHANGED))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set boot animation style", e)
            Toast.makeText(context, R.string.boot_animation_applied_error, Toast.LENGTH_SHORT).show()
        }
    }

    fun handleCustomPick(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val dest = File(BootAnimationUtils.CUSTOM_PATH).also { it.parentFile?.mkdirs() }
            val temp = File(dest.parentFile, "${dest.name}.tmp")
            val backup = File(dest.parentFile, "${dest.name}.bak")
            try {
                temp.delete()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_CUSTOM_BOOTANIMATION_BYTES) {
                                throw IllegalArgumentException("Custom boot animation exceeds size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                } ?: throw IllegalArgumentException("Could not open stream for $uri")

                val validationError = runCatching {
                    ZipFile(temp).use { zip ->
                        val desc = zip.getEntry("desc.txt")
                        when {
                            desc == null || desc.isDirectory -> "missing desc.txt"
                            !hasValidDescHeader(zip, desc) ->
                                "desc.txt must start with \"width height fps\""
                            zip.entries().asSequence()
                                .none { !it.isDirectory && FRAME_ENTRY.matches(it.name) } ->
                                "no png or jpg frames"
                            else -> missingPart(zip, desc)?.let { "part $it has no frames" }
                        }
                    }
                }.getOrElse { "not a valid zip" }
                if (validationError != null) {
                    throw IllegalArgumentException("Invalid boot animation ZIP: $validationError")
                }

                // The boot animation refuses compressed entries, so repack instead of failing.
                val compressed = ZipFile(temp).use { zip ->
                    zip.entries().asSequence().any { !it.isDirectory && it.method != ZipEntry.STORED }
                }
                if (compressed) {
                    val stored = File(dest.parentFile, "${dest.name}.stored")
                    try {
                        repackStored(temp, stored)
                        if (stored.length() > MAX_CUSTOM_BOOTANIMATION_BYTES || !stored.renameTo(temp)) {
                            throw IllegalArgumentException("Could not repack the boot animation")
                        }
                    } finally {
                        stored.delete()
                    }
                }

                backup.delete()
                if (dest.exists() && !dest.renameTo(backup)) {
                    throw IllegalStateException("Could not preserve existing boot animation")
                }
                if (!temp.renameTo(dest)) {
                    if (backup.exists()) backup.renameTo(dest)
                    throw IllegalStateException("Could not install custom boot animation")
                }
                backup.delete()
                dest.setReadable(true, false)

                val thumb = loadThumbnailForStyle(context, BootAnimationUtils.NAME_CUSTOM)
                withContext(Dispatchers.Main) {
                    thumbnails[BootAnimationUtils.NAME_CUSTOM] = thumb
                    customInstalled = true
                    applyStyle(BootAnimationUtils.NAME_CUSTOM)
                    Toast.makeText(context, R.string.boot_animation_applied, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying custom boot animation", e)
                if (!dest.exists() && backup.exists()) {
                    backup.renameTo(dest)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, R.string.boot_animation_applied_error, Toast.LENGTH_SHORT).show()
                }
            } finally {
                temp.delete()
                if (dest.exists()) backup.delete()
            }
        }
    }

    // File picker — modern Activity Result API
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { handleCustomPick(it) } }

    // Initial load
    LaunchedEffect(Unit) { loadPreview(selectedName) }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .expressiveVerticalScroll(),
        ) {
            // ── Preview card ─────────────────────────────────────────────────
            PreviewCard(
                previewDrawable = previewDrawable,
                isLoading = isLoadingPreview,
                selectedName = BootAnimationUtils.getLabel(context, selectedName),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Style selector list ───────────────────────────────────────────
            StyleSelectorCard(
                styles = styles,
                selectedName = selectedName,
                customInstalled = customInstalled,
                thumbnails = thumbnails,
                onSelect = { name ->
                    when {
                        name != BootAnimationUtils.NAME_CUSTOM -> applyStyle(name)
                        // Use the installed zip; tapping it again picks a replacement
                        customInstalled && selectedName != name -> applyStyle(name)
                        else -> fileLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            )
                        )
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
    // Colour of the part being played, like the boot animation clears to it
    var previewBackground by remember(previewDrawable) { mutableStateOf<Color?>(null) }
    Surface(
        modifier = modifier,
        shape = ExpressiveCorners.single,
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
                    .clip(ExpressiveCorners.preview)
                    .background(previewBackground ?: Color.Black),
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
                            onBackground = { previewBackground = it },
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
// Style selector — Expressive group of choice rows with a thumbnail slot
// ---------------------------------------------------------------------------

@Composable
private fun StyleSelectorCard(
    styles: List<String>,
    selectedName: String,
    customInstalled: Boolean,
    thumbnails: Map<String, Drawable?>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ExpressiveGroup(title = "", modifier = modifier, horizontalInset = 0.dp) {
        styles.forEach { name ->
            item {
                val isCustom = name == BootAnimationUtils.NAME_CUSTOM
                ExpressiveChoiceRow(
                    title = BootAnimationUtils.getLabel(context, name),
                    selected = selectedName == name,
                    onClick = { onSelect(name) },
                    summary = when {
                        !isCustom -> null
                        !customInstalled -> stringResource(R.string.boot_animation_custom_pick)
                        selectedName == name ->
                            stringResource(R.string.boot_animation_custom_replace)
                        else -> null
                    },
                    leading = {
                        ThumbnailSlot(
                            thumbnail = thumbnails[name],
                            isCustom = isCustom,
                            isSelected = selectedName == name,
                        )
                    },
                )
            }
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
            .clip(ExpressiveCorners.picker)
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
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
                    .clip(ExpressiveCorners.picker),
            )
            isCustom -> Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
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
    onBackground: (Color?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Follow the colour of the frame being shown, parts of a zip can differ
    LaunchedEffect(drawable) {
        if (drawable !is AnimationDrawable) {
            onBackground(null)
            return@LaunchedEffect
        }
        while (true) {
            val frame = drawable.current as? BootAnimationUtils.PartFrame
            onBackground(frame?.let { Color(it.background) })
            delay(50)
        }
    }

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

private fun loadDrawableForStyle(context: android.content.Context, name: String): Drawable? {
    val zipPath = BootAnimationUtils.getPathForName(name) ?: return null
    if (!File(zipPath).exists()) {
        Log.w(TAG, "Boot animation zip not found for style $name: $zipPath")
        return null
    }
    return loadFramesFromPath(context, zipPath)
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

private fun hasValidDescHeader(zip: ZipFile, desc: ZipEntry): Boolean = runCatching {
    zip.getInputStream(desc).bufferedReader().use { it.readLine() }
        ?.trim()?.split(DESC_SPLIT)
        ?.let { parts -> parts.size >= 3 && parts.take(3).all { (it.toIntOrNull() ?: 0) > 0 } }
        ?: false
}.getOrDefault(false)

/** Name of the first part in desc.txt that has no frames in the zip, or null. */
private fun missingPart(zip: ZipFile, desc: ZipEntry): String? {
    val names = zip.entries().asSequence().map { it.name }.toList()
    return zip.getInputStream(desc).bufferedReader().use { it.readLines() }.drop(1)
        .map { it.trim().split(DESC_SPLIT) }
        .filter { it.size >= 4 && (it[0] == "p" || it[0] == "c") }
        .map { it[3] }
        .firstOrNull { part -> names.none { it.startsWith("$part/") && FRAME_ENTRY.matches(it) } }
}

/** Rewrites [src] with every entry STORED, which is what the boot animation can read. */
private fun repackStored(src: File, dst: File) {
    ZipFile(src).use { zip ->
        ZipOutputStream(FileOutputStream(dst)).use { out ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                out.putNextEntry(ZipEntry(entry.name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                })
                out.write(bytes)
                out.closeEntry()
            }
        }
    }
}

/** Decodes [entry] with a sample size that keeps its longest side near [maxSize] pixels. */
private fun decodeSampled(zip: ZipFile, entry: ZipEntry, maxSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
}

/**
 * Thumbnail of a style. Decoding one means opening the zip and decoding up to
 * [THUMBNAIL_MAX_TRIES] frames, so the result is kept in the cache dir, keyed by the zip's size
 * and modification time, and read back from there while the zip is unchanged.
 */
private fun loadThumbnailForStyle(context: android.content.Context, name: String): Drawable? {
    val zipPath = BootAnimationUtils.getPathForName(name) ?: return null
    val zipFile = File(zipPath)
    if (!zipFile.exists()) return null

    val dir = File(context.cacheDir, THUMBNAIL_CACHE_DIR)
    val prefix = "${name}_"
    val cached = File(dir, "$prefix${zipFile.length()}_${zipFile.lastModified()}.png")
    if (cached.isFile()) {
        BitmapFactory.decodeFile(cached.path)?.let {
            return BitmapDrawable(context.resources, it)
        }
        cached.delete()
    }

    val bitmap = decodeThumbnail(zipFile, name) ?: return null
    runCatching {
        dir.mkdirs()
        // Drop the thumbnails of an older version of this zip.
        dir.listFiles { f -> f.name.startsWith(prefix) }?.forEach { it.delete() }
        val temp = File(dir, "${cached.name}.tmp")
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!temp.renameTo(cached)) temp.delete()
    }.onFailure { Log.w(TAG, "Failed to cache thumbnail for style $name", it) }
    return BitmapDrawable(context.resources, bitmap)
}

private fun decodeThumbnail(zipFile: File, name: String): Bitmap? {
    return runCatching {
        ZipFile(zipFile).use { zf ->
            // PNG/JPG frame-based: find a meaningful frame
            val frameNames = zf.entries().asSequence()
                .map { it.name }
                .filter { FRAME_ENTRY.matches(it) }
                .sorted()
                .toList()

            // Start in the middle, the first frames are often a blank fade-in
            val order = if (frameNames.isEmpty()) {
                emptyList()
            } else {
                (listOf(frameNames.size / 2) + frameNames.indices).take(THUMBNAIL_MAX_TRIES)
            }
            for (i in order) {
                val entry = zf.getEntry(frameNames[i]) ?: continue
                val bitmap = placeTrimmed(zf, frameNames, i) ?: continue
                if (isMeaningfulFrame(bitmap)) return bitmap
            }
            null
        }
    }.getOrElse { e ->
        Log.e(TAG, "Failed to load thumbnail for style $name", e)
        null
    }
}

/**
 * Thumbnail of frame [index]. A trimmed frame is a placement offset for a cropped png, so it
 * is drawn onto a canvas of the animation size; other frames are returned as decoded.
 */
private fun placeTrimmed(zf: ZipFile, frameNames: List<String>, index: Int): Bitmap? {
    val name = frameNames[index]
    val entry = zf.getEntry(name) ?: return null
    val bitmap = decodeSampled(zf, entry, THUMBNAIL_MAX_SIZE) ?: return null
    val part = name.substringBefore('/')
    val trim = BootAnimationUtils.loadTrimData(zf, part)
    val size = BootAnimationUtils.getAnimationSize(zf)
    val inPart = frameNames.filter { it.startsWith("$part/") }.indexOf(name)
    if (trim.isEmpty() || inPart !in trim.indices || size[0] <= 0 || size[1] <= 0) return bitmap
    val scale = minOf(1f, THUMBNAIL_MAX_SIZE / maxOf(size[0], size[1]).toFloat())
    val r = trim[inPart]
    return Bitmap.createBitmap(
        maxOf(1, Math.round(size[0] * scale)), maxOf(1, Math.round(size[1] * scale)),
        Bitmap.Config.ARGB_8888,
    ).also {
        Canvas(it).drawBitmap(
            bitmap, null,
            RectF(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale), null,
        )
        bitmap.recycle()
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
