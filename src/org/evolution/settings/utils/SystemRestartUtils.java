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
package org.evolution.settings.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;

import com.android.settings.R;

public class SystemRestartUtils {

    private static final String TAG = "SystemRestartUtils";

    public static void showSystemUIRestartDialog(Context context) {
        new AlertDialog.Builder(context)
            .setTitle(com.android.internal.R.string.systemui_restart_title)
            .setMessage(com.android.internal.R.string.systemui_restart_message)
            .setPositiveButton(com.android.internal.R.string.ok, (dialog, which) -> restartSystemUI(context))
            .setNegativeButton(com.android.internal.R.string.cancel, null)
            .show();
    }

    public static void restartSystemUI(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int currentValue = Settings.System.getInt(resolver, "system_ui_restart", 0);
        int newValue = (currentValue == 0) ? 1 : 0;
        Settings.System.putInt(resolver, "system_ui_restart", newValue);
    }

    public static void reloadSystemUI(Context context) {
        ContentResolver resolver = context.getContentResolver();
        int currentValue = Settings.System.getInt(resolver, "system_ui_reload", 0);
        int newValue = (currentValue == 0) ? 1 : 0;
        Settings.System.putInt(resolver, "system_ui_reload", newValue);
    }
}
