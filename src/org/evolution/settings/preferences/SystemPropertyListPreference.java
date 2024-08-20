/*
 * Copyright (C) 2023-2024 the risingOS Project
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

package org.evolution.settings.preferences;

import android.content.Context;
import android.os.SystemProperties;
import android.util.AttributeSet;

import com.android.settingslib.development.SystemPropPoker;

import lineageos.preference.SelfRemovingListPreference;

public class SystemPropertyListPreference extends SelfRemovingListPreference {

    public SystemPropertyListPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public SystemPropertyListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SystemPropertyListPreference(Context context) {
        super(context);
    }

    @Override
    protected boolean isPersisted() {
        return !SystemProperties.get(getKey(), "").isEmpty();
    }

    @Override
    protected void putString(String key, String value) {
        SystemProperties.set(key, value);
        SystemPropPoker.getInstance().poke();
    }

    @Override
    protected String getString(String key, String defaultValue) {
        return SystemProperties.get(key, defaultValue);
    }
}
