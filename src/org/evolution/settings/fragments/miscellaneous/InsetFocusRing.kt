/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.settings.widget.insetFocusRing as settingsInsetFocusRing

/**
 * Material 3 1.5.0-alpha23 inset focus ring on alpha16. The implementation lives in Settings
 * (com.android.settings.widget.InsetFocusRing) and is shared with the model page; see there for
 * the details. Kept here so the spoofing screens call it unchanged.
 */
fun Modifier.insetFocusRing(
    shape: Shape,
    ringWidth: Dp = Dp.Unspecified,
    ringHeight: Dp = Dp.Unspecified,
): Modifier = settingsInsetFocusRing(shape, ringWidth, ringHeight)

/**
 * Focus ring for a stock [androidx.compose.material3.Switch]. The 52x32 track sits inside a
 * 48dp-tall touch target, so the ring is centred on the track.
 */
fun Modifier.switchFocusRing(): Modifier =
    insetFocusRing(CircleShape, ringWidth = 52.dp, ringHeight = 32.dp)

/**
 * Focus ring for a stock [androidx.compose.material3.IconButton]. The button pads its touch target
 * to 48dp but draws a 40dp round container, so the ring is centred on the container.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.iconButtonFocusRing(): Modifier {
    val size = IconButtonDefaults.smallContainerSize()
    return insetFocusRing(IconButtonDefaults.standardShape, size.width, size.height)
}

/**
 * Focus ring for a stock [androidx.compose.material3.RadioButton]. Alpha23 attaches its circular
 * ring to the 48dp touch target, not to the 20dp radio drawn inside it, so the ring is a 48dp
 * circle. It is clamped to the component's bounds, so a radio laid out smaller keeps a circle.
 */
fun Modifier.radioButtonFocusRing(): Modifier =
    insetFocusRing(CircleShape, ringWidth = 48.dp, ringHeight = 48.dp)
