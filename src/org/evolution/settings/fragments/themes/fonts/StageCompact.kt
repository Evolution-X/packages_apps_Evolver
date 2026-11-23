/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.animation.ValueAnimator
import android.view.View
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.settings.R

/**
 * Whether the pinned stage should be shrunk: true once [state] has scrolled past about 48dp,
 * false again only back at the very top, so it doesn't flicker around the threshold.
 */
@Composable
internal fun rememberCompactOnScroll(state: LazyGridState): Boolean {
    var compact by remember { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 48.dp.roundToPx() }
    LaunchedEffect(state, threshold) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index > 0 || offset > threshold) {
                    compact = true
                } else if (offset == 0) {
                    compact = false
                }
            }
    }
    return compact
}

/** Shrinks the font preview stage to its clock rows, or restores it. */
internal fun View.setStageCompact(compact: Boolean) {
    val compactor = getTag(R.id.font_preview_stage) as? StageCompactor
        ?: StageCompactor(
            findViewById(R.id.font_preview_stage),
            findViewById(R.id.font_preview_notification)
        ).also { setTag(R.id.font_preview_stage, it) }
    compactor.set(compact)
}

/**
 * Resizes the stage frame by frame and fades the notification out, the way Remixer's font page
 * does. A layout transition would shrink the stage after the grid under it had already moved.
 */
private class StageCompactor(private val stage: View, private val notification: View) {
    private val regular = stage.resources.getDimensionPixelSize(R.dimen.preview_stage_height_regular)
    private val compact = stage.resources.getDimensionPixelSize(R.dimen.preview_stage_height_compact)
    private var fraction = 0f
    private var target = false
    private var animator: ValueAnimator? = null

    fun set(compact: Boolean) {
        if (compact == target) return
        target = compact
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fraction, if (compact) 1f else 0f).apply {
            duration = DURATION_MS
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
    }

    private fun apply(value: Float) {
        fraction = value
        stage.layoutParams = stage.layoutParams.apply {
            height = Math.round(regular + (compact - regular) * value)
        }
        notification.alpha = 1f - value
        notification.visibility = if (value == 1f) View.INVISIBLE else View.VISIBLE
    }

    private companion object {
        const val DURATION_MS = 220L
    }
}
