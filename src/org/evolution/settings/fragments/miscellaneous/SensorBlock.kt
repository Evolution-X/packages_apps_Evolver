/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
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
import java.util.Locale

// ---------------------------------------------------------------------------
// Data model
// ---------------------------------------------------------------------------

private data class SensorBlockAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
)

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
// Settings helpers — mirrors the original Java read/write logic exactly.
// Delimiter is '|' (pipe), same as the old Java code.
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

private fun readMasterEnabled(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.SENSOR_BLOCK, 0) != 0

private fun writeMasterEnabled(context: Context, enabled: Boolean) {
    Settings.Global.putInt(
        context.contentResolver,
        Settings.Global.SENSOR_BLOCK,
        if (enabled) 1 else 0,
    )
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
    val haptic = LocalHapticFeedback.current

    // State
    var masterEnabled by remember { mutableStateOf(false) }
    val allApps = remember { mutableStateListOf<SensorBlockAppItem>() }
    val blockedPackages = remember { mutableStateListOf<String>() }

    // Dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    fun stopPackage(pkg: String) {
        try { activityManager?.forceStopPackage(pkg) } catch (_: Exception) {}
    }

    fun persistBlocked() {
        writeBlockedSet(context, blockedPackages.toSet())
    }

    fun addApp(pkg: String) {
        if (!blockedPackages.contains(pkg)) {
            blockedPackages.add(pkg)
            persistBlocked()
            if (masterEnabled) stopPackage(pkg)
        }
    }

    fun removeApp(pkg: String) {
        blockedPackages.remove(pkg)
        persistBlocked()
        stopPackage(pkg)
    }

    fun clearAll() {
        val toStop = blockedPackages.toList()
        blockedPackages.clear()
        writeBlockedSet(context, emptySet())
        toStop.forEach { stopPackage(it) }
    }

    fun setMasterEnabled(enabled: Boolean) {
        masterEnabled = enabled
        writeMasterEnabled(context, enabled)
        // Force-stop all blocked packages so the change takes effect immediately.
        if (!enabled) {
            blockedPackages.forEach { stopPackage(it) }
        }
    }

    // ---------------------------------------------------------------------------
    // Initial load
    // ---------------------------------------------------------------------------

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val enabledNow = readMasterEnabled(context)
            val blocked = readBlockedSet(context)

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
                .map { app ->
                    SensorBlockAppItem(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null },
                        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    )
                }

            withContext(Dispatchers.Main) {
                masterEnabled = enabledNow
                blockedPackages.addAll(blocked)
                allApps.addAll(installed)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Dialogs
    // ---------------------------------------------------------------------------

    if (showAddDialog) {
        SensorBlockAddAppDialog(
            allApps = allApps,
            blockedPackages = blockedPackages.toSet(),
            onDismiss = { showAddDialog = false },
            onAppAdded = { pkg ->
                scope.launch { addApp(pkg) }
                showAddDialog = false
            },
        )
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.sensor_block_clear_all)) },
            text = { Text(stringResource(R.string.sensor_block_clear_all_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        clearAll()
                        showClearAllConfirm = false
                    },
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

    // ---------------------------------------------------------------------------
    // Main scaffold
    // ---------------------------------------------------------------------------

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Header card with master switch
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Block,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.sensor_block_header_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (!masterEnabled)
                                    stringResource(R.string.sensor_block_disabled)
                                else if (blockedPackages.isEmpty())
                                    stringResource(R.string.sensor_block_none_configured)
                                else
                                    stringResource(R.string.sensor_block_count, blockedPackages.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = masterEnabled,
                        onCheckedChange = { newValue ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            setMasterEnabled(newValue)
                        },
                        thumbContent = {
                            Crossfade(
                                targetState = masterEnabled,
                                animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
                                label = "switch_icon",
                            ) { isChecked ->
                                if (isChecked) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Body — only visible when master switch is on
            AnimatedVisibility(
                visible = masterEnabled,
                enter = fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            expandFrom = Alignment.Top,
                        ),
                exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
                        shrinkVertically(
                            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            shrinkTowards = Alignment.Top,
                        ),
            ) {
                Column {
                    // Action row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
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
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.sensor_block_clear_all),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (blockedPackages.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.sensor_block_configured_apps),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )

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
                                    SensorBlockAppCard(
                                        app = app,
                                        onRemove = {
                                            scope.launch(Dispatchers.IO) { removeApp(pkg) }
                                        },
                                    )
                                }
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    } else {
                        // Empty state
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    Icons.Default.Block,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.sensor_block_no_apps_configured),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Per-app card
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SensorBlockAppCard(
    app: SensorBlockAppItem,
    onRemove: () -> Unit,
) {
    val iconBitmap = remember(app.packageName) {
        app.icon?.toBitmap(96, 96)?.asImageBitmap()
    }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.sensor_block_remove_title)) },
            text = { Text(stringResource(R.string.sensor_block_remove_confirm, app.label)) },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveConfirm = false
                        onRemove()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (app.isSystem) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ) { Text(stringResource(R.string.sensor_block_system_badge)) }
                        }
                    }
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showRemoveConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.remove))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add app dialog
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorBlockAddAppDialog(
    allApps: List<SensorBlockAppItem>,
    blockedPackages: Set<String>,
    onDismiss: () -> Unit,
    onAppAdded: (String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<SensorBlockAppItem?>(null) }

    val filteredApps = remember(searchQuery, showSystemApps, allApps, blockedPackages) {
        allApps.filter { app ->
            if (blockedPackages.contains(app.packageName)) return@filter false
            if (!showSystemApps && app.isSystem) return@filter false
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
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (showSystemApps) stringResource(R.string.hide_system_apps)
                                    else stringResource(R.string.show_system_apps),
                                )
                            },
                            onClick = {
                                showSystemApps = !showSystemApps
                                showMenu = false
                            },
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_apps)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                    },
                )

                if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank())
                                stringResource(R.string.sensor_block_no_apps_available)
                            else
                                stringResource(R.string.sensor_block_no_apps_found, searchQuery),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val iconBitmap = remember(app.packageName) {
                                app.icon?.toBitmap(96, 96)?.asImageBitmap()
                            }
                            val isSelected = selectedApp?.packageName == app.packageName

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedApp = app }
                                    .background(
                                        if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                        else Color.Transparent,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (iconBitmap != null) {
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            app.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (app.isSystem) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Badge(
                                                containerColor = MaterialTheme.colorScheme.tertiary,
                                                contentColor = MaterialTheme.colorScheme.onTertiary,
                                            ) { Text(stringResource(R.string.sensor_block_system_badge)) }
                                        }
                                    }
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        selectedApp?.let { onAppAdded(it.packageName) }
                    },
                    enabled = selectedApp != null,
                ) {
                    Text(stringResource(R.string.add))
                }
            }
        },
        dismissButton = null,
    )
}
