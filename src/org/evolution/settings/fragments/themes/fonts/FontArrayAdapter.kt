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
import android.graphics.Typeface
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

import com.android.settings.R

import androidx.core.content.ContextCompat

class FontArrayAdapter(
    context: Context,
    textViewResourceId: Int,
    private val fontPackageNames: List<String>,
    private val fontManager: FontManager,
    private val mIsNightMode: Boolean
) : ArrayAdapter<String>(context, textViewResourceId, fontPackageNames) {

    private val mContext = context
    private val typefaces = fontManager.fonts

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent) as TextView
        val typeface = getTypefaceForPosition(position)
        if (typeface != null) {
            view.typeface = typeface
        }
        view.text = getLabelForPosition(position)
        view.setTextColor(ContextCompat.getColor(mContext,
            if (mIsNightMode) R.color.font_drop_down_bg_light else R.color.font_drop_down_bg_dark))
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getDropDownView(position, convertView, parent) as TextView
        val typeface = getTypefaceForPosition(position)
        if (typeface != null) {
            view.typeface = typeface
        }
        view.text = getLabelForPosition(position)
        view.setTextColor(ContextCompat.getColor(mContext,
            if (mIsNightMode) R.color.font_drop_down_bg_light else R.color.font_drop_down_bg_dark))
        return view
    }

    private fun getLabelForPosition(position: Int): String {
        return fontManager.getLabel(mContext, getFontPackageName(position))
    }

    private fun getTypefaceForPosition(position: Int): Typeface? {
        return fontManager.getTypeface(context, getFontPackageName(position))
    }

    private fun getFontPackageName(position: Int): String {
        return fontPackageNames[position]
    }
}
