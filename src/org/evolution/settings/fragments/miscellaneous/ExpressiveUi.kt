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
import androidx.compose.ui.graphics.Shape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.VisualTransformation
import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.material3.Typography
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.android.internal.util.evolution.ThemeUtils
import androidx.compose.ui.unit.Dp
import com.android.settings.R
import com.android.settings.widget.scrollbar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuGroupShapes
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
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
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsRadius
import com.android.settingslib.spa.framework.theme.SettingsShape
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.dialog.getDialogWidth
import com.android.settingslib.spa.widget.ui.CategoryTitle

// ---------------------------------------------------------------------------
// Shared Material 3 Expressive building blocks for every Evolver screen.
//
// Everything here is layered on SettingsLib SPA tokens (SettingsSpace,
// SettingsShape, SettingsDimension) and widgets (CategoryTitle,
// getDialogWidth) so the screens follow the same rhythm as stock Settings:
// 16dp screen inset, 20dp grouped-card corners with 2dp gaps between rows,
// surfaceBright row containers, and full-pill buttons.
// ---------------------------------------------------------------------------

/** Semantic colour roles for [ExpressiveStatusChip]. Mapped onto theme roles, never hard-coded. */
enum class ExpressiveTone { Positive, Warning, Negative, Neutral }

/**
 * [verticalScroll] with the Material 3 alpha23 scrollbar. The scrollbar is chained ahead of the
 * scroll so it stays put while the content moves.
 */
@Composable
fun Modifier.expressiveVerticalScroll(): Modifier {
    val state = rememberScrollState()
    return this
        .scrollbar(state.scrollIndicatorState, Orientation.Vertical)
        .verticalScroll(state)
}

/** Scrollable page body shared by the Expressive screens. */
@Composable
fun ExpressiveScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .expressiveVerticalScroll()
            .padding(top = SettingsSpace.extraSmall4, bottom = SettingsSpace.medium1),
        content = content,
    )
}

/** Horizontal screen inset for items that live outside a [ExpressiveGroup]. */
fun Modifier.expressiveInset(): Modifier = padding(horizontal = SettingsSpace.small1)

