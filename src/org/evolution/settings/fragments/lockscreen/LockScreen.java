/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.lockscreen;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import org.evolution.settings.utils.DeviceUtils;
import org.evolution.settings.utils.PreferenceUtils;

@SearchIndexable
public class LockScreen extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "LockScreen";

    private static final String KEY_FP_ERROR = "fp_error_vibrate";
    private static final String KEY_FP_SUCCESS = "fp_success_vibrate";
    private static final String KEY_RIPPLE_EFFECT = "enable_ripple_effect";
    private static final String LOCKSCREEN_GESTURES_CATEGORY = "lockscreen_gestures_category";

    private Preference mRippleEffect;
    private SwitchPreferenceCompat mFpErrorVib;
    private SwitchPreferenceCompat mFpSuccessVib;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_lock_screen);

        final Context context = getContext();

        final PreferenceCategory gestCategory = findPreference(LOCKSCREEN_GESTURES_CATEGORY);

        mFpSuccessVib = findPreference(KEY_FP_SUCCESS);
        mFpErrorVib = findPreference(KEY_FP_ERROR);
        mRippleEffect = findPreference(KEY_RIPPLE_EFFECT);

        boolean hasFingerprint = DeviceUtils.hasFingerprint(context);
        if (!hasFingerprint) {
            gestCategory.removePreference(mRippleEffect);
        }

        if (!hasFingerprint || !DeviceUtils.hasVibrator(context)) {
            gestCategory.removePreference(mFpSuccessVib);
            gestCategory.removePreference(mFpErrorVib);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_lock_screen) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);

                    boolean hasFingerprint = DeviceUtils.hasFingerprint(context);
                    if (!hasFingerprint) {
                        keys.add(KEY_RIPPLE_EFFECT);
                    }

                    if (!hasFingerprint || !DeviceUtils.hasVibrator(context)) {
                        keys.add(KEY_FP_SUCCESS);
                        keys.add(KEY_FP_ERROR);
                    }

                    return keys;
                }
            };
}
