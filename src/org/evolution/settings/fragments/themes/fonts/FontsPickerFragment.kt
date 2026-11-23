/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.android.settings.R
import com.android.settings.widget.scrollbar
import com.android.settingslib.spa.framework.theme.SettingsTheme
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.lockscreen.ComposeToViewNestedScrollBridge
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.insetFocusRing

/**
 * The font picker: a pinned lock screen preview that is drawn in whichever font is tapped, over a
 * grid of fonts. Tapping a card only previews it; Apply in the preview switches the system font.
 */
class FontsPickerFragment : Fragment() {

    private lateinit var controller: FontController
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    private val options = mutableStateOf<List<FontOption>>(emptyList())
    private val appliedKey = mutableStateOf("android")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().setTitle(R.string.font_styles_title)
        controller = FontController(requireContext())
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { controller.importFont(it, lifecycleScope, ::reload) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        isNestedScrollingEnabled = true
        setContent {
            SettingsTheme {
                FontsPickerScreen(
                    options = options.value,
                    appliedKey = appliedKey.value,
                    onApply = { option ->
                        controller.apply(
                            requireContext(), appliedKey.value, option.key, ::reload
                        )
                    },
                    onAddFont = { importLauncher.launch(arrayOf("font/ttf", "font/otf")) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val (list, key) = withContext(Dispatchers.IO) {
                controller.loadOptions() to controller.appliedKey()
            }
            options.value = list
            appliedKey.value = key
        }
    }
}

@Composable
private fun FontsPickerScreen(
    options: List<FontOption>,
    appliedKey: String,
    onApply: (FontOption) -> Unit,
    onAddFont: () -> Unit
) {
    var previewKey by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = options.firstOrNull { it.key == (previewKey ?: appliedKey) } ?: options.firstOrNull()
        ?: return

    val hostView = LocalView.current
    val nestedScrollInterop = remember(hostView) { ComposeToViewNestedScrollBridge(hostView) }
    DisposableEffect(nestedScrollInterop) {
        onDispose { nestedScrollInterop.stop() }
    }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = Modifier.fillMaxSize()) {
        FontStage(
            option = shown,
            applied = shown.key == appliedKey,
            canApply = shown.key != appliedKey && shown.key != FontController.CUSTOM_KEY,
            onApply = { onApply(shown) }
        )

        val gridState = rememberLazyGridState()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = 4.dp, bottom = navBarBottom + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollInterop)
                .scrollbar(gridState.scrollIndicatorState, Orientation.Vertical)
        ) {
            items(options, key = { it.key }) { option ->
                FontCard(
                    option = option,
                    previewed = option.key == shown.key,
                    applied = option.key == appliedKey,
                    onClick = { previewKey = option.key }
                )
            }
            item(key = "add_font") { AddFontCard(onClick = onAddFont) }
        }
    }
}

/** The pinned lock screen preview, kept as plain views like the other preview stages. */
@Composable
private fun FontStage(
    option: FontOption,
    applied: Boolean,
    canApply: Boolean,
    onApply: () -> Unit
) {
    val latestOnApply by rememberUpdatedState(onApply)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.font_preview, null).also { view ->
                view.findViewById<TextView>(R.id.font_preview_date).text =
                    DateFormat.format(
                        DateFormat.getBestDateTimePattern(
                            ctx.resources.configuration.locales[0], "EEEMMMd"
                        ),
                        Date()
                    )
                view.findViewById<TextView>(R.id.font_preview_body)
                    .setText(R.string.font_preview_quote)
                view.findViewById<TextView>(R.id.font_preview_apply)
                    .setOnClickListener { latestOnApply() }
            }
        },
        update = { view ->
            val typeface = option.typeface ?: Typeface.DEFAULT
            view.findViewById<TextView>(R.id.font_preview_clock).typeface = typeface
            view.findViewById<TextView>(R.id.font_preview_date).typeface = typeface
            view.findViewById<TextView>(R.id.font_preview_body).typeface = typeface
            view.findViewById<TextView>(R.id.font_preview_title).apply {
                this.typeface = Typeface.create(typeface, Typeface.BOLD)
                text = option.label
            }
            view.findViewById<TextView>(R.id.font_preview_apply).apply {
                setText(if (applied) R.string.font_applied else R.string.apply)
                isSelected = canApply
                isEnabled = canApply
                alpha = if (canApply) 1f else 0.6f
            }
        }
    )
}

@Composable
private fun FontCard(
    option: FontOption,
    previewed: Boolean,
    applied: Boolean,
    onClick: () -> Unit
) {
    val family = remember(option.typeface) { option.typeface?.let { FontFamily(it) } }
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .insetFocusRing(ExpressiveCorners.single)
            .semantics { selected = previewed },
        shape = ExpressiveCorners.single,
        border = if (previewed) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (applied) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = stringResource(R.string.font_sample_glyphs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = family,
                    modifier = Modifier.weight(1f)
                )
                if (applied) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = family,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddFontCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .insetFocusRing(ExpressiveCorners.single),
        shape = ExpressiveCorners.single,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.add_font),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
