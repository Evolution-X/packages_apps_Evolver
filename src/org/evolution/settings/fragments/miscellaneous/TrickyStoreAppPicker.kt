/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.evolution.settings.fragments.miscellaneous

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TargetMode(val symbol: String) {
    AUTO(""),
    LEAF_HACK("?"),
    CERT_GEN("!"),
}

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    var targetMode: TargetMode = TargetMode.AUTO,
    var isInTarget: Boolean = false,
)

class TrickyStoreAppPickerSheet : BottomSheetDialogFragment() {

    var onDismissed: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                AppPickerContent(
                    onDismiss = {
                        dismissAllowingStateLoss()
                        onDismissed?.invoke()
                    }
                )
            }
        }
    }

    @Composable
    private fun AppPickerContent(onDismiss: () -> Unit) {
        val context = requireContext()
        var searchQuery by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(true) }
        var showSystemApps by remember { mutableStateOf(false) }
        val allApps = remember { mutableStateListOf<AppEntry>() }
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        fun loadTargetMap(): Map<String, TargetMode> {
            val result = mutableMapOf<String, TargetMode>()
            val content = Settings.Secure.getString(
                context.contentResolver, TARGET_KEY
            ) ?: return result
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    when {
                        trimmed.endsWith("?") ->
                            result[trimmed.dropLast(1)] = TargetMode.LEAF_HACK
                        trimmed.endsWith("!") ->
                            result[trimmed.dropLast(1)] = TargetMode.CERT_GEN
                        else ->
                            result[trimmed] = TargetMode.AUTO
                    }
                }
            }
            return result
        }

        fun saveTargets() {
            val lines = allApps
                .filter { it.isInTarget }
                .map { it.packageName + it.targetMode.symbol }
            Settings.Secure.putString(
                context.contentResolver,
                TARGET_KEY,
                lines.joinToString("\n")
            )
        }

        LaunchedEffect(showSystemApps) {
            isLoading = true
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val targetMap = loadTargetMap()
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        val isExcluded = EXCLUDED_SUFFIXES.any { app.packageName.contains(it) }
                        if (isSystem && isExcluded) return@filter false
                        if (isSystem && !showSystemApps && !targetMap.containsKey(app.packageName))
                            return@filter false
                        true
                    }
                    .sortedWith(compareBy(
                        { !targetMap.containsKey(it.packageName) },
                        { pm.getApplicationLabel(it).toString().lowercase() }
                    ))
                    .map { app ->
                        AppEntry(
                            packageName = app.packageName,
                            label       = pm.getApplicationLabel(app).toString(),
                            icon        = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                            targetMode  = targetMap[app.packageName] ?: TargetMode.AUTO,
                            isInTarget  = targetMap.containsKey(app.packageName),
                        )
                    }
                withContext(Dispatchers.Main) {
                    allApps.clear()
                    allApps.addAll(installed)
                    isLoading = false
                }
            }
        }

        val filteredApps = remember(searchQuery, allApps.toList()) {
            val query = searchQuery.lowercase()
            allApps.filter { app ->
                query.isEmpty() ||
                    app.label.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
            }
        }

        ModalBottomSheet(
            onDismissRequest = {
                saveTargets()
                onDismiss()
            },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                // ---- Header ----
                Text(
                    text       = stringResource(R.string.ts_select_target_apps),
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text  = stringResource(R.string.ts_select_target_apps_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ---- Search ----
                OutlinedTextField(
                    value         = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text(stringResource(R.string.ts_search_apps)) },
                    leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon  = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    },
                    singleLine = true,
                    shape      = RoundedCornerShape(12.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ---- Toolbar row: System apps chip | selected count ----
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = showSystemApps,
                        onClick  = { showSystemApps = !showSystemApps },
                        label    = { Text(stringResource(R.string.ts_system_apps)) },
                        leadingIcon = if (showSystemApps) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                    )

                    Text(
                        text  = stringResource(R.string.ts_selected_count, allApps.count { it.isInTarget }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // ---- Select all / Reset row ----
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick  = {
                            val indices = filteredApps.map { f ->
                                allApps.indexOfFirst { it.packageName == f.packageName }
                            }.filter { it >= 0 }
                            indices.forEach { i ->
                                allApps[i] = allApps[i].copy(isInTarget = true)
                            }
                            saveTargets()
                        },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.ts_select_all), style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick  = {
                            val indices = allApps.indices.toList()
                            indices.forEach { i ->
                                allApps[i] = allApps[i].copy(
                                    isInTarget = false,
                                    targetMode = TargetMode.AUTO,
                                )
                            }
                            saveTargets()
                        },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.ts_reset), style = MaterialTheme.typography.labelMedium)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ---- List ----
                if (isLoading) {
                    Box(
                        modifier         = Modifier.fillMaxWidth().height(300.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier            = Modifier.fillMaxWidth().height(400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppListItem(
                                app          = app,
                                onToggle     = {
                                    val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                    if (i >= 0) {
                                        allApps[i] = allApps[i].copy(isInTarget = !allApps[i].isInTarget)
                                        saveTargets()
                                    }
                                },
                                onModeChange = { mode ->
                                    val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                    if (i >= 0) {
                                        allApps[i] = allApps[i].copy(targetMode = mode)
                                        saveTargets()
                                    }
                                },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG        = "TrickyStoreAppPickerSheet"
        const val TARGET_KEY = "spoof_trickystore_target"

        private val EXCLUDED_SUFFIXES = listOf(
            ".auto_generated", ".appsearch", ".backup", ".carrier",
            ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
            ".qti", ".qualcomm", ".resources", ".systemui.clocks",
            ".systemui.plugin", ".theme", ".iconpack",
        )
    }
}

@Composable
private fun AppListItem(
    app: AppEntry,
    onToggle: () -> Unit,
    onModeChange: (TargetMode) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = RoundedCornerShape(12.dp),
        color = if (app.isInTarget)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // ---- Identity row ----
            Row(
                modifier          = Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // App icon
                Box(
                    modifier         = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = remember(app.packageName) {
                        runCatching { app.icon?.toBitmap() }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap             = bmp.asImageBitmap(),
                            contentDescription = app.label,
                            modifier           = Modifier.size(40.dp).clip(CircleShape),
                        )
                    } else {
                        Text(
                            text       = app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Labels
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = app.label,
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    Text(
                        text     = app.packageName,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Checkbox
                Checkbox(
                    checked         = app.isInTarget,
                    onCheckedChange = { onToggle() },
                )
            }

            // ---- Mode selector (visible when selected) ----
            AnimatedVisibility(
                visible = app.isInTarget,
                enter   = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                          expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit    = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                          shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        TargetMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = app.targetMode == mode,
                                onClick  = { onModeChange(mode) },
                                shape    = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = TargetMode.entries.size,
                                ),
                                label = {
                                    Text(
                                        text = when (mode) {
                                            TargetMode.AUTO      -> stringResource(R.string.ts_mode_auto)
                                            TargetMode.LEAF_HACK -> stringResource(R.string.ts_mode_leaf)
                                            TargetMode.CERT_GEN  -> stringResource(R.string.ts_mode_cert)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
