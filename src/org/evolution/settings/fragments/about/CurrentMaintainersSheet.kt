/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.settings.R
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

// ---------------------------------------------------------------------------
// Lightweight data class passed from About.kt → Compose layer
// ---------------------------------------------------------------------------

data class MaintainerUiEntry(
    val maintainer: String,
    val devices: String,
    val github: String,
    val donateUrl: String?,
    val forumUrls: List<Pair<String, String>>,
)

// ---------------------------------------------------------------------------
// BottomSheetDialogFragment
// ---------------------------------------------------------------------------

class CurrentMaintainersSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "CurrentMaintainersSheet"
    }

    private val _entries = mutableStateListOf<MaintainerUiEntry>()
    private val _isLoading = mutableStateOf(true)

    var entries: List<MaintainerUiEntry>
        get() = _entries
        set(value) { _entries.clear(); _entries.addAll(value) }

    var isLoading: Boolean
        get() = _isLoading.value
        set(value) { _isLoading.value = value }

    fun refresh(newEntries: List<MaintainerUiEntry>) {
        _entries.clear()
        _entries.addAll(newEntries)
        _isLoading.value = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SettingsTheme {
                SheetContent(
                    entries   = _entries,
                    isLoading = _isLoading.value,
                    onDismiss = { dismissAllowingStateLoss() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Avatar composable — backed by GithubAvatarLoader, no Coil needed
// ---------------------------------------------------------------------------

@Composable
private fun GithubAvatarImage(
    username: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { imageView ->
            if (username.isNotBlank()) {
                GithubAvatarLoader.getInstance().loadIntoImageView(context, imageView, username)
            }
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Sheet Compose content
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SheetContent(
    entries: List<MaintainerUiEntry>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(searchQuery, entries) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) entries
        else entries.filter { e ->
            e.maintainer.lowercase().contains(q) ||
                e.devices.lowercase().contains(q)
        }
    }

    fun openUrl(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("CurrentMaintainersSheet", "Cannot open url: $url", e)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.about_current_maintainers_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isLoading)
                    stringResource(R.string.about_current_maintainers_loading_summary)
                else
                    stringResource(R.string.about_current_maintainers_sheet_subtitle, entries.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.about_current_maintainers_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }

                filtered.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty())
                                stringResource(R.string.about_current_maintainers_no_results)
                            else
                                stringResource(R.string.about_current_maintainers_empty_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it.maintainer + "|" + it.github }) { entry ->
                            MaintainerRow(
                                entry = entry,
                                onOpenUrl = ::openUrl,
                            )
                        }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Per-maintainer row
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaintainerRow(
    entry: MaintainerUiEntry,
    onOpenUrl: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val githubUrl  = if (entry.github.isNotBlank()) "https://github.com/${entry.github}" else null
    val hasAnyLink = githubUrl != null || entry.donateUrl != null || entry.forumUrls.isNotEmpty()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = RoundedCornerShape(14.dp),
        color = if (expanded)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasAnyLink) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (entry.github.isNotBlank()) {
                        GithubAvatarImage(
                            username = entry.github,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                        )
                    } else {
                        Text(
                            text = entry.maintainer.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.maintainer,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.devices.isNotBlank()) {
                        Text(
                            text = entry.devices,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit  = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                        shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                ) {
                    if (githubUrl != null) {
                        LinkButton(
                            label   = stringResource(R.string.maintainer_link_github),
                            iconRes = R.drawable.ic_github_2,
                            onClick = { onOpenUrl(githubUrl) },
                        )
                    }
                    if (entry.donateUrl != null) {
                        LinkButton(
                            label   = stringResource(R.string.maintainer_link_donate),
                            iconRes = R.drawable.ic_donate,
                            onClick = { onOpenUrl(entry.donateUrl) },
                        )
                    }
                    if (entry.forumUrls.size == 1) {
                        LinkButton(
                            label   = stringResource(R.string.maintainer_link_forum),
                            iconRes = R.drawable.ic_forum,
                            onClick = { onOpenUrl(entry.forumUrls[0].second) },
                        )
                    } else if (entry.forumUrls.size > 1) {
                        entry.forumUrls.forEach { (deviceLabel, url) ->
                            LinkButton(
                                label   = deviceLabel,
                                iconRes = R.drawable.ic_forum,
                                onClick = { onOpenUrl(url) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tonal button for GitHub / Donate / Forum
// ---------------------------------------------------------------------------

@Composable
private fun LinkButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick  = onClick,
        modifier = Modifier,
        shape    = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                painter            = painterResource(iconRes),
                contentDescription = null,
                modifier           = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text     = label,
                style    = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
