/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TENSOR_TARGETS_KEY = "tensor_targets"

// TensorTargetEntry kept for any external references (e.g. tests), but the
// internal list now uses AppListEntry (isSelected = targeted).
data class TensorTargetEntry(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isSystem: Boolean,
    var targeted: Boolean = false,
)

class TensorTargetsSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.tensor_spoof_title)
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
                TensorTargetsContent(context = requireContext())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TensorTargetsContent(context: android.content.Context) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val scope = rememberCoroutineScope()

    var globalEnabled by remember {
        mutableStateOf(
            Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.PI_TENSOR_SPOOF, 0) == 1,
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<AppListEntry>() }

    val hiddenApps = remember {
        context.resources.getStringArray(R.array.tensor_targets_hidden_apps).toSet()
    }
    val defaultApps = remember {
        context.resources.getStringArray(R.array.tensor_targets_default_apps).toSet()
    }

    // -----------------------------------------------------------------------
    // Settings helpers
    // -----------------------------------------------------------------------

    fun readTargetsSet(): MutableSet<String> {
        val raw = Settings.Secure.getString(context.contentResolver, TENSOR_TARGETS_KEY)
        if (raw.isNullOrBlank()) return mutableSetOf()
        return raw.split(",").filter { it.isNotBlank() }.toMutableSet()
    }

    fun writeTargetsSet(set: Set<String>) {
        Settings.Secure.putString(
            context.contentResolver,
            TENSOR_TARGETS_KEY,
            set.joinToString(","),
        )
    }

    fun applyChange(packageName: String) {
        val current = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
        writeTargetsSet(current)
        stopPackage(activityManager, packageName)
    }

    // -----------------------------------------------------------------------
    // Load (re-runs when showSystemApps changes)
    // -----------------------------------------------------------------------

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            var targetsSet = readTargetsSet()
            if (targetsSet.isEmpty()) {
                targetsSet = defaultApps.toMutableSet()
                writeTargetsSet(targetsSet)
            }

            val installed = filterInstalledApps(
                pm = pm,
                showSystem = showSystemApps,
                targeted = targetsSet,
                hidden = hiddenApps,
                extraFilter = { app ->
                    !app.packageName.contains("android.settings")
                },
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

    val activeCount = allApps.count { it.isSelected }

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
                title = stringResource(R.string.tensor_spoof_enable),
                subtitle = when {
                    !globalEnabled -> stringResource(R.string.app_spoofing_disabled)
                    activeCount == 0 -> stringResource(R.string.tensor_targets_none_configured)
                    else -> stringResource(R.string.tensor_targets_count, activeCount)
                },
                checked = globalEnabled,
                onCheckedChange = { checked ->
                    globalEnabled = checked
                    Settings.Secure.putInt(
                        context.contentResolver,
                        Settings.Secure.PI_TENSOR_SPOOF,
                        if (checked) 1 else 0,
                    )
                    scope.launch(Dispatchers.IO) {
                        killPackages(activityManager, allApps.filter { it.isSelected }
                            .map { it.packageName }.toSet())
                    }
                },
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = if (globalEnabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SpoofingAnimatedVisibility(visible = globalEnabled) {
                Column {
                    AppPickerSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        enabled = globalEnabled,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = showSystemApps,
                            onClick = { if (globalEnabled) showSystemApps = !showSystemApps },
                            label = { Text(stringResource(R.string.show_system_apps)) },
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                filteredApps.forEach { app ->
                                    val i = allApps.indexOfFirst {
                                        it.packageName == app.packageName }
                                    if (i >= 0 && !allApps[i].isSelected) {
                                        allApps[i] = allApps[i].copy(isSelected = true)
                                        scope.launch(Dispatchers.IO) {
                                            applyChange(app.packageName) }
                                    }
                                }
                            },
                            enabled = globalEnabled,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_select_all),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                allApps.indices.forEach { i ->
                                    allApps[i] = allApps[i].copy(
                                        isSelected = defaultApps.contains(allApps[i].packageName),
                                    )
                                }
                                scope.launch(Dispatchers.IO) {
                                    writeTargetsSet(defaultApps)
                                }
                            },
                            enabled = globalEnabled,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_reset),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isLoading) {
                        SpoofingLoadingBox(modifier = Modifier.weight(1f))
                    } else if (filteredApps.isEmpty()) {
                        SpoofingEmptyState(
                            icon = Icons.Default.Memory,
                            title = stringResource(R.string.tensor_targets_none_configured),
                            description = stringResource(
                                R.string.tensor_targets_empty_description),
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
                                    checked = app.isSelected,
                                    enabled = globalEnabled,
                                    onToggle = { nowTargeted ->
                                        val index = allApps.indexOfFirst {
                                            it.packageName == app.packageName }
                                        if (index < 0) return@AppPickerItem
                                        allApps[index] = allApps[index].copy(isSelected = nowTargeted)
                                        scope.launch(Dispatchers.IO) {
                                            applyChange(app.packageName)
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
