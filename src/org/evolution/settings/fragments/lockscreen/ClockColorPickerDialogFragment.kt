/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package org.evolution.settings.fragments.lockscreen

import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.evolution.settings.utils.ClockColorPickerDialog

class ClockColorPickerDialogFragment : DialogFragment() {

    private var onColorApplied: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Dialog_NoActionBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val storedArgb = Settings.Secure.getIntForUser(
            requireContext().contentResolver,
            KEY_CLOCK_COLOR,
            DEFAULT_COLOR_ARGB,
            UserHandle.USER_CURRENT
        )
        val initialHex = String.format("%06X", storedArgb and 0x00FFFFFF)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SettingsTheme {
                    ClockColorPickerDialog(
                        initialColor = initialHex,
                        onDismiss = { dismiss() },
                        onColorSelected = { color ->
                            persistColor(color)
                            onColorApplied?.invoke()
                            dismiss()
                        }
                    )
                }
            }
        }
    }

    private fun persistColor(color: Color) {
        val argb = (color.toArgb() and 0x00FFFFFF) or 0xFF000000.toInt()
        Settings.Secure.putIntForUser(
            requireContext().contentResolver,
            KEY_CLOCK_COLOR,
            argb,
            UserHandle.USER_CURRENT
        )
    }

    fun setOnColorAppliedListener(block: () -> Unit) {
        onColorApplied = block
    }

    companion object {
        const val TAG = "ClockColorPickerDialogFragment"

        const val KEY_CLOCK_COLOR = "lock_screen_custom_clock_custom_color"

        const val DEFAULT_COLOR_ARGB = 0xFFFFFFFF.toInt()

        @JvmStatic
        fun newInstance(): ClockColorPickerDialogFragment = ClockColorPickerDialogFragment()
    }
}
