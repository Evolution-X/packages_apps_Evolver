/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes.fonts

import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.settings.R
import com.android.settings.core.SubSettingLauncher
import com.android.settings.widget.scrollbar
import com.android.settingslib.spa.framework.theme.SettingsTheme
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.lockscreen.ClockUtils
import org.evolution.settings.fragments.lockscreen.ComposeToViewNestedScrollBridge
import org.evolution.settings.fragments.miscellaneous.ExpressiveCorners
import org.evolution.settings.fragments.miscellaneous.insetFocusRing
import org.evolution.settings.fragments.miscellaneous.rememberClickSound

private const val TAB_FONTS = 0
private const val TAB_EMOJI = 1
private const val MAX_SAMPLE_LENGTH = 40

/** What one tab lists, and which of it is applied. */
private class TabData(val options: List<StyleOption>, val appliedKey: String)

/**
 * The font and emoji picker: a pinned lock screen preview drawn in whichever style is tapped,
 * with a chip per tab, over a grid of styles. Tapping a card only previews it; Apply in the
 * preview switches the system style. The "tab" argument ("emoji") picks the tab it opens on.
 */
class FontEmojiPickerFragment : Fragment() {

    private lateinit var fonts: FontController
    private lateinit var emoji: EmojiController
    private lateinit var fontLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var emojiLauncher: ActivityResultLauncher<Array<String>>

