/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class IdlePolicy {
    BALANCED, AGGRESSIVE, CUSTOM;

    val defaultMinutes: Int
        get() = when (this) {
            BALANCED -> 60
            AGGRESSIVE -> 15
            CUSTOM -> 30
        }

    companion object {
        fun fromString(v: String) = entries.firstOrNull {
            it.name == v 
            } ?: BALANCED
    }
}

internal enum class IdleAction {
    STANDBY_BUCKET_RARE,
    STANDBY_BUCKET_RESTRICTED,
    KILL_BACKGROUND,
    FULL_KILL;

    val icon: ImageVector
        get() = when (this) {
            STANDBY_BUCKET_RARE -> Icons.Default.Timer
            STANDBY_BUCKET_RESTRICTED -> Icons.Default.Block
            KILL_BACKGROUND -> Icons.Default.Stop
            FULL_KILL -> Icons.Default.FlashOn
        }

    companion object {
        fun fromString(v: String) = entries.firstOrNull {
            it.name == v 
            } ?: STANDBY_BUCKET_RARE
    }
}

private val CRITICAL_SYSTEM_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.android.providers.telephony",
    "com.android.server.telecom",
    "com.google.android.apps.messaging",
    "com.google.android.dialer",
    "com.whatsapp",
    "org.evolution.dolby"
)

private data class IdleAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean
)

private data class IdleAppConfig(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
    val policy: IdlePolicy,
    val customTimeoutMinutes: Int,
    val action: IdleAction
)

private data class EnforcementRecord(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val actionTaken: IdleAction,
    val lastKillMs: Long,
    val killCount: Int
)

private fun readEnabled(ctx: Context) =
    Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.IDLE_MANAGER, 1) == 1

private fun writeEnabled(ctx: Context, v: Boolean) =
    Settings.Secure.putInt(
        ctx.contentResolver,
        Settings.Secure.IDLE_MANAGER,
        if (v) 1 else 0
    )

private fun readAppConfigs(ctx: Context): LinkedHashMap<String, IdleAppConfig> {
    val result = linkedMapOf<String, IdleAppConfig>()
    val json = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.IDLE_MANAGER_APPS
    ) ?: return result
    if (json.isBlank()) return result
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val pkg = obj.getString("package")
            val pol = IdlePolicy.fromString(obj.optString("policy", "BALANCED"))
            val mins = obj.optInt("timeout_minutes", pol.defaultMinutes)
            val act = IdleAction.fromString(
                obj.optString("action", IdleAction.STANDBY_BUCKET_RARE.name)
            )
            result[pkg] = IdleAppConfig(
                packageName = pkg,
                label = pkg,
                icon = android.graphics.drawable.ColorDrawable(0),
                isSystem = false,
                policy = pol,
                customTimeoutMinutes = mins,
                action = act
            )
        }
    } catch (_: Exception) {}
    return result
}

private fun writeAppConfigs(ctx: Context, configs: Map<String, IdleAppConfig>) {
    val arr = JSONArray()
    configs.values.forEach { c ->
        arr.put(JSONObject().apply {
            put("package", c.packageName)
            put("policy", c.policy.name)
            put("timeout_minutes", c.customTimeoutMinutes)
            put("action", c.action.name)
        })
    }
    Settings.Secure.putString(
        ctx.contentResolver,
        Settings.Secure.IDLE_MANAGER_APPS,
        arr.toString()
    )
}

private fun readEnforcementRecords(
    ctx: Context,
    apps: List<IdleAppItem>
): List<EnforcementRecord> {
    val json = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.IDLE_MANAGER_KILL_STATS
    ) ?: return emptyList()
    if (json.isBlank()) return emptyList()
    val appMap = apps.associateBy { it.packageName }
    val records = mutableListOf<EnforcementRecord>()
    try {
        val root = JSONObject(json)
        root.keys().forEach { pkg ->
            val entry = root.optJSONObject(pkg) ?: return@forEach
            val app = appMap[pkg]
            records.add(
                EnforcementRecord(
                    packageName = pkg,
                    label = app?.label ?: pkg,
                    icon = app?.icon,
                    actionTaken = IdleAction.fromString(
                        entry.optString("last_action", IdleAction.STANDBY_BUCKET_RARE.name)
                    ),
                    lastKillMs = entry.optLong("last_kill", 0L),
                    killCount = entry.optInt("count", 0)
                )
            )
        }
    } catch (_: Exception) {}
    return records.sortedByDescending { it.lastKillMs }
}

