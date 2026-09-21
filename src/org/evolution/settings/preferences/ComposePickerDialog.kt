/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.settings.widget.scrollbar
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.ExpressiveDialog
import org.evolution.settings.fragments.miscellaneous.ExpressiveTextButton
import org.evolution.settings.fragments.miscellaneous.ExpressiveTheme
import org.evolution.settings.fragments.miscellaneous.insetFocusRing

private const val TAG = "ComposePickerDialog"

/** Handle for a dialog opened with [showComposeDialog]. */
class ComposeDialogHandle internal constructor(
    private val host: ViewGroup,
    private val view: View,
) {
    /** Closes the dialog. Safe to call more than once. */
    fun dismiss() {
        if (view.parent === host) host.removeView(view)
    }
}

/**
 * Shows Compose [content] (normally a Material 3 `AlertDialog`) from a View-based preference.
 *
 * The composition is hosted by a zero-sized [ComposeView] on the activity's decor view, which
 * already carries the lifecycle and saved-state owners Compose needs. The `AlertDialog` opens its
 * own window from there. [ComposeDialogHandle.dismiss] removes the host, which disposes the
 * composition and closes the dialog. Returns null if [Context] isn't backed by an activity.
 */
fun Context.showComposeDialog(
    content: @Composable (dismiss: () -> Unit) -> Unit,
): ComposeDialogHandle? {
    val activity = findActivity()
    if (activity == null) {
        Log.w(TAG, "No activity behind $this, can't show a Compose dialog")
        return null
    }
    val host = activity.window.decorView as ViewGroup
    val view = ComposeView(activity)
    val handle = ComposeDialogHandle(host, view)
    view.setContent { ExpressiveTheme { content { handle.dismiss() } } }
    host.addView(view, ViewGroup.LayoutParams(0, 0))
    return handle
}

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * One row of a picker. [icon] is drawn in the row's content colour before the label, [trailing]
 * after it (a preview button, say), and [labelFontFamily] renders the label in its own font. It
 * is a provider so a long list only loads the fonts that scroll into view.
 */
class PickerOption(
    val label: String,
    val labelFontFamily: (() -> FontFamily?)? = null,
    val trailing: (@Composable () -> Unit)? = null,
    val icon: (@Composable () -> Unit)? = null,
)

/** Extra text button next to Cancel, like "Add font". It closes the picker, then runs [onClick]. */
class PickerAction(val label: String, val onClick: () -> Unit)

/**
 * Picker dialog: [options] in a scrollable list (two columns in landscape) with the current one
 * highlighted. [onSelect] returns true to close the dialog, false to keep it open. [action] adds
 * a button before Cancel, and [onClosed] runs however the dialog goes away.
 */
fun Context.showPickerDialog(
    title: CharSequence?,
    options: List<PickerOption>,
    selectedIndex: Int,
    action: PickerAction? = null,
    onClosed: () -> Unit = {},
    onSelect: (Int) -> Boolean,
): ComposeDialogHandle? = showComposeDialog { dismiss ->
    DisposableEffect(Unit) { onDispose(onClosed) }
    PickerDialog(
        title = title?.toString(),
        options = options,
        selectedIndex = selectedIndex,
        action = action,
        onSelect = { index -> if (onSelect(index)) dismiss() },
        onDismiss = dismiss,
    )
}

/**
 * Two-choice prompt for View-based preferences. Unlike [ExpressiveConfirmDialog] the second button
 * does something ([onLater]); tapping outside runs neither.
 */
fun Context.showChoiceDialog(
    title: String,
    message: String,
    confirmText: String,
    laterText: String,
    onConfirm: () -> Unit,
    onLater: () -> Unit,
): ComposeDialogHandle? = showComposeDialog { dismiss ->
    ExpressiveDialog(
        onDismissRequest = dismiss,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text = message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            ExpressiveTextButton(onClick = { dismiss(); onConfirm() }) { Text(confirmText) }
        },
        dismissButton = {
            ExpressiveTextButton(onClick = { dismiss(); onLater() }) { Text(laterText) }
        },
    )
}

@Composable
private fun PickerDialog(
    title: String?,
    options: List<PickerOption>,
    selectedIndex: Int,
    action: PickerAction?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val maxHeight = (configuration.screenHeightDp * if (landscape) 0.75f else 0.6f).dp
    val firstVisible = selectedIndex.coerceAtLeast(0)
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = if (landscape) firstVisible - firstVisible % 2 else firstVisible,
    )

    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(text = it, style = MaterialTheme.typography.headlineSmall) } },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (landscape) 2 else 1),
                state = gridState,
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .scrollbar(gridState.scrollIndicatorState, Orientation.Vertical),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(options) { index, option ->
                    PickerOptionRow(
                        option = option,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
        },
        confirmButton = {
            ExpressiveTextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        dismissButton = action?.let { extra ->
            {
                ExpressiveTextButton(onClick = { onDismiss(); extra.onClick() }) {
                    Text(extra.label)
                }
            }
        },
    )
}

@Composable
private fun PickerOptionRow(option: PickerOption, selected: Boolean, onClick: () -> Unit) {
    val shape = ExpressiveCorners.single
    val colors = MaterialTheme.colorScheme
    val contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .insetFocusRing(shape)
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            option.icon?.let { icon ->
                Box(
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { icon() }
                Spacer(Modifier.size(12.dp))
            }
            val family = remember(option) { option.labelFontFamily?.invoke() }
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = family,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = if (option.icon == null) 12.dp else 0.dp),
            )
            option.trailing?.invoke()
        }
    }
}

/** The current time as the clock previews show it: hh:mm, or kk:mm with a 24-hour clock. */
fun pickerClockText(context: Context): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "kk:mm" else "hh:mm"
    return DateFormat.format(pattern, System.currentTimeMillis()).toString()
}

/** A 32dp [drawable] tinted with the current content colour. Draws nothing for null. */
@Composable
fun PickerDrawableIcon(drawable: Drawable?) {
    val px = with(LocalDensity.current) { 32.dp.roundToPx() }
    val bitmap = remember(drawable, px) {
        drawable?.let { runCatching { it.toBitmap(px, px).asImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            colorFilter = ColorFilter.tint(LocalContentColor.current),
        )
    } else {
        Spacer(Modifier.size(32.dp))
    }
}
