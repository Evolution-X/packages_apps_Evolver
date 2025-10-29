/*
 * Copyright (C) 2023 The risingOS Android Project
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
package org.evolution.settings.fragments.themes.fonts

import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ForegroundColorSpan

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import org.evolution.settings.fragments.OptimizedSettingsFragment

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FontPickerPreview : OptimizedSettingsFragment() {

    private lateinit var fontSelector: TextView
    private lateinit var previewText: TextView
    private lateinit var fontManager: FontManager
    private lateinit var externalFontInstaller: ExternalFontInstaller
    private lateinit var applyFab: ExtendedFloatingActionButton
    private lateinit var customFontInfoCard: LinearLayout
    private lateinit var customFontDivider: View
    private lateinit var customFontNameText: TextView
    private lateinit var resetCustomFontButton: Button
    private var currentFontPosition = -1
    private var hasCustomFont = false
    private var customFontName = ""
    private var previewFontUri: Uri? = null
    private var previewTypeface: Typeface? = null
    private var needsReboot = false

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { fontUri ->
            lifecycleScope.launch {
                val typeface = externalFontInstaller.loadTypefaceFromUri(fontUri)
                if (typeface != null) {
                    previewTypeface = typeface
                    previewFontUri = fontUri
                    showFontPreviewDialog(typeface)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            R.string.toast_invalid_font_file,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontManager = FontManager(requireActivity(), false)
        externalFontInstaller = ExternalFontInstaller(requireActivity())
        activity?.title = activity?.getString(R.string.font_styles_title)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val rootView = inflater.inflate(R.layout.font_picker_preview, container, false)
        
        fontSelector = rootView.findViewById(R.id.font_selector)
        previewText = rootView.findViewById(R.id.font_preview_text)
        customFontInfoCard = rootView.findViewById(R.id.custom_font_info_card)
        customFontDivider = rootView.findViewById(R.id.custom_font_divider)
        customFontNameText = rootView.findViewById(R.id.custom_font_name_text)
        resetCustomFontButton = rootView.findViewById(R.id.reset_custom_font_button)
        
        setupPreviewText()
        setupFontSelector()
        setupCustomFontCard()
        
        applyFab = rootView.findViewById(R.id.apply_extended_fab)
        applyFab.setOnClickListener {
            if (needsReboot) {
                ExternalFontInstaller.rebootDevice()
            } else if (currentFontPosition != -1) {
                if (hasCustomFont && currentFontPosition == 0) {
                    needsReboot = true
                    updateApplyButton()
                } else {
                    fontManager.enableFontPackage(currentFontPosition)
                }
            }
        }

        loadCurrentFont()

        return rootView
    }

    private fun setupPreviewText() {
        val text = previewText.text.toString()
        val spannableString = SpannableString(text)
        val typedValue = TypedValue()
        activity?.theme?.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
        val colorAccent = typedValue.data
        val startIndex = text.indexOf("A")
        val endIndex = text.length
        spannableString.setSpan(
            ForegroundColorSpan(colorAccent), 
            startIndex, 
            endIndex, 
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        previewText.text = spannableString
    }

    private fun setupFontSelector() {
        val backgroundColor = ContextCompat.getColor(requireContext(), 
            if (isNightMode()) R.color.font_drop_down_bg_dark else R.color.font_drop_down_bg_light)
        fontSelector.setTextColor(ContextCompat.getColor(requireContext(),
            if (isNightMode()) R.color.font_drop_down_bg_light else R.color.font_drop_down_bg_dark))
        fontSelector.backgroundTintList = ColorStateList.valueOf(backgroundColor)

        fontSelector.setOnClickListener { v ->
            showFontSelectorPopup(v, backgroundColor)
        }
    }

    private fun setupCustomFontCard() {
        resetCustomFontButton.setOnClickListener {
            showResetCustomFontDialog()
        }
    }

    private fun updateApplyButton() {
        if (needsReboot) {
            applyFab.text = getString(R.string.reboot_now)
            applyFab.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_restart)
        } else {
            applyFab.text = getString(R.string.apply_button_text)
            applyFab.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_check_24dp)
        }
    }

    private fun showResetCustomFontDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.reset_default_title)
            .setMessage(R.string.reset_default_message)
            .setPositiveButton(android.R.string.ok) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                resetCustomFont()
            }
            .setNegativeButton(android.R.string.cancel) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
            }
            .show()
    }

    private fun resetCustomFont() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Settings.Secure.putString(
                    requireContext().contentResolver,
                    "custom_font_name",
                    ""
                )
                
                externalFontInstaller.resetFontUpdates()
            }
            
            withContext(Dispatchers.Main) {
                customFontName = ""
                hasCustomFont = false
                needsReboot = true
                
                updateCustomFontCardVisibility()
                updateApplyButton()
                
                val currentFontPackage = fontManager.currentFontPackage
                val fontPackageNames = fontManager.allFontPackages
                currentFontPosition = fontPackageNames.indexOf(currentFontPackage)
                if (currentFontPosition != -1) {
                    fontSelector.text = fontManager.getLabel(
                        requireContext(), 
                        fontPackageNames[currentFontPosition]
                    )
                    applyFontToPreview(fontPackageNames[currentFontPosition])
                }
                
                Toast.makeText(
                    requireContext(),
                    R.string.reset,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateCustomFontCardVisibility() {
        if (hasCustomFont) {
            customFontInfoCard.visibility = View.VISIBLE
            customFontDivider.visibility = View.VISIBLE
            customFontNameText.text = customFontName
        } else {
            customFontInfoCard.visibility = View.GONE
            customFontDivider.visibility = View.GONE
        }
    }

    private fun showFontSelectorPopup(anchor: View, backgroundColor: Int) {
        val popupView = LayoutInflater.from(activity).inflate(R.layout.popup_font_selector, null)
        val popupWindow = PopupWindow(
            popupView, 
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            true
        )

        val fontListView = popupView.findViewById<ListView>(R.id.font_list_view)
        val fontPackageNames = fontManager.allFontPackages.toMutableList()
        
        val displayItems = mutableListOf<FontDisplayItem>()
        
        if (hasCustomFont) {
            displayItems.add(FontDisplayItem.CustomFont(customFontName))
        }
        
        fontPackageNames.forEach { pkg ->
            displayItems.add(FontDisplayItem.SystemFont(pkg))
        }
        
        displayItems.add(FontDisplayItem.AddCustomFont)

        val fontAdapter = EnhancedFontArrayAdapter(
            requireActivity(),
            displayItems,
            fontManager,
            isNightMode()
        )
        fontListView.adapter = fontAdapter

        fontListView.setOnItemClickListener { _, _, position, _ ->
            val selectedItem = displayItems[position]
            
            when (selectedItem) {
                is FontDisplayItem.AddCustomFont -> {
                    popupWindow.dismiss()
                    fontPickerLauncher.launch(arrayOf("font/ttf", "font/otf"))
                }
                is FontDisplayItem.CustomFont -> {
                    currentFontPosition = 0
                    applyCustomFontToPreview()
                    fontSelector.text = customFontName
                    popupWindow.dismiss()
                }
                is FontDisplayItem.SystemFont -> {
                    val fontPackages = fontManager.allFontPackages
                    val actualPosition = fontPackages.indexOf(selectedItem.packageName)
                    if (actualPosition >= 0) {
                        currentFontPosition = actualPosition
                        applyFontToPreview(selectedItem.packageName)
                        fontSelector.text = fontManager.getLabel(requireContext(), selectedItem.packageName)
                    }
                    popupWindow.dismiss()
                }
            }
        }

        popupView.setBackgroundResource(R.drawable.custom_background)
        val backgroundDrawable = popupView.background
        backgroundDrawable?.setColorFilter(backgroundColor, PorterDuff.Mode.SRC_ATOP)
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        popupWindow.showAsDropDown(anchor, 0, 10)
    }

    private fun showFontPreviewDialog(typeface: Typeface) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(
            R.layout.dialog_font_preview, null
        )
        val previewTextView = dialogView.findViewById<TextView>(R.id.preview_text)
        previewTextView.typeface = typeface
        
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.font_preview_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.add_font) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                installCustomFont()
            }
            .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                previewFontUri = null
                previewTypeface = null
            }
            .show()
    }

    private fun installCustomFont() {
        lifecycleScope.launch {
            previewFontUri?.let { uri ->
                val postScriptName = externalFontInstaller.installFontFromUri(uri)
                if (postScriptName != null) {
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        "custom_font_name",
                        postScriptName
                    )
                    customFontName = postScriptName
                    hasCustomFont = true
                    needsReboot = true
                    
                    applyCustomFontToPreview()
                    fontSelector.text = customFontName
                    currentFontPosition = 0
                    
                    updateCustomFontCardVisibility()
                    updateApplyButton()
                }
                previewFontUri = null
                previewTypeface = null
            }
        }
    }

    private fun loadCurrentFont() {
        customFontName = Settings.Secure.getString(
            requireContext().contentResolver,
            "custom_font_name"
        ) ?: ""
        hasCustomFont = customFontName.isNotEmpty()

        updateCustomFontCardVisibility()

        if (hasCustomFont) {
            applyCustomFontToPreview()
            fontSelector.text = customFontName
            currentFontPosition = 0
        } else {
            val currentFontPackage = fontManager.currentFontPackage
            val fontPackageNames = fontManager.allFontPackages
            currentFontPosition = fontPackageNames.indexOf(currentFontPackage)
            if (currentFontPosition != -1) {
                fontSelector.text = fontManager.getLabel(
                    requireContext(), 
                    fontPackageNames[currentFontPosition]
                )
                applyFontToPreview(fontPackageNames[currentFontPosition])
            }
        }
    }

    private fun isNightMode(): Boolean {
        val nightModeFlags = requireContext().resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
        return nightModeFlags?.equals(Configuration.UI_MODE_NIGHT_YES) == true
    }

    private fun applyFontToPreview(fontPackage: String) {
        val typeface = fontManager.getTypeface(requireContext(), fontPackage)
        if (typeface != null) {
            previewText.typeface = typeface
        } else {
            previewText.typeface = Typeface.create("googlesans", Typeface.NORMAL)
        }
    }

    private fun applyCustomFontToPreview() {
        try {
            val typeface = Typeface.create(
                ExternalFontInstaller.DEFAULT_FONT_FAMILY,
                Typeface.NORMAL
            )
            previewText.typeface = typeface
        } catch (e: Exception) {
            previewText.typeface = Typeface.DEFAULT
        }
    }

    override fun getMetricsCategory(): Int {
        return MetricsProto.MetricsEvent.VIEW_UNKNOWN
    }
}

sealed class FontDisplayItem {
    data class SystemFont(val packageName: String) : FontDisplayItem()
    data class CustomFont(val name: String) : FontDisplayItem()
    object AddCustomFont : FontDisplayItem()
}

class EnhancedFontArrayAdapter(
    context: Context,
    private val items: List<FontDisplayItem>,
    private val fontManager: FontManager,
    private val isNightMode: Boolean
) : android.widget.BaseAdapter() {
    
    private val mContext = context
    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
        val textView = view.findViewById<TextView>(android.R.id.text1)
        
        val item = items[position]
        when (item) {
            is FontDisplayItem.AddCustomFont -> {
                textView.text = mContext.getString(R.string.add_custom_font)
                textView.typeface = Typeface.DEFAULT_BOLD
            }
            is FontDisplayItem.CustomFont -> {
                textView.text = item.name.ifEmpty { mContext.getString(R.string.custom_font) }
                try {
                    val typeface = Typeface.create(ExternalFontInstaller.DEFAULT_FONT_FAMILY, Typeface.NORMAL)
                    textView.typeface = typeface
                } catch (e: Exception) {
                    textView.typeface = Typeface.DEFAULT
                }
            }
            is FontDisplayItem.SystemFont -> {
                val typeface = fontManager.getTypeface(mContext, item.packageName)
                if (typeface != null) {
                    textView.typeface = typeface
                }
                textView.text = fontManager.getLabel(mContext, item.packageName)
            }
        }
        
        textView.setTextColor(ContextCompat.getColor(mContext,
            if (isNightMode) R.color.font_drop_down_bg_light else R.color.font_drop_down_bg_dark))
        return view
    }
}
