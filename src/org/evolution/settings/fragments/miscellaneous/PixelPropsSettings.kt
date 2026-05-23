/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val PP_TARGETS_KEY = "pi_pp_targets"
private const val PP_MODEL_KEY   = "pi_pp_model"
private const val PP_ENABLED_KEY = "pi_pp_spoof"

private val DEFAULT_PP_TARGETS = setOf(
    "com.amazon.avod.thirdpartyclient",
    "com.android.chrome",
    "com.breel.wallpapers20",
    "com.disney.disneyplus",
    "com.google.android.aicore",
    "com.google.android.apps.accessibility.magnifier",
    "com.google.android.apps.aiwallpapers",
    "com.google.android.apps.bard",
    "com.google.android.apps.customization.pixel",
    "com.google.android.apps.emojiwallpaper",
    "com.google.android.apps.pixel.agent",
    "com.google.android.apps.pixel.creativeassistant",
    "com.google.android.apps.pixel.nowplaying",
    "com.google.android.apps.pixel.psi",
    "com.google.android.apps.pixel.subzero",
    "com.google.android.apps.pixel.support",
    "com.google.android.apps.privacy.wildlife",
    "com.google.android.apps.subscriptions.red",
    "com.google.android.apps.wallpaper",
    "com.google.android.apps.wallpaper.pixel",
    "com.google.android.apps.weather",
    "com.google.android.googlequicksearchbox",
    "com.google.android.pcs",
    "com.google.android.wallpaper.effects",
    "com.google.pixel.livewallpaper",
    "com.microsoft.android.smsorganizer",
    "com.nhs.online.nhsonline",
    "com.nothing.smartcenter",
    "com.realme.link",
    "in.startv.hotstar",
    "jp.id_credit_sp2.android",
)

