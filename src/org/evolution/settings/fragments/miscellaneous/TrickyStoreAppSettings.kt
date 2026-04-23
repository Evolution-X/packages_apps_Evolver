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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val isSystem: Boolean = false,
    var targetMode: TargetMode = TargetMode.AUTO,
    var isInTarget: Boolean = false,
)

private val EXCLUDED_SUFFIXES = listOf(
    ".auto_generated", ".appsearch", ".backup", ".carrier",
    ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
    ".qti", ".qualcomm", ".resources", ".systemui.clocks",
    ".systemui.plugin", ".theme", ".iconpack",
)

class TrickyStoreAppSettings : Fragment() {

    companion object {
        const val TARGET_KEY = "spoof_trickystore_target"
        val DEFAULT_TARGETS = setOf(
            "com.google.android.gms",
            "com.android.vending",
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.ts_manage_target_apps)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                TrickyStoreAppSettingsContent(
                    context = requireContext(),
                )
            }
        }
    }
}

@Composable
private fun TrickyStoreAppSettingsContent(
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<AppEntry>() }

    fun loadTargetMap(): Map<String, TargetMode> {
        val result = mutableMapOf<String, TargetMode>()
        val content = Settings.Secure.getString(
            context.contentResolver, TrickyStoreAppSettings.TARGET_KEY
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
        val snapshot = allApps.toList()
        val lines = snapshot
            .filter { it.isInTarget }
            .map { it.packageName + it.targetMode.symbol }
        Settings.Secure.putString(
            context.contentResolver,
            TrickyStoreAppSettings.TARGET_KEY,
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
                        isSystem    = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
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

    val activeCount = allApps.count { it.isInTarget }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ---- Header card ----
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(24.dp),
                colors   = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier         = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text       = stringResource(R.string.ts_manage_target_apps),
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text  = if (activeCount == 0)
                                stringResource(R.string.ts_no_targets)
                            else
                                stringResource(R.string.ts_target_apps_count, activeCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.ts_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape      = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Toolbar: system apps chip | selected count ----
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

                if (activeCount > 0) {
                    Text(
                        text  = stringResource(R.string.ts_selected_count, activeCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

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
                            if (!allApps[i].isInTarget) {
                                allApps[i] = allApps[i].copy(isInTarget = true)
                            }
                        }
                        scope.launch(Dispatchers.IO) { saveTargets() }
                    },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.ts_select_all),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                OutlinedButton(
                    onClick  = {
                        allApps.indices.forEach { i ->
                            allApps[i] = allApps[i].copy(
                                isInTarget = false,
                                targetMode = TargetMode.AUTO,
                            )
                        }
                        TrickyStoreAppSettings.DEFAULT_TARGETS.forEach { pkg ->
                            val i = allApps.indexOfFirst { it.packageName == pkg }
                            if (i >= 0) allApps[i] = allApps[i].copy(isInTarget = true)
                        }
                        scope.launch(Dispatchers.IO) { saveTargets() }
                    },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.ts_reset),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- List ----
            if (isLoading) {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        TrickyStoreAppItem(
                            app          = app,
                            onToggle     = { nowEnabled ->
                                val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (i >= 0) {
                                    allApps[i] = allApps[i].copy(isInTarget = nowEnabled)
                                    scope.launch(Dispatchers.IO) { saveTargets() }
                                }
                            },
                            onModeChange = { mode ->
                                val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (i >= 0) {
                                    allApps[i] = allApps[i].copy(targetMode = mode)
                                    scope.launch(Dispatchers.IO) { saveTargets() }
                                }
                            },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TrickyStoreAppItem(
    app: AppEntry,
    onToggle: (Boolean) -> Unit,
    onModeChange: (TargetMode) -> Unit,
) {
    val iconBitmap = remember(app.packageName) {
        runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = RoundedCornerShape(14.dp),
        color = if (app.isInTarget)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // ---- Identity row ----
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!app.isInTarget) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // App icon
                if (iconBitmap != null) {
                    Image(
                        bitmap             = iconBitmap,
                        contentDescription = app.label,
                        modifier           = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier         = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text       = app.label,
                            style      = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            modifier   = Modifier.weight(1f, fill = false),
                        )
                        if (app.isSystem) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor   = MaterialTheme.colorScheme.onTertiary,
                            ) { Text(stringResource(R.string.ts_system_badge)) }
                        }
                    }
                    Text(
                        text     = app.packageName,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Switch
                Switch(
                    checked         = app.isInTarget,
                    onCheckedChange = onToggle,
                )
            }

            // ---- Mode selector (visible when enabled) ----
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
