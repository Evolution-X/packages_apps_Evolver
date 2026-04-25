/*
 * SPDX-FileCopyrightText: 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.UserManager
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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import com.android.internal.util.evolution.HideAppsUtils
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// ---------------------------------------------------------------------------
// Hide state per app: independently tracks applist hide + dev-status hide
// ---------------------------------------------------------------------------

/**
 * Bitmask-style enum representing which "hide" modes are active for an app.
 * Stored as a combination so both can be toggled independently.
 */
enum class HideMode {
    /** App is visible in both app list and developer status */
    NONE,
    /** App is hidden from the app list only */
    APP_LIST,
    /** App is hidden from developer status only */
    DEV_STATUS,
    /** App is hidden from both */
    BOTH;

    val hidesAppList: Boolean get() = this == APP_LIST || this == BOTH
    val hidesDevStatus: Boolean get() = this == DEV_STATUS || this == BOTH

    fun withAppList(enabled: Boolean): HideMode = when {
        enabled && hidesDevStatus -> BOTH
        enabled -> APP_LIST
        hidesDevStatus -> DEV_STATUS
        else -> NONE
    }

    fun withDevStatus(enabled: Boolean): HideMode = when {
        enabled && hidesAppList -> BOTH
        enabled -> DEV_STATUS
        hidesAppList -> APP_LIST
        else -> NONE
    }
}

data class HideAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var hideMode: HideMode = HideMode.NONE,
)

// ---------------------------------------------------------------------------
// Fragment
// ---------------------------------------------------------------------------

class HideAppsSettings : Fragment() {

    @SuppressLint("QueryPermissionsNeeded")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.hide_apps_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                HideAppsContent(
                    context = requireContext(),
                    userInfos = UserManager.get(requireContext()).users,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compose UI
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HideAppsContent(
    context: android.content.Context,
    userInfos: List<UserInfo>,
) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val hideAppsUtils = remember { HideAppsUtils() }
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<HideAppEntry>() }

    // Hidden system package name fragments – kept in sync with legacy XML arrays
    val hiddenApps = remember {
        context.resources.getStringArray(R.array.hide_apps_hidden_apps).toSet()
    }

    // ---- read helpers -------------------------------------------------------

