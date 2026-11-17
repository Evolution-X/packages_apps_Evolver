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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import java.util.Locale
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

// What the selected animation is played on: the boot screen or the power-off screen
private const val TARGET_BOOT = "boot"
private const val TARGET_SHUTDOWN = "shutdown"

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
    var target by rememberSaveable { mutableStateOf(TARGET_BOOT) }
    val isShutdown = target == TARGET_SHUTDOWN

    // Every per-target piece of state is keyed on the target, so switching tabs
    // starts from a clean, correctly initialised state
    var styles by remember(target) { mutableStateOf(stylesFor(context, target)) }
    var selectedName by remember(target) {
        mutableStateOf(effectiveNameFor(context, target))
    }
    var customInstalled by remember(target) {
        mutableStateOf(customFile(target).isFile)
    }
    var previewDrawable by remember(target) { mutableStateOf<Drawable?>(null) }
    var previewInfo by remember(target) {
        mutableStateOf<BootAnimationUtils.AnimationInfo?>(null)
    }
    var previewNote by remember(target) { mutableStateOf<String?>(null) }
    var previewSource by remember(target) { mutableStateOf<String?>(null) }
    var previewPath by remember(target) { mutableStateOf<String?>(null) }
    var isLoadingPreview by remember(target) { mutableStateOf(true) }
    // One entry per style — no eviction needed
    val thumbnails = remember(target) { mutableStateMapOf<String, Drawable?>() }
    // (partIndex, playIndex) of the frame currently shown by the preview
    var previewPart by remember { mutableStateOf(-1 to -1) }
    var previewBackground by remember { mutableStateOf<Color?>(null) }

    // Fullscreen preview state
    var fullscreen by remember { mutableStateOf(false) }
    var fullscreenDrawable by remember { mutableStateOf<Drawable?>(null) }
    var isLoadingFullscreen by remember { mutableStateOf(false) }
    var fullscreenJob by remember { mutableStateOf<Job?>(null) }
    var fullscreenPlaying by remember { mutableStateOf(true) }
    var fullscreenPart by remember { mutableStateOf(-1 to -1) }
    var fullscreenBackground by remember { mutableStateOf<Color?>(null) }

    var showDeleteDialog by remember { mutableStateOf(false) }

    fun labelFor(name: String): String = when {
        isShutdown && name == BootAnimationUtils.NAME_DEFAULT ->
            context.getString(R.string.boot_animation_shutdown_default)
        else -> BootAnimationUtils.getLabel(context, name)
    }

    // Load thumbnails with bounded concurrency
    LaunchedEffect(target, styles) {
        val semaphore = Semaphore(3)
        styles.forEach { name ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    val thumb = loadThumbnailForStyle(context, target, name)
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
            previewInfo = null
            previewNote = null
            previewSource = null
            previewPath = null
            previewPart = -1 to -1
            previewBackground = null
            var resolved = name
            if (!isShutdown && name == BootAnimationUtils.NAME_RANDOM) {
                // "random" has no zip of its own: show one of the installed styles
                resolved = BootAnimationUtils.pickRandomPreviewName(context)
                previewSource = labelFor(resolved)
            }
            val path = pathFor(target, resolved)
            if (path == null) {
                if (isShutdown && resolved == BootAnimationUtils.NAME_DEFAULT) {
                    previewNote = context.getString(R.string.boot_animation_shutdown_no_default)
                }
            } else if (!File(path).exists()) {
                Log.w(TAG, "Boot animation zip not found for style $resolved: $path")
            } else {
                val pair = withContext(Dispatchers.IO) {
                    loadFramesFromPath(context, path) to BootAnimationUtils.getAnimationInfo(
                        path,
                        context.resources.displayMetrics.widthPixels,
                        context.resources.displayMetrics.heightPixels,
                    )
                }
                val (drawable, info) = pair
                previewDrawable = drawable
                previewInfo = info
                previewPath = path
            }
            isLoadingPreview = false
        }
    }

    fun applyStyle(name: String) {
        if (!BootAnimationUtils.isValidName(name)) return
        try {
            if (isShutdown) {
                // An empty property makes the engine fall back to the stock animation
                BootAnimationUtils.applyShutdownStyle(
                    if (name == BootAnimationUtils.NAME_DEFAULT) "" else name)
            } else {
                SystemProperties.set(BootAnimationUtils.PROP_NAME, name)
            }
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
            val dest = customFile(target).also { it.parentFile?.mkdirs() }
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

                val thumb = loadThumbnailForStyle(context, target, BootAnimationUtils.NAME_CUSTOM)
                withContext(Dispatchers.Main) {
                    thumbnails[BootAnimationUtils.NAME_CUSTOM] = thumb
                    customInstalled = true
                    styles = stylesFor(context, target)
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

    fun deleteCustom() {
        scope.launch(Dispatchers.IO) {
            val dest = customFile(target)
            val backup = File(dest.parentFile, "${dest.name}.bak")
            val temp = File(dest.parentFile, "${dest.name}.tmp")
            temp.delete()
            backup.delete()
            val removed = dest.delete()
            // Drop the cached thumbnails of the deleted zip
            File(context.cacheDir, THUMBNAIL_CACHE_DIR).listFiles { file ->
                file.name.startsWith("${target}_${BootAnimationUtils.NAME_CUSTOM}_")
            }?.forEach { it.delete() }
            withContext(Dispatchers.Main) {
                if (removed || !dest.exists()) {
                    customInstalled = false
                    styles = stylesFor(context, target)
                    thumbnails.remove(BootAnimationUtils.NAME_CUSTOM)
                    applyStyle(BootAnimationUtils.NAME_DEFAULT)
                    Toast.makeText(context, R.string.boot_animation_delete_done, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.boot_animation_applied_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openFullscreen() {
        val path = previewPath ?: return
        fullscreenJob?.cancel()
        fullscreen = true
        fullscreenPlaying = true
        fullscreenPart = -1 to -1
        fullscreenBackground = null
        fullscreenJob = scope.launch {
            isLoadingFullscreen = true
            fullscreenDrawable = null
            fullscreenDrawable = withContext(Dispatchers.IO) {
                BootAnimationUtils.getFullscreenPreviewFrames(context, path)
                    .takeIf { it.numberOfFrames > 0 }
            }
            isLoadingFullscreen = false
        }
    }

    // File picker — modern Activity Result API
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { handleCustomPick(it) } }

    // Initial load, and a reload whenever the target switches
    LaunchedEffect(target) { loadPreview(selectedName) }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .expressiveVerticalScroll(),
        ) {
            // ── Boot / shutdown switch ───────────────────────────────────────
            TargetSwitch(
                target = target,
                onSelect = { target = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Preview card ─────────────────────────────────────────────────
            PreviewCard(
                previewDrawable = previewDrawable,
                isLoading = isLoadingPreview,
                selectedName = labelFor(selectedName),
                sourceName = previewSource,
                info = previewInfo,
                note = previewNote,
                part = previewPart,
                onBackground = { previewBackground = it },
                onPart = { part, play -> previewPart = part to play },
                onClick = { if (previewDrawable != null) openFullscreen() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Style selector list ───────────────────────────────────────────
            StyleSelectorCard(
                target = target,
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

            // ── Remove the custom zip ────────────────────────────────────────
            if (customInstalled) {
                Spacer(modifier = Modifier.height(12.dp))
                DeleteCustomRow(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (fullscreen) {
        FullscreenPreviewDialog(
            isLoading = isLoadingFullscreen,
            drawable = fullscreenDrawable,
            playing = fullscreenPlaying,
            onPlaying = { fullscreenPlaying = it },
            info = previewInfo,
            part = fullscreenPart,
            background = fullscreenBackground,
            onBackground = { fullscreenBackground = it },
            onPart = { part, play -> fullscreenPart = part to play },
            onClose = {
                fullscreenJob?.cancel()
                fullscreenJob = null
                fullscreen = false
                fullscreenDrawable = null
                isLoadingFullscreen = false
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.boot_animation_delete_title)) },
            text = {
                Text(
                    stringResource(
                        if (isShutdown) R.string.boot_animation_delete_message_shutdown
                        else R.string.boot_animation_delete_message
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    deleteCustom()
                }) {
                    Text(stringResource(R.string.boot_animation_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.boot_animation_delete_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Target switch — the animation plays at boot or at power-off
// ---------------------------------------------------------------------------

@Composable
private fun TargetSwitch(
    target: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TargetChip(
            value = TARGET_BOOT,
            selected = target == TARGET_BOOT,
            onSelect = onSelect,
            labelRes = R.string.boot_animation_target_boot,
            modifier = Modifier.weight(1f),
        )
        TargetChip(
            value = TARGET_SHUTDOWN,
            selected = target == TARGET_SHUTDOWN,
            onSelect = onSelect,
            labelRes = R.string.boot_animation_target_shutdown,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TargetChip(
    value: String,
    selected: Boolean,
    onSelect: (String) -> Unit,
    labelRes: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(ExpressiveCorners.single).clickable { onSelect(value) },
        shape = ExpressiveCorners.single,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .wrapContentWidth(Alignment.CenterHorizontally),
        )
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
    sourceName: String?,
    info: BootAnimationUtils.AnimationInfo?,
    note: String?,
    part: Pair<Int, Int>,
    onBackground: (Color?) -> Unit,
    onPart: (Int, Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Colour of the part being played, like the boot animation clears to it
    Surface(
        modifier = modifier
            .clip(ExpressiveCorners.single)
            .clickable(onClick = onClick),
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
                        text = if (sourceName != null) "$selectedName · $sourceName" else selectedName,
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
                            onBackground = onBackground,
                            onPart = onPart,
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

            note?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (part.first >= 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.boot_animation_part_indicator,
                        part.first + 1, info?.partCount ?: 0, part.second + 1,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            info?.let { DetailsChips(info = it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Details chips — resolution, frame rate, parts, size and feature badges
// ---------------------------------------------------------------------------

@Composable
private fun DetailsChips(info: BootAnimationUtils.AnimationInfo) {
    val partsLabel = stringResource(
        R.string.boot_animation_details_parts, info.partCount, info.frameCount)
    val soundLabel = stringResource(R.string.boot_animation_badge_sound)
    val trimLabel = stringResource(R.string.boot_animation_badge_trim)
    val dynamicLabel = stringResource(R.string.boot_animation_badge_dynamic)
    val progressLabel = stringResource(R.string.boot_animation_badge_progress)
    val aspectWarning = stringResource(R.string.boot_animation_aspect_warning)

    val chips = buildList {
        if (info.width > 0 && info.height > 0) add("${info.width}×${info.height}")
        if (info.fps > 0) add("${info.fps} fps")
        if (info.partCount > 0) add(partsLabel)
        if (info.zipBytes > 0) add(formatBytes(info.zipBytes))
        if (info.hasSound) add(soundLabel)
        if (info.hasTrim) add(trimLabel)
        if (info.hasDynamicColors) add(dynamicLabel)
        if (info.progressEnabled) add(progressLabel)
    }

    if (chips.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { chip ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }

    if (info.aspectMismatch) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = aspectWarning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return if (bytes >= 1024L * 1024L) {
        "%.1f MB".format(Locale.US, bytes / (1024f * 1024f))
    } else {
        "%.0f KB".format(Locale.US, bytes / 1024f)
    }
}

// ---------------------------------------------------------------------------
// Style selector — Expressive group of choice rows with a thumbnail slot
// ---------------------------------------------------------------------------

@Composable
private fun StyleSelectorCard(
    target: String,
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
                val isRandom = name == BootAnimationUtils.NAME_RANDOM
                ExpressiveChoiceRow(
                    title = when {
                        target == TARGET_SHUTDOWN && name == BootAnimationUtils.NAME_DEFAULT ->
                            stringResource(R.string.boot_animation_shutdown_default)
                        else -> BootAnimationUtils.getLabel(context, name)
                    },
                    selected = selectedName == name,
                    onClick = { onSelect(name) },
                    summary = when {
                        isRandom -> stringResource(R.string.boot_animation_random_note)
                        !isCustom -> null
                        !customInstalled -> stringResource(
                            if (target == TARGET_SHUTDOWN) R.string.boot_animation_shutdown_custom_pick
                            else R.string.boot_animation_custom_pick
                        )
                        selectedName == name -> stringResource(
                            if (target == TARGET_SHUTDOWN) {
                                R.string.boot_animation_shutdown_custom_replace
                            } else {
                                R.string.boot_animation_custom_replace
                            }
                        )
                        else -> null
                    },
                    leading = {
                        ThumbnailSlot(
                            thumbnail = thumbnails[name],
                            isCustom = isCustom,
                            isRandom = isRandom,
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
    isRandom: Boolean,
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
            isRandom -> Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
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
// Delete custom row
// ---------------------------------------------------------------------------

@Composable
private fun DeleteCustomRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(ExpressiveCorners.single)
            .clickable(onClick = onClick),
        shape = ExpressiveCorners.single,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.boot_animation_delete_row_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.boot_animation_delete_row_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Fullscreen preview dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FullscreenPreviewDialog(
    isLoading: Boolean,
    drawable: Drawable?,
    playing: Boolean,
    onPlaying: (Boolean) -> Unit,
    info: BootAnimationUtils.AnimationInfo?,
    part: Pair<Int, Int>,
    background: Color?,
    onBackground: (Color?) -> Unit,
    onPart: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background ?: Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isLoading -> LoadingIndicator(modifier = Modifier.size(40.dp))
                    drawable != null -> Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AnimationPreviewView(
                            drawable = drawable,
                            playing = playing,
                            onBackground = onBackground,
                            onPart = onPart,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        FullscreenControls(
                            playing = playing,
                            onPlaying = onPlaying,
                            info = info,
                            part = part,
                            onClose = onClose,
                        )
                    }
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

@Composable
private fun FullscreenControls(
    playing: Boolean,
    onPlaying: (Boolean) -> Unit,
    info: BootAnimationUtils.AnimationInfo?,
    part: Pair<Int, Int>,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onPlaying(!playing) }) {
            Icon(
                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.boot_animation_fullscreen_pause
                    else R.string.boot_animation_fullscreen_play
                ),
                tint = Color.White,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (info != null && info.width > 0) "${info.width}×${info.height} · ${info.fps} fps"
                       else "",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            if (part.first >= 0 && info != null && info.partCount > 0) {
                Text(
                    text = stringResource(
                        R.string.boot_animation_part_indicator,
                        part.first + 1, info.partCount, part.second + 1,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.boot_animation_fullscreen_close),
                tint = Color.White,
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
    playing: Boolean = true,
    onBackground: (Color?) -> Unit,
    onPart: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Follow the colour of the frame being shown, parts of a zip can differ
    LaunchedEffect(drawable, playing) {
        if (drawable !is AnimationDrawable) {
            onBackground(null)
            onPart(-1, -1)
            return@LaunchedEffect
        }
        while (playing) {
            val frame = drawable.current as? BootAnimationUtils.PartFrame
            onBackground(
                if (frame != null &&
                    frame.background != BootAnimationUtils.PartFrame.NO_BACKGROUND) {
                    Color(frame.background)
                } else {
                    null
                }
            )
            if (frame != null && frame.partIndex >= 0) onPart(frame.partIndex, frame.playIndex)
            else onPart(-1, -1)
            delay(50)
        }
    }

    DisposableEffect(drawable, playing) {
        if (playing) {
            when (drawable) {
                is AnimatedImageDrawable -> drawable.start()
                is AnimationDrawable -> drawable.start()
            }
        } else {
            when (drawable) {
                is AnimatedImageDrawable -> drawable.stop()
                is AnimationDrawable -> drawable.stop()
            }
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
// Target helpers
// ---------------------------------------------------------------------------

private fun stylesFor(context: android.content.Context, target: String): List<String> {
    return if (target == TARGET_SHUTDOWN) {
        BootAnimationUtils.getShutdownStyleNames()
    } else {
        BootAnimationUtils.getStyleNames(context)
    }
}

private fun effectiveNameFor(context: android.content.Context, target: String): String {
    return if (target == TARGET_SHUTDOWN) {
        BootAnimationUtils.getShutdownEffectiveName()
    } else {
        BootAnimationUtils.getEffectiveName()
    }
}

private fun pathFor(target: String, name: String): String? {
    return if (target == TARGET_SHUTDOWN) {
        BootAnimationUtils.getShutdownPathForName(name)
    } else {
        BootAnimationUtils.getPathForName(name)
    }
}

private fun customFile(target: String): File {
    return if (target == TARGET_SHUTDOWN) {
        File(BootAnimationUtils.SHUTDOWN_CUSTOM_PATH)
    } else {
        File(BootAnimationUtils.CUSTOM_PATH)
    }
}

// ---------------------------------------------------------------------------
// IO helpers
// ---------------------------------------------------------------------------

private fun loadFramesFromPath(context: android.content.Context, zipPath: String): AnimationDrawable? {
    return BootAnimationUtils.getBootAnimationFramesFromPath(context, zipPath)
        .takeIf { it.numberOfFrames > 0 }
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
        .filter { it.size >= 4 && (it[0] == "p" || it[0] == "c" || it[0] == "f") }
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
private fun loadThumbnailForStyle(
    context: android.content.Context,
    target: String,
    name: String,
): Drawable? {
    val zipPath = pathFor(target, name) ?: return null
    val zipFile = File(zipPath)
    if (!zipFile.exists()) return null

    val dir = File(context.cacheDir, THUMBNAIL_CACHE_DIR)
    val prefix = "${target}_${name}_"
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
