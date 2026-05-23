/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Settings helpers
// ---------------------------------------------------------------------------

private fun readBlockedSet(context: Context): Set<String> {
    val raw = Settings.Global.getString(
        context.contentResolver,
        Settings.Global.SENSOR_BLOCKED_APP,
    ) ?: return emptySet()
    return raw.split("|").filter { it.isNotBlank() }.toSet()
}

private fun writeBlockedSet(context: Context, packages: Set<String>) {
    val value = if (packages.isEmpty()) null else packages.joinToString("|")
    Settings.Global.putString(context.contentResolver, Settings.Global.SENSOR_BLOCKED_APP, value)
}

private fun readSensorMasterEnabled(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.SENSOR_BLOCK, 0) != 0

private fun writeSensorMasterEnabled(context: Context, enabled: Boolean) {
    Settings.Global.putInt(
        context.contentResolver,
        Settings.Global.SENSOR_BLOCK,
        if (enabled) 1 else 0,
    )
}

// ---------------------------------------------------------------------------
// Fragment
// ---------------------------------------------------------------------------

class SensorBlock : Fragment() {

    @SuppressLint("QueryPermissionsNeeded")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.sensor_block_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                SensorBlockContent(context = requireContext())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SensorBlockContent(context: Context) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val scope = rememberCoroutineScope()

    var masterEnabled by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<AppListEntry>() }
    val blockedPackages = remember { mutableStateListOf<String>() }

    var showAddDialog by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    fun persistBlocked() {
        writeBlockedSet(context, blockedPackages.toSet())
    }

    fun toggleApp(pkg: String, block: Boolean) {
        if (block) {
            if (!blockedPackages.contains(pkg)) {
                blockedPackages.add(pkg)
                persistBlocked()
                if (masterEnabled) stopPackage(activityManager, pkg)
            }
        } else {
            blockedPackages.remove(pkg)
            persistBlocked()
            stopPackage(activityManager, pkg)
        }
        // Mirror state into allApps list for AppPickerItem checked state
        val i = allApps.indexOfFirst { it.packageName == pkg }
        if (i >= 0) allApps[i] = allApps[i].copy(isSelected = block)
    }

    fun clearAll() {
        val toStop = blockedPackages.toList()
        blockedPackages.clear()
        writeBlockedSet(context, emptySet())
        allApps.indices.forEach { i -> allApps[i] = allApps[i].copy(isSelected = false) }
        toStop.forEach { stopPackage(activityManager, it) }
    }

    fun setMasterEnabled(enabled: Boolean) {
        masterEnabled = enabled
        writeSensorMasterEnabled(context, enabled)
        if (!enabled) killPackages(activityManager, blockedPackages.toSet())
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val enabledNow = readSensorMasterEnabled(context)
            val blocked = readBlockedSet(context)
            val installed = filterInstalledApps(
                pm = pm,
                showSystem = true,
                targeted = blocked,
            )
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase(java.util.Locale.getDefault()) }
                .map { app ->
                    AppListEntry(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                        isSystem = app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                        isSelected = app.packageName in blocked,
                    )
                }
            withContext(Dispatchers.Main) {
                masterEnabled = enabledNow
                blockedPackages.addAll(blocked)
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dialogs
    // -----------------------------------------------------------------------

    if (showAddDialog) {
        SensorBlockAddAppDialog(
            allApps = allApps,
            blockedPackages = blockedPackages.toSet(),
            onDismiss = { showAddDialog = false },
            onToggle = { pkg, nowBlocked ->
                scope.launch(Dispatchers.IO) { toggleApp(pkg, nowBlocked) }
            },
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            icon = {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text(stringResource(R.string.sensor_block_clear_all)) },
            text = { Text(stringResource(R.string.sensor_block_clear_all_confirm)) },
            confirmButton = {
                Button(
                    onClick = { clearAll(); showClearAllConfirm = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.sensor_block_clear_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SpoofingHeaderCard(
                title = stringResource(R.string.sensor_block_header_title),
                subtitle = when {
                    !masterEnabled -> stringResource(R.string.sensor_block_disabled)
                    blockedPackages.isEmpty() -> stringResource(R.string.sensor_block_no_apps_configured)
                    else -> stringResource(R.string.sensor_block_count, blockedPackages.size)
                },
                checked = masterEnabled,
                onCheckedChange = { newValue ->
                    setMasterEnabled(newValue)
                },
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    tint = if (masterEnabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SpoofingAnimatedVisibility(visible = masterEnabled) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.sensor_block_add_apps),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        OutlinedButton(
                            onClick = { showClearAllConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.sensor_block_clear_all),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoading) {
                        SpoofingLoadingBox(modifier = Modifier.weight(1f))
                    } else if (blockedPackages.isEmpty()) {
                        SpoofingEmptyState(
                            icon = Icons.Default.Block,
                            title = stringResource(R.string.sensor_block_no_apps_configured),
                            description = stringResource(R.string.sensor_block_empty_description),
                        )
                    } else {
                        SectionLabel(stringResource(R.string.sensor_block_configured_apps))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = blockedPackages.toList(),
                                key = { it },
                            ) { pkg ->
                                val app = allApps.find { it.packageName == pkg }
                                if (app != null) {
                                    AppPickerItem(
                                        packageName = app.packageName,
                                        label = app.label,
                                        icon = app.icon,
                                        isSystem = app.isSystem,
                                        checked = true,
                                        onToggle = { nowChecked ->
                                            scope.launch { toggleApp(pkg, nowChecked) }
                                        },
                                    )
                                }
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add app dialog — toggle list (same pattern as PixelProps / TensorTargets)
// No confirm button; toggles are immediate. Dismiss via back/outside tap.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorBlockAddAppDialog(
    allApps: List<AppListEntry>,
    blockedPackages: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val filteredApps = remember(searchQuery, showSystemApps, allApps, blockedPackages) {
        allApps.filter { app ->
            if (!showSystemApps && app.isSystem && !blockedPackages.contains(app.packageName))
                return@filter false
            if (searchQuery.isBlank()) return@filter true
            app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.sensor_block_add_apps))
                // Uses shared menu composable from SpoofingSharedUi — but that lives in
                // AppPickerCommon. We inline the FilterChip approach here to stay
                // consistent with Tensor/PixelProps instead of a MoreVert menu.
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                    label = {
                        Text(
                            if (showSystemApps) stringResource(R.string.hide_system_apps)
                            else stringResource(R.string.show_system_apps),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = if (showSystemApps) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppPickerSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                )

                if (filteredApps.isEmpty()) {
                    SpoofingEmptyState(
                        icon = Icons.Default.Block,
                        title = if (searchQuery.isBlank())
                            stringResource(R.string.sensor_block_no_apps_available)
                        else
                            stringResource(R.string.sensor_block_no_apps_found, searchQuery),
                        description = "",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppPickerItem(
                                packageName = app.packageName,
                                label = app.label,
                                icon = app.icon,
                                isSystem = app.isSystem,
                                checked = blockedPackages.contains(app.packageName),
                                onToggle = { nowChecked ->
                                    onToggle(app.packageName, nowChecked)
                                },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = null,
    )
}