    private val fontData = mutableStateOf(TabData(emptyList(), "android"))
    private val emojiData = mutableStateOf(TabData(emptyList(), "android"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startOnEmoji = arguments?.getString(ARG_TAB) == "emoji"
        requireActivity().setTitle(
            if (startOnEmoji) R.string.emoji_style_title else R.string.font_styles_title
        )
        fonts = FontController(requireContext())
        emoji = EmojiController(requireContext())
        fontLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { fonts.importFont(it, lifecycleScope, ::reload) }
        }
        emojiLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { emoji.importEmoji(it, lifecycleScope, ::reload) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        val startTab = if (arguments?.getString(ARG_TAB) == "emoji") TAB_EMOJI else TAB_FONTS
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        isNestedScrollingEnabled = true
        setContent {
            SettingsTheme {
                FontEmojiPickerScreen(
                    startTab = startTab,
                    fonts = fontData.value,
                    emoji = emojiData.value,
                    fontBusy = fonts.busy,
                    onApplyFont = { option ->
                        fonts.apply(
                            requireContext(),
                            fontData.value.appliedKey,
                            option.key,
                            option.label,
                            ::reload
                        )
                    },
                    onApplyEmoji = { option ->
                        emoji.apply(
                            requireContext(),
                            option.key,
                            hadCustom = emojiData.value.appliedKey == EmojiController.CUSTOM_KEY,
                            onChanged = ::reload
                        )
                    },
                    onAddFont = { if (!fonts.busy) fontLauncher.launch(FONT_TYPES) },
                    onBrowseOnline = {
                        SubSettingLauncher(requireContext())
                            .setDestination(OnlineFontPickerFragment::class.java.name)
                            .setTitleRes(R.string.font_online_title)
                            .setSourceMetricsCategory(MetricsEvent.EVOLVER)
                            .launch()
                    },
                    onAddEmoji = { if (emoji.ensureIdle()) emojiLauncher.launch(EMOJI_TYPES) },
                    onEmojiApps = { hasCustom ->
                        if (hasCustom) emoji.confirmResetApps(requireContext())
                        else emoji.confirmRestoreApps(requireContext())
                    }
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
            val (font, emojis) = withContext(Dispatchers.IO) {
                TabData(fonts.loadOptions(), fonts.appliedKey()) to
                    TabData(emoji.loadOptions(), emoji.appliedKey())
            }
            fontData.value = font
            emojiData.value = emojis
        }
    }

    private companion object {
        const val ARG_TAB = "tab"
        val FONT_TYPES = arrayOf("font/ttf", "font/otf")
        val EMOJI_TYPES = arrayOf(
            "font/ttf",
            "font/otf",
            "application/x-font-ttf",
            "application/x-font-otf",
            "application/octet-stream",
        )
    }
}

@Composable
private fun FontEmojiPickerScreen(
    startTab: Int,
    fonts: TabData,
    emoji: TabData,
    fontBusy: Boolean,
    onApplyFont: (StyleOption) -> Unit,
    onApplyEmoji: (StyleOption) -> Unit,
    onAddFont: () -> Unit,
    onBrowseOnline: () -> Unit,
    onAddEmoji: () -> Unit,
    onEmojiApps: (hasCustom: Boolean) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var fontPreview by rememberSaveable { mutableStateOf<String?>(null) }
    var emojiPreview by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<FontCategory?>(null) }
    var sample by rememberSaveable { mutableStateOf("") }

    val emojiTab = tab == TAB_EMOJI
    val data = if (emojiTab) emoji else fonts
    val previewKey = if (emojiTab) emojiPreview else fontPreview
    val shown = data.options.firstOrNull { it.key == (previewKey ?: data.appliedKey) }
        ?: data.options.firstOrNull()
        ?: run {
            // Until the first load is in, there is nothing to preview or pick.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
    val busy = fontBusy && !emojiTab

    val glyphs = stringResource(R.string.font_sample_glyphs)
    val cardSample = sample.ifBlank { glyphs }
    val gridState = rememberLazyGridState()
    // The emoji preview is the notification, which the compact stage hides.
    val compact = rememberCompactOnScroll(gridState) && !emojiTab

    val hostView = LocalView.current
    val nestedScrollInterop = remember(hostView) { ComposeToViewNestedScrollBridge(hostView) }
    DisposableEffect(nestedScrollInterop) {
        onDispose { nestedScrollInterop.stop() }
    }
    // All, then each category that has a font in it.
    val filters = remember(fonts.options) {
        listOf<FontCategory?>(null) +
            FontCategory.values().filter { c -> fonts.options.any { it.category == c } }
    }
    val visible = if (emojiTab || filter == null) {
        data.options
    } else {
        data.options.filter { it.category == filter }
    }
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val customKey = if (emojiTab) EmojiController.CUSTOM_KEY else FontController.CUSTOM_KEY

    Column(modifier = Modifier.fillMaxSize()) {
        PreviewStage(
            emojiTab = emojiTab,
            option = shown,
            applied = shown.key == data.appliedKey,
            busy = busy,
            canApply = !busy && shown.key != data.appliedKey && shown.key != customKey,
            filters = if (emojiTab) emptyList() else filters,
            filter = filter,
            sample = sample,
            compact = compact,
            onFilter = { filter = it },
            onTab = { tab = it },
            onApply = { if (emojiTab) onApplyEmoji(shown) else onApplyFont(shown) }
        )

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
            if (!emojiTab) {
                item(key = "sample", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = sample,
                        onValueChange = { sample = it.take(MAX_SAMPLE_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        trailingIcon = {
                            if (sample.isNotEmpty()) {
                                IconButton(onClick = { sample = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.font_sample_clear)
                                    )
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.font_sample_hint)) }
                    )
                }
            }
            items(visible, key = { "${tab}_${it.key}" }) { option ->
                StyleCard(
                    option = option,
                    emoji = emojiTab,
                    sample = cardSample,
                    previewed = option.key == shown.key,
                    applied = option.key == data.appliedKey,
                    onClick = {
                        if (emojiTab) emojiPreview = option.key else fontPreview = option.key
                    }
                )
            }
            item(key = "add") {
                ActionCard(
                    label = stringResource(
                        if (emojiTab) R.string.add_emoji_font else R.string.add_font
                    ),
                    icon = Icons.Default.Add,
                    onClick = if (emojiTab) onAddEmoji else onAddFont
                )
            }
            if (!emojiTab) {
                item(key = "online") {
                    ActionCard(
                        label = stringResource(R.string.font_online_title),
                        icon = Icons.Default.Search,
                        onClick = onBrowseOnline
                    )
                }
            }
            if (emojiTab) {
                val hasCustom = emoji.options.any { it.key == EmojiController.CUSTOM_KEY }
                item(key = "apps") {
                    ActionCard(
                        label = stringResource(
                            if (hasCustom) R.string.emoji_apps_reset_short
                            else R.string.emoji_apps_restore_short
                        ),
                        icon = Icons.Default.Refresh,
                        onClick = { onEmojiApps(hasCustom) }
                    )
                }
            }
        }
    }
}

/** The pinned lock screen preview, kept as plain views like the other preview stages. */
@Composable
private fun PreviewStage(
    emojiTab: Boolean,
    option: StyleOption,
    applied: Boolean,
    busy: Boolean,
    canApply: Boolean,
    filters: List<FontCategory?>,
    filter: FontCategory?,
    sample: String,
    compact: Boolean,
    onFilter: (FontCategory?) -> Unit,
    onTab: (Int) -> Unit,
    onApply: () -> Unit
) {
    val latestOnApply by rememberUpdatedState(onApply)
    val latestOnTab by rememberUpdatedState(onTab)
    val latestOnFilter by rememberUpdatedState(onFilter)
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
                view.findViewById<TextView>(R.id.font_tab_fonts)
                    .setOnClickListener { latestOnTab(TAB_FONTS) }
                view.findViewById<TextView>(R.id.font_tab_emoji)
                    .setOnClickListener { latestOnTab(TAB_EMOJI) }
                // The view plays the click sound itself; the haptic tick is the clock picker's.
                view.findViewById<TextView>(R.id.font_preview_apply).setOnClickListener {
                    ClockUtils.performClickFeedback(it, confirm = true)
                    latestOnApply()
                }
            }
        },
        update = { view ->
            val typeface = option.typeface ?: Typeface.DEFAULT
            // A font restyles the whole lock screen; an emoji style only the emoji in it.
            val textFace = if (emojiTab) Typeface.DEFAULT else typeface
            view.findViewById<TextView>(R.id.font_preview_clock).typeface = textFace
            view.findViewById<TextView>(R.id.font_preview_date).typeface = textFace
            view.findViewById<TextView>(R.id.font_preview_title).apply {
                this.typeface = Typeface.create(textFace, Typeface.BOLD)
                text = option.label
            }
            view.findViewById<TextView>(R.id.font_preview_body).apply {
                this.typeface = typeface
                text = when {
                    emojiTab -> context.getString(R.string.emoji_preview_sample)
                    sample.isBlank() -> context.getString(R.string.font_preview_quote)
                    else -> sample
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (emojiTab) 22f else 12f)
            }
            view.findViewById<TextView>(R.id.font_tab_fonts).isSelected = !emojiTab
            view.findViewById<TextView>(R.id.font_tab_emoji).isSelected = emojiTab
            updateFilterChips(view, filters, filter) { latestOnFilter(it) }
            view.setStageCompact(compact)
            view.findViewById<TextView>(R.id.font_preview_apply).apply {
                setText(
                    when {
                        busy -> R.string.font_applying
                        applied -> R.string.font_applied
                        else -> R.string.apply
                    }
                )
                isSelected = canApply
                isEnabled = canApply
                alpha = if (canApply) 1f else 0.6f
            }
        }
    )
}