/** Local stand-in for SPA's ExpandIcon, which is `internal` to SettingsLib and not linkable. */
@Composable
private fun ExpressiveExpandIcon(expanded: Boolean) {
    Icon(
        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = null,
        modifier = Modifier.padding(horizontal = SettingsSpace.extraSmall4),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Theme for every Expressive screen: SettingsTheme (SPA colours, typography, shapes) re-themed with
 * [MaterialExpressiveTheme] and the expressive [MotionScheme], so springs used by
 * [MaterialTheme.motionScheme] callers and the Material 3 Expressive components match the rest of
 * the screen. The colour scheme, shapes and typography are passed explicitly: outside a nested
 * expressive theme, [MaterialExpressiveTheme] would otherwise fall back to its own defaults.
 */
@Composable
fun ExpressiveTheme(content: @Composable () -> Unit) {
    SettingsTheme {
        MaterialExpressiveTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = rememberEvolverTypography(MaterialTheme.typography),
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/**
 * SPA builds its typography from `DeviceFontFamilyName`s (and fixed `variable-*-emphasized`
 * aliases), which never follow the font picked in Themes: the picker enables a font overlay that
 * changes `config_bodyFontFamily` / `config_headlineFontFamily`, and the custom-font flow replaces
 * the system family. The XML pages read those strings through a [Typeface], so do the same here
 * and point every text style at the result. Left untouched while no font overlay or custom font is
 * active, so the stock Google Sans look (and its weights) is unchanged.
 */
@Composable
private fun rememberEvolverTypography(base: Typography): Typography {
    val context = LocalContext.current
    return remember(base, context) {
        if (!isFontOverridden(context)) return@remember base
        val plain = configFontFamily(context, "config_bodyFontFamily")
        val brand = configFontFamily(context, "config_headlineFontFamily") ?: plain
        if (plain == null && brand == null) base
        else base.withFontFamilies(brand = brand ?: plain!!, plain = plain ?: brand!!)
    }
}

private fun isFontOverridden(context: android.content.Context): Boolean = runCatching {
    val custom = Settings.Secure.getString(context.contentResolver, "custom_font_name")
    !custom.isNullOrEmpty() ||
        ThemeUtils.getInstance(context).getOverlayInfos(ThemeUtils.FONT_KEY).any { it.isEnabled }
}.getOrDefault(false)

private fun configFontFamily(context: android.content.Context, name: String): FontFamily? =
    runCatching {
        val id = context.resources.getIdentifier(name, "string", "android")
        if (id == 0) return null
        val family = context.resources.getString(id)
        if (family.isEmpty()) null else FontFamily(Typeface.create(family, Typeface.NORMAL))
    }.getOrNull()

private fun Typography.withFontFamilies(brand: FontFamily, plain: FontFamily): Typography {
    fun TextStyle.with(f: FontFamily) = copy(fontFamily = f)
    return copy(
        displayLarge = displayLarge.with(brand),
        displayMedium = displayMedium.with(brand),
        displaySmall = displaySmall.with(brand),
        headlineLarge = headlineLarge.with(brand),
        headlineMedium = headlineMedium.with(brand),
        headlineSmall = headlineSmall.with(brand),
        titleLarge = titleLarge.with(plain),
        titleMedium = titleMedium.with(plain),
        titleSmall = titleSmall.with(plain),
        bodyLarge = bodyLarge.with(plain),
        bodyMedium = bodyMedium.with(plain),
        bodySmall = bodySmall.with(plain),
        labelLarge = labelLarge.with(plain),
        labelMedium = labelMedium.with(plain),
        labelSmall = labelSmall.with(plain),
        displayLargeEmphasized = displayLargeEmphasized.with(brand),
        displayMediumEmphasized = displayMediumEmphasized.with(brand),
        displaySmallEmphasized = displaySmallEmphasized.with(brand),
        headlineLargeEmphasized = headlineLargeEmphasized.with(brand),
        headlineMediumEmphasized = headlineMediumEmphasized.with(brand),
        headlineSmallEmphasized = headlineSmallEmphasized.with(brand),
        titleLargeEmphasized = titleLargeEmphasized.with(plain),
        titleMediumEmphasized = titleMediumEmphasized.with(plain),
        titleSmallEmphasized = titleSmallEmphasized.with(plain),
        bodyLargeEmphasized = bodyLargeEmphasized.with(plain),
        bodyMediumEmphasized = bodyMediumEmphasized.with(plain),
        bodySmallEmphasized = bodySmallEmphasized.with(plain),
        labelLargeEmphasized = labelLargeEmphasized.with(plain),
        labelMediumEmphasized = labelMediumEmphasized.with(plain),
        labelSmallEmphasized = labelSmallEmphasized.with(plain),
    )
}

/**
 * Pill buttons with an explicit pressed shape. [ButtonDefaults.shapes] resolves its shapes from
 * the ambient theme at the time the button recomposes; after the soft keyboard comes and goes the
 * action buttons were left with a square shape. Pinning both shapes keeps them a pill at rest and
 * a rounded rectangle while pressed, whatever interaction state they are left in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun expressiveButtonShapes(): ButtonShapes = ButtonDefaults.shapes(
    shape = SettingsShape.CornerFull,
    pressedShape = RoundedCornerShape(SettingsRadius.large2),
)

/** Shapes for the row currently being drawn by a [ExpressiveGroup]; null outside of a group. */
private val LocalExpressiveRowShapes = compositionLocalOf<ListItemShapes?> { null }

/** Segmented shapes of the enclosing group row, or a standalone (all outer corners) row. */
@Composable
private fun currentExpressiveRowShapes(): ListItemShapes =
    LocalExpressiveRowShapes.current ?: expressiveRowShapes(index = 0, count = 1)

/**
 * Corner roles shared by every Expressive container, taken from SettingsLib's expressive
 * preference backgrounds (settingslib_round_background{,_top,_center,_bottom}.xml): 20dp outer
 * corners, 4dp inner corners, and the 24dp popup of the expressive spinner
 * (settingslib_expressive_spinner_dropdown_background.xml). Buttons, chips and search fields stay
 * fully rounded; containers never do.
 */
object ExpressiveCorners {
    private val outer = SettingsRadius.large2
    private val inner = SettingsRadius.extraSmall2

    /** Standalone card or lone row: all four corners are outer corners. */
    val single = RoundedCornerShape(outer)
    val first = RoundedCornerShape(
        topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner,
    )
    val middle = RoundedCornerShape(inner)
    val last = RoundedCornerShape(
        topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer,
    )

    /** Dropdown popup, same radius as the XML spinner popup. */
    val popup = SettingsShape.CornerLarge3

    /** Colour pickers and sliders, swatches and thumbnails: 12dp, SettingsLib's medium corner. */
    val picker = SettingsShape.CornerMedium

    /** Large preview surfaces such as the boot animation viewport: 16dp. */
    val preview = SettingsShape.CornerLarge1
}

/**
 * Row padding that reproduces the XML expressive preference: 16dp at the sides and 16dp above and
 * below the text frame, inside a 72dp minimum height ([SettingsDimension.preferenceMinHeight]).
 */
internal val ExpressiveRowPadding = PaddingValues(
    horizontal = SettingsSpace.small1,
    vertical = SettingsSpace.small1,
)

/**
 * Shapes for row [index] of [count], with SettingsLib's 20dp / 4dp corners. The stock
 * [ListItemDefaults.segmentedShapes] uses 16dp outer corners and returns the 4dp inner shape for
 * `count == 1`, neither of which matches the XML preference screens. A pressed row morphs to the
 * standalone shape, as in stock Material 3 Expressive.
 */
@Composable
fun expressiveRowShapes(index: Int, count: Int): ListItemShapes {
    val resting = when {
        count <= 1 -> ExpressiveCorners.single
        index == 0 -> ExpressiveCorners.first
        index == count - 1 -> ExpressiveCorners.last
        else -> ExpressiveCorners.middle
    }
    return ListItemDefaults.shapes(
        shape = resting,
        selectedShape = resting,
        pressedShape = ExpressiveCorners.single,
        focusedShape = resting,
        hoveredShape = resting,
    )
}

/** Menu group shapes with the XML spinner popup's corners, for every Expressive dropdown. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun expressiveMenuGroupShapes(): MenuGroupShapes =
    MenuDefaults.groupShape(index = 0, count = 1).copy(
        shape = ExpressiveCorners.popup,
        inactiveShape = ExpressiveCorners.popup,
    )

/** Builder passed to [ExpressiveGroup]; every [item] becomes one segment of the group. */
class ExpressiveGroupScope internal constructor() {
    internal val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items += content
    }
}

/**
 * A titled group of rows: [CategoryTitle] above a column of segmented rows (large outer corners,
 * small inner corners, 2dp gaps). Each row is declared with [ExpressiveGroupScope.item] so the
 * group knows its index and count and can hand every row the right
 * [ListItemDefaults.segmentedShapes]. When [collapsible] is set the title doubles as an
 * expand/collapse toggle and the state survives configuration changes.
 */
@Composable
fun ExpressiveGroup(
    title: String,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    horizontalInset: Dp = SettingsSpace.small1,
    content: ExpressiveGroupScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val items = ExpressiveGroupScope().apply(content).items
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
                    .insetFocusRing(MaterialTheme.shapes.medium)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) { CategoryTitle(title) }
                ExpressiveExpandIcon(expanded)
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
                        LocalExpressiveRowShapes provides
                            expressiveRowShapes(index = index, count = items.size),
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
private fun ExpressiveListItem(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    overline: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
) {
    val shapes = currentExpressiveRowShapes()
    val container = MaterialTheme.colorScheme.surfaceBright
    val trailingFrame: (@Composable () -> Unit)? = trailing?.let { t ->
        { ExpressiveTrailingFrame(t) }
    }
    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            modifier = modifier
                .fillMaxWidth()
                .insetFocusRing(shapes.shape)
                .heightIn(min = SettingsDimension.preferenceMinHeight),
            enabled = enabled,
            colors = ListItemDefaults.colors(containerColor = container),
            leadingContent = leading?.let { l -> { l() } },
            trailingContent = trailingFrame,
            overlineContent = overline,
            supportingContent = supporting,
            // The stock default top-aligns trailing content on tall rows; the XML rows never do.
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = ExpressiveRowPadding,
            content = headline,
        )
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                // alpha23's static SegmentedListItem merges its children into one node and
                // reports itself disabled, so TalkBack reads the row as a unit.
                .semantics(mergeDescendants = true) { if (!enabled) disabled() }
                .alpha(if (enabled) 1f else 0.38f),
            shape = shapes.shape,
            color = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = SettingsDimension.preferenceMinHeight)
                    .padding(ExpressiveRowPadding),
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
                if (trailingFrame != null) {
                    Spacer(Modifier.width(SettingsSpace.small1))
                    trailingFrame()
                }
            }
        }
    }
}

