/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.security.trickystore.TrickyStoreService
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.widget.scrollbar
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settings.SettingsPreferenceFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TargetMode(val symbol: String) {
    AUTO(""),
    LEAF_HACK("?"),
    CERT_GEN("!"),
    SKIP("-"),
}

// AppEntry kept for external references; internally we use AppListEntry +
// a parallel targetMode list. The targetMode for each package is stored in
// a separate map so we don't duplicate the whole AppListEntry on every mode change.
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean = false,
    var targetMode: TargetMode = TargetMode.AUTO,
    var isInTarget: Boolean = false,
)

private val EXCLUDED_SUFFIXES = listOf(
    ".auto_generated", ".appsearch", ".backup", ".carrier",
    ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
    ".qti", ".qualcomm", ".resources", ".systemui.clocks",
    ".systemui.plugin", ".theme", ".iconpack",
)

class TrickyStoreAppSettings : SettingsPreferenceFragment() {

    companion object {
        const val TARGET_KEY = "spoof_trickystore_target"
        // The default target list lives in the framework
        // (TrickyStoreService.DEFAULT_TARGET_LIST), where AxSpoofManager seeds it
        // at boot. Split it here into the plain AUTO packages and the packages
        // with a mode, so the app picker keeps working with both.
        private val defaultTargetEntries: List<Pair<String, TargetMode>> =
            TrickyStoreService.DEFAULT_TARGET_LIST
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    when {
                        line.endsWith("!") -> line.dropLast(1) to TargetMode.CERT_GEN
                        line.endsWith("?") -> line.dropLast(1) to TargetMode.LEAF_HACK
                        line.endsWith("-") -> line.dropLast(1) to TargetMode.SKIP
                        else -> line to TargetMode.AUTO
                    }
                }

        val DEFAULT_TARGETS: Set<String> = defaultTargetEntries
            .filter { it.second == TargetMode.AUTO }
            .map { it.first }
            .toSet()

        val DEFAULT_TARGET_MODES: Map<String, TargetMode> = defaultTargetEntries
            .filter { it.second != TargetMode.AUTO }
            .toMap()

        /** The default target list, as written to SPOOF_TRICKYSTORE_TARGET. */
        fun buildDefaultTargetSeed(): String = TrickyStoreService.DEFAULT_TARGET_LIST

        /**
         * The targets that are installed for this user. Uninstalled defaults stay in the
         * stored list (so an app installed later is covered at once) but are not counted
         * as configured anywhere in the UI.
         */
        fun installedTargets(pm: PackageManager, targets: Map<String, TargetMode>) =
            targets.filterKeys { isInstalled(pm, it) }

        /**
         * Parses the text target format ("pkg", "pkg?", "pkg!", "pkg-", "#" comments) or the
         * JSON array form ({"package", "mode"}) into package -> mode, keeping order. Skip
         * entries are kept: dropping them here would silently remove them on the next save.
         */
        fun parseTargetList(list: String?): Map<String, TargetMode> {
            val result = LinkedHashMap<String, TargetMode>()
            if (list.isNullOrBlank()) return result
            val trimmed = list.trim()
            if (trimmed.startsWith("[")) {
                runCatching {
                    val array = org.json.JSONArray(trimmed)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val pkg = obj.optString("package").takeIf { it.isNotEmpty() } ?: continue
                        result[pkg] = when (obj.optString("mode").uppercase()) {
                            "LEAF_HACK" -> TargetMode.LEAF_HACK
                            "GENERATE" -> TargetMode.CERT_GEN
                            "SKIP" -> TargetMode.SKIP
                            else -> TargetMode.AUTO
                        }
                    }
                }
                return result
            }
            trimmed.lines().forEach { raw ->
                val t = raw.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEach
                when {
                    t.endsWith("?") -> result[t.dropLast(1).trim()] = TargetMode.LEAF_HACK
                    t.endsWith("!") -> result[t.dropLast(1).trim()] = TargetMode.CERT_GEN
                    t.endsWith("-") -> result[t.dropLast(1).trim()] = TargetMode.SKIP
                    else -> result[t] = TargetMode.AUTO
                }
            }
            return result
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.tricky_store_title)
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
            ExpressiveTheme {
                TrickyStoreAppSettingsContent(context = requireContext())
            }
        }
    }
}

