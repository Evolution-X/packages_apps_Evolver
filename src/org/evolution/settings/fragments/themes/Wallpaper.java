/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes;

import android.content.Context;
import android.os.Bundle;

import androidx.preference.Preference;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;

import org.evolution.settings.preferences.CustomSeekBarPreference;
import org.evolution.settings.utils.SystemUtils;

@SearchIndexable
public class Wallpaper extends SettingsPreferenceFragment 
            implements Preference.OnPreferenceChangeListener {
    public static final String TAG = "Wallpaper";
    
    private Preference mBlurWpPref;
    private Preference mBlurWpStylePref;
    private Preference mEffectTypePref;
    private Preference mEffectTargetPref;
    private Preference mSaturationPref;
    private Preference mPixelationPref;
    private Preference mVignettePref;
    private Preference mPosterizePref;
    private Preference mDimPref;
    private Preference mDimLvlPref;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.wallpaper_settings);
        
        mBlurWpPref = findPreference("persist.sys.wallpaper.blur_enabled");
        mBlurWpPref.setOnPreferenceChangeListener(this);
        
        mBlurWpStylePref = findPreference("persist.sys.wallpaper.blur_type");
        mBlurWpStylePref.setOnPreferenceChangeListener(this);
        
        mEffectTypePref = findPreference("persist.sys.wallpaper.effect_type");
        mEffectTypePref.setOnPreferenceChangeListener(this);
        
        mEffectTargetPref = findPreference("persist.sys.wallpaper.effect_target");
        mEffectTargetPref.setOnPreferenceChangeListener(this);
        
        mSaturationPref = findPreference("persist.sys.wallpaper.saturation_level");
        mSaturationPref.setOnPreferenceChangeListener(this);
        
        mPixelationPref = findPreference("persist.sys.wallpaper.pixelation_size");
        mPixelationPref.setOnPreferenceChangeListener(this);
        
        mVignettePref = findPreference("persist.sys.wallpaper.vignette_intensity");
        mVignettePref.setOnPreferenceChangeListener(this);
        
        mPosterizePref = findPreference("persist.sys.wallpaper.posterize_levels");
        mPosterizePref.setOnPreferenceChangeListener(this);
        
        mDimPref = findPreference("persist.sys.wallpaper.dim_enabled");
        mDimPref.setOnPreferenceChangeListener(this);
        
        mDimLvlPref = findPreference("persist.sys.wallpaper.dim_level");
        mDimLvlPref.setOnPreferenceChangeListener(this);
        
        updateEffectDependencies();
        updateBlurDependencies();
        updateDimDependencies();
    }
    
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mBlurWpPref 
                || preference == mBlurWpStylePref 
                || preference == mEffectTypePref
                || preference == mEffectTargetPref
                || preference == mSaturationPref
                || preference == mPixelationPref
                || preference == mVignettePref
                || preference == mPosterizePref
                || preference == mDimPref 
                || preference == mDimLvlPref) {
            
            if (preference == mDimLvlPref) {
                android.os.SystemProperties.set("persist.sys.wallpaper.dim_level", newValue.toString());
            } else if (preference == mSaturationPref) {
                android.os.SystemProperties.set("persist.sys.wallpaper.saturation_level", newValue.toString());
            } else if (preference == mPixelationPref) {
                android.os.SystemProperties.set("persist.sys.wallpaper.pixelation_size", newValue.toString());
            } else if (preference == mVignettePref) {
                android.os.SystemProperties.set("persist.sys.wallpaper.vignette_intensity", newValue.toString());
            } else if (preference == mPosterizePref) {
                android.os.SystemProperties.set("persist.sys.wallpaper.posterize_levels", newValue.toString());
            }
            
            if (preference == mEffectTypePref) {
                updateEffectDependencies();
            } else if (preference == mBlurWpPref) {
                updateBlurDependencies();
            } else if (preference == mDimPref) {
                updateDimDependencies();
            }
            
            SystemUtils.showSystemUiRestartDialog(getContext());
            return true;
        }
        return false;
    }
    
    private void updateEffectDependencies() {
        int effectType = android.os.SystemProperties.getInt("persist.sys.wallpaper.effect_type", 0);
        mEffectTargetPref.setVisible(effectType > 0);
        mVignettePref.setVisible(effectType == 3);
        mPixelationPref.setVisible(effectType == 4);
        mSaturationPref.setVisible(effectType == 5);
        mPosterizePref.setVisible(effectType == 11);
    }
    
    private void updateBlurDependencies() {
        int blurEnabled = android.os.SystemProperties.getInt("persist.sys.wallpaper.blur_enabled", 0);
        mBlurWpStylePref.setVisible(blurEnabled > 0);
    }
    
    private void updateDimDependencies() {
        int dimEnabled = android.os.SystemProperties.getInt("persist.sys.wallpaper.dim_enabled", 0);
        mDimLvlPref.setVisible(dimEnabled > 0);
    }
    
    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }
    
    /**
     * For search
     */
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.wallpaper_settings);
}