@Composable
private fun StyleCard(
    option: StyleOption,
    emoji: Boolean,
    sample: String,
    previewed: Boolean,
    applied: Boolean,
    onClick: () -> Unit
) {
    val family = remember(option.typeface) { option.typeface?.let { FontFamily(it) } }
    val clickSound = rememberClickSound()
    val appliedState = stringResource(R.string.font_applied)
    Card(
        onClick = {
            clickSound()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .insetFocusRing(ExpressiveCorners.single)
            .semantics {
                selected = previewed
                if (applied) stateDescription = appliedState
            },
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
                    text = if (emoji) EmojiController.PREVIEW_EMOJI else sample,
                    style = if (emoji) {
                        MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                    fontFamily = family,
                    maxLines = 1,
                    // The sample glyphs are decoration; the card is read by its label.
                    modifier = Modifier.weight(1f).clearAndSetSemantics { }
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
                // A font names itself; an emoji style is named in the system font.
                fontFamily = if (emoji) null else family,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActionCard(label: String, icon: ImageVector, onClick: () -> Unit) {
    val clickSound = rememberClickSound()
    Card(
        onClick = {
            clickSound()
            onClick()
        },
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
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * One chip per category with a font in it, so a look can be picked without scrolling the grid.
 * The row is only shown on the font tab, and only when there is more than one chip.
 */
private fun updateFilterChips(
    view: View,
    filters: List<FontCategory?>,
    filter: FontCategory?,
    onFilter: (FontCategory?) -> Unit
) {
    view.findViewById<View>(R.id.font_filter_scroll).visibility =
        if (filters.size > 1) View.VISIBLE else View.GONE
    val group = view.findViewById<LinearLayout>(R.id.font_filter_chips)
    // Chips are only rebuilt when the categories change, not on every selection.
    if (group.tag != filters) {
        group.tag = filters
        group.removeAllViews()
        val res = group.resources
        val inflater = LayoutInflater.from(group.context)
        filters.forEach { category ->
            val chip = inflater.inflate(R.layout.charging_style_chip, group, false) as TextView
            chip.setText(
                when (category) {
                    null -> R.string.font_filter_all
                    FontCategory.SANS -> R.string.font_filter_sans
                    FontCategory.SERIF -> R.string.font_filter_serif
                    FontCategory.DISPLAY -> R.string.font_filter_display
                    FontCategory.SCRIPT -> R.string.font_filter_script
                    FontCategory.MONO -> R.string.font_filter_mono
                }
            )
            chip.setOnClickListener { onFilter(category) }
            group.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    res.getDimensionPixelSize(R.dimen.charging_chip_height)
                ).apply { marginEnd = res.getDimensionPixelSize(R.dimen.preview_stage_gap) }
            )
        }
    }
    for (i in 0 until group.childCount) group.getChildAt(i).isSelected = filters[i] == filter
}