// Internal model that pairs AppListEntry (isSelected = isInTarget) with a TargetMode.
// We keep a parallel mutableStateList for targetModes so that changing a mode
// does not recreate the full AppListEntry list.
private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
    pm.getPackageInfo(pkg, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private data class TrickyAppState(
    val entry: AppListEntry,
    val mode: TargetMode,
)

@Composable
private fun TrickyStoreAppSettingsContent(
    context: android.content.Context,
) {
    val scope = rememberCoroutineScope()
    val activityManager = remember {
        context.getSystemService(ActivityManager::class.java)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<TrickyAppState>() }

    fun loadTargetMap(): Map<String, TargetMode> =
        TrickyStoreAppSettings.parseTargetList(
            Settings.Secure.getString(
                context.contentResolver, TrickyStoreAppSettings.TARGET_KEY,
            ),
        )

    fun saveTargets() {
        val lines = allApps
            .filter { it.entry.isSelected }
            .map { it.entry.packageName + it.mode.symbol }
        Settings.Secure.putString(
            context.contentResolver,
            TrickyStoreAppSettings.TARGET_KEY,
            lines.joinToString("\n"),
        )
    }

    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            // Seed defaults on first run if key is empty
            val existing = Settings.Secure.getString(
                context.contentResolver, TrickyStoreAppSettings.TARGET_KEY)
            if (existing.isNullOrEmpty()) {
                Settings.Secure.putString(
                    context.contentResolver,
                    TrickyStoreAppSettings.TARGET_KEY,
                    TrickyStoreAppSettings.buildDefaultTargetSeed(),
                )
            }

            val pm = context.packageManager
            val targetMap = loadTargetMap()
            val targeted = targetMap.keys.toSet()

            val installed = filterInstalledApps(
                pm = pm,
                showSystem = showSystemApps,
                targeted = targeted,
                extraFilter = { app ->
                    val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    val isSuffixExcluded = EXCLUDED_SUFFIXES.any { app.packageName.contains(it) }
                    // Xposed managers are always skipped by the framework (attesting through a
                    // hooked process breaks STRONG), so don't offer them as targets.
                    val isXposed = app.packageName in TrickyStoreService.XPOSED_PACKAGES
                    !(isSystem && isSuffixExcluded) && !isXposed
                },
            )

            // Prune stale entries (uninstalled apps), keeping protected defaults
            val installedPackages = installed.map { it.packageName }.toSet()
            val cleanedTargetMap = targetMap.filterKeys {
                it in installedPackages || it in TrickyStoreAppSettings.DEFAULT_TARGETS
            }
            if (cleanedTargetMap.size != targetMap.size) {
                val lines = cleanedTargetMap.map { (pkg, mode) -> pkg + mode.symbol }
                Settings.Secure.putString(
                    context.contentResolver,
                    TrickyStoreAppSettings.TARGET_KEY,
                    lines.joinToString("\n"),
                )
            }

            val mappedApps = installed
                .sortedWith(targetedFirstComparator(pm, targeted))
                .map { app ->
                    TrickyAppState(
                        entry = AppListEntry(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            isSelected = cleanedTargetMap.containsKey(app.packageName),
                        ),
                        mode = cleanedTargetMap[app.packageName] ?: TargetMode.AUTO,
                    )
                }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(mappedApps)
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, allApps.toList()) {
        val query = searchQuery.lowercase()
        allApps.filter { state ->
            query.isEmpty() ||
                state.entry.label.lowercase().contains(query) ||
                state.entry.packageName.lowercase().contains(query)
        }
    }

    val activeCount = allApps.count { it.entry.isSelected }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = SettingsSpace.small1),
        ) {
            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            // No switch — TrickyStore is always active
            ExpressiveHeaderCard(
                title = stringResource(R.string.ts_manage_target_apps),
                subtitle = if (activeCount == 0)
                    stringResource(R.string.ts_no_targets)
                else
                    stringResource(R.string.ts_target_apps_count, activeCount),
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall6))

            AppPickerSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
            )

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            AppPickerActions(
                showSystemApps = showSystemApps,
                onToggleSystemApps = { showSystemApps = !showSystemApps },
                onSelectAll = {
                    filteredApps.forEach { s ->
                        val i = allApps.indexOfFirst {
                            it.entry.packageName == s.entry.packageName }
                        if (i >= 0 && !allApps[i].entry.isSelected)
                            allApps[i] = allApps[i].copy(
                                entry = allApps[i].entry.copy(isSelected = true))
                    }
                    scope.launch(Dispatchers.IO) { saveTargets() }
                },
                onReset = {
                    val previouslySelected = allApps
                        .filter { it.entry.isSelected }
                        .map { it.entry.packageName }
                        .toSet()

                    allApps.indices.forEach { i ->
                        val pkg = allApps[i].entry.packageName
                        allApps[i] = allApps[i].copy(
                            entry = allApps[i].entry.copy(
                                isSelected = pkg in TrickyStoreAppSettings.DEFAULT_TARGETS ||
                                        pkg in TrickyStoreAppSettings.DEFAULT_TARGET_MODES,
                            ),
                            mode = TrickyStoreAppSettings.DEFAULT_TARGET_MODES[pkg]
                                ?: TargetMode.AUTO,
                        )
                    }

                    val newSelected = allApps
                        .filter { it.entry.isSelected }
                        .map { it.entry.packageName }
                        .toSet()

                    // Kill anything that was added or removed by the reset
                    val toKill = (previouslySelected + newSelected) -
                        previouslySelected.intersect(newSelected)

                    scope.launch(Dispatchers.IO) {
                        saveTargets()
                        killPackages(activityManager, toKill)
                    }
                },
            )

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            ExpressiveActionRow(
                actions = listOf(
                    ExpressiveAction(
                        text = stringResource(R.string.action_add_installed),
                        icon = Icons.Rounded.Add,
                        onClick = {
                        scope.launch(Dispatchers.IO) {
                                // Re-use the already-loaded allApps list: non-system apps
                                // are those whose isSystem flag is false. No redundant PM
                                // query needed.
                                val teeBroken = runCatching {
                                    android.security.trickystore
                                        .TrickyStoreService.getInstance().isTeeBroken()
                                }.getOrDefault(false)
    
                                var addedCount = 0
                                withContext(Dispatchers.Main) {
                                    allApps.indices.forEach { i ->
                                        val state = allApps[i]
                                        if (!state.entry.isSelected && !state.entry.isSystem) {
                                            val mode = when {
                                                TrickyStoreAppSettings.DEFAULT_TARGET_MODES
                                                    .containsKey(state.entry.packageName) ->
                                                    TrickyStoreAppSettings.DEFAULT_TARGET_MODES[state.entry.packageName]!!
                                                teeBroken -> TargetMode.LEAF_HACK
                                                else -> TargetMode.AUTO
                                            }
                                            allApps[i] = state.copy(
                                                entry = state.entry.copy(isSelected = true),
                                                mode = mode,
                                            )
                                            addedCount++
                                        }
                                    }
                                }
                                saveTargets()
                                withContext(Dispatchers.Main) {
                                    val msg = context.resources.getQuantityString(
                                        R.plurals.ts_added_installed_count,
                                        addedCount,
                                        addedCount,
                                    )
                                    android.widget.Toast.makeText(
                                        context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ),
                ),
                inset = false,
            )

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            AppListAnimatedContent(
                isLoading = isLoading,
                isEmpty = filteredApps.isEmpty(),
                hasSearchQuery = searchQuery.isNotEmpty(),
                modifier = Modifier.weight(1f),
                emptyContent = {
                    ExpressiveEmptyState(
                        icon = Icons.Default.Shield,
                        title = stringResource(R.string.ts_no_targets),
                        description = stringResource(R.string.ts_target_apps_empty_description),
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            ) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .scrollbar(listState.scrollIndicatorState, Orientation.Vertical),
                    verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1),
                ) {
                    itemsIndexed(filteredApps, key = { _, state -> state.entry.packageName }) { index, state ->
                        AppPickerItem(
                            packageName = state.entry.packageName,
                            label = state.entry.label,
                            icon = state.entry.icon,
                            isSystem = state.entry.isSystem,
                            checked = state.entry.isSelected,
                            shapes = ListItemDefaults.segmentedShapes(index = index, count = filteredApps.size),
                            onToggle = { nowEnabled ->
                                val i = allApps.indexOfFirst {
                                    it.entry.packageName == state.entry.packageName }
                                if (i >= 0) {
                                    val teeBroken = runCatching {
                                        android.security.trickystore
                                            .TrickyStoreService.getInstance().isTeeBroken()
                                    }.getOrDefault(false)
                                    val defaultMode = when {
                                        !nowEnabled -> allApps[i].mode
                                        teeBroken -> TargetMode.LEAF_HACK
                                        else -> allApps[i].mode
                                    }
                                    allApps[i] = allApps[i].copy(
                                        entry = allApps[i].entry.copy(isSelected = nowEnabled),
                                        mode = defaultMode,
                                    )
                                    scope.launch(Dispatchers.IO) {
                                        saveTargets()
                                        if (!nowEnabled) {
                                            killPackages(
                                                activityManager,
                                                setOf(state.entry.packageName),
                                            )
                                        }
                                    }
                                }
                            },
                            extraContent = if (state.entry.isSelected) {
                                {
                                    AnimatedVisibility(
                                        visible = state.entry.isSelected,
                                        enter = fadeIn(
                                            MaterialTheme.motionScheme.defaultEffectsSpec()) +
                                                expandVertically(
                                                    MaterialTheme.motionScheme.defaultSpatialSpec()),
                                        exit = fadeOut(
                                            MaterialTheme.motionScheme.fastEffectsSpec()) +
                                               shrinkVertically(
                                                   MaterialTheme.motionScheme.fastSpatialSpec()),
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(
                                                    ButtonGroupDefaults.ConnectedSpaceBetween),
                                            ) {
                                                val modes = TargetMode.entries
                                                modes.forEachIndexed { modeIndex, mode ->
                                                    val modeShapes = when (modeIndex) {
                                                        0 -> ButtonGroupDefaults
                                                            .connectedLeadingButtonShapes()
                                                        modes.lastIndex -> ButtonGroupDefaults
                                                            .connectedTrailingButtonShapes()
                                                        else -> ButtonGroupDefaults
                                                            .connectedMiddleButtonShapes()
                                                    }
                                                    val modeChecked = state.mode == mode
                                                    ToggleButton(
                                                        checked = state.mode == mode,
                                                        onCheckedChange = {
                                                            val i = allApps.indexOfFirst {
                                                                it.entry.packageName ==
                                                                    state.entry.packageName }
                                                            if (i >= 0) {
                                                                allApps[i] = allApps[i].copy(
                                                                    mode = mode)
                                                                scope.launch(Dispatchers.IO) {
                                                                    saveTargets() }
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .insetFocusRing(
                                                                if (modeChecked) modeShapes.checkedShape
                                                                else modeShapes.shape,
                                                                ringHeight = ButtonDefaults.MinHeight,
                                                            )
                                                            .semantics { role = Role.RadioButton },
                                                        shapes = modeShapes,
                                                        contentPadding = PaddingValues(
                                                            horizontal = SettingsSpace.extraSmall2),
                                                    ) {
                                                        Text(
                                                            text = when (mode) {
                                                                TargetMode.AUTO ->
                                                                    stringResource(
                                                                        R.string.ts_mode_auto)
                                                                TargetMode.LEAF_HACK ->
                                                                    stringResource(
                                                                        R.string.ts_mode_leaf)
                                                                TargetMode.CERT_GEN ->
                                                                    stringResource(
                                                                        R.string.ts_mode_cert)
                                                                TargetMode.SKIP ->
                                                                    stringResource(
                                                                        R.string.ts_mode_skip)
                                                            },
                                                            style = MaterialTheme.typography
                                                                .labelSmall,
                                                            maxLines = 1,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else null,
                        )
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}