    fun readAppListSet(): Set<String> {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.HIDE_APPLIST)
            ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun readDevStatusSet(): Set<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.HIDE_DEVELOPER_STATUS
        ) ?: return emptySet()
        return raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    // ---- write helpers (mirror legacy utils) --------------------------------

    fun applyAppListChange(packageName: String, add: Boolean) {
        for (info in userInfos) {
            if (add) hideAppsUtils.addApp(context, packageName, info.id, HideAppsUtils.Mode.APP_LIST)
            else hideAppsUtils.removeApp(context, packageName, info.id, HideAppsUtils.Mode.APP_LIST)
        }
        try { activityManager?.forceStopPackage(packageName) } catch (_: Exception) {}
    }

    fun applyDevStatusChange(packageName: String, add: Boolean) {
        for (info in userInfos) {
            if (add) hideAppsUtils.addApp(context, packageName, info.id, HideAppsUtils.Mode.DEV_STATUS)
            else hideAppsUtils.removeApp(context, packageName, info.id, HideAppsUtils.Mode.DEV_STATUS)
        }
        try { activityManager?.forceStopPackage(packageName) } catch (_: Exception) {}
    }

    // ---- load app list on first composition ---------------------------------

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val appListSet = readAppListSet()
            val devStatusSet = readDevStatusSet()

            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    if (app.packageName in hiddenApps) return@filter false
                    if (app.packageName.contains("android.settings")) return@filter false
                    if (isSystem && !showSystemApps &&
                        app.packageName !in appListSet &&
                        app.packageName !in devStatusSet
                    ) return@filter false
                    true
                }
                .sortedWith(compareBy(
                    // already-configured float to top
                    { app ->
                        app.packageName !in appListSet && app.packageName !in devStatusSet
                    },
                    { app -> pm.getApplicationLabel(app).toString().lowercase(Locale.getDefault()) }
                ))
                .map { app ->
                    val inAppList = app.packageName in appListSet
                    val inDevStatus = app.packageName in devStatusSet
                    val mode = when {
                        inAppList && inDevStatus -> HideMode.BOTH
                        inAppList -> HideMode.APP_LIST
                        inDevStatus -> HideMode.DEV_STATUS
                        else -> HideMode.NONE
                    }
                    HideAppEntry(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null },
                        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        hideMode = mode,
                    )
                }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    // Initialize utils per user
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            for (info in userInfos) {
                hideAppsUtils.setApps(context, info.id, HideAppsUtils.Mode.APP_LIST)
                hideAppsUtils.setApps(context, info.id, HideAppsUtils.Mode.DEV_STATUS)
            }
        }
    }

    // ---- filtered view ------------------------------------------------------

    val filteredApps = remember(searchQuery, allApps.toList()) {
        val q = searchQuery.lowercase()
        allApps.filter { app ->
            q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
        }
    }

    val activeCount = allApps.count { it.hideMode != HideMode.NONE }

    // ---- UI -----------------------------------------------------------------

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {

            // Header card
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
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.hide_apps_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (activeCount == 0)
                                stringResource(R.string.hide_apps_none_configured)
                            else
                                stringResource(R.string.hide_apps_count, activeCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.search_apps)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.hide_apps_clear_search),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Chips row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
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

                if (activeCount > 0) {
                    Text(
                        text = stringResource(R.string.hide_apps_count, activeCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // List
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        HideAppListItem(
                            app = app,
                            onModeChange = { newMode ->
                                val index = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (index < 0) return@HideAppListItem
                                val old = allApps[index]
                                allApps[index] = old.copy(hideMode = newMode)

                                // Apply changes
                                scope.launch(Dispatchers.IO) {
                                    if (newMode.hidesAppList != old.hideMode.hidesAppList) {
                                        applyAppListChange(app.packageName, newMode.hidesAppList)
                                    }
                                    if (newMode.hidesDevStatus != old.hideMode.hidesDevStatus) {
                                        applyDevStatusChange(app.packageName, newMode.hidesDevStatus)
                                    }
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

// ---------------------------------------------------------------------------
// Per-app row
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HideAppListItem(
    app: HideAppEntry,
    onModeChange: (HideMode) -> Unit,
) {
    val isActive = app.hideMode != HideMode.NONE
    val iconBitmap = remember(app.packageName) {
        app.icon?.toBitmap(96, 96)?.asImageBitmap()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = RoundedCornerShape(14.dp),
        color = if (isActive)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            // App identity row – tapping toggles between NONE and previous active state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // Toggle: if currently hidden in any way -> clear; otherwise default to BOTH
                        onModeChange(if (isActive) HideMode.NONE else HideMode.BOTH)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Icon
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
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (app.isSystem) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary,
                            ) { Text(stringResource(R.string.hide_apps_system_badge)) }
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

                Spacer(modifier = Modifier.width(8.dp))

                // Quick visual indicator
                if (isActive) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Segmented controls — shown when app is active (any hide mode)
            AnimatedVisibility(
                visible = isActive,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                        shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Row 1: App List toggle
                    HideToggleRow(
                        label = stringResource(R.string.hide_apps_toggle_app_list),
                        checked = app.hideMode.hidesAppList,
                        onToggle = { onModeChange(app.hideMode.withAppList(it)) },
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Row 2: Developer status toggle
                    HideToggleRow(
                        label = stringResource(R.string.hide_apps_toggle_dev_status),
                        checked = app.hideMode.hidesDevStatus,
                        onToggle = { onModeChange(app.hideMode.withDevStatus(it)) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small inline toggle row (label + Yes/No segmented button)
// ---------------------------------------------------------------------------

@Composable
private fun HideToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !checked,
                onClick = { onToggle(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text(stringResource(R.string.default_value), style = MaterialTheme.typography.labelSmall) },
                icon = {
                    if (!checked) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                },
            )
            SegmentedButton(
                selected = checked,
                onClick = { onToggle(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text(stringResource(R.string.hide_apps_toggle_hidden), style = MaterialTheme.typography.labelSmall) },
                icon = {
                    if (checked) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                    }
                },
            )
        }
    }
}
