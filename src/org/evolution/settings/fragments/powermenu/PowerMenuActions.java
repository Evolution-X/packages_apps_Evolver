/*
 * Copyright (C) 2016-2024 crDroid Android Project
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

package org.evolution.settings.fragments.powermenu;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.controls.ControlsProviderService;

import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.widget.LockPatternUtils;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;
import com.android.settingslib.applications.ServiceListing;
import com.android.settings.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lineageos.app.LineageGlobalActions;
import lineageos.providers.LineageSettings;

import org.evolution.settings.utils.TelephonyUtils;
import org.lineageos.internal.util.PowerMenuConstants;

import static org.lineageos.internal.util.PowerMenuConstants.*;

@SearchIndexable
public class PowerMenuActions extends SettingsPreferenceFragment {
    final static String TAG = "PowerMenuActions";

    private static final String CATEGORY_POWER_MENU_ITEMS = "power_menu_items";

    private PreferenceCategory mPowerMenuItemsCategory;

    private SwitchPreferenceCompat mScreenshotPref;
    private SwitchPreferenceCompat mOnTheGoPref;
    private SwitchPreferenceCompat mAirplanePref;
    private SwitchPreferenceCompat mUsersPref;
    private SwitchPreferenceCompat mLockDownPref;
    private SwitchPreferenceCompat mEmergencyPref;
    private SwitchPreferenceCompat mDeviceControlsPref;

    private PreferenceCategory mRestartItemsCategory;
    private SwitchPreferenceCompat mRestartRecoveryPref;
    private SwitchPreferenceCompat mRestartBootloaderPref;
    private SwitchPreferenceCompat mRestartFastbootPref;
    private SwitchPreferenceCompat mRestartDownloadPref;
    private SwitchPreferenceCompat mRestartSystemUIPref;

    private static final String CATEGORY_POWER_MENU_RESTART_ITEMS = "power_menu_restart_items";

    private static final String RESTART_ACTION_KEY_RESTART_RECOVERY = "restart_recovery";
    private static final String RESTART_ACTION_KEY_RESTART_BOOTLOADER = "restart_bootloader";
    private static final String RESTART_ACTION_KEY_RESTART_FASTBOOT = "restart_fastboot";
    private static final String RESTART_ACTION_KEY_RESTART_DOWNLOAD = "restart_download";
    private static final String RESTART_ACTION_KEY_RESTART_SYSTEMUI = "restart_systemui";

    // LineageSettings key used to persist user-chosen restart actions
    private static final String RESTART_ACTIONS_SETTING =
            LineageSettings.System.POWER_MENU_RESTART_ACTIONS;

    // Fallback default set matching the resource overlay config_restartActionsList
    private static final Set<String> DEFAULT_RESTART_ACTIONS = new HashSet<>(Arrays.asList(
            RESTART_ACTION_KEY_RESTART_RECOVERY,
            RESTART_ACTION_KEY_RESTART_BOOTLOADER,
            RESTART_ACTION_KEY_RESTART_SYSTEMUI
    ));

    private ContentObserver mRestartActionsObserver;

    private LineageGlobalActions mLineageGlobalActions;

    Context mContext;
    private LockPatternUtils mLockPatternUtils;
    private UserManager mUserManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.power_menu_actions);
        mContext = getActivity().getApplicationContext();
        mLockPatternUtils = new LockPatternUtils(mContext);
        mUserManager = UserManager.get(mContext);
        mLineageGlobalActions = LineageGlobalActions.getInstance(mContext);

        mPowerMenuItemsCategory = findPreference(CATEGORY_POWER_MENU_ITEMS);

        for (String action : PowerMenuConstants.getAllActions()) {
            if (action.equals(GLOBAL_ACTION_KEY_SCREENSHOT)) {
                mScreenshotPref = (SwitchPreferenceCompat) findPreference(GLOBAL_ACTION_KEY_SCREENSHOT);
            } else if (action.equals(GLOBAL_ACTION_KEY_ONTHEGO)) {
                mOnTheGoPref = (SwitchPreferenceCompat) findPreference(GLOBAL_ACTION_KEY_ONTHEGO);
            } else if (action.equals(GLOBAL_ACTION_KEY_AIRPLANE)) {
                mAirplanePref = (SwitchPreferenceCompat) findPreference(GLOBAL_ACTION_KEY_AIRPLANE);
            } else if (action.equals(GLOBAL_ACTION_KEY_USERS)) {
                mUsersPref = (SwitchPreferenceCompat) findPreference(GLOBAL_ACTION_KEY_USERS);
            } else if (action.equals(GLOBAL_ACTION_KEY_LOCKDOWN)) {
                mLockDownPref = (SwitchPreferenceCompat) findPreference(GLOBAL_ACTION_KEY_LOCKDOWN);
            } else if (action.equals(GLOBAL_ACTION_KEY_EMERGENCY)) {
                mEmergencyPref = (SwitchPreferenceCompat) findPreference(GLOBAL_ACTION_KEY_EMERGENCY);
            } else if (action.equals(GLOBAL_ACTION_KEY_DEVICECONTROLS)) {
                mDeviceControlsPref = findPreference(GLOBAL_ACTION_KEY_DEVICECONTROLS);
            }
        }

        if (!TelephonyUtils.isVoiceCapable(getActivity())) {
            mPowerMenuItemsCategory.removePreference(mEmergencyPref);
            mEmergencyPref = null;
        }

        mRestartItemsCategory = findPreference(CATEGORY_POWER_MENU_RESTART_ITEMS);
        mRestartRecoveryPref = findPreference(RESTART_ACTION_KEY_RESTART_RECOVERY);
        mRestartBootloaderPref = findPreference(RESTART_ACTION_KEY_RESTART_BOOTLOADER);
        mRestartFastbootPref = findPreference(RESTART_ACTION_KEY_RESTART_FASTBOOT);
        mRestartDownloadPref = findPreference(RESTART_ACTION_KEY_RESTART_DOWNLOAD);
        mRestartSystemUIPref = findPreference(RESTART_ACTION_KEY_RESTART_SYSTEMUI);
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mScreenshotPref != null) {
            mScreenshotPref.setChecked(mLineageGlobalActions.userConfigContains(
                    GLOBAL_ACTION_KEY_SCREENSHOT));
        }

        if (mOnTheGoPref != null) {
            mOnTheGoPref.setChecked(mLineageGlobalActions.userConfigContains(
                    GLOBAL_ACTION_KEY_ONTHEGO));
        }

        if (mAirplanePref != null) {
            mAirplanePref.setChecked(mLineageGlobalActions.userConfigContains(
                    GLOBAL_ACTION_KEY_AIRPLANE));
        }

        if (mEmergencyPref != null) {
            mEmergencyPref.setChecked(mLineageGlobalActions.userConfigContains(
                    GLOBAL_ACTION_KEY_EMERGENCY));
        }

        if (mDeviceControlsPref != null) {
            mDeviceControlsPref.setChecked(mLineageGlobalActions.userConfigContains(
                    GLOBAL_ACTION_KEY_DEVICECONTROLS));

            // Enable preference if any device control app is installed
            ServiceListing serviceListing = new ServiceListing.Builder(mContext)
                    .setIntentAction(ControlsProviderService.SERVICE_CONTROLS)
                    .setPermission(Manifest.permission.BIND_CONTROLS)
                    .setNoun("Controls Provider")
                    .setSetting("controls_providers")
                    .setTag("controls_providers")
                    .build();
            serviceListing.addCallback(
                    services -> mDeviceControlsPref.setEnabled(!services.isEmpty()));
            serviceListing.reload();
        }

        // Restore restart action toggle states from persisted setting
        Set<String> enabledRestartActions = getEnabledRestartActions();
        if (mRestartRecoveryPref != null) {
            mRestartRecoveryPref.setChecked(
                    enabledRestartActions.contains(RESTART_ACTION_KEY_RESTART_RECOVERY));
        }
        if (mRestartBootloaderPref != null) {
            mRestartBootloaderPref.setChecked(
                    enabledRestartActions.contains(RESTART_ACTION_KEY_RESTART_BOOTLOADER));
        }
        if (mRestartFastbootPref != null) {
            mRestartFastbootPref.setChecked(
                    enabledRestartActions.contains(RESTART_ACTION_KEY_RESTART_FASTBOOT));
        }
        if (mRestartDownloadPref != null) {
            mRestartDownloadPref.setChecked(
                    enabledRestartActions.contains(RESTART_ACTION_KEY_RESTART_DOWNLOAD));
        }
        if (mRestartSystemUIPref != null) {
            mRestartSystemUIPref.setChecked(
                    enabledRestartActions.contains(RESTART_ACTION_KEY_RESTART_SYSTEMUI));
        }

        // Observe external changes to restart actions setting (e.g. via ADB)
        if (mRestartActionsObserver == null) {
            mRestartActionsObserver = new ContentObserver(new Handler()) {
                @Override
                public void onChange(boolean selfChange) {
                    Set<String> updated = getEnabledRestartActions();
                    if (mRestartRecoveryPref != null)
                        mRestartRecoveryPref.setChecked(
                                updated.contains(RESTART_ACTION_KEY_RESTART_RECOVERY));
                    if (mRestartBootloaderPref != null)
                        mRestartBootloaderPref.setChecked(
                                updated.contains(RESTART_ACTION_KEY_RESTART_BOOTLOADER));
                    if (mRestartFastbootPref != null)
                        mRestartFastbootPref.setChecked(
                                updated.contains(RESTART_ACTION_KEY_RESTART_FASTBOOT));
                    if (mRestartDownloadPref != null)
                        mRestartDownloadPref.setChecked(
                                updated.contains(RESTART_ACTION_KEY_RESTART_DOWNLOAD));
                    if (mRestartSystemUIPref != null)
                        mRestartSystemUIPref.setChecked(
                                updated.contains(RESTART_ACTION_KEY_RESTART_SYSTEMUI));
                }
            };
        }
        mContext.getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(RESTART_ACTIONS_SETTING),
                false,
                mRestartActionsObserver,
                UserHandle.myUserId());

        updatePreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePreferences();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mRestartActionsObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mRestartActionsObserver);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        boolean value;

        if (preference == mScreenshotPref) {
            value = mScreenshotPref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_SCREENSHOT);

        } else if (preference == mOnTheGoPref) {
            value = mOnTheGoPref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_ONTHEGO);

        } else if (preference == mAirplanePref) {
            value = mAirplanePref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_AIRPLANE);

        } else if (preference == mUsersPref) {
            value = mUsersPref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_USERS);

        } else if (preference == mLockDownPref) {
            value = mLockDownPref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_LOCKDOWN);

        } else if (preference == mEmergencyPref) {
            value = mEmergencyPref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_EMERGENCY);

        } else if (preference == mDeviceControlsPref) {
            value = mDeviceControlsPref.isChecked();
            mLineageGlobalActions.updateUserConfig(value, GLOBAL_ACTION_KEY_DEVICECONTROLS);

        } else if (preference == mRestartRecoveryPref) {
            updateRestartAction(RESTART_ACTION_KEY_RESTART_RECOVERY,
                    mRestartRecoveryPref.isChecked());

        } else if (preference == mRestartBootloaderPref) {
            updateRestartAction(RESTART_ACTION_KEY_RESTART_BOOTLOADER,
                    mRestartBootloaderPref.isChecked());

        } else if (preference == mRestartFastbootPref) {
            updateRestartAction(RESTART_ACTION_KEY_RESTART_FASTBOOT,
                    mRestartFastbootPref.isChecked());

        } else if (preference == mRestartDownloadPref) {
            updateRestartAction(RESTART_ACTION_KEY_RESTART_DOWNLOAD,
                    mRestartDownloadPref.isChecked());

        } else if (preference == mRestartSystemUIPref) {
            updateRestartAction(RESTART_ACTION_KEY_RESTART_SYSTEMUI,
                    mRestartSystemUIPref.isChecked());

        } else {
            return super.onPreferenceTreeClick(preference);
        }
        return true;
    }

    private void updatePreferences() {
        boolean advancedRebootEnabled = LineageSettings.Secure.getIntForUser(
                mContext.getContentResolver(),
                LineageSettings.Secure.ADVANCED_REBOOT,
                0,
                UserHandle.myUserId()) == 1;
        if (mRestartItemsCategory != null) {
            mRestartItemsCategory.setEnabled(advancedRebootEnabled);
        }

        boolean isKeyguardSecure = mLockPatternUtils.isSecure(UserHandle.myUserId());
        if (mLockDownPref != null) {
            mLockDownPref.setEnabled(isKeyguardSecure);
            mLockDownPref.setChecked(mLineageGlobalActions.userConfigContains(
                    GLOBAL_ACTION_KEY_LOCKDOWN));
            if (isKeyguardSecure) {
                mLockDownPref.setSummary(null);
            } else {
                mLockDownPref.setSummary(R.string.power_menu_actions_lockdown_unavailable);
            }
        }
        if (mUsersPref != null) {
            if (!UserHandle.MU_ENABLED || !UserManager.supportsMultipleUsers()) {
                mPowerMenuItemsCategory.removePreference(mUsersPref);
                mUsersPref = null;
            } else {
                List<UserInfo> users = mUserManager.getUsers();
                boolean enabled = (users.size() > 1);
                mUsersPref.setChecked(mLineageGlobalActions.userConfigContains(
                        GLOBAL_ACTION_KEY_USERS) && enabled);
                mUsersPref.setEnabled(enabled);
            }
        }
    }

    /**
     * Read the persisted set of enabled restart actions from LineageSettings.
     * Falls back to DEFAULT_RESTART_ACTIONS if the key has never been written,
     * mirroring the behaviour that the resource overlay config_restartActionsList
     * previously provided.
     */
    private Set<String> getEnabledRestartActions() {
        String stored = LineageSettings.System.getStringForUser(
                mContext.getContentResolver(), RESTART_ACTIONS_SETTING,
                UserHandle.myUserId());
        if (stored == null) {
            return new HashSet<>(DEFAULT_RESTART_ACTIONS);
        }
        if (stored.isEmpty()) {
            return new HashSet<>();
        }
        return new HashSet<>(Arrays.asList(stored.split("\\|")));
    }

    /**
     * Persist the updated restart action set to LineageSettings so that
     * GlobalActionsDialogLite can read it at menu-open time.
     */
    private void updateRestartAction(String actionKey, boolean enabled) {
        Set<String> actions = getEnabledRestartActions();
        if (enabled) {
            actions.add(actionKey);
        } else {
            actions.remove(actionKey);
        }
        LineageSettings.System.putStringForUser(
                mContext.getContentResolver(),
                RESTART_ACTIONS_SETTING,
                String.join("|", actions),
                UserHandle.myUserId());
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.power_menu_actions);
}
