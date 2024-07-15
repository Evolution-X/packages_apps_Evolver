/*
 * Copyright (C) 2023 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes.fonts;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.List;

public class FontPickerPreview extends SettingsPreferenceFragment {

    private TextView fontSelector;
    private TextView previewText;
    private FontManager fontManager;
    private ExtendedFloatingActionButton applyFab;
    private int currentFontPosition = -1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fontManager = new FontManager(getActivity(), false);
        getActivity().setTitle(getActivity().getString(R.string.font_styles_title));
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.font_picker_preview, container, false);

        fontSelector = rootView.findViewById(R.id.font_selector);
        previewText = rootView.findViewById(R.id.font_preview_text);

        String text = previewText.getText().toString();
        SpannableString spannableString = new SpannableString(text);
        TypedValue typedValue = new TypedValue();
        getActivity().getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
        int colorAccent = typedValue.data;
        int startIndex = text.indexOf("A");
        int endIndex = text.length();
        spannableString.setSpan(
            new ForegroundColorSpan(colorAccent),
            startIndex,
            endIndex,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        previewText.setText(spannableString);

        List<String> fontPackageNames = fontManager.getAllFontPackages();

        int backgroundColor = ContextCompat.getColor(getContext(),
                isNightMode() ? R.color.font_drop_down_bg_dark : R.color.font_drop_down_bg_light);
        fontSelector.setTextColor(ContextCompat.getColor(getContext(), isNightMode()
                ? R.color.font_drop_down_bg_light
                : R.color.font_drop_down_bg_dark));
        fontSelector.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));

        fontSelector.setOnClickListener(v -> {
            View popupView = LayoutInflater.from(getActivity()).inflate(R.layout.popup_font_selector, null);
            PopupWindow popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);

            ListView fontListView = popupView.findViewById(R.id.font_list_view);
            FontArrayAdapter fontAdapter = new FontArrayAdapter(
                    getActivity(),
                    android.R.layout.simple_list_item_1,
                    fontPackageNames,
                    fontManager,
                    isNightMode()
            );
            fontListView.setAdapter(fontAdapter);

            fontListView.setOnItemClickListener((parent, view, position, id) -> {
                currentFontPosition = position;
                String fontPackage = fontPackageNames.get(currentFontPosition);
                applyFontToPreview(fontPackage);
                fontSelector.setText(fontManager.getLabel(getContext(), fontPackage));
                popupWindow.dismiss();
            });

            popupView.setBackgroundResource(R.drawable.custom_background);
            Drawable backgroundDrawable = popupView.getBackground();
            if (backgroundDrawable != null) {
                backgroundDrawable.setColorFilter(backgroundColor, PorterDuff.Mode.SRC_ATOP);
            }
            popupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popupWindow.setOutsideTouchable(true);
            popupWindow.setFocusable(true);
            popupWindow.showAsDropDown(v, 0, 10);
        });

        applyFab = rootView.findViewById(R.id.apply_extended_fab);
        applyFab.setOnClickListener(view -> {
            if (currentFontPosition != -1) {
                fontManager.enableFontPackage(currentFontPosition);
            }
        });

        String currentFontPackage = fontManager.getCurrentFontPackage();
        currentFontPosition = fontPackageNames.indexOf(currentFontPackage);
        if (currentFontPosition != -1) {
            fontSelector.setText(fontManager.getLabel(getContext(), fontPackageNames.get(currentFontPosition)));
            applyFontToPreview(fontPackageNames.get(currentFontPosition));
        }

        return rootView;
    }

    private boolean isNightMode() {
        int nightModeFlags = getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyFontToPreview(String fontPackage) {
        Typeface typeface = fontManager.getTypeface(getContext(), fontPackage);
        if (typeface != null) {
            previewText.setTypeface(typeface);
        } else {
            previewText.setTypeface(Typeface.create("googlesans", Typeface.NORMAL));
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.VIEW_UNKNOWN;
    }
}
