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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val TENSOR_TARGETS_KEY = "tensor_targets"

data class TensorTargetEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var targeted: Boolean = false,
)

class TensorTargetsSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.tensor_spoof_title)
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Compose owns the entire view, no preferences XML needed
    }

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
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.PI_TENSOR_SPOOF, 0) == 1
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<TensorTargetEntry>() }

    val hiddenApps = remember {
        context.resources.getStringArray(R.array.tensor_targets_hidden_apps).toSet()
    }
    val defaultApps = remember {
        context.resources.getStringArray(R.array.tensor_targets_default_apps).toSet()
    }

    fun readTargetsSet(): MutableSet<String> {
        val raw = Settings.Secure.getString(context.contentResolver, TENSOR_TARGETS_KEY)
        if (raw.isNullOrBlank()) return mutableSetOf()
        return raw.split(",").filter { it.isNotBlank() }.toMutableSet()
    }

    fun writeTargetsSet(set: Set<String>) {
        Settings.Secure.putString(
            context.contentResolver,
            TENSOR_TARGETS_KEY,
            set.joinToString(",")
        )
    }

    fun applyChange(packageName: String, add: Boolean) {
        val current = allApps.filter { it.targeted }.map { it.packageName }.toMutableSet()
        writeTargetsSet(current)
        try { activityManager?.forceStopPackage(packageName) } catch (_: Exception) {}
    }

    fun killAllTargets() {
        val current = readTargetsSet()
        for (pkg in current) {
            try { activityManager?.forceStopPackage(pkg) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            var targetsSet = readTargetsSet()

            if (targetsSet.isEmpty()) {
                targetsSet = defaultApps.toMutableSet()
                writeTargetsSet(targetsSet)
            }

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    if (app.packageName in hiddenApps) return@filter false
                    if (app.packageName.contains("android.settings")) return@filter false
                    if (isSystem && !showSystemApps && app.packageName !in targetsSet) return@filter false
                    true
                }
                .sortedWith(compareBy(
                    { app -> app.packageName !in targetsSet },
                    { app -> pm.getApplicationLabel(app).toString().lowercase(Locale.getDefault()) }
                ))
                .map { app ->
                    TensorTargetEntry(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null },
                        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        targeted = app.packageName in targetsSet,
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

    val activeCount = allApps.count { it.targeted }

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
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            globalEnabled = !globalEnabled
                            Settings.Secure.putInt(
                                context.contentResolver,
                                Settings.Secure.PI_TENSOR_SPOOF,
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
                            Icons.Default.Memory,
                            contentDescription = null,
                            tint = if (globalEnabled) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tensor_spoof_enable),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (activeCount == 0)
                                stringResource(R.string.tensor_targets_none_configured)
                            else
                                stringResource(R.string.tensor_targets_count, activeCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = globalEnabled,
                        onCheckedChange = { checked ->
                            globalEnabled = checked
                            Settings.Secure.putInt(
                                context.contentResolver,
                                Settings.Secure.PI_TENSOR_SPOOF,
                                if (checked) 1 else 0,
                            )
                            scope.launch(Dispatchers.IO) { killAllTargets() }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(modifier = Modifier.alpha(if (globalEnabled) 1f else 0.38f)) {
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
                                val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (i >= 0 && !allApps[i].targeted) {
                                    allApps[i] = allApps[i].copy(targeted = true)
                                    scope.launch(Dispatchers.IO) { applyChange(app.packageName, true) }
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
                                allApps[i] = allApps[i].copy(targeted = defaultApps.contains(allApps[i].packageName))
                            }
                            scope.launch(Dispatchers.IO) { writeTargetsSet(defaultApps.toMutableSet()) }
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.tensor_targets_none_configured),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.tensor_targets_empty_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
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
                            checked = app.targeted,
                            enabled = globalEnabled,
                            onToggle = { nowTargeted ->
                                val index = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (index < 0) return@AppPickerItem
                                allApps[index] = allApps[index].copy(targeted = nowTargeted)
                                scope.launch(Dispatchers.IO) {
                                    applyChange(app.packageName, nowTargeted)
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
