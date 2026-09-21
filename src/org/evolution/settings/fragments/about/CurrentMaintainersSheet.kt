/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about

import org.evolution.settings.fragments.miscellaneous.expressiveButtonShapes
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.fragment.app.DialogFragment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.settings.R
import com.android.settings.widget.scrollbar
import com.android.settingslib.spa.framework.theme.SettingsSpace
import org.evolution.settings.fragments.miscellaneous.AppPickerSearchField
import org.evolution.settings.fragments.miscellaneous.insetFocusRing
import org.evolution.settings.fragments.miscellaneous.expressiveRowShapes
import com.android.settingslib.spa.framework.theme.SettingsTheme

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

/**
 * Hosts the Compose [ModalBottomSheet] in a transparent, frameless dialog. The Compose sheet draws
 * the only sheet, scrim and drag handle; this fragment only supplies the window and lifecycle.
 */
class CurrentMaintainersSheet : DialogFragment() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
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
                scaleType = ImageView.ScaleType.FIT_CENTER
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
        // alpha23 no longer pads the sheet for the keyboard itself; alpha16 still does and
        // consumes the inset, so this is a no-op until we link alpha23.
        modifier = Modifier.imePadding(),
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.about_current_maintainers_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
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

            AppPickerSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = stringResource(R.string.about_current_maintainers_search_hint),
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
                        ContainedLoadingIndicator()
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
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                            .scrollbar(listState.scrollIndicatorState, Orientation.Vertical),
                        verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1),
                    ) {
                        itemsIndexed(
                            filtered,
                            key = { _, it -> it.maintainer + "|" + it.github },
                        ) { index, entry ->
                            MaintainerRow(
                                entry = entry,
                                index = index,
                                count = filtered.size,
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
    index: Int,
    count: Int,
    onOpenUrl: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val githubUrl  = if (entry.github.isNotBlank()) "https://github.com/${entry.github}" else null
    val hasAnyLink = githubUrl != null || entry.donateUrl != null || entry.forumUrls.isNotEmpty()
    val scheme = MaterialTheme.colorScheme

    SegmentedListItem(
        onClick = { if (hasAnyLink) expanded = !expanded },
        shapes = expressiveRowShapes(index = index, count = count),
        modifier = Modifier
            .fillMaxWidth()
            .insetFocusRing(expressiveRowShapes(index = index, count = count).shape)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        colors = ListItemDefaults.colors(
            containerColor = if (expanded) scheme.primaryContainer else scheme.surfaceBright,
            contentColor = if (expanded) scheme.onPrimaryContainer else scheme.onSurface,
            supportingContentColor =
                if (expanded) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        ),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceVariant),
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
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (entry.devices.isNotBlank()) {
                    Text(
                        text = entry.devices,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
        },
        content = {
            Text(
                text = entry.maintainer,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

// ---------------------------------------------------------------------------
// Tonal button for GitHub / Donate / Forum
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinkButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val height = ButtonDefaults.MinHeight
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.insetFocusRing(CircleShape, ringHeight = height),
        shapes = expressiveButtonShapes(),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
        Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
