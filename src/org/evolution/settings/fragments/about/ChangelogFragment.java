/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */
package org.evolution.settings.fragments.about;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.android.internal.logging.nano.MetricsProto;

public class ChangelogFragment extends SettingsPreferenceFragment {

    TextView textView;

    private static final String CHANGELOG_PATH = "https://raw.githubusercontent.com/Evolution-X/changelog/refs/heads/bka/changelogs/LATEST.txt";

    private int getThemeColor(Context context, int attr) {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(attr, typedValue, true);
        return context.getColor(typedValue.resourceId);
    }

    private void setSpan(SpannableStringBuilder sb, Object span, int start, int end) {
        sb.setSpan(span, start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.changelog, container, false);
    }

    @Override
    public void onViewCreated(final View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

	final Context context = view.getContext();

        textView = view.findViewById(R.id.changelog_text);

        new Thread(() -> {
            InputStreamReader inputReader = null;
            StringBuilder data = new StringBuilder();

            Pattern date = Pattern.compile("(={20}|\\d{4}-\\d{2}-\\d{2})");
            Pattern commit = Pattern.compile("([a-f0-9]{7})");
            Pattern committer = Pattern.compile("\\[(\\D.*?)]");
            Pattern title = Pattern.compile("(\\R\\s+[\\*]\\s.*)");
	    Pattern notableRomChanges = Pattern.compile("(?m)^Notable ROM changes.*$");

            try {
                char tmp[] = new char[2048];
                int numRead;
                inputReader = new InputStreamReader(new URL(CHANGELOG_PATH).openStream());
                while ((numRead = inputReader.read(tmp)) >= 0) {
                    data.append(tmp, 0, numRead);
                }
            } catch (IOException e) {
                textView.post(() -> textView.setText(R.string.changelog_error));
                return;
            } finally {
                try {
                    if (inputReader != null) inputReader.close();
                } catch (IOException e) {}
            }

            SpannableStringBuilder sb = new SpannableStringBuilder(data);

            Resources.Theme theme = context.getTheme();

 	    final int color = getThemeColor(context, android.R.attr.colorAccent);
     		final int textColor = getThemeColor(context, android.R.attr.textColorPrimary);

            sb.setSpan(new ForegroundColorSpan(textColor), 0, sb.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);

            Matcher m = date.matcher(data);
            while (m.find()) {
                setSpan(sb, new ForegroundColorSpan(color), m.start(1), m.end(1));
                setSpan(sb, new StyleSpan(Typeface.BOLD), m.start(1), m.end(1));
            }

            m = commit.matcher(data);
            while (m.find()) {
                setSpan(sb, new StyleSpan(Typeface.NORMAL), m.start(1), m.end(1));
            }

            m = committer.matcher(data);
            while (m.find()) {
                setSpan(sb, new ForegroundColorSpan(color), m.start(1), m.end(1));
                setSpan(sb, new StyleSpan(Typeface.NORMAL), m.start(1), m.end(1));
            }

            m = title.matcher(data);
            while (m.find()) {
                setSpan(sb, new ForegroundColorSpan(color), m.start(1), m.end(1));
                setSpan(sb, new StyleSpan(Typeface.BOLD), m.start(1), m.end(1));
            }

            m = notableRomChanges.matcher(data);
            while (m.find()) {
                setSpan(sb, new StyleSpan(Typeface.BOLD), m.start(), m.end());
            }

            final SpannableStringBuilder result = sb;
            textView.post(() -> textView.setText(result));
        }).start();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {

    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }
}
