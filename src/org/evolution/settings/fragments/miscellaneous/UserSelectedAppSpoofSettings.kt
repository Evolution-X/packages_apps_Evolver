/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class UserSelectedAppSpoofSettings : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.user_selectable_app_spoofing_title)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View {
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy
                    .DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                SettingsTheme {
                    AppSpoofingContent(requireContext())
                }
            }
        }
    }
}

private const val SPOOFED_APPS_SETTING = Settings.Secure.PER_APPS_DEVICE_SPOOF
private const val SPOOFED_APPS_CACHE_SETTING = Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE
private const val SPOOFED_APPS_ENABLED_SETTING = Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED

private data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean
)

@Composable
private fun AppSpoofingContent(context: Context) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val profileValues = context.resources.getStringArray(R.array.neoteric_spoof_profile_values)
    val profileLabels = context.resources.getStringArray(R.array.neoteric_spoof_profile_labels)
    val profileLabelMap = remember(profileValues.contentToString(), profileLabels.contentToString()) {
        profileValues.indices.associate { idx ->
            profileValues[idx] to profileLabels.getOrElse(idx) { profileValues[idx] }
        }
    }

    var allApps by remember { mutableStateOf(listOf<AppItem>()) }
    var configuredMap by remember { mutableStateOf(linkedMapOf<String, String>()) }
    var spoofEnabled by remember { mutableStateOf(true) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AppItem?>(null) }

    fun loadState() {
        spoofEnabled = readEnabled(context)
        configuredMap = linkedMapOf<String, String>().apply {
            putAll(readConfigured(context, spoofEnabled))
        }
    }

    fun persistConfigured() {
        writeConfigured(context, configuredMap, spoofEnabled)
    }

    fun stopPackage(pkg: String) {
        try {
            activityManager?.forceStopPackage(pkg)
        } catch (_: Exception) {
        }
    }

    fun stopAllConfiguredPackages() {
        configuredMap.keys.forEach { stopPackage(it) }
    }

    fun allKnownConfiguredPackages(): Set<String> {
        val keys = linkedSetOf<String>()
        keys.addAll(configuredMap.keys)
        keys.addAll(readMapSetting(context, SPOOFED_APPS_SETTING).keys)
        keys.addAll(readMapSetting(context, SPOOFED_APPS_CACHE_SETTING).keys)
        return keys
    }

    fun setMasterEnabled(enabled: Boolean) {
        val targets = allKnownConfiguredPackages()
        spoofEnabled = enabled
        writeEnabled(context, enabled)
        if (enabled) {
            val cached = readMapSetting(context, SPOOFED_APPS_CACHE_SETTING)
            writeMapSetting(context, SPOOFED_APPS_SETTING, cached)
        } else {
            val active = readMapSetting(context, SPOOFED_APPS_SETTING)
            writeMapSetting(context, SPOOFED_APPS_CACHE_SETTING, active)
            writeMapSetting(context, SPOOFED_APPS_SETTING, emptyMap())
        }
        targets.forEach { stopPackage(it) }
    }

    fun clearAllConfigured() {
        val targets = allKnownConfiguredPackages()
        configuredMap = linkedMapOf()
        writeMapSetting(context, SPOOFED_APPS_CACHE_SETTING, emptyMap())
        writeMapSetting(context, SPOOFED_APPS_SETTING, emptyMap())
        targets.forEach { stopPackage(it) }
    }

    LaunchedEffect(Unit) {
        loadState()
        allApps = withContext(Dispatchers.IO) {
            pm.getInstalledPackages(PackageManager.MATCH_ANY_USER)
                .mapNotNull { pkg ->
                    val ai = pkg.applicationInfo ?: return@mapNotNull null
                    AppItem(
                        packageName = pkg.packageName,
                        label = ai.loadLabel(pm).toString(),
                        icon = ai.loadIcon(pm),
                        isSystem = ai.isSystemApp
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(Locale.getDefault()) }
        }
    }

    if (showAddDialog) {
        AddAppDialog(
            allApps = allApps,
            configuredPackages = configuredMap.keys,
            profileValues = profileValues,
            profileLabels = profileLabels,
            onDismiss = { showAddDialog = false },
            onAppAdded = { app, profile ->
                configuredMap = LinkedHashMap(configuredMap).apply {
                    put(app.packageName, profile)
                }
                persistConfigured()
                if (spoofEnabled) stopPackage(app.packageName)
                showAddDialog = false
            }
        )
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text(stringResource(R.string.user_select_spoofing_profile_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profileValues.forEachIndexed { index, value ->
                        val label = profileLabels.getOrElse(index) { value }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val target = editTarget ?: return@clickable
                                    configuredMap = LinkedHashMap(configuredMap).apply {
                                        put(target.packageName, value)
                                    }
                                    persistConfigured()
                                    if (spoofEnabled) stopPackage(target.packageName)
                                    showModelDialog = false
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Apps, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_spoofing_header_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (spoofEnabled) {
                                    stringResource(
                                        R.string.app_spoofing_configured_count,
                                        configuredMap.size
                                    )
                                } else {
                                    stringResource(R.string.app_spoofing_disabled)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = spoofEnabled,
                        onCheckedChange = { setMasterEnabled(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (spoofEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.app_spoofing_add_apps))
                    }

                    OutlinedButton(
                        onClick = { showModelDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.app_spoofing_spoofed_model))
                    }

                    OutlinedButton(
                        onClick = {
                            clearAllConfigured()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.app_spoofing_clear_all))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (configuredMap.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.app_spoofing_configured_apps),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    configuredMap.forEach { (pkg, profile) ->
                        val app = allApps.find { it.packageName == pkg }
                        if (app != null) {
                            AppConfigCard(
                                app = app,
                                profile = profile,
                                profileLabel = profileLabelMap[profile] ?: profile,
                                onRemove = {
                                    configuredMap = LinkedHashMap(configuredMap).apply { remove(pkg) }
                                    persistConfigured()
                                    stopPackage(pkg)
                                },
                                onEdit = {
                                    editTarget = app
                                    showModelDialog = true
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.app_spoofing_no_apps_configured),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppConfigCard(
    app: AppItem,
    profile: String,
    profileLabel: String,
    onRemove: () -> Unit,
    onEdit: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val iconBitmap = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.app_spoofing_spoof_model),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                profileLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            profile,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.remove))
                }
                FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppDialog(
    allApps: List<AppItem>,
    configuredPackages: Set<String>,
    profileValues: Array<String>,
    profileLabels: Array<String>,
    onDismiss: () -> Unit,
    onAppAdded: (AppItem, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var showProfileSelector by remember { mutableStateOf(false) }

    val addableProfiles = profileValues.filter { it != "None" }
    val profileLabelMap = profileValues.indices.associate { idx ->
        profileValues[idx] to profileLabels.getOrElse(idx) { profileValues[idx] }
    }

    val filteredApps = allApps.filter { app ->
        if (configuredPackages.contains(app.packageName)) return@filter false
        if (!showSystemApps && app.isSystem) return@filter false
        if (searchQuery.isBlank()) return@filter true
        app.label.contains(searchQuery, true) || app.packageName.contains(searchQuery, true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.app_spoofing_add_apps))
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (showSystemApps) {
                                        stringResource(R.string.hide_system_apps)
                                    } else {
                                        stringResource(R.string.show_system_apps)
                                    }
                                )
                            },
                            onClick = {
                                showSystemApps = !showSystemApps
                                showMenu = false
                            }
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!showProfileSelector) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.search_apps)) }
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredApps.forEach { app ->
                            val icon = remember(app.packageName) { app.icon.toBitmap(96, 96).asImageBitmap() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedApp = app
                                        showProfileSelector = true
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.app_spoofing_select_spoof_model),
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onClick = { showProfileSelector = false }) {
                        Text(stringResource(R.string.app_spoofing_change_app))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        addableProfiles.forEach { profile ->
                            val label = profileLabelMap[profile] ?: profile
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedProfile = profile }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectedProfile == profile) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Spacer(modifier = Modifier.width(24.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(label)
                                    Text(
                                        profile,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val app = selectedApp ?: return@TextButton
                    val profile = selectedProfile ?: return@TextButton
                    onAppAdded(app, profile)
                },
                enabled = selectedApp != null && selectedProfile != null
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun readMapSetting(context: Context, key: String): Map<String, String> {
    val stored = Settings.Secure.getString(context.contentResolver, key) ?: return emptyMap()
    if (stored.isBlank()) return emptyMap()
    val map = linkedMapOf<String, String>()
    stored.split(",").forEach { entry ->
        val parts = entry.split(":")
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            map[parts[0]] = parts[1]
        }
    }
    return map
}

private fun writeMapSetting(context: Context, key: String, values: Map<String, String>) {
    val encoded = values.entries.joinToString(",") { "${it.key}:${it.value}" }
    Settings.Secure.putString(context.contentResolver, key, encoded)
}

private fun readEnabled(context: Context): Boolean {
    return Settings.Secure.getInt(context.contentResolver, SPOOFED_APPS_ENABLED_SETTING, 1) == 1
}

private fun writeEnabled(context: Context, enabled: Boolean) {
    Settings.Secure.putInt(context.contentResolver, SPOOFED_APPS_ENABLED_SETTING, if (enabled) 1 else 0)
}

private fun readConfigured(context: Context, enabled: Boolean): Map<String, String> {
    return if (enabled) {
        val active = readMapSetting(context, SPOOFED_APPS_SETTING)
        if (active.isNotEmpty()) {
            writeMapSetting(context, SPOOFED_APPS_CACHE_SETTING, active)
        }
        active.ifEmpty { readMapSetting(context, SPOOFED_APPS_CACHE_SETTING) }
    } else {
        readMapSetting(context, SPOOFED_APPS_CACHE_SETTING)
    }
}

private fun writeConfigured(context: Context, values: Map<String, String>, enabled: Boolean) {
    writeMapSetting(context, SPOOFED_APPS_CACHE_SETTING, values)
    if (enabled) {
        writeMapSetting(context, SPOOFED_APPS_SETTING, values)
    } else {
        writeMapSetting(context, SPOOFED_APPS_SETTING, emptyMap())
    }
}
