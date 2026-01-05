/*
 * Copyright (C) 2025 AxionOS Project
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
package org.evolution.settings.preferences

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.RadioButton
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settings.R

class RadioButtonPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    interface OnRadioButtonClickedListener {
        fun onRadioButtonClicked(preference: RadioButtonPreference)
    }

    private var isSelectedInternal = false
    private var listener: OnRadioButtonClickedListener? = null

    private val clickListener = View.OnClickListener {
        listener?.onRadioButtonClicked(this)
    }

    init {
        widgetLayoutResource = R.layout.radio_button_preference_widget
    }

    fun setOnRadioButtonClickedListener(listener: OnRadioButtonClickedListener?) {
        this.listener = listener
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val radioButton = holder.findViewById(R.id.radio_button) as? RadioButton
        radioButton?.apply {
            isChecked = isSelectedInternal
            setOnClickListener(clickListener)
        }
        holder.itemView.setOnClickListener(clickListener)
    }

    var isSelected: Boolean
        get() = isSelectedInternal
        set(value) {
            if (value == isSelectedInternal) return
            isSelectedInternal = value
            notifyChanged()
        }
}
