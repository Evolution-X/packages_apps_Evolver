/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.quicksettings;

import android.content.Context;
import android.widget.RadioButton;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settings.R;

/**
 * One row of the Remix looks: a small preview of the look drawn by {@link QsStylePreviewView},
 * its name and description, and a radio button for the look that is in use.
 */
public class QsRemixLookPreference extends Preference {

    private final int mLook;
    private boolean mChecked;

    public QsRemixLookPreference(Context context, int look) {
        super(context);
        mLook = look;
        setLayoutResource(R.layout.qs_remix_look_row);
        setIconSpaceReserved(false);
    }

    public int getLook() {
        return mLook;
    }

    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final QsStylePreviewView thumb = (QsStylePreviewView) holder.findViewById(R.id.qs_look_thumb);
        thumb.setMini(true);
        thumb.setStyle(QsStylePreviewView.REMIX);
        thumb.setRemixLook(mLook);
        ((RadioButton) holder.findViewById(R.id.qs_look_radio)).setChecked(mChecked);
    }
}
