/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ButtonColors
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.ui.unit.Dp
import com.android.settings.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuGroupShapes
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.dialog.getDialogWidth
import com.android.settingslib.spa.widget.ui.CategoryTitle

// ---------------------------------------------------------------------------
// Shared Material 3 Expressive building blocks for the spoofing screens.
//
// Everything here is layered on SettingsLib SPA tokens (SettingsSpace,
// SettingsShape, SettingsDimension) and widgets (CategoryTitle,
// getDialogWidth) so the screens follow the same rhythm as stock Settings:
// 16dp screen inset, 20dp grouped-card corners with 2dp gaps between rows,
// surfaceBright row containers, and full-pill buttons.
// ---------------------------------------------------------------------------

/** Semantic colour roles for [SpoofingStatusChip]. Mapped onto theme roles, never hard-coded. */
enum class SpoofingTone { Positive, Warning, Negative, Neutral }

/** Scrollable page body shared by the spoofing screens. */
@Composable
fun SpoofingScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = SettingsSpace.extraSmall4, bottom = SettingsSpace.medium1),
        content = content,
    )
}

/** Horizontal screen inset for items that live outside a [SpoofingGroup]. */
fun Modifier.spoofingInset(): Modifier = padding(horizontal = SettingsSpace.small1)

/** Local stand-in for SPA's ExpandIcon, which is `internal` to SettingsLib and not linkable. */
@Composable
private fun SpoofingExpandIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = null,
        modifier = Modifier.padding(horizontal = SettingsSpace.extraSmall4),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Theme for every spoofing screen: SettingsTheme (SPA colours, typography, shapes) with the
 * expressive [MotionScheme] swapped in, so springs used by [MaterialTheme.motionScheme] callers
 * and the Material 3 Expressive components match the rest of the screen.
 */
@Composable
fun SpoofingTheme(content: @Composable () -> Unit) {
    SettingsTheme {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/** Shapes for the row currently being drawn by a [SpoofingGroup]; null outside of a group. */
private val LocalSpoofingRowShapes = compositionLocalOf<ListItemShapes?> { null }

/** Segmented shapes of the enclosing group row, or a standalone (all outer corners) row. */
@Composable
private fun currentSpoofingRowShapes(): ListItemShapes =
    LocalSpoofingRowShapes.current ?: spoofingRowShapes(index = 0, count = 1)

/**
 * [ListItemDefaults.segmentedShapes] with an explicit fully rounded shape for single rows
 * (model selector, standalone rows). The stock helper can hand back the small inner-corner shape
 * for `count == 1`, which made lone rows look squarer than the cards around them.
 */
@Composable
fun spoofingRowShapes(index: Int, count: Int): ListItemShapes {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    return if (count == 1) shapes.copy(shape = MaterialTheme.shapes.large) else shapes
}

/** Menu group shapes with the same large corners as the cards, for every spoofing dropdown. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun spoofingMenuGroupShapes(): MenuGroupShapes =
    MenuDefaults.groupShape(index = 0, count = 1).copy(
        shape = SettingsShape.CornerExtraLarge1,
        inactiveShape = SettingsShape.CornerExtraLarge1,
    )

/** Builder passed to [SpoofingGroup]; every [item] becomes one segment of the group. */
class SpoofingGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/**
 * A titled group of rows: [CategoryTitle] above a column of segmented rows (large outer corners,
 * small inner corners, 2dp gaps). Each row is declared with [SpoofingGroupScope.item] so the
 * group knows its index and count and can hand every row the right
 * [ListItemDefaults.segmentedShapes]. When [collapsible] is set the title doubles as an
 * expand/collapse toggle and the state survives configuration changes.
 */
@Composable
fun SpoofingGroup(
    title: String,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    horizontalInset: Dp = SettingsSpace.small1,
    content: SpoofingGroupScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val items = SpoofingGroupScope().apply(content).items
    Column(
        modifier = modifier.padding(
            horizontal = horizontalInset,
            vertical = SettingsSpace.extraSmall4,
        ),
    ) {
        if (collapsible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) { CategoryTitle(title) }
                SpoofingExpandIcon(expanded)
            }
        } else if (title.isNotEmpty()) {
            CategoryTitle(title)
        }
        AnimatedVisibility(visible = !collapsible || expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1),
            ) {
                items.forEachIndexed { index, rowContent ->
                    CompositionLocalProvider(
                        LocalSpoofingRowShapes provides
                            spoofingRowShapes(index = index, count = items.size),
                    ) { rowContent() }
                }
            }
        }
    }
}

