/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import com.android.settingslib.spa.framework.theme.SettingsShape
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.evolution.PixelDeviceRepository
import com.android.internal.util.evolution.PixelPropsUtils
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settings.SettingsPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PP_TARGETS_KEY = "pi_pp_targets"
private const val PP_MODEL_KEY   = "pi_pp_model"
private const val PP_ENABLED_KEY = "pi_pp_spoof"

// DEFAULT_PP_TARGETS now lives in PixelDeviceRepository.DEFAULT_PP_TARGETS (Java Set<String>),
// shared with PixelPropsUtils rather than kept as a separate Kotlin copy.

// PpAppEntry kept for external references; internally we use AppListEntry
data class PpAppEntry(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isSystem: Boolean,
    var isTargeted: Boolean = false,
)

class PixelPropsSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.spoofing_pixel_props_title)
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SpoofingTheme {
                PixelPropsContent(context = requireContext())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PixelPropsContent(context: android.content.Context) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val scope = rememberCoroutineScope()

    val isTablet = remember {
        context.resources.configuration.smallestScreenWidthDp >= 600
    }

    // Mainline device gate — show unsupported card and bail
    val isMainlineDevice = remember { PixelPropsUtils.isMainlinePixelDevice() }
    if (isMainlineDevice) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(SettingsSpace.small1),
            contentAlignment = Alignment.Center,
        ) {
            SpoofingEmptyState(
                icon = Icons.Default.Android,
                title = stringResource(R.string.spoofing_pixel_props_title),
                description = stringResource(R.string.pp_mainline_unsupported),
            )
        }
        return
    }

    var globalEnabled by remember {
        mutableStateOf(
            Settings.Secure.getInt(context.contentResolver, PP_ENABLED_KEY, 1) == 1,
        )
    }
    var profiles by remember {
        mutableStateOf(PixelDeviceRepository.FALLBACK_PROFILES)
    }
    var selectedCodename by remember {
        mutableStateOf(
            Settings.Secure.getString(context.contentResolver, PP_MODEL_KEY)
                ?: if (isTablet) "tangorpro" else PixelDeviceRepository.getDefaultPhoneCodename(),
        )
    }
    var showModelDropdown by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<AppListEntry>() }

    fun readTargetsSet(): Set<String> {
        val raw = Settings.Secure.getString(context.contentResolver, PP_TARGETS_KEY)
        if (raw.isNullOrBlank()) return PixelDeviceRepository.DEFAULT_PP_TARGETS
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun writeTargetsSet(targets: Set<String>) {
        Settings.Secure.putString(
            context.contentResolver,
            PP_TARGETS_KEY,
            targets.joinToString(","),
        )
    }

    // Fetch profiles in the background — stable-only, since prop spoofing
    // should never present as a canary/beta build.
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val fresh = PixelDeviceRepository.getStableProfiles(context)
            val resolved = fresh.ifEmpty { PixelDeviceRepository.FALLBACK_PROFILES }
            withContext(Dispatchers.Main) {
                profiles = resolved
                if (resolved.none { it.codename == selectedCodename }) {
                    val defaultCodename = if (isTablet) "tangorpro"
                        else PixelDeviceRepository.getDefaultPhoneCodename(resolved)
                    selectedCodename = defaultCodename
                    Settings.Secure.putString(
                        context.contentResolver, PP_MODEL_KEY, defaultCodename)
                }
            }
        }
    }

    // Load apps (re-runs when showSystemApps changes)
    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val targetsSet = readTargetsSet().toMutableSet()
            val installed = filterInstalledApps(
                pm = pm,
                showSystem = showSystemApps,
                targeted = targetsSet,
            )
                .sortedWith(targetedFirstComparator(pm, targetsSet))
                .map { app ->
                    AppListEntry(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                        isSystem = app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
                        isSelected = app.packageName in targetsSet,
                    )
                }

            // Prune stale package names
            val installedPkgs = installed.map { it.packageName }.toSet()
            val pruned = targetsSet.filter { it in installedPkgs }.toSet()
            if (pruned.size < targetsSet.size) writeTargetsSet(pruned)

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, allApps.toList()) {
        val q = searchQuery.lowercase()
        allApps.filter { app ->
            q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
        }
    }

    val displayProfiles = remember(profiles) { profiles }

    val activeCount = allApps.count { it.isSelected }
    val selectedProfile = profiles.find { it.codename == selectedCodename }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = SettingsSpace.small1),
        ) {
            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            SpoofingHeaderCard(
                title = stringResource(R.string.spoofing_pixel_props_title),
                subtitle = when {
                    !globalEnabled -> stringResource(R.string.app_spoofing_disabled)
                    activeCount == 0 -> stringResource(R.string.ts_no_targets)
                    else -> stringResource(R.string.pp_target_count, activeCount)
                },
                checked = globalEnabled,
                onCheckedChange = { checked ->
                    globalEnabled = checked
                    Settings.Secure.putInt(
                        context.contentResolver, PP_ENABLED_KEY, if (checked) 1 else 0)
                    scope.launch(Dispatchers.IO) {
                        killPackages(activityManager, allApps.filter { it.isSelected }
                            .map { it.packageName }.toSet())
                    }
                },
            ) {
                Icon(
                    Icons.Default.Android,
                    contentDescription = null,
                    tint = if (globalEnabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall6))

            SpoofingAnimatedVisibility(visible = globalEnabled) {
                Column {
                    // Model selector
                    SpoofingGroup(
                        title = "",
                        horizontalInset = 0.dp,
                    ) {
                        item {
                            Box {
                                SpoofingRow(
                                    title = selectedProfile?.model
                                        ?: PixelDeviceRepository.DEVICE_MODEL_MAP[selectedCodename]
                                        ?: selectedCodename,
                                    summary = selectedProfile?.fingerprint,
                                    onClick = { showModelDropdown = true },
                                    trailing = {
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                                DropdownMenuPopup(
                                    expanded = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false },
                                ) {
                                    DropdownMenuGroup(
                                        shapes = spoofingMenuGroupShapes(),
                                        modifier = Modifier
                                            .heightIn(max = 320.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        displayProfiles.forEachIndexed { index, profile ->
                                            val isSelected = profile.codename == selectedCodename
                                            DropdownMenuItem(
                                                selected = isSelected,
                                                text = { Text(text = profile.model) },
                                                shapes = MenuDefaults.itemShape(
                                                    index = index,
                                                    count = displayProfiles.size,
                                                ),
                                                trailingIcon = if (isSelected) {
                                                    {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                } else null,
                                                onClick = {
                                                    selectedCodename = profile.codename
                                                    Settings.Secure.putString(
                                                        context.contentResolver,
                                                        PP_MODEL_KEY,
                                                        profile.codename,
                                                    )
                                                    showModelDropdown = false
                                                    scope.launch(Dispatchers.IO) {
                                                        killPackages(
                                                            activityManager,
                                                            allApps.filter { it.isSelected }
                                                                .map { it.packageName }.toSet(),
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(SettingsSpace.extraSmall6))

                    AppPickerSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        enabled = globalEnabled,
                    )

                    Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

                    AppPickerActions(
                        showSystemApps = showSystemApps,
                        onToggleSystemApps = { showSystemApps = !showSystemApps },
                        onSelectAll = {
                            filteredApps.forEach { f ->
                                val i = allApps.indexOfFirst { it.packageName == f.packageName }
                                if (i >= 0 && !allApps[i].isSelected)
                                    allApps[i] = allApps[i].copy(isSelected = true)
                            }
                            scope.launch(Dispatchers.IO) {
                                val current = allApps.filter { it.isSelected }
                                    .map { it.packageName }.toSet()
                                writeTargetsSet(current)
                            }
                        },
                        onReset = {
                            allApps.indices.forEach { i ->
                                allApps[i] = allApps[i].copy(
                                    isSelected = allApps[i].packageName in PixelDeviceRepository.DEFAULT_PP_TARGETS,
                                )
                            }
                            scope.launch(Dispatchers.IO) {
                                Settings.Secure.putString(
                                    context.contentResolver,
                                    PixelDeviceRepository.STABLE_CACHE_KEY,
                                    "",
                                )
                                writeTargetsSet(PixelDeviceRepository.DEFAULT_PP_TARGETS)
                                killPackages(activityManager, PixelDeviceRepository.DEFAULT_PP_TARGETS)
                                val fresh = PixelDeviceRepository.getStableProfiles(context, true)
                                val resolved = fresh.ifEmpty { PixelDeviceRepository.FALLBACK_PROFILES }
                                val defaultCodename = if (isTablet) "tangorpro"
                                    else PixelDeviceRepository.getDefaultPhoneCodename(resolved)
                                Settings.Secure.putString(
                                    context.contentResolver,
                                    PP_MODEL_KEY,
                                    defaultCodename,
                                )
                                withContext(Dispatchers.Main) {
                                    profiles = resolved
                                    selectedCodename = defaultCodename
                                }
                            }
                        },
                    )

                    Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

                    AppListAnimatedContent(
                        isLoading = isLoading,
                        isEmpty = filteredApps.isEmpty(),
                        hasSearchQuery = searchQuery.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        emptyContent = {
                            SpoofingEmptyState(
                                icon = Icons.Default.Android,
                                title = stringResource(R.string.ts_no_targets),
                                description = stringResource(R.string.pp_empty_description),
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1),
                        ) {
                            itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { index, app ->
                                AppPickerItem(
                                    packageName = app.packageName,
                                    label = app.label,
                                    icon = app.icon,
                                    isSystem = app.isSystem,
                                    checked = app.isSelected,
                                    shapes = ListItemDefaults.segmentedShapes(index = index, count = filteredApps.size),
                                    onToggle = { nowTargeted ->
                                        if (isLoading) return@AppPickerItem
                                        val index = allApps.indexOfFirst {
                                            it.packageName == app.packageName }
                                        if (index < 0) return@AppPickerItem
                                        allApps[index] = allApps[index].copy(isSelected = nowTargeted)
                                        val snapshot = allApps.filter { it.isSelected }
                                            .map { it.packageName }.toSet()
                                        scope.launch(Dispatchers.IO) {
                                            writeTargetsSet(snapshot)
                                            stopPackage(activityManager, app.packageName)
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
    }
}
