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
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.android.settings.R

class SelectedWidgetAdapter(
    private val onRemove: (String) -> Unit,
    private val onReorder: (List<String>) -> Unit
) : RecyclerView.Adapter<SelectedWidgetAdapter.WidgetViewHolder>() {

    private val widgets = mutableListOf<String>()

    private val widgetIcons = mapOf(
        "torch" to R.drawable.ic_flashlight,
        "wifi" to R.drawable.ic_wifi,
        "data" to R.drawable.ic_data,
        "ringer" to R.drawable.ic_vibrate,
        "bt" to R.drawable.ic_bt,
        "hotspot" to R.drawable.ic_hotspot
    )

    fun setWidgets(newList: List<String>) {
        widgets.clear()
        widgets.addAll(newList)
        notifyDataSetChanged()
    }

    fun getWidgets(): List<String> = widgets.toList()

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        val item = widgets.removeAt(from)
        widgets.add(to, item)
        notifyItemMoved(from, to)
        onReorder(widgets)
    }

    fun removeItem(position: Int) {
        val item = widgets.removeAt(position)
        notifyItemRemoved(position)
        onRemove(item)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WidgetViewHolder {
        val container = FrameLayout(parent.context)
        val lp = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.layoutParams = lp
        return WidgetViewHolder(container)
    }

    override fun getItemCount(): Int = widgets.size

    override fun onBindViewHolder(holder: WidgetViewHolder, position: Int) {
        val widget = widgets[position]
        val chip = LayoutInflater.from(holder.itemView.context)
            .inflate(R.layout.item_widget_chip, holder.container, false) as Chip

        chip.text = widget
        chip.isChecked = true

        widgetIcons[widget]?.let {
            chip.chipIcon = ContextCompat.getDrawable(holder.itemView.context, it)
        }

        val onRemoveClick = {
            removeItem(holder.bindingAdapterPosition)
        }
        chip.setOnClickListener { onRemoveClick() }
        chip.setOnCloseIconClickListener { onRemoveClick() }

        holder.container.removeAllViews()
        holder.container.addView(chip)
    }

    inner class WidgetViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
}
