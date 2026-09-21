/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences.colorpicker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import org.evolution.settings.fragments.miscellaneous.ExpressiveTheme
import org.evolution.settings.utils.PreferenceColorPickerDialog

/**
 * Hosts the skydoves color picker for [ColorPickerPreference] and reports the chosen
 * ARGB color back through the FragmentManager result API.
 */
class ColorPickerPrefDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Dialog_NoActionBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY).orEmpty()
        val title = args.getString(ARG_TITLE)
        val initialColor = args.getInt(ARG_INITIAL_COLOR)
        val alphaEnabled = args.getBoolean(ARG_ALPHA_ENABLED)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ExpressiveTheme {
                    PreferenceColorPickerDialog(
                        title = title,
                        initialColor = initialColor,
                        alphaEnabled = alphaEnabled,
                        onDismiss = { dismiss() },
                        onColorSelected = { color ->
                            parentFragmentManager.setFragmentResult(
                                requestKey,
                                Bundle().apply { putInt(RESULT_COLOR, color) }
                            )
                            dismiss()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "ColorPickerPrefDialogFragment"
        const val RESULT_COLOR = "color"

        private const val ARG_REQUEST_KEY = "request_key"
        private const val ARG_TITLE = "title"
        private const val ARG_INITIAL_COLOR = "initial_color"
        private const val ARG_ALPHA_ENABLED = "alpha_enabled"

        @JvmStatic
        fun newInstance(
            requestKey: String,
            title: String?,
            initialColor: Int,
            alphaEnabled: Boolean
        ): ColorPickerPrefDialogFragment = ColorPickerPrefDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putInt(ARG_INITIAL_COLOR, initialColor)
                putBoolean(ARG_ALPHA_ENABLED, alphaEnabled)
            }
        }
    }
}
