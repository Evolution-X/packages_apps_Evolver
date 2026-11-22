/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.quicksettings;

import android.content.ContentResolver;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.widget.LayoutPreference;

import java.util.ArrayList;

@SearchIndexable
public class QsPanelStyleSettings extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String KEY_QS_COLOROS_MEDIA_ART_FILL = "qs_coloros_media_art_fill";
    private static final String KEY_QS_NOTHING_COLOR_STYLE = "qs_nothing_color_style";
    private static final String KEY_QS_PANEL_STYLE = "qs_panel_style";
    private static final String KEY_QS_PANEL_STYLE_THUMBS = "qs_panel_style_thumbs";
    private static final String KEY_QS_REMIX_STYLE = "qs_remix_style";
    private static final String KEY_QS_TILE_ICON_SHAPE = "qs_tile_icon_shape";
    private static final String KEY_QS_TILE_LABEL_HIDE = "qs_tile_label_hide";
    private static final String KEY_QS_TILE_SHAPE = "qs_tile_shape";

    private static final int PANEL_STYLE_CLASSIC = 1;
    private static final int PANEL_STYLE_COLOROS = 2;
    private static final int PANEL_STYLE_NOTHINGOS = 3;
    private static final int PANEL_STYLE_REMIX = 4;

    private ListPreference mQsPanelStyle;
    private QsStylePreviewView mQsStylePreview;
    private final QsStylePreviewView[] mQsStyleThumbs = new QsStylePreviewView[5];
    private Preference mQsColorOSMediaArtFill;
    private Preference mQsNothingColorStyle;
    private Preference mQsRemixStyle;
    private final ArrayList<QsRemixLookPreference> mRemixLooks = new ArrayList<>();
    private Preference mQsTileIconShape;
    private Preference mQsTileLabelHide;
    private Preference mQsTileShape;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_qs_panel_style);

        final ContentResolver resolver = getContext().getContentResolver();

        mQsPanelStyle = findPreference(KEY_QS_PANEL_STYLE);
        mQsPanelStyle.setOnPreferenceChangeListener(this);
        mQsTileShape = findPreference(KEY_QS_TILE_SHAPE);
        mQsTileIconShape = findPreference(KEY_QS_TILE_ICON_SHAPE);
        mQsTileLabelHide = findPreference(KEY_QS_TILE_LABEL_HIDE);
        mQsColorOSMediaArtFill = findPreference(KEY_QS_COLOROS_MEDIA_ART_FILL);
        mQsNothingColorStyle = findPreference(KEY_QS_NOTHING_COLOR_STYLE);
        mQsNothingColorStyle.setOnPreferenceChangeListener(this);
        mQsRemixStyle = findPreference(KEY_QS_REMIX_STYLE);
        addRemixLooks(resolver);

        final int panelStyle = Settings.System.getIntForUser(resolver,
                Settings.System.QS_PANEL_STYLE, 0, UserHandle.USER_CURRENT);
        updatePanelStylePrefs(panelStyle);
        bindStyleThumbs(resolver, panelStyle);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // The pinned header sits above the list, so the preview stays in view while the options
        // below it scroll.
        final View header = setPinnedHeaderView(R.layout.qs_panel_style_preview_pinned);
        mQsStylePreview = header.findViewById(R.id.qs_style_preview);

        final ContentResolver resolver = getContext().getContentResolver();
        mQsStylePreview.setNothingColorStyle(Settings.System.getIntForUser(resolver,
                Settings.System.QS_NOTHING_COLOR_STYLE, QsStylePreviewView.NOTHING_COLORS_FLAT,
                UserHandle.USER_CURRENT));
        mQsStylePreview.setRemixLook(Settings.System.getIntForUser(resolver,
                KEY_QS_REMIX_STYLE, QsStylePreviewView.LOOK_STOCK, UserHandle.USER_CURRENT));
        mQsStylePreview.setStyle(Settings.System.getIntForUser(resolver,
                Settings.System.QS_PANEL_STYLE, 0, UserHandle.USER_CURRENT));
    }

    // One row per look, each drawn with its own preview, so a look can be told apart without
    // picking it first.
    private void addRemixLooks(ContentResolver resolver) {
        if (!(mQsRemixStyle instanceof PreferenceCategory)) {
            return;
        }
        final PreferenceCategory category = (PreferenceCategory) mQsRemixStyle;
        final String[] values = getResources().getStringArray(R.array.qs_remix_style_values);
        final String[] entries = getResources().getStringArray(R.array.qs_remix_style_entries);
        final String[] summaries = getResources().getStringArray(R.array.qs_remix_style_summaries);
        final int current = Settings.System.getIntForUser(resolver, KEY_QS_REMIX_STYLE,
                QsStylePreviewView.LOOK_STOCK, UserHandle.USER_CURRENT);
        for (int i = 0; i < values.length && i < entries.length && i < summaries.length; i++) {
            final int look = Integer.parseInt(values[i]);
            final QsRemixLookPreference row = new QsRemixLookPreference(getPrefContext(), look);
            row.setKey("qs_remix_look_" + look);
            row.setTitle(entries[i]);
            row.setSummary(summaries[i]);
            row.setChecked(look == current);
            row.setOnPreferenceClickListener(pref -> {
                selectRemixLook(look);
                return true;
            });
            category.addPreference(row);
            mRemixLooks.add(row);
        }
    }

    private void selectRemixLook(int look) {
        Settings.System.putIntForUser(getContext().getContentResolver(), KEY_QS_REMIX_STYLE,
                look, UserHandle.USER_CURRENT);
        for (QsRemixLookPreference row : mRemixLooks) {
            row.setChecked(row.getLook() == look);
        }
        updatePanelStylePreviewLook(look);
    }

    private void bindStyleThumbs(ContentResolver resolver, int panelStyle) {
        final LayoutPreference thumbsPref = findPreference(KEY_QS_PANEL_STYLE_THUMBS);
        if (thumbsPref == null) {
            return;
        }
        final int nothingColors = Settings.System.getIntForUser(resolver,
                Settings.System.QS_NOTHING_COLOR_STYLE, QsStylePreviewView.NOTHING_COLORS_FLAT,
                UserHandle.USER_CURRENT);
        final int remixLook = Settings.System.getIntForUser(resolver,
                KEY_QS_REMIX_STYLE, QsStylePreviewView.LOOK_STOCK, UserHandle.USER_CURRENT);
        final int[] thumbIds = {
            R.id.qs_style_thumb_card, R.id.qs_style_thumb_classic,
            R.id.qs_style_thumb_coloros, R.id.qs_style_thumb_nothingos,
            R.id.qs_style_thumb_remix,
        };
        final int[] titleIds = {
            R.id.qs_style_thumb_card_title, R.id.qs_style_thumb_classic_title,
            R.id.qs_style_thumb_coloros_title, R.id.qs_style_thumb_nothingos_title,
            R.id.qs_style_thumb_remix_title,
        };
        for (int i = 0; i < mQsStyleThumbs.length; i++) {
            final int style = i;
            final QsStylePreviewView thumb = thumbsPref.findViewById(thumbIds[i]);
            final TextView title = thumbsPref.findViewById(titleIds[i]);
            thumb.setMini(true);
            thumb.setStyle(style);
            thumb.setNothingColorStyle(nothingColors);
            thumb.setRemixLook(remixLook);
            thumb.setSelected(style == panelStyle);
            thumb.setContentDescription(title.getText());
            thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            thumb.setOnClickListener(v -> selectPanelStyle(style));
            mQsStyleThumbs[i] = thumb;
        }
    }

    private void updatePanelStylePreview(int panelStyle) {
        if (mQsStylePreview != null) {
            mQsStylePreview.setStyle(panelStyle);
        }
        for (int i = 0; i < mQsStyleThumbs.length; i++) {
            if (mQsStyleThumbs[i] != null) {
                mQsStyleThumbs[i].setSelected(i == panelStyle);
            }
        }
    }

    private void updatePanelStylePreviewColors(int nothingColors) {
        if (mQsStylePreview != null) {
            mQsStylePreview.setNothingColorStyle(nothingColors);
        }
        for (QsStylePreviewView thumb : mQsStyleThumbs) {
            if (thumb != null) {
                thumb.setNothingColorStyle(nothingColors);
            }
        }
    }

    private void updatePanelStylePreviewLook(int look) {
        if (mQsStylePreview != null) {
            mQsStylePreview.setRemixLook(look);
        }
        for (QsStylePreviewView thumb : mQsStyleThumbs) {
            if (thumb != null) {
                thumb.setRemixLook(look);
            }
        }
    }

    /** Picks a panel style from a thumbnail, as the list preference does. */
    private void selectPanelStyle(int style) {
        final String value = String.valueOf(style);
        if (mQsPanelStyle == null || value.equals(mQsPanelStyle.getValue())) {
            return;
        }
        if (mQsPanelStyle.callChangeListener(value)) {
            mQsPanelStyle.setValue(value);
        }
    }

    private void updatePanelStylePrefs(int panelStyle) {
        final boolean isClassic = panelStyle == PANEL_STYLE_CLASSIC;
        final boolean isColorOS = panelStyle == PANEL_STYLE_COLOROS;
        final boolean isNothingOS = panelStyle == PANEL_STYLE_NOTHINGOS;
        final boolean isRemix = panelStyle == PANEL_STYLE_REMIX;

        // ColorOS pins the tile shapes, but still lets the labels be hidden.
        // NothingOS pins both the shapes and the labels.
        if (mQsTileShape != null) {
            mQsTileShape.setVisible(!isClassic && !isColorOS && !isNothingOS && !isRemix);
        }
        if (mQsTileIconShape != null) {
            mQsTileIconShape.setVisible(isClassic);
        }
        if (mQsTileLabelHide != null) {
            mQsTileLabelHide.setVisible(isClassic || isColorOS);
        }
        if (mQsColorOSMediaArtFill != null) {
            mQsColorOSMediaArtFill.setVisible(isColorOS);
        }
        if (mQsNothingColorStyle != null) {
            mQsNothingColorStyle.setVisible(isNothingOS);
        }
        // The Remix style picks its own tile shapes, so only its look is offered.
        if (mQsRemixStyle != null) {
            mQsRemixStyle.setVisible(isRemix);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mQsPanelStyle) {
            final int value = Integer.parseInt((String) newValue);
            updatePanelStylePrefs(value);
            updatePanelStylePreview(value);
            return true;
        } else if (preference == mQsNothingColorStyle) {
            updatePanelStylePreviewColors(Integer.parseInt((String) newValue));
            return true;
        }
        return false;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_qs_panel_style);
}
