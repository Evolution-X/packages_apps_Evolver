/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.material3.SegmentedListItem
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.dialog.getDialogWidth
import com.android.settingslib.spa.widget.ui.CategoryTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

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
                ExpressiveTheme {
                    AppSpoofingContent(requireContext())
                }
            }
        }
    }
}

private const val SPOOFED_APPS_SETTING = Settings.Secure.PER_APPS_DEVICE_SPOOF
private const val SPOOFED_APPS_CACHE_SETTING = Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE
private const val SPOOFED_APPS_ENABLED_SETTING = Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED
private const val CUSTOM_SPOOF_PROFILES_SETTING = Settings.Secure.CUSTOM_SPOOF_PROFILES

private data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean
)

private data class CustomSpoofProfile(
    val id: String,
    val name: String,
    val brand: String,
    val manufacturer: String,
    val device: String,
    val model: String,
    val fingerprint: String,
    val product: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppSpoofingContent(context: Context) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val profileValues = context.resources.getStringArray(R.array.perapp_spoof_profile_values)
    val profileLabels = context.resources.getStringArray(R.array.perapp_spoof_profile_labels)
    val profileLabelMap = remember(profileValues.contentToString(), profileLabels.contentToString()) {
        profileValues.indices.associate { idx ->
            profileValues[idx] to profileLabels.getOrElse(idx) { profileValues[idx] }
        }
    }

    val scope = rememberCoroutineScope()

    var allApps by remember { mutableStateOf(listOf<AppItem>()) }
    var configuredMap by remember { mutableStateOf(linkedMapOf<String, String>()) }
    var spoofEnabled by remember { mutableStateOf(true) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showAddCustomProfileDialog by remember { mutableStateOf(false) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var customProfileToEdit by remember { mutableStateOf<CustomSpoofProfile?>(null) }
    var editTarget by remember { mutableStateOf<AppItem?>(null) }
    var customProfiles by remember { mutableStateOf(listOf<CustomSpoofProfile>()) }

    fun loadState() {
        spoofEnabled = readEnabled(context)
        configuredMap = linkedMapOf<String, String>().apply {
            putAll(readConfigured(context, spoofEnabled))
        }
        customProfiles = readCustomProfiles(context)
    }

    fun persistConfigured() {
        writeConfigured(context, configuredMap, spoofEnabled)
    }

    fun stopPackage(pkg: String) {
        try {
            activityManager?.forceStopPackage(pkg)
        } catch (_: Exception) {}
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
            val toRestore = cached.ifEmpty { readMapSetting(context, SPOOFED_APPS_SETTING) }
            writeMapSetting(context, SPOOFED_APPS_SETTING, toRestore)
            configuredMap = LinkedHashMap(toRestore)
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
                        if (pkg.overlayTarget != null) return@mapNotNull null
                        AppItem(
                            packageName = pkg.packageName,
                            label = ai.loadLabel(pm).toString(),
                            icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
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
            customProfiles = customProfiles,
            onDismiss = { showAddDialog = false },
            onAppAdded = { app, profile ->
                configuredMap = LinkedHashMap(configuredMap).apply {
                    put(app.packageName, profile)
                }
                persistConfigured()
                if (spoofEnabled) stopPackage(app.packageName)
                showAddDialog = false
            },
            onWriteCustomProfiles = { updated ->
                writeCustomProfiles(context, updated)
                customProfiles = updated
            }
        )
    }

    if (showModelDialog) {
        val currentProfile = editTarget?.let { configuredMap[it.packageName] }
        ExpressiveDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text(stringResource(R.string.user_select_spoofing_profile_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .expressiveVerticalScroll(),
                ) {
                    ExpressiveGroup(title = "", horizontalInset = 0.dp) {
                        profileValues.forEachIndexed { index, value ->
                            item {
                                ExpressiveChoiceRow(
                                    title = profileLabels.getOrElse(index) { value },
                                    selected = currentProfile == value,
                                    onClick = {
                                        val target = editTarget ?: return@ExpressiveChoiceRow
                                        configuredMap = LinkedHashMap(configuredMap).apply {
                                            put(target.packageName, value)
                                        }
                                        persistConfigured()
                                        if (spoofEnabled) stopPackage(target.packageName)
                                        showModelDialog = false
                                    },
                                )
                            }
                        }
                        customProfiles.forEach { profile ->
                            item {
                                ExpressiveChoiceRow(
                                    title = profile.name,
                                    selected = currentProfile == profile.id,
                                    onClick = {
                                        val target = editTarget ?: return@ExpressiveChoiceRow
                                        configuredMap = LinkedHashMap(configuredMap).apply {
                                            put(target.packageName, profile.id)
                                        }
                                        persistConfigured()
                                        if (spoofEnabled) stopPackage(target.packageName)
                                        showModelDialog = false
                                    },
                                    trailing = {
                                        IconButton(shapes = IconButtonDefaults.shapes(), modifier = Modifier.iconButtonFocusRing(), onClick = {
                                            customProfileToEdit = profile
                                            showAddCustomProfileDialog = true
                                            showModelDialog = false
                                        }) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                        IconButton(shapes = IconButtonDefaults.shapes(), modifier = Modifier.iconButtonFocusRing(), onClick = {
                                            val newList = customProfiles.filter { it.id != profile.id }
                                            writeCustomProfiles(context, newList)
                                            customProfiles = newList
                                            val newConfigured = configuredMap.filterValues { it != profile.id }
                                            if (newConfigured.size != configuredMap.size) {
                                                configuredMap = LinkedHashMap(newConfigured)
                                                persistConfigured()
                                            }
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExpressiveTextButton(onClick = {
                        showModelDialog = false
                        showAddCustomProfileDialog = true
                    }) {
                        Text(stringResource(R.string.add_new_spoof_profile))
                    }
                    ExpressiveTextButton(onClick = { showModelDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }

    if (showAddCustomProfileDialog) {
        val passedId = remember(customProfileToEdit) {
            customProfileToEdit?.id ?: ("CUSTOM_" + System.currentTimeMillis())
        }
        AddCustomProfileDialog(
            context = context,
            initialProfile = customProfileToEdit,
            onDismiss = {
                showAddCustomProfileDialog = false
                customProfileToEdit = null
            },
            onSave = { newProfile ->
                val updatedProfiles = if (customProfileToEdit != null) {
                    customProfiles.map { if (it.id == newProfile.id) newProfile else it }
                } else {
                    customProfiles + newProfile
                }
                writeCustomProfiles(context, updatedProfiles)
                customProfiles = updatedProfiles
                showAddCustomProfileDialog = false
                customProfileToEdit = null
                showModelDialog = true
            },
            id = passedId
        )
    }

    if (showClearAllConfirm) {
        ExpressiveConfirmDialog(
            title = stringResource(R.string.app_spoofing_clear_all),
            message = stringResource(R.string.app_spoofing_clear_all_confirm),
            confirmText = stringResource(R.string.app_spoofing_clear_all),
            destructive = true,
            onConfirm = { clearAllConfigured() },
            onDismiss = { showClearAllConfirm = false },
            dismissText = stringResource(R.string.cancel),
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .expressiveVerticalScroll()
                .padding(innerPadding)
                .padding(horizontal = SettingsSpace.small1)
        ) {
            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            ExpressiveHeaderCard(
                title = stringResource(R.string.app_spoofing_header_title),
                subtitle = if (spoofEnabled)
                    stringResource(R.string.app_spoofing_configured_count, configuredMap.size)
                else
                    stringResource(R.string.app_spoofing_disabled),
                checked = spoofEnabled,
                onCheckedChange = { newValue ->
                    setMasterEnabled(newValue)
                },
            ) {
                Icon(
                    Icons.Default.Apps,
                    contentDescription = null,
                    tint = if (spoofEnabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(SettingsSpace.small1))

            ExpressiveAnimatedVisibility(visible = spoofEnabled) {
                Column {
                    ExpressivePrimaryAction(
                        text = stringResource(R.string.app_spoofing_add_apps),
                        icon = Icons.Default.Add,
                        onClick = { showAddDialog = true },
                        inset = false,
                    )
                    if (configuredMap.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))
                        ExpressiveActionRow(
                            actions = listOf(
                                ExpressiveAction(
                                    text = stringResource(R.string.app_spoofing_clear_all),
                                    icon = Icons.Default.Delete,
                                    onClick = { showClearAllConfirm = true },
                                ),
                            ),
                            inset = false,
                        )
                    }

                    Spacer(modifier = Modifier.height(SettingsSpace.small1))

                    if (configuredMap.isNotEmpty()) {
                        CategoryTitle(stringResource(R.string.app_spoofing_configured_apps))

                        val configuredApps = configuredMap.entries.toList().mapNotNull { (pkg, profile) ->
                            allApps.find { it.packageName == pkg }?.let { Triple(pkg, profile, it) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1)) {
                            configuredApps.forEachIndexed { index, (pkg, profile, app) ->
                                val customProfile = customProfiles.find { it.id == profile }
                                val displayLabel = customProfile?.name ?: profileLabelMap[profile] ?: profile

                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                                            expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                                           shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec())
                                ) {
                                    AppConfigCard(
                                        app = app,
                                        profile = profile,
                                        profileLabel = displayLabel,
                                        shapes = ListItemDefaults.segmentedShapes(
                                            index = index,
                                            count = configuredApps.size,
                                        ),
                                        onRemove = {
                                            configuredMap = LinkedHashMap(configuredMap).apply { remove(pkg) }
                                            persistConfigured()
                                            stopPackage(pkg)
                                        },
                                        onEdit = {
                                            editTarget = app
                                            showModelDialog = true
                                        },
                                        onLongPress = {
                                            editTarget = app
                                            showModelDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        ExpressiveEmptyState(
                            icon = Icons.Default.Apps,
                            title = stringResource(R.string.app_spoofing_no_apps_configured),
                            description = stringResource(R.string.app_spoofing_empty_description),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppConfigCard(
    app: AppItem,
    profile: String,
    profileLabel: String,
    shapes: ListItemShapes,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    if (showRemoveConfirm) {
        ExpressiveConfirmDialog(
            title = stringResource(R.string.app_spoofing_remove_title),
            message = stringResource(R.string.app_spoofing_remove_confirm, app.label),
            confirmText = stringResource(R.string.remove),
            destructive = true,
            onConfirm = onRemove,
            onDismiss = { showRemoveConfirm = false },
            dismissText = stringResource(R.string.cancel),
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
            .insetFocusRing(shapes.shape)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = onLongPress
            ),
        shape = shapes.shape,
        color = MaterialTheme.colorScheme.surfaceBright
    ) {
        Column(modifier = Modifier.padding(SettingsSpace.small1)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconOrPlaceholder(
                    packageName = app.packageName,
                    icon = app.icon,
                    sizeDp = APP_ICON_SIZE_CARD,
                )
                Spacer(modifier = Modifier.width(SettingsSpace.extraSmall6))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMediumEmphasized,
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
                ExpressiveStatusChip(text = profileLabel)
                Spacer(modifier = Modifier.width(SettingsSpace.extraSmall4))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(SettingsSpace.extraSmall5))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SettingsShape.CornerLarge1,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(SettingsSpace.extraSmall6)) {
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
                Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))
                Text(
                    text = stringResource(R.string.app_spoofing_longpress_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall6))
            ExpressiveActionRow(
                actions = listOf(
                    ExpressiveAction(
                        text = stringResource(R.string.edit),
                        icon = Icons.Default.Edit,
                        onClick = onEdit,
                    ),
                    ExpressiveAction(
                        text = stringResource(R.string.remove),
                        icon = Icons.Default.Delete,
                        onClick = { showRemoveConfirm = true },
                    ),
                ),
                inset = false,
            )
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
    customProfiles: List<CustomSpoofProfile>,
    onDismiss: () -> Unit,
    onAppAdded: (AppItem, String) -> Unit,
    onWriteCustomProfiles: (List<CustomSpoofProfile>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }
    var showProfileSelector by remember { mutableStateOf(false) }
    var showAddCustomProfileDialog by remember { mutableStateOf(false) }
    var customProfileToEdit by remember { mutableStateOf<CustomSpoofProfile?>(null) }

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

    val currentCtx = androidx.compose.ui.platform.LocalContext.current
    var customProfilesList by remember(customProfiles) { mutableStateOf(customProfiles) }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showProfileSelector) {
                    ExpressiveTextButton(onClick = { showProfileSelector = false }) {
                        Text(stringResource(R.string.app_spoofing_change_app))
                    }
                } else {
                    Text(stringResource(R.string.app_spoofing_add_apps))
                    Box {
                        IconButton(shapes = IconButtonDefaults.shapes(), modifier = Modifier.iconButtonFocusRing(), onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenuPopup(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuGroup(shapes = expressiveMenuGroupShapes()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (showSystemApps) stringResource(R.string.hide_system_apps)
                                            else stringResource(R.string.show_system_apps)
                                        )
                                    },
                                    shape = MenuDefaults.itemShape(index = 0, count = 1).shape,
                                    onClick = {
                                        showSystemApps = !showSystemApps
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp),
                verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall5)
            ) {
                if (!showProfileSelector) {
                    AppPickerSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                    )

                    if (filteredApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4)
                            ) {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (searchQuery.isBlank())
                                        stringResource(R.string.app_spoofing_no_apps_available)
                                    else
                                        stringResource(R.string.app_spoofing_no_apps_found, searchQuery),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (searchQuery.isNotBlank()) {
                                    ExpressiveTextButton(onClick = { searchQuery = "" }) {
                                        Text(stringResource(R.string.common_clear_search))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .expressiveVerticalScroll()
                        ) {
                            filteredApps.forEachIndexed { index, app ->
                                val isSelected = selectedApp?.packageName == app.packageName
                                val rowShapes = expressiveRowShapes(index = index, count = filteredApps.size)
                                SegmentedListItem(
                                    selected = isSelected,
                                    onClick = { selectedApp = app },
                                    shapes = rowShapes,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            bottom = if (index < filteredApps.lastIndex) {
                                                SettingsSpace.extraSmall1
                                            } else {
                                                0.dp
                                            },
                                        )
                                        .insetFocusRing(rowShapes.shape),
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    leadingContent = {
                                        AppIconOrPlaceholder(
                                            packageName = app.packageName,
                                            icon = app.icon,
                                            sizeDp = APP_ICON_SIZE_LIST,
                                        )
                                    },
                                    trailingContent = if (isSelected) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    } else null,
                                    supportingContent = {
                                        Text(
                                            app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                ) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    if (selectedApp != null) {
                        val selectorShapes = ButtonDefaults.shapes()
                        FilledTonalButton(
                            onClick = { showProfileSelector = true },
                            shapes = selectorShapes,
                            modifier = Modifier
                                .fillMaxWidth()
                                .insetFocusRing(
                                    selectorShapes.shape,
                                    ringHeight = ButtonDefaults.MinHeight,
                                )
                        ) {
                            Text(
                                if (selectedProfile != null)
                                    stringResource(R.string.app_spoofing_spoof_model) + ": " +
                                        (profileLabelMap[selectedProfile] ?: selectedProfile ?: "")
                                else
                                    stringResource(R.string.app_spoofing_select_spoof_model)
                            )
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.app_spoofing_select_spoof_model),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .expressiveVerticalScroll()
                    ) {
                        ExpressiveGroup(title = "", horizontalInset = 0.dp) {
                            addableProfiles.forEach { profile ->
                                val customProfile = customProfilesList.find { it.id == profile }
                                item {
                                    ExpressiveChoiceRow(
                                        title = customProfile?.name ?: profileLabelMap[profile] ?: profile,
                                        summary = profile,
                                        selected = selectedProfile == profile,
                                        onClick = {
                                            selectedProfile = profile
                                            showProfileSelector = false
                                        },
                                    )
                                }
                            }
                            customProfilesList.forEach { profile ->
                                item {
                                    ExpressiveChoiceRow(
                                        title = profile.name,
                                        summary = profile.id,
                                        selected = selectedProfile == profile.id,
                                        onClick = {
                                            selectedProfile = profile.id
                                            showProfileSelector = false
                                        },
                                        trailing = {
                                            IconButton(shapes = IconButtonDefaults.shapes(), modifier = Modifier.iconButtonFocusRing(), onClick = {
                                                customProfileToEdit = profile
                                                showAddCustomProfileDialog = true
                                            }) {
                                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                            IconButton(shapes = IconButtonDefaults.shapes(), modifier = Modifier.iconButtonFocusRing(), onClick = {
                                                val newList = customProfilesList.filter { it.id != profile.id }
                                                onWriteCustomProfiles(newList)
                                                customProfilesList = newList
                                            }) {
                                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (showProfileSelector) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExpressiveTextButton(onClick = {
                        customProfileToEdit = null
                        showAddCustomProfileDialog = true
                    }) {
                        Text(stringResource(R.string.add_new_spoof_profile))
                    }
                    ExpressiveTextButton(onClick = { showProfileSelector = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ExpressiveTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(SettingsSpace.extraSmall4))
                    ExpressiveTextButton(
                        onClick = {
                            val app = selectedApp ?: return@ExpressiveTextButton
                            val profile = selectedProfile ?: "None"
                            onAppAdded(app, profile)
                        },
                        enabled = selectedApp != null
                    ) {
                        Text(stringResource(R.string.add))
                    }
                }
            }
        },
        dismissButton = null
    )

    if (showAddCustomProfileDialog) {
        val passedId = remember(customProfileToEdit) {
            customProfileToEdit?.id ?: ("CUSTOM_" + System.currentTimeMillis())
        }
        AddCustomProfileDialog(
            context = currentCtx,
            initialProfile = customProfileToEdit,
            onDismiss = {
                showAddCustomProfileDialog = false
                customProfileToEdit = null
            },
            onSave = { newProfile ->
                val updatedProfiles = if (customProfileToEdit != null) {
                    customProfilesList.map { if (it.id == newProfile.id) newProfile else it }
                } else {
                    customProfilesList + newProfile
                }
                onWriteCustomProfiles(updatedProfiles)
                customProfilesList = updatedProfiles
                showAddCustomProfileDialog = false
                customProfileToEdit = null
            },
            id = passedId
        )
    }
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

private fun readCustomProfiles(context: Context): List<CustomSpoofProfile> {
    val jsonStr = Settings.Secure.getString(context.contentResolver, CUSTOM_SPOOF_PROFILES_SETTING)
    if (jsonStr.isNullOrBlank()) return emptyList()
    return try {
        val jsonArray = JSONArray(jsonStr)
        val profiles = mutableListOf<CustomSpoofProfile>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            profiles.add(
                CustomSpoofProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    brand = obj.getString("brand"),
                    manufacturer = obj.getString("manufacturer"),
                    device = obj.getString("device"),
                    model = obj.getString("model"),
                    fingerprint = obj.optString("fingerprint", ""),
                    product = obj.optString("product", "")
                )
            )
        }
        profiles
    } catch (e: Exception) {
        emptyList()
    }
}

private fun writeCustomProfiles(context: Context, profiles: List<CustomSpoofProfile>) {
    val jsonArray = JSONArray()
    profiles.forEach { profile ->
        val obj = JSONObject().apply {
            put("id", profile.id)
            put("name", profile.name)
            put("brand", profile.brand)
            put("manufacturer", profile.manufacturer)
            put("device", profile.device)
            put("model", profile.model)
            put("fingerprint", profile.fingerprint)
            put("product", profile.product)
        }
        jsonArray.put(obj)
    }
    Settings.Secure.putString(context.contentResolver, CUSTOM_SPOOF_PROFILES_SETTING, jsonArray.toString())
}

@Composable
private fun AddCustomProfileDialog(
    context: Context,
    initialProfile: CustomSpoofProfile? = null,
    onDismiss: () -> Unit,
    onSave: (CustomSpoofProfile) -> Unit,
    id: String
) {
    var name by remember(initialProfile) { mutableStateOf(initialProfile?.name ?: "") }
    var brand by remember(initialProfile) { mutableStateOf(initialProfile?.brand ?: "") }
    var manufacturer by remember(initialProfile) { mutableStateOf(initialProfile?.manufacturer ?: "") }
    var device by remember(initialProfile) { mutableStateOf(initialProfile?.device ?: "") }
    var model by remember(initialProfile) { mutableStateOf(initialProfile?.model ?: "") }
    var fingerprint by remember(initialProfile) { mutableStateOf(initialProfile?.fingerprint ?: "") }
    var product by remember(initialProfile) { mutableStateOf(initialProfile?.product ?: "") }

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_custom_spoof_profile_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .expressiveVerticalScroll(),
                verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4)
            ) {
                ExpressiveTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.custom_spoof_profile_name),
                )
                ExpressiveTextField(
                    value = brand,
                    onValueChange = { brand = it },
                    label = stringResource(R.string.custom_spoof_profile_brand),
                )
                ExpressiveTextField(
                    value = manufacturer,
                    onValueChange = { manufacturer = it },
                    label = stringResource(R.string.custom_spoof_profile_manufacturer),
                )
                ExpressiveTextField(
                    value = device,
                    onValueChange = { device = it },
                    label = stringResource(R.string.custom_spoof_profile_device),
                )
                ExpressiveTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = stringResource(R.string.custom_spoof_profile_model),
                )
                ExpressiveTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = stringResource(R.string.custom_spoof_profile_fingerprint),
                )
                ExpressiveTextField(
                    value = product,
                    onValueChange = { product = it },
                    label = stringResource(R.string.custom_spoof_profile_product),
                )
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = {
                    onSave(CustomSpoofProfile(
                        id = id, name = name, brand = brand, manufacturer = manufacturer,
                        device = device, model = model, fingerprint = fingerprint, product = product
                    ))
                },
                enabled = name.isNotBlank() && brand.isNotBlank() && manufacturer.isNotBlank() &&
                          device.isNotBlank() && model.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
