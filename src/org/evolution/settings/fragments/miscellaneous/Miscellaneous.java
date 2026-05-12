/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.evolution.Utils;
import com.android.settings.R;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settingslib.search.SearchIndexable;

import java.util.List;

import lineageos.providers.LineageSettings;

import org.evolution.settings.fragments.miscellaneous.ShakeGesturesController;
import org.evolution.settings.preferences.CustomSeekBarPreference;
import org.evolution.settings.preferences.SystemSettingSwitchPreference;
import org.evolution.settings.utils.PreferenceUtils;

import static org.lineageos.internal.util.DeviceKeysConstants.*;

@SearchIndexable
public class Miscellaneous extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener {

    private static final String TAG = "Miscellaneous";

    private static final String KEY_THREE_FINGERS_SWIPE = "three_fingers_swipe";
    private static final String KEY_SHAKE_GESTURES = "shake_gestures_enabled";
    private static final String FLASHLIGHT_CALL_PREF = "flashlight_on_call";
    private static final String FLASHLIGHT_DND_PREF = "flashlight_on_call_ignore_dnd";
    private static final String FLASHLIGHT_RATE_PREF = "flashlight_on_call_rate";

    private ListPreference mThreeFingersSwipeAction;
    private ListPreference mFlashOnCall;
    private SwitchPreferenceCompat mFlashOnCallIgnoreDND;
    private CustomSeekBarPreference mFlashOnCallRate;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.evolution_settings_miscellaneous);

        Context mContext = getActivity().getApplicationContext();
        final ContentResolver resolver = mContext.getContentResolver();
        final PreferenceScreen prefScreen = getPreferenceScreen();
        final Resources res = mContext.getResources();

        Action threeFingersSwipeAction = Action.fromSettings(getContentResolver(),
                LineageSettings.System.KEY_THREE_FINGERS_SWIPE_ACTION,
                Action.NOTHING);
        mThreeFingersSwipeAction = initList(KEY_THREE_FINGERS_SWIPE, threeFingersSwipeAction);

        if (!Utils.deviceHasFlashlight(mContext)) {
            prefScreen.removePreference(prefScreen.findPreference(FLASHLIGHT_CALL_PREF));
            prefScreen.removePreference(prefScreen.findPreference(FLASHLIGHT_DND_PREF));
            prefScreen.removePreference(prefScreen.findPreference(FLASHLIGHT_RATE_PREF));
        } else {
            mFlashOnCall = (ListPreference)
                    prefScreen.findPreference(FLASHLIGHT_CALL_PREF);
            mFlashOnCall.setOnPreferenceChangeListener(this);

            mFlashOnCallIgnoreDND = (SwitchPreferenceCompat)
                    prefScreen.findPreference(FLASHLIGHT_DND_PREF);
            int value = Settings.System.getInt(resolver,
                    Settings.System.FLASHLIGHT_ON_CALL, 0);

            mFlashOnCallRate = (CustomSeekBarPreference)
                    prefScreen.findPreference(FLASHLIGHT_RATE_PREF);

            mFlashOnCallIgnoreDND.setEnabled(value > 1);
            mFlashOnCallRate.setEnabled(value > 0);
        }

        updateShakeGesturesSummary();

        Preference shakeGestures = findPreference(KEY_SHAKE_GESTURES);
        if (shakeGestures != null) {
            shakeGestures.setOnPreferenceChangeListener((pref, newValue) -> {
                pref.getHandler().post(() -> updateShakeGesturesSummary());
                return true;
            });
        }
    }

    private ListPreference initList(String key, Action value) {
        return initList(key, value.ordinal());
    }

    private ListPreference initList(String key, int value) {
        ListPreference list = (ListPreference) getPreferenceScreen().findPreference(key);
        if (list == null) return null;
        list.setValue(Integer.toString(value));
        list.setSummary(list.getEntry());
        list.setOnPreferenceChangeListener(this);
        return list;
    }

    private void handleListChange(ListPreference pref, Object newValue, String setting) {
        String value = (String) newValue;
        int index = pref.findIndexOfValue(value);
        pref.setSummary(pref.getEntries()[index]);
        LineageSettings.System.putIntForUser(getContentResolver(), setting, Integer.valueOf(value), UserHandle.USER_CURRENT);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final Context context = getContext();
        final ContentResolver resolver = context.getContentResolver();
        if (preference == mThreeFingersSwipeAction) {
            handleListChange((ListPreference) preference, newValue,
                    LineageSettings.System.KEY_THREE_FINGERS_SWIPE_ACTION);
            return true;
        }
        if (preference == mFlashOnCall) {
            int value = Integer.parseInt((String) newValue);
            mFlashOnCallIgnoreDND.setEnabled(value > 1);
            mFlashOnCallRate.setEnabled(value > 0);
            return true;
        }
        return false;
    }

    private void updateShakeGesturesSummary() {
        Preference pref = findPreference(KEY_SHAKE_GESTURES);
        if (pref == null) return;
        pref.setSummary(new ShakeGesturesController(getContext(), KEY_SHAKE_GESTURES).getSummary());
    }

    @Override
    public void onResume() {
        super.onResume();
        updateShakeGesturesSummary();
        PreferenceUtils.reloadCustomPrimarySwitches(getPreferenceScreen());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.evolution_settings_miscellaneous) {

                @Override
                public List<String> getNonIndexableKeys(Context context) {
                    List<String> keys = super.getNonIndexableKeys(context);
                    final Resources res = context.getResources();

                    if (!Utils.deviceHasFlashlight(context)) {
                        keys.add(FLASHLIGHT_CALL_PREF);
                        keys.add(FLASHLIGHT_DND_PREF);
                        keys.add(FLASHLIGHT_RATE_PREF);
                    }

                    return keys;
                }
            };
}