private fun formatElapsed(ms: Long): String {
    if (ms == 0L) return "never"
    val elapsed = System.currentTimeMillis() - ms
    val mins = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hrs = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        hrs < 24 -> "${hrs}h ago"
        else -> "${days}d ago"
    }
}

class IdleManagerSettings : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.idle_manager_title)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ) = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(
            ViewCompositionStrategy
                .DisposeOnViewTreeLifecycleDestroyed
        )
        setContent {
            SettingsTheme {
                IdleManagerRoot(requireContext())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IdleManagerRoot(ctx: Context) {
    val pm = ctx.packageManager
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var allApps by remember {
        mutableStateOf(listOf<IdleAppItem>()) 
    }

    var configuredApps by remember {
        mutableStateOf(linkedMapOf<String, IdleAppConfig>()) 
    }

    var globalEnabled by remember {
        mutableStateOf(true) 
    }

    var records by remember {
        mutableStateOf(listOf<EnforcementRecord>()) 
    }

    var selectedTab by remember {
        mutableIntStateOf(0) 
    }

    var showAddDialog by remember {
        mutableStateOf(false) 
    }

    var showEditDialog by remember {
        mutableStateOf<IdleAppConfig?>(null) 
    }

    var showClearConfirm by remember {
        mutableStateOf(false) 
    }

    fun mergeWithAppInfo(
        raw: LinkedHashMap<String, IdleAppConfig>,
        apps: List<IdleAppItem>
    ): LinkedHashMap<String, IdleAppConfig> {
        val out = linkedMapOf<String, IdleAppConfig>()
        raw.forEach { (pkg, cfg) ->
            val app = apps.find { it.packageName == pkg } ?: return@forEach
            out[pkg] = cfg.copy(label = app.label, icon = app.icon, isSystem = app.isSystem)
        }
        return out
    }

    fun refreshRecords() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                readEnforcementRecords(ctx, allApps)
            }
            records = result
        }
    }

    fun loadAll() {
        scope.launch {
            val enabled = withContext(Dispatchers.IO) {
                readEnabled(ctx)
            }
            val raw = withContext(Dispatchers.IO) {
                readAppConfigs(ctx)
            }
            globalEnabled = enabled
            configuredApps = mergeWithAppInfo(raw, allApps)
            refreshRecords()
        }
    }

    fun persist(updated: LinkedHashMap<String, IdleAppConfig>) {
        configuredApps = updated
        scope.launch(Dispatchers.IO) {
            writeAppConfigs(ctx, updated)
        }
    }

    fun upsert(pkg: String, policy: IdlePolicy, mins: Int, action: IdleAction, app: IdleAppItem) {
        persist(linkedMapOf<String, IdleAppConfig>().apply {
            putAll(configuredApps)
            put(pkg, IdleAppConfig(pkg, app.label, app.icon, app.isSystem, policy, mins, action))
        })
    }

    fun remove(pkg: String) {
        persist(linkedMapOf<String, IdleAppConfig>().apply {
            putAll(configuredApps.filter { it.key != pkg })
        })
    }

    fun clearAll() = persist(linkedMapOf())

    fun clearStats() {
        scope.launch(Dispatchers.IO) {
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.IDLE_MANAGER_KILL_STATS,
                ""
            )
            withContext(Dispatchers.Main) {
                records = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            pm.getInstalledPackages(PackageManager.MATCH_ANY_USER)
                .mapNotNull { pkg ->
                    val ai = pkg.applicationInfo ?: return@mapNotNull null
                    IdleAppItem(
                        packageName = pkg.packageName,
                        label = ai.loadLabel(pm).toString(),
                        icon = ai.loadIcon(pm),
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                   || (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    )
                }
                .distinctBy {
                    it.packageName 
                }
                .sortedBy {
                    it.label.lowercase(Locale.getDefault()) 
                }
        }
        loadAll()
    }

    if (showAddDialog) {
        AddAppDialog(
            allApps = allApps,
            configuredPackages = configuredApps.keys,
            onDismiss = {
                showAddDialog = false 
            },
            onAppAdded = { app, policy, mins, action ->
                upsert(app.packageName, policy, mins, action, app)
                showAddDialog = false
            }
        )
    }

    showEditDialog?.let { target ->
        EditAppDialog(
            config = target,
            onDismiss = {
                showEditDialog = null 
            },
            onSave = { policy, mins, action ->
                upsert(
                    target.packageName, policy, mins, action,
                    IdleAppItem(target.packageName, target.label, target.icon, target.isSystem)
                )
                showEditDialog = null
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = {
                showClearConfirm = false 
            },
            icon  = { 
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) 
            },
            title = { 
                Text(stringResource(R.string.idle_manager_clear_all)) 
            },
            text  = { 
                Text(stringResource(R.string.idle_manager_clear_all_confirm)) 
            },
            confirmButton = {
                Button(
                    onClick = { clearAll(); showClearConfirm = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.idle_manager_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearConfirm = false 
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MasterToggleCard(
                enabled = globalEnabled,
                appCount = configuredApps.size,
                onToggle = { v ->
                    scope.launch {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress) 
                    }
                    globalEnabled = v
                    scope.launch(Dispatchers.IO) {
                        writeEnabled(ctx, v)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            AnimatedVisibility(visible = globalEnabled) {
                Column {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0 
                            },
                            text = {
                                Text(stringResource(R.string.idle_manager_tab_apps)) 
                            },
                            icon = {
                                Icon(Icons.Default.Apps, null) 
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                refreshRecords()
                            },
                            text = { 
                                Text(stringResource(R.string.idle_manager_tab_dashboard)) 
                            },
                            icon = {
                                Icon(Icons.Default.Dashboard, null) 
                            }
                        )
                    }

                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(200)) 
                        },
                        label = "tab_content"
                    ) { tab ->
                        LaunchedEffect(tab) {
                            if (tab == 1) {
                                refreshRecords()
                            }
                        }
                        when (tab) {
                            0 -> AppsTab(
                                configuredApps = configuredApps,
                                onAdd = { 
                                    showAddDialog = true 
                                },
                                onClearAll = {
                                    showClearConfirm = true 
                                },
                                onEdit = { 
                                    showEditDialog = it 
                                },
                                onRemove = { 
                                    remove(it) 
                                },
                                records = records
                            )
                            1 -> DashboardTab(
                                records = records,
                                onRefresh = { 
                                    refreshRecords()
                                },
                                onClearStats = {
                                    clearStats()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MasterToggleCard(
    enabled: Boolean,
    appCount: Int,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
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
                        Icons.Default.BatteryAlert, null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        stringResource(R.string.idle_manager_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (enabled)
                                    stringResource(R.string.idle_manager_app_count, appCount)
                                else
                                    stringResource(R.string.idle_manager_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                thumbContent = {
                    Crossfade(
                        targetState = enabled,
                        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
                        label = "sw"
                    ) { on ->
                        if (on) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                        else Icon(Icons.Rounded.Close, null, Modifier.size(16.dp))
                    }
                }
            )
        }
    }
}

@Composable
private fun AppsTab(
    configuredApps: LinkedHashMap<String, IdleAppConfig>,
    onAdd: () -> Unit,
    onClearAll: () -> Unit,
    onEdit: (IdleAppConfig) -> Unit,
    onRemove: (String) -> Unit,
    records: List<EnforcementRecord>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.idle_manager_add_apps),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.idle_manager_clear_all),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (configuredApps.isEmpty()) {
            EmptyAppsState()
        } else {
            Text(
                stringResource(R.string.idle_manager_configured_apps),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            configuredApps.values.forEach { cfg ->
                AppConfigCard(
                    config = cfg,
                    record = records.find {
                        it.packageName == cfg.packageName
                    },
                    onEdit = {
                        onEdit(cfg)
                    },
                    onRemove = {
                        onRemove(cfg.packageName)
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun DashboardTab(
    records: List<EnforcementRecord>,
    onRefresh: () -> Unit,
    onClearStats: () -> Unit
) {
    var showClearStatsConfirm by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(10_000L)
            onRefresh()
        }
    }

    if (showClearStatsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStatsConfirm = false },
            icon = {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.idle_manager_clear_stats_title))
            },
            text = {
                Text(stringResource(R.string.idle_manager_clear_stats_confirm))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearStats()
                        showClearStatsConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.idle_manager_clear_stats_title))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearStatsConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                label = stringResource(R.string.idle_manager_apps_acted_on),
                value = records.size.toString(),
                icon = Icons.Default.Apps,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = stringResource(R.string.idle_manager_total_actions),
                value = records.sumOf {
                    it.killCount
                }.toString(),
                icon = Icons.Default.FlashOn,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        if (records.isNotEmpty()) {
            val byAction = IdleAction.entries.mapNotNull { action ->
                val count = records.count { it.actionTaken == action }
                if (count > 0) action to count else null
            }
            if (byAction.isNotEmpty()) {
                Text(
                    stringResource(R.string.idle_manager_action_breakdown),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    byAction.forEach { (action, count) ->
                        ActionBadgeCard(action, count, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.idle_manager_recent_activity),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp)
            )
            if (records.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showClearStatsConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 6.dp
                    )
                ) {
                    Icon(
                        Icons.Default.Delete, null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.action_clear),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (records.isEmpty()) {
            EmptyDashboardState()
        } else {
            records.forEach { rec ->
                EnforcementRecordCard(record = rec)
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.idle_manager_refresh_dashboard))
        }

        Spacer(Modifier.height(85.dp))
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionBadgeCard(action: IdleAction, count: Int, modifier: Modifier = Modifier) {
    val color = actionColor(action)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(action.icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                actionDisplayName(action),
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EnforcementRecordCard(record: EnforcementRecord) {
    val color = actionColor(record.actionTaken)
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (record.icon != null) {
                val bmp = remember(record.packageName) {
                    record.icon.toBitmap(80, 80).asImageBitmap()
                }
                Image(bmp, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
            } else {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Apps, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    record.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    record.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.idle_manager_last_killed,
                        formatElapsed(record.lastKillMs)
                    ) + " · ×${record.killCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(color.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(record.actionTaken.icon, null,
                        tint = color,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        actionDisplayName(record.actionTaken),
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppConfigCard(
    config: IdleAppConfig,
    record: EnforcementRecord?,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    var showRemoveAlert by remember {
        mutableStateOf(false)
    }

    val iconBmp = remember(config.packageName) {
        config.icon.toBitmap(96, 96).asImageBitmap()
    }

    val isCritical = config.isSystem && CRITICAL_SYSTEM_PACKAGES.contains(config.packageName)
    val policyColor = policyColor(config.policy)
    val actionColor = actionColor(config.action)

    if (showRemoveAlert) {
        AlertDialog(
            onDismissRequest = { showRemoveAlert = false },
            icon  = {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text(stringResource(R.string.idle_manager_remove_title))
            },
            text  = {
                Text(stringResource(R.string.idle_manager_remove_confirm, config.label))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRemoveAlert = false
                        onRemove()
                    },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRemoveAlert = false
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
            .combinedClickable(onClick = {
                expanded = !expanded
            },
            onLongClick = onEdit
            ),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
            )
    ) {
        Column(
            Modifier.padding(16.dp)) {

            Row(
                Modifier.fillMaxWidth(), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Image(
                        iconBmp,
                        null,
                        Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                    )
                    if (config.isSystem) {
                        Badge(
                            Modifier.align(Alignment.BottomEnd),
                            containerColor = if (isCritical)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.tertiary
                        ) {
                            Text("SYS")
                        }
                    }
                    record?.let {
                        if (it.killCount > 0) {
                            Badge(
                                Modifier.align(Alignment.TopEnd),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(if (it.killCount > 99) "99+" else it.killCount.toString())
                            }
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        config.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        config.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    record?.let {
                        if (it.killCount > 0) {
                            Text(
                                stringResource(
                                    R.string.idle_manager_last_killed,
                                    formatElapsed(it.lastKillMs)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(policyColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            policyLabel(config.policy, config.customTimeoutMinutes),
                            style = MaterialTheme.typography.labelSmall,
                            color = policyColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(actionColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                config.action.icon, null,
                                tint = actionColor,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                actionDisplayName(config.action),
                                style = MaterialTheme.typography.labelSmall,
                                color = actionColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                if (isCritical) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.idle_manager_critical_system_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        DetailRow(
                            stringResource(R.string.idle_manager_policy_label),
                            policyLabel(config.policy, config.customTimeoutMinutes),
                            policyColor
                        )
                        if (config.policy == IdlePolicy.CUSTOM) {
                            DetailRow(
                                stringResource(R.string.idle_manager_timeout_label),
                                stringResource(
                                    R.string.idle_manager_timeout_minutes,
                                    config.customTimeoutMinutes
                                ),
                                policyColor
                            )
                        }
                        DetailRow(
                            stringResource(R.string.idle_manager_enforcement_action),
                            actionDisplayName(config.action),
                            actionColor
                        )
                        record?.let {
                            if (it.killCount > 0) {
                                DetailRow(
                                    stringResource(R.string.idle_manager_kill_count_label),
                                    it.killCount.toString(),
                                    MaterialTheme.colorScheme.secondary
                                )
                                DetailRow(
                                    stringResource(R.string.idle_manager_last_killed_label),
                                    formatElapsed(it.lastKillMs),
                                    MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.idle_manager_longpress_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        showRemoveAlert = true
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        Icons.Default.Delete, null, Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.remove))
                }
                FilledTonalButton(
                    onClick = onEdit, 
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Edit, null, Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.edit))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, color: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppDialog(
    allApps: List<IdleAppItem>,
    configuredPackages: Set<String>,
    onDismiss: () -> Unit,
    onAppAdded: (IdleAppItem, IdlePolicy, Int, IdleAction) -> Unit
) {
    var search by remember {
        mutableStateOf("")
    }
    var showSystem by remember {
        mutableStateOf(false)
    }
    var showMenu by remember {
        mutableStateOf(false)
    }
    var selectedApps by remember {
        mutableStateOf(setOf<IdleAppItem>())
    }
    var showPolicyStep by remember {
        mutableStateOf(false)
    }

    val filtered = allApps.filter { app ->
        if (configuredPackages.contains(app.packageName)) 
            return@filter false
        if (!showSystem && app.isSystem) 
            return@filter false
        if (search.isBlank()) 
            return@filter true
        app.label.contains(search, true) || app.packageName.contains(search, true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showPolicyStep) {
                    TextButton(onClick = {
                        showPolicyStep = false
                    }) {
                        Text(stringResource(R.string.idle_manager_back_to_apps))
                    }
                    Text(
                        stringResource(R.string.idle_manager_select_policy),
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Text(
                        stringResource(R.string.idle_manager_add_apps)
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, null)
                        }
                        DropdownMenu(showMenu, { showMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (showSystem) stringResource(R.string.hide_system_apps)
                                        else stringResource(R.string.show_system_apps)
                                    )
                                },
                                onClick = {
                                    showSystem = !showSystem
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().height(500.dp)) {
                if (!showPolicyStep) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.action_search_apps)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f)
                        .verticalScroll(rememberScrollState())) {
                        if (filtered.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (search.isBlank())
                                        stringResource(R.string.idle_manager_no_apps_available)
                                    else
                                        stringResource(
                                            R.string.idle_manager_no_apps_found, search),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                        .copy(alpha = 0.5f)
                                )
                            }
                        } else {
                            filtered.forEach { app ->
                                AppSelectRow(
                                    app = app,
                                    selected = selectedApps.any {
                                        it.packageName == app.packageName },
                                    onClick = {
                                        selectedApps =
                                            if (selectedApps.any {
                                                    it.packageName == app.packageName })
                                                selectedApps.filter {
                                                    it.packageName != app.packageName }.toSet()
                                            else
                                                selectedApps + app
                                    }
                                )
                            }
                        }
                    }
                    if (selectedApps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showPolicyStep = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    R.string.idle_manager_select_policy_count,
                                    selectedApps.size
                                )
                            )
                        }
                    }
                } else {
                    PolicyAndActionSelector(
                        modifier = Modifier.weight(1f),
                        onConfirm = { policy, mins, action ->
                            selectedApps.forEach { app ->
                                onAppAdded(app, policy, mins, action)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {
            if (!showPolicyStep) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
        dismissButton = null
    )
}

@Composable
private fun AppSelectRow(app: IdleAppItem, selected: Boolean, onClick: () -> Unit) {
    val isCritical = app.isSystem && CRITICAL_SYSTEM_PACKAGES.contains(app.packageName)
    val iconBmp = remember(app.packageName) {
        app.icon.toBitmap(80, 80).asImageBitmap()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) 
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else 
                    Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Image(iconBmp, null,
                Modifier.size(32.dp)
                    .clip(RoundedCornerShape(8.dp)))
            if (app.isSystem) {
                Badge(
                    Modifier.align(Alignment.BottomEnd),
                    containerColor = if (isCritical) 
                        MaterialTheme.colorScheme.error
                    else 
                        MaterialTheme.colorScheme.tertiary
                ) {}
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCritical) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Warning, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                Icons.Default.Check, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EditAppDialog(
    config: IdleAppConfig,
    onDismiss: () -> Unit,
    onSave: (IdlePolicy, Int, IdleAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                R.string.idle_manager_edit_policy, 
                config.label)
            )
        },
        text = {
            PolicyAndActionSelector(
                initialPolicy = config.policy,
                initialMinutes = config.customTimeoutMinutes,
                initialAction = config.action,
                onConfirm = {
                    policy, mins, action -> onSave(policy, mins, action) 
                }
            )
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PolicyAndActionSelector(
    modifier: Modifier = Modifier,
    initialPolicy: IdlePolicy = IdlePolicy.BALANCED,
    initialMinutes: Int = 60,
    initialAction: IdleAction = IdleAction.STANDBY_BUCKET_RARE,
    onConfirm: (IdlePolicy, Int, IdleAction) -> Unit
) {
    var selectedPolicy by 
    remember { 
        mutableStateOf(initialPolicy) 
    }
    var customMinutes  by 
    remember {
        mutableFloatStateOf(initialMinutes.toFloat().coerceIn(5f, 240f))
    }
    var selectedAction by 
    remember { 
        mutableStateOf(initialAction) 
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.idle_manager_idle_timeout),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdlePolicy.entries.forEach { pol ->
                FilterChip(
                    selected = selectedPolicy == pol,
                    onClick = {
                        selectedPolicy = pol 
                    },
                    label = {
                        Text(
                            policyDisplayName(pol),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    leadingIcon = if (selectedPolicy == pol) {
                        { 
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp)) 
                        }
                    } else null
                )
            }
        }

        AnimatedVisibility(
            visible = selectedPolicy == IdlePolicy.CUSTOM,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.idle_manager_timeout_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            formatMinutes(customMinutes.toInt()),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Slider(
                    value = customMinutes,
                    onValueChange = { customMinutes = it },
                    valueRange = 5f..240f,
                    steps = 46,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "5 min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "4 hours",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = when (selectedPolicy) {
                    IdlePolicy.BALANCED   ->
                        stringResource(R.string.idle_manager_policy_balanced_desc)
                    IdlePolicy.AGGRESSIVE ->
                        stringResource(R.string.idle_manager_policy_aggressive_desc)
                    IdlePolicy.CUSTOM     ->
                        stringResource(
                            R.string.idle_manager_policy_custom_desc_dynamic,
                            customMinutes.toInt()
                        )
                },
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            stringResource(R.string.idle_manager_enforcement_action),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        IdleAction.entries.forEach { action ->
            ActionOptionCard(
                action = action,
                selected = selectedAction == action,
                onClick = {
                    selectedAction = action 
                }
            )
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                val mins = when (selectedPolicy) {
                    IdlePolicy.BALANCED -> 60
                    IdlePolicy.AGGRESSIVE -> 15
                    IdlePolicy.CUSTOM -> customMinutes.toInt()
                }
                onConfirm(selectedPolicy, mins, selectedAction)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_apply))
        }
    }
}

@Composable
private fun ActionOptionCard(action: IdleAction, selected: Boolean, onClick: () -> Unit) {
    val color = actionColor(action)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) 
                    color.copy(alpha = 0.12f)
                else 
                    MaterialTheme.colorScheme.surfaceBright,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(
                        alpha = if (selected) 0.2f else 0.08f
                    )),
                contentAlignment = Alignment.Center
            ) {
                Icon(action.icon, null,
                    tint = color, 
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    actionDisplayName(action),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) color else Color.Unspecified
                )
                Text(
                    actionDescription(action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyAppsState() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.PowerSettingsNew, null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.idle_manager_no_apps_configured),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.idle_manager_no_apps_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EmptyDashboardState() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Shield, null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.idle_manager_no_activity),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.idle_manager_no_activity_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun policyColor(policy: IdlePolicy): Color = when (policy) {
    IdlePolicy.BALANCED -> MaterialTheme.colorScheme.tertiary
    IdlePolicy.AGGRESSIVE -> MaterialTheme.colorScheme.error
    IdlePolicy.CUSTOM -> MaterialTheme.colorScheme.primary
}

@Composable
private fun actionColor(action: IdleAction): Color = when (action) {
    IdleAction.STANDBY_BUCKET_RARE -> MaterialTheme.colorScheme.tertiary
    IdleAction.STANDBY_BUCKET_RESTRICTED -> MaterialTheme.colorScheme.secondary
    IdleAction.KILL_BACKGROUND -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
    IdleAction.FULL_KILL -> MaterialTheme.colorScheme.error
}

@Composable
private fun policyDisplayName(policy: IdlePolicy): String = when (policy) {
    IdlePolicy.BALANCED -> stringResource(R.string.idle_manager_policy_balanced)
    IdlePolicy.AGGRESSIVE -> stringResource(R.string.idle_manager_policy_aggressive)
    IdlePolicy.CUSTOM -> stringResource(R.string.idle_manager_policy_custom)
}

@Composable
private fun actionDisplayName(action: IdleAction): String = when (action) {
    IdleAction.STANDBY_BUCKET_RARE -> stringResource(R.string.idle_action_rare_bucket)
    IdleAction.STANDBY_BUCKET_RESTRICTED -> stringResource(R.string.idle_action_restricted)
    IdleAction.KILL_BACKGROUND -> stringResource(R.string.idle_action_kill_bg)
    IdleAction.FULL_KILL -> stringResource(R.string.idle_action_full_kill)
}

@Composable
private fun actionDescription(action: IdleAction): String = when (action) {
    IdleAction.STANDBY_BUCKET_RARE -> stringResource(R.string.idle_action_rare_bucket_desc)
    IdleAction.STANDBY_BUCKET_RESTRICTED -> stringResource(R.string.idle_action_restricted_desc)
    IdleAction.KILL_BACKGROUND -> stringResource(R.string.idle_action_kill_bg_desc)
    IdleAction.FULL_KILL -> stringResource(R.string.idle_action_full_kill_desc)
}

private fun policyLabel(policy: IdlePolicy, customMinutes: Int): String = when (policy) {
    IdlePolicy.BALANCED -> "60 min"
    IdlePolicy.AGGRESSIVE -> "15 min"
    IdlePolicy.CUSTOM -> "$customMinutes min"
}

private fun formatMinutes(mins: Int): String = when {
    mins < 60 -> "$mins min"
    mins == 60 -> "1 hour"
    mins % 60 == 0 -> "${mins / 60} hours"
    else -> "${mins / 60}h ${mins % 60}m"
}