/**
 * Trailing slot with the footprint of SettingsLib's preference widget frame: at least 48dp wide,
 * content centred. Switches (52dp) fill it, so chevrons, dropdown arrows and chips land on the
 * same centre line as the switches in the rows around them.
 */
@Composable
private fun ExpressiveTrailingFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .trailingWidthCap()
            .widthIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Caps trailing content at half of the width it is offered. Rows measure their trailing content
 * first and hand the title what is left, so an uncapped chip squeezes the title to one letter
 * per line.
 */
internal fun Modifier.trailingWidthCap(): Modifier = layout { measurable, constraints ->
    val capped = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = maxOf(constraints.minWidth, constraints.maxWidth / 2))
    } else {
        constraints
    }
    val placeable = measurable.measure(capped)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Base row: surfaceBright segment, optional leading icon, title + summary and an optional
 * trailing slot. Disabled rows are dimmed and not clickable.
 */
@Composable
fun ExpressiveRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ExpressiveListItem(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        leading = leading ?: icon?.let { vector ->
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

/** Navigation row: [ExpressiveRow] with a trailing chevron, or a [chip] when one is supplied. */
@Composable
fun ExpressiveNavRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    chip: String? = null,
    chipTone: ExpressiveTone = ExpressiveTone.Neutral,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ExpressiveRow(
        title = title,
        modifier = modifier,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        trailing = {
            if (chip != null) {
                ExpressiveStatusChip(chip, chipTone)
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
fun ExpressiveValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    ExpressiveListItem(
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
fun ExpressiveStatusChip(
    text: String,
    tone: ExpressiveTone = ExpressiveTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ExpressiveTone.Positive -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ExpressiveTone.Warning -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ExpressiveTone.Negative -> scheme.errorContainer to scheme.onErrorContainer
        ExpressiveTone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
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
fun ExpressivePrimaryAction(
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
            .then(if (inset) Modifier.expressiveInset() else Modifier)
            .fillMaxWidth()
            .insetFocusRing(SettingsShape.CornerFull)
            .heightIn(min = ButtonDefaults.MediumContainerHeight),
        enabled = enabled && !loading,
        shapes = expressiveButtonShapes(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        val height = ButtonDefaults.MediumContainerHeight
        val iconSize = ButtonDefaults.iconSizeFor(height)
        if (loading) {
            LoadingIndicator(modifier = Modifier.size(iconSize))
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        }
        Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
        Text(text = text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

data class ExpressiveAction(
    val text: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    /**
     * Null for a plain action. Non-null makes the pill a [TonalToggleButton] that is checked when
     * true (check icon, primary container, morphed shape) and exposes its state to accessibility.
     */
    val selected: Boolean? = null,
    val onClick: () -> Unit,
)

/** Equal-width row of tonal pill buttons ("Import", "Delete", "Reset"). */
@Composable
fun ExpressiveActionRow(
    actions: List<ExpressiveAction>,
    modifier: Modifier = Modifier,
    inset: Boolean = true,
) {
    Row(
        modifier = modifier
            .then(if (inset) Modifier.expressiveInset() else Modifier)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4),
    ) {
        actions.forEach { action ->
            val checked = action.selected
            val iconSize = ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)
            val iconSpacing = ButtonDefaults.iconSpacingFor(ButtonDefaults.MinHeight)
            val label: @Composable RowScope.() -> Unit = {
                Icon(
                    imageVector = if (checked == true) Icons.Rounded.Check else action.icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
                Spacer(Modifier.width(iconSpacing))
                Text(
                    text = action.text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (checked != null) {
                val shapes = ToggleButtonDefaults.shapes()
                TonalToggleButton(
                    checked = checked,
                    onCheckedChange = { action.onClick() },
                    enabled = action.enabled,
                    modifier = Modifier
                        .weight(1f)
                        .insetFocusRing(if (checked) shapes.checkedShape else shapes.shape)
                        .heightIn(min = 48.dp),
                    shapes = shapes,
                    colors = ToggleButtonDefaults.tonalToggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = SettingsSpace.extraSmall6),
                    content = label,
                )
            } else {
                FilledTonalButton(
                    onClick = action.onClick,
                    enabled = action.enabled,
                    modifier = Modifier
                        .weight(1f)
                        .insetFocusRing(SettingsShape.CornerFull)
                        .heightIn(min = 48.dp),
                    shapes = expressiveButtonShapes(),
                    contentPadding = PaddingValues(horizontal = SettingsSpace.extraSmall6),
                    content = label,
                )
            }
        }
    }
}

/**
 * Expressive text button: [TextButton] with the pressed-shape morph of [ButtonDefaults.shapes].
 * Used for every dialog action and inline text action on the Expressive screens.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val shapes = expressiveButtonShapes()
    TextButton(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier.insetFocusRing(shapes.shape, ringHeight = ButtonDefaults.MinHeight),
        enabled = enabled,
        colors = colors,
        content = content,
    )
}

/**
 * Shared dialog for every Expressive screen: SPA dialog width, the same extra-large corners as the
 * cards and a surfaceContainerHigh container so [ExpressiveGroup] rows and [ExpressiveTextField]s
 * (surfaceBright / surfaceContainerHighest) stay visible inside it. Pass [properties] to make a
 * dialog non-cancelable (`dismissOnBackPress = false`, `dismissOnClickOutside = false`); keep
 * `usePlatformDefaultWidth = false` so the SPA width applies.
 */
@Composable
fun ExpressiveDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
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
        properties = properties,
    )
}

/** Confirmation dialog sized and shaped like SPA's `SettingsAlertDialog`. */
@Composable
fun ExpressiveConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String = stringResource(android.R.string.cancel),
    destructive: Boolean = false,
) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.expressiveVerticalScroll()) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            ExpressiveTextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmText) }
        },
        dismissButton = { ExpressiveTextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

/**
 * Navigation row with its own switch: tapping the row opens [onClick], the switch (behind a
 * divider, like SettingsLib's primary-switch preference) toggles the feature in place.
 */
@Composable
fun ExpressiveSwitchNavRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveRow(
        title = title,
        modifier = modifier,
        summary = summary,
        onClick = onClick,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VerticalDivider(modifier = Modifier.height(SettingsDimension.itemDividerHeight))
                Spacer(Modifier.width(SettingsSpace.extraSmall6))
                CheckCloseSwitch(checked = checked, onCheckedChange = onCheckedChange)
            }
        },
    )
}

/**
 * Switch row: a regular [ExpressiveRow] (a surfaceBright segment of the enclosing
 * [ExpressiveGroup]) with the shared check/close switch as the trailing slot.
 */
@Composable
fun ExpressiveSwitchRow(
    titleText: String,
    summaryText: String,
    isChecked: Boolean,
    onChange: (Boolean) -> Unit,
    isEnabled: Boolean = true,
) {
    ExpressiveRow(
        title = titleText,
        summary = summaryText,
        enabled = isEnabled,
        onClick = { onChange(!isChecked) },
        trailing = {
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
        ExpressiveActionRow(
            actions = listOf(
                ExpressiveAction(
                    text = stringResource(R.string.battery_usage_system_apps),
                    icon = Icons.Rounded.PhoneAndroid,
                    enabled = enabled,
                    selected = showSystemApps,
                    onClick = onToggleSystemApps,
                ),
                ExpressiveAction(
                    text = stringResource(R.string.action_select_all),
                    icon = Icons.Rounded.DoneAll,
                    enabled = enabled,
                    onClick = onSelectAll,
                ),
            ),
            inset = false,
        )
        ExpressiveActionRow(
            actions = listOf(
                ExpressiveAction(
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
fun expressiveTextFieldColors(
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

/**
 * Rounded filled text field used by the Expressive dialogs in place of `OutlinedTextField`.
 *
 * It keeps the row radius and a [expressiveTextFieldColors] container on purpose instead of
 * alpha23's `TextFieldDefaults.roundedShape` (12dp) and `tonalColors()` (a surfaceContainer
 * fill, which disappears inside the surfaceContainerHigh dialog). A hint goes in [placeholder];
 * [label] floats above the text. Pass [shape] and [containerColor] for a variant such as the
 * pill-shaped search field; the focus ring follows [shape]. [readOnly] and [prefix] cover display-only
 * values such as a hex colour.
 */
@Composable
fun ExpressiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    isError: Boolean = false,
    supportingText: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: String? = null,
    shape: Shape = ExpressiveCorners.single,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().insetFocusRing(shape),
        enabled = enabled,
        readOnly = readOnly,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it) } },
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else maxLines,
        shape = shape,
        colors = expressiveTextFieldColors(containerColor),
    )
}

/**
 * Single-choice row for profile pickers: a [ExpressiveRow] that shows a check when [selected].
 * Extra [trailing] content (e.g. edit/delete buttons) is drawn before the check.
 */
@Composable
fun ExpressiveChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ExpressiveRow(
        title = title,
        modifier = modifier,
        summary = summary,
        onClick = onClick,
        leading = leading,
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