private data class PpAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var isTargeted: Boolean,
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
            SettingsTheme {
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
    
    val isMainlineDevice = remember { PixelPropsUtils.isMainlinePixelDevice() }
    if (isMainlineDevice) {
        Box(
            modifier         = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape  = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Column(
                    modifier            = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Android,
                        contentDescription = null,
                        modifier           = Modifier.size(40.dp),
                        tint               = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text       = stringResource(R.string.spoofing_pixel_props_title),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text      = stringResource(R.string.pp_mainline_unsupported),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        return
    }

    var globalEnabled by remember {
        mutableStateOf(
            Settings.Secure.getInt(context.contentResolver, PP_ENABLED_KEY, 1) == 1
        )
    }

    var profiles by remember {
        mutableStateOf(PixelDeviceRepository.readCache(context))
    }
    var selectedCodename by remember {
        mutableStateOf(
            Settings.Secure.getString(context.contentResolver, PP_MODEL_KEY)
                ?: if (isTablet) "tangorpro" else "mustang"
        )
    }
    var showModelDropdown by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<PpAppEntry>() }

    fun readTargetsSet(): Set<String> {
        val raw = Settings.Secure.getString(context.contentResolver, PP_TARGETS_KEY)
        if (raw.isNullOrBlank()) return DEFAULT_PP_TARGETS
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun writeTargetsSet(targets: Set<String>) {
        Settings.Secure.putString(
            context.contentResolver,
            PP_TARGETS_KEY,
            targets.joinToString(","),
        )
    }

    fun stopPackage(pkg: String) {
        try { activityManager?.forceStopPackage(pkg) } catch (_: Exception) {}
    }

    fun killTargets(targets: Set<String>) {
        targets.forEach { stopPackage(it) }
    }

    fun killAllTargets() {
        val current = readTargetsSet()
        current.forEach { stopPackage(it) }
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val fresh = PixelDeviceRepository.getProfiles(context)
            withContext(Dispatchers.Main) {
                profiles = fresh
                if (fresh.none { it.codename == selectedCodename }) {
                    val defaultCodename = if (isTablet) "tangorpro" else "mustang"
                    selectedCodename = defaultCodename
                    Settings.Secure.putString(
                        context.contentResolver, PP_MODEL_KEY, defaultCodename)
                }
            }
        }
    }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val targetsSet = readTargetsSet().toMutableSet()
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    if (isSystem && !showSystemApps && app.packageName !in targetsSet)
                        return@filter false
                    true
                }
                .sortedWith(compareBy(
                    { app -> app.packageName !in targetsSet },
                    { app -> pm.getApplicationLabel(app).toString()
                        .lowercase(Locale.getDefault()) }
                ))
                .map { app ->
                    PpAppEntry(
                        packageName = app.packageName,
                        label       = pm.getApplicationLabel(app).toString(),
                        icon        = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                        isSystem    = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        isTargeted  = app.packageName in targetsSet,
                    )
                }

            val installedPkgs = installed.map { it.packageName }.toSet()
            val pruned = targetsSet.filter { it in installedPkgs }.toSet()
            if (pruned.size < targetsSet.size) {
                writeTargetsSet(pruned)
            }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    val appSnapshot = allApps.toList()
    val filteredApps = remember(searchQuery, appSnapshot) {
        val q = searchQuery.lowercase()
        appSnapshot.filter { app ->
            q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
        }
    }

    val activeCount = allApps.count { it.isTargeted }
    val selectedProfile = profiles.find { it.codename == selectedCodename }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(24.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            globalEnabled = !globalEnabled
                            Settings.Secure.putInt(
                                context.contentResolver, PP_ENABLED_KEY,
                                if (globalEnabled) 1 else 0,
                            )
                            scope.launch(Dispatchers.IO) { killAllTargets() }
                        }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (globalEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Android,
                            contentDescription = null,
                            tint = if (globalEnabled) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text       = stringResource(R.string.spoofing_pixel_props_title),
                            style      = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text  = if (activeCount == 0)
                                stringResource(R.string.ts_no_targets)
                            else
                                stringResource(R.string.pp_target_count, activeCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked          = globalEnabled,
                        onCheckedChange  = { checked ->
                            globalEnabled = checked
                            Settings.Secure.putInt(
                                context.contentResolver, PP_ENABLED_KEY,
                                if (checked) 1 else 0,
                            )
                            scope.launch(Dispatchers.IO) { killAllTargets() }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            AnimatedVisibility(
                visible = globalEnabled,
                enter   = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                          expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit    = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                          shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                Column {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text  = stringResource(R.string.pp_spoof_as),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box {
                                OutlinedButton(
                                    onClick  = { showModelDropdown = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape    = RoundedCornerShape(10.dp),
                                ) {
                                    Text(
                                        text = selectedProfile?.model
                                            ?: PixelDeviceRepository.DEVICE_MODEL_MAP[selectedCodename]
                                            ?: selectedCodename,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                DropdownMenu(
                                    expanded         = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false },
                                    modifier         = Modifier.heightIn(max = 320.dp),
                                ) {
                                    profiles.forEach { profile ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text     = profile.model,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    if (profile.codename == selectedCodename) {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint     = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                selectedCodename = profile.codename
                                                Settings.Secure.putString(
                                                    context.contentResolver,
                                                    PP_MODEL_KEY,
                                                    profile.codename,
                                                )
                                                showModelDropdown = false
                                                scope.launch(Dispatchers.IO) { killAllTargets() }
                                            },
                                        )
                                    }
                                }
                            }
                            if (selectedProfile != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text  = selectedProfile.fingerprint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    AppPickerSearchField(
                        query         = searchQuery,
                        onQueryChange = { searchQuery = it },
                        enabled       = globalEnabled,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = showSystemApps,
                            onClick  = { showSystemApps = !showSystemApps },
                            label    = { Text(stringResource(R.string.show_system_apps)) },
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
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick  = {
                                filteredApps.forEach { f ->
                                    val i = allApps.indexOfFirst { it.packageName == f.packageName }
                                    if (i >= 0 && !allApps[i].isTargeted) {
                                        allApps[i] = allApps[i].copy(isTargeted = true)
                                    }
                                }
                                scope.launch(Dispatchers.IO) {
                                    val current = allApps.filter { it.isTargeted }
                                        .map { it.packageName }.toSet()
                                    writeTargetsSet(current)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_select_all),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }

                        OutlinedButton(
                            onClick  = {
                                allApps.indices.forEach { i ->
                                    allApps[i] = allApps[i].copy(
                                        isTargeted = allApps[i].packageName in DEFAULT_PP_TARGETS
                                    )
                                }
                                scope.launch(Dispatchers.IO) {
                                    writeTargetsSet(DEFAULT_PP_TARGETS)
                                    killTargets(DEFAULT_PP_TARGETS)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_reset),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoading) {
                        Box(
                            modifier         = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator()
                        }
                    } else if (filteredApps.isEmpty()) {
                        Box(
                            modifier         = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text  = stringResource(R.string.ts_no_targets),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text      = stringResource(R.string.pp_empty_description),
                                    style     = MaterialTheme.typography.bodySmall,
                                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier            = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                AppPickerItem(
                                    packageName = app.packageName,
                                    label       = app.label,
                                    icon        = app.icon,
                                    isSystem    = app.isSystem,
                                    checked     = app.isTargeted,
                                    onToggle    = { nowTargeted ->
                                        if (isLoading) return@AppPickerItem
                                        val index = allApps.indexOfFirst {
                                            it.packageName == app.packageName
                                        }
                                        if (index < 0) return@AppPickerItem
                                        allApps[index] = allApps[index].copy(isTargeted = nowTargeted)
                                        val snapshot = allApps
                                            .filter { it.isTargeted }
                                            .map { it.packageName }
                                            .toSet()
                                        scope.launch(Dispatchers.IO) {
                                            writeTargetsSet(snapshot)
                                            stopPackage(app.packageName)
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