/**
 * Shared list item: a [SegmentedListItem] (pressed-shape morph, ripple, disabled colours) when
 * [onClick] is set, or a static surface with the same shape and layout when it is not.
 */
@Composable
private fun SpoofingListItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    overline: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    val shapes = currentSpoofingRowShapes()
    val container = MaterialTheme.colorScheme.surfaceBright
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            colors = ListItemDefaults.colors(containerColor = container),
            leadingContent = leading?.let { l -> { l() } },
            trailingContent = trailing?.let { t -> { t() } },
            overlineContent = overline,
            supportingContent = supporting,
            content = headline,
        )
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.38f),
            shape = shapes.shape,
            color = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .padding(SettingsDimension.itemPadding)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(SettingsDimension.itemPaddingStart))
                }
                Column(modifier = Modifier.weight(1f)) {
                    overline?.invoke()
                    headline()
                    if (supporting != null) {
                        CompositionLocalProvider(
                            LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { supporting() }
                        }
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(SettingsSpace.extraSmall4))
                    trailing()
                }
            }
        }
    }
}

/**
 * Base row: surfaceBright segment, optional leading icon, title + summary and an optional
 * trailing slot. Disabled rows are dimmed and not clickable.
 */
@Composable
fun SpoofingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SpoofingListItem(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        leading = icon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    modifier = Modifier.size(SettingsDimension.itemIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supporting = summary?.takeIf { it.isNotEmpty() }?.let { text ->
            { Text(text = text) }
        },
        trailing = trailing,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

/** Navigation row: [SpoofingRow] with a trailing chevron, or a [chip] when one is supplied. */
@Composable
fun SpoofingNavRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    chip: String? = null,
    chipTone: SpoofingTone = SpoofingTone.Neutral,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SpoofingRow(
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            if (chip != null) {
                SpoofingStatusChip(chip, chipTone)
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Read-only key/value row used for the active configuration. The key is a small primary label,
 * the value is selectable so a fingerprint can be copied. Pass [onClick] to make it editable.
 */
@Composable
fun SpoofingValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SpoofingListItem(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        overline = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        if (onClick == null) {
            SelectionContainer {
                Text(text = value, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Small pill used for status values ("Valid", "Auto", "21d left"). */
@Composable
fun SpoofingStatusChip(
    text: String,
    tone: SpoofingTone = SpoofingTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        SpoofingTone.Positive -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        SpoofingTone.Warning -> scheme.secondaryContainer to scheme.onSecondaryContainer
        SpoofingTone.Negative -> scheme.errorContainer to scheme.onErrorContainer
        SpoofingTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = SettingsShape.CornerFull,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = SettingsSpace.extraSmall5,
                vertical = SettingsSpace.extraSmall2,
            ),
        )
    }
}

/** Full-width primary action (e.g. "Fetch Pixel Canary") with an optional loading state. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpoofingPrimaryAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    inset: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        modifier = modifier
            .then(if (inset) Modifier.spoofingInset() else Modifier)
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        enabled = enabled && !loading,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(24.dp))
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(SettingsSpace.extraSmall4))
        Text(text = text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

data class SpoofingAction(
    val text: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    /** Renders the pill as a toggled-on filter (check icon, primary container). */
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/** Equal-width row of tonal pill buttons ("Import", "Delete", "Reset"). */
@Composable
fun SpoofingActionRow(
    actions: List<SpoofingAction>,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
) {
    Row(
        modifier = modifier
            .then(if (inset) Modifier.spoofingInset() else Modifier)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4),
    ) {
        actions.forEach { action ->
            FilledTonalButton(
                onClick = action.onClick,
                enabled = action.enabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shapes = ButtonDefaults.shapes(),
                contentPadding = PaddingValues(horizontal = SettingsSpace.extraSmall6),
                colors = if (action.selected) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Icon(
                    imageVector = if (action.selected) Icons.Rounded.Check else action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(SettingsSpace.extraSmall3))
                Text(
                    text = action.text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Expressive text button: [TextButton] with the pressed-shape morph of [ButtonDefaults.shapes].
 * Used for every dialog action and inline text action on the spoofing screens.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpoofingTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        content = content,
    )
}

/**
 * Shared dialog for every spoofing screen: SPA dialog width, the same extra-large corners as the
 * cards and a surfaceContainerHigh container so [SpoofingGroup] rows and [SpoofingTextField]s
 * (surfaceBright / surfaceContainerHighest) stay visible inside it.
 */
@Composable
fun SpoofingDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.width(getDialogWidth()),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = SettingsShape.CornerExtraLarge1,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

/** Confirmation dialog sized and shaped like SPA's `SettingsAlertDialog`. */
@Composable
fun SpoofingConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = stringResource(android.R.string.cancel),
    destructive: Boolean = false,
) {
    SpoofingDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            SpoofingTextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmText) }
        },
        dismissButton = { SpoofingTextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

/**
 * Navigation row with its own switch: tapping the row opens [onClick], the switch (behind a
 * divider, like SettingsLib's primary-switch preference) toggles the feature in place.
 */
@Composable
fun SpoofingSwitchNavRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SpoofingRow(
        title = title,
        modifier = modifier,
        summary = summary,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(modifier = Modifier.height(32.dp))
                Spacer(Modifier.width(SettingsSpace.extraSmall6))
                CheckCloseSwitch(checked = checked, onCheckedChange = onCheckedChange)
            }
        },
    )
}

/**
 * Switch row: a regular [SpoofingRow] (a surfaceBright segment of the enclosing
 * [SpoofingGroup]) with the shared check/close switch as the trailing slot.
 */
@Composable
fun SpoofingSwitchRow(
    titleText: String,
    summaryText: String,
    isChecked: Boolean,
    onChange: (Boolean) -> Unit,
    isEnabled: Boolean = true,
) {
    SpoofingRow(
        title = titleText,
        summary = summaryText,
        enabled = isEnabled,
        onClick = { onChange(!isChecked) },
        trailing = {
            Spacer(Modifier.width(SettingsSpace.small1))
            CheckCloseSwitch(
                checked = isChecked,
                onCheckedChange = onChange,
                enabled = isEnabled,
            )
        },
    )
}

/**
 * "System apps / Select all / Reset" toolbar shared by every app-picker screen. System apps is a
 * toggle and renders as a selected pill when on.
 */
@Composable
fun AppPickerActions(
    showSystemApps: Boolean,
    onToggleSystemApps: () -> Unit,
    onSelectAll: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4),
    ) {
        SpoofingActionRow(
            actions = listOf(
                SpoofingAction(
                    text = stringResource(R.string.battery_usage_system_apps),
                    icon = Icons.Rounded.PhoneAndroid,
                    enabled = enabled,
                    selected = showSystemApps,
                    onClick = onToggleSystemApps,
                ),
                SpoofingAction(
                    text = stringResource(R.string.action_select_all),
                    icon = Icons.Rounded.DoneAll,
                    enabled = enabled,
                    onClick = onSelectAll,
                ),
            ),
            inset = false,
        )
        SpoofingActionRow(
            actions = listOf(
                SpoofingAction(
                    text = stringResource(R.string.action_reset),
                    icon = Icons.Rounded.History,
                    enabled = enabled,
                    onClick = onReset,
                ),
            ),
            inset = false,
        )
    }
}

/**
 * Filled, indicator-less text field colours shared with [AppPickerSearchField]. The container
 * defaults to surfaceContainerHighest so a field stays visible on a dialog's surface.
 */
@Composable
fun spoofingTextFieldColors(
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = container,
    unfocusedContainerColor = container,
    disabledContainerColor = container,
    errorContainerColor = container,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
)

/** Rounded filled text field used by the spoofing dialogs in place of `OutlinedTextField`. */
@Composable
fun SpoofingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        shape = SettingsShape.CornerLarge1,
        colors = spoofingTextFieldColors(),
    )
}

/**
 * Single-choice row for profile pickers: a [SpoofingRow] that shows a check when [selected].
 * Extra [trailing] content (e.g. edit/delete buttons) is drawn before the check.
 */
@Composable
fun SpoofingChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SpoofingRow(
        title = title,
        modifier = modifier,
        summary = summary,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke()
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
