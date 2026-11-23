/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.content.res.Resources
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.android.settings.R
import com.android.settings.widget.scrollbar
import com.android.settingslib.spa.framework.theme.SettingsTheme
import java.io.File
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.lockscreen.ComposeToViewNestedScrollBridge
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.insetFocusRing

/** Where the download of the selected style stands, for the Apply button and the preview. */
private sealed interface LoadState {
    object Idle : LoadState
    class Loading(val progress: Float) : LoadState
    class Ready(val file: File, val typeface: Typeface) : LoadState
    object Failed : LoadState
}

/**
 * Browses Google Fonts on the same pinned lock screen preview as the font page. Tapping a card
 * downloads that family's default style, shows it in the preview, and offers the other styles as
 * chips; Apply installs the style as the custom font, which needs a reboot.
 */
class OnlineFontPickerFragment : Fragment() {

    private lateinit var fonts: FontController
    private lateinit var repo: OnlineFontRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fonts = FontController(requireContext())
        repo = OnlineFontRepository(requireContext())
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
                OnlineFontPickerScreen(
                    repo = repo,
                    onInstall = { file ->
                        fonts.installFile(file, this@OnlineFontPickerFragment.lifecycleScope) {}
                    }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        repo.trimCache()
    }
}

@Composable
private fun OnlineFontPickerScreen(repo: OnlineFontRepository, onInstall: (File) -> Unit) {
    var families by remember { mutableStateOf<List<OnlineFont>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var listAttempt by remember { mutableIntStateOf(0) }
    var downloadAttempt by remember { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var variant by rememberSaveable { mutableStateOf<String?>(null) }
    val load = remember { mutableStateOf<LoadState>(LoadState.Idle) }

    // The saved list shows at once; a missing or day-old one is fetched behind it.
    LaunchedEffect(listAttempt) {
        failed = false
        repo.resetFailures()
        val saved = repo.savedFamilies()
        if (saved != null) families = saved
        if (saved == null || repo.familiesStale()) {
            try {
                families = repo.fetchFamilies()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (saved == null) failed = true
            }
        }
    }

    val list = families
    val font = list?.firstOrNull { it.family == selected }
    val style = font?.let { f -> variant?.takeIf { it in f.variants } ?: f.defaultVariant }

    // Starts over when another family or style is picked, which cancels a download in progress.
    LaunchedEffect(font?.family, style, downloadAttempt) {
        val f = font
        val v = style
        if (f == null || v == null) {
            load.value = LoadState.Idle
            return@LaunchedEffect
        }
        load.value = LoadState.Loading(0f)
        try {
            val file = repo.download(f, v) { load.value = LoadState.Loading(it) }
            val typeface = withContext(Dispatchers.IO) { repo.load(file) }
            load.value = if (typeface != null) LoadState.Ready(file, typeface) else LoadState.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            load.value = LoadState.Failed
        }
    }

    val context = LocalContext.current
    val categories = remember { OnlineFontCategory.values().toList() }
    val categoryLabels = remember { categories.map { context.getString(it.labelRes) } }
    val visible = remember(list, category, query) {
        val api = categories[category].apiName
        val needle = query.trim()
        list.orEmpty().filter {
            (api == null || it.category == api) &&
                (needle.isEmpty() || it.family.contains(needle, ignoreCase = true))
        }
    }

    val hostView = LocalView.current
    val nestedScrollInterop = remember(hostView) { ComposeToViewNestedScrollBridge(hostView) }
    DisposableEffect(nestedScrollInterop) {
        onDispose { nestedScrollInterop.stop() }
    }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = Modifier.fillMaxSize()) {
        OnlineStage(
            font = font,
            state = load,
            previewTypeface = font?.let { repo.cachedPreview(it) },
            styles = font?.variants.orEmpty(),
            style = style,
            onStyle = { variant = it },
            categories = categoryLabels,
            category = category,
            onCategory = { category = it },
            onApply = {
                when (val state = load.value) {
                    is LoadState.Ready -> onInstall(state.file)
                    LoadState.Failed -> downloadAttempt++
                    else -> Unit
                }
            }
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
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.font_online_search)) }
                )
            }
            when {
                failed && list == null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.font_online_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { listAttempt++ }) {
                            Text(stringResource(R.string.font_online_retry))
                        }
                    }
                }
                list == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.font_online_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                visible.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.font_online_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
                else -> items(visible, key = { it.family }) { option ->
                    OnlineFontCard(
                        font = option,
                        repo = repo,
                        previewed = option.family == font?.family,
                        onClick = {
                            selected = option.family
                            variant = null
                        }
                    )
                }
            }
        }
    }
}

