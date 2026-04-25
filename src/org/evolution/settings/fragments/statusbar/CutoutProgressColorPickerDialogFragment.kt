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

package org.evolution.settings.fragments.statusbar

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.evolution.settings.utils.ColorPickerDialog

class CutoutProgressColorPickerDialogFragment : DialogFragment() {

    private var initialColor: Color = Color(0xFF2196F3)
    private var dialogTitle: String = "Choose color"
    private var onColorSelected: ((Color) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Dialog_NoActionBar)

        arguments?.let { args ->
            args.getString(ARG_COLOR_HEX)?.let { hex ->
                try {
                    initialColor = Color(AndroidColor.parseColor("#${hex.removePrefix("#")}"))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            args.getString(ARG_TITLE)?.let { title ->
                dialogTitle = title
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                SettingsTheme {
                    ColorPickerDialog(
                        initialColor = initialColor,
                        onDismiss = { dismiss() },
                        onColorSelected = { color ->
                            onColorSelected?.invoke(color)
                            dismiss()
                        }
                    )
                }
            }
        }
    }

    fun setOnColorSelectedListener(listener: (Color) -> Unit) {
        onColorSelected = listener
    }

    companion object {
        const val TAG = "CutoutProgressColorPickerDialogFragment"

        private const val ARG_COLOR_HEX = "color_hex"
        private const val ARG_TITLE = "dialog_title"

        fun newInstance(title: String, colorHex: String): CutoutProgressColorPickerDialogFragment {
            return CutoutProgressColorPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_COLOR_HEX, colorHex)
                }
            }
        }
    }
}
