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
package org.evolution.settings.fragments.lockscreen

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.android.settings.R

class WidgetPickerAdapter(
    private val maxSelection: Int,
    private val onToggleWidget: (String) -> Unit
) : RecyclerView.Adapter<WidgetPickerAdapter.ViewHolder>() {

    private val allWidgets = listOf("torch", "wifi", "data", "ringer", "bt", "hotspot")

    private val widgetIcons = mapOf(
        "torch" to R.drawable.ic_flashlight,
        "wifi" to R.drawable.ic_wifi,
        "data" to R.drawable.ic_data,
        "ringer" to R.drawable.ic_vibrate,
        "bt" to R.drawable.ic_bt,
        "hotspot" to R.drawable.ic_hotspot
    )

    private val selected = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_widget_picker, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = allWidgets.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val widget = allWidgets[position]
        holder.name.text = getWidgetDisplayName(holder.itemView.context, widget)
        holder.checkbox.isChecked = selected.contains(widget)

        widgetIcons[widget]?.let { iconRes ->
            val icon = AppCompatResources.getDrawable(holder.itemView.context, iconRes)?.mutate()
            icon?.setTint(holder.name.currentTextColor)
            holder.icon.setImageDrawable(icon)
        }

        holder.itemView.setOnClickListener {
            toggleSelection(widget, position)
        }

        holder.checkbox.setOnClickListener {
            toggleSelection(widget, position)
        }
    }

    private fun getWidgetDisplayName(context: Context, id: String): String {
        return when (id) {
            "torch" -> context.getString(R.string.widget_torch)
            "wifi" -> context.getString(R.string.widget_wifi)
            "data" -> context.getString(R.string.widget_data)
            "ringer" -> context.getString(R.string.widget_ringer)
            "bt" -> context.getString(R.string.widget_bt)
            "hotspot" -> context.getString(R.string.widget_hotspot)
            else -> id
        }
    }

    private fun toggleSelection(widget: String, position: Int) {
        if (selected.contains(widget)) {
            selected.remove(widget)
            onToggleWidget(widget)
        } else if (selected.size < maxSelection) {
            selected.add(widget)
            onToggleWidget(widget)
        }
        notifyItemChanged(position)
    }

    fun setSelection(current: List<String>) {
        selected.clear()
        selected.addAll(current)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.widget_icon)
        val name: TextView = view.findViewById(R.id.widget_name)
        val checkbox: CheckBox = view.findViewById(R.id.widget_checkbox)
    }
}