/**
 * The pinned lock screen preview on the font page's layout, without its tab chips: category
 * chips under it, and once a family is picked a chip per style of it.
 */
@Composable
private fun OnlineStage(
    font: OnlineFont?,
    state: State<LoadState>,
    previewTypeface: Typeface?,
    styles: List<String>,
    style: String?,
    onStyle: (String) -> Unit,
    categories: List<String>,
    category: Int,
    onCategory: (Int) -> Unit,
    onApply: () -> Unit
) {
    val latestOnApply by rememberUpdatedState(onApply)
    val latestOnStyle by rememberUpdatedState(onStyle)
    val latestOnCategory by rememberUpdatedState(onCategory)
    val latestStyles by rememberUpdatedState(styles)
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
                view.findViewById<View>(R.id.font_tabs).visibility = View.GONE
                view.findViewById<TextView>(R.id.font_preview_apply)
                    .setOnClickListener { latestOnApply() }
            }
        },
        update = { view ->
            // Read here, so the progress only redraws the preview and not the grid.
            val load = state.value
            val typeface = (load as? LoadState.Ready)?.typeface ?: previewTypeface ?: Typeface.DEFAULT
            view.findViewById<TextView>(R.id.font_preview_clock).typeface = typeface
            view.findViewById<TextView>(R.id.font_preview_date).typeface = typeface
            view.findViewById<TextView>(R.id.font_preview_title).apply {
                this.typeface = Typeface.create(typeface, Typeface.BOLD)
                text = font?.family ?: context.getString(R.string.font_online_title)
            }
            view.findViewById<TextView>(R.id.font_preview_body).apply {
                this.typeface = typeface
                setText(R.string.font_preview_quote)
            }
            updateChips(
                view.findViewById(R.id.font_filter_scroll),
                view.findViewById(R.id.font_filter_chips),
                categories,
                category
            ) { latestOnCategory(it) }
            updateChips(
                view.findViewById(R.id.font_style_scroll),
                view.findViewById(R.id.font_style_chips),
                styles.map { styleLabel(view.resources, it) },
                styles.indexOf(style)
            ) { latestOnStyle(latestStyles[it]) }
            view.findViewById<TextView>(R.id.font_preview_apply).apply {
                val ready = load is LoadState.Ready
                text = when (load) {
                    is LoadState.Loading ->
                        context.getString(R.string.font_online_progress, (load.progress * 100).toInt())
                    LoadState.Failed -> context.getString(R.string.font_online_retry)
                    else -> context.getString(R.string.apply)
                }
                val enabled = ready || load == LoadState.Failed
                isSelected = enabled
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.6f
            }
        }
    )
}

@Composable
private fun OnlineFontCard(
    font: OnlineFont,
    repo: OnlineFontRepository,
    previewed: Boolean,
    onClick: () -> Unit
) {
    // The family name and "Aa" in the family itself, fetched when the card scrolls into view.
    val typeface by produceState<Typeface?>(repo.cachedPreview(font), font.family) {
        value = repo.preview(font) ?: value
    }
    val family = remember(typeface) { typeface?.let { FontFamily(it) } }
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text(
                    text = stringResource(R.string.font_sample_glyphs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = family,
                    maxLines = 1
                )
            }
            Text(
                text = font.family,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = family,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** "Regular", "Bold Italic", and so on, for a style such as "400" or "700i". */
private fun styleLabel(res: Resources, variant: String): String {
    val names = res.getStringArray(R.array.font_online_weight_names)
    val name = names[(variant.weight() / 100 - 1).coerceIn(0, names.lastIndex)]
    return if (variant.isItalic()) res.getString(R.string.font_online_style_italic, name) else name
}

/**
 * One chip per label in [group], hidden with its [scroll] when there is nothing to choose.
 * Chips are only rebuilt when the labels change, not on every selection.
 */
private fun updateChips(
    scroll: View,
    group: LinearLayout,
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    scroll.visibility = if (labels.size > 1) View.VISIBLE else View.GONE
    if (group.tag != labels) {
        group.tag = labels
        group.removeAllViews()
        val res = group.resources
        val inflater = LayoutInflater.from(group.context)
        labels.forEachIndexed { index, label ->
            val chip = inflater.inflate(R.layout.charging_style_chip, group, false) as TextView
            chip.text = label
            chip.setOnClickListener { onSelect(index) }
            group.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    res.getDimensionPixelSize(R.dimen.charging_chip_height)
                ).apply { marginEnd = res.getDimensionPixelSize(R.dimen.preview_stage_gap) }
            )
        }
    }
    for (i in 0 until group.childCount) group.getChildAt(i).isSelected = i == selected
}
