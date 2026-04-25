/*
 * SPDX-FileCopyrightText: Evolution X
 * Copyright (C) 2025 crDroid Android Project
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * SPDX-License-Identifier: Apache-2.0
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
package org.evolution.settings.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

import androidx.appcompat.app.AlertDialog

import com.android.settings.R
import com.android.internal.util.evolution.Utils

object SystemUtils {

    @JvmStatic
    fun showSystemUiRestartDialog(context: Context) {
        AlertDialog.Builder(context)
            .setTitle(R.string.systemui_restart_title)
            .setMessage(R.string.systemui_restart_message)
            .setPositiveButton(R.string.systemui_restart_yes) { _, _ ->
                restartSystemUI(context)
            }
            .setNegativeButton(R.string.systemui_restart_not_now, null)
            .show()
    }

    @JvmStatic
    fun restartSystemUI(context: Context) {
        Toast.makeText(
            context,
            R.string.systemui_restart_process,
            Toast.LENGTH_LONG
        ).show()

        Handler(Looper.getMainLooper()).postDelayed({
            Utils.restartSystemUI()
        }, 2000) // 2-second delay
    }

    @JvmStatic
    fun reloadSystemUI(context: Context) {
        val resolver = context.contentResolver
        val currentValue = Settings.System.getInt(resolver, "system_ui_reload", 0)
        Settings.System.putInt(resolver, "system_ui_reload", if (currentValue == 0) 1 else 0)
    }
}
