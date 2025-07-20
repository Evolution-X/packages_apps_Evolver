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

import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import com.android.settings.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class WidgetPickerBottomSheetDialog : BottomSheetDialogFragment() {

    private lateinit var selectedRecyclerView: RecyclerView
    private lateinit var pickerRecyclerView: RecyclerView
    private lateinit var confirmButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var guideTextView: View

    private val workingWidgets = mutableListOf<String>()
    private val maxWidgets = 4

    private val selectedAdapter = SelectedWidgetAdapter(
        onRemove = { removeWidget(it) },
        onReorder = { newList ->
            workingWidgets.clear()
            workingWidgets.addAll(newList)
        }
    )
    private val pickerAdapter = WidgetPickerAdapter(maxWidgets) { widget ->
        if (workingWidgets.contains(widget)) {
            removeWidget(widget)
        } else {
            addWidget(widget)
        }
    }

    override fun onStart() {
        super.onStart()

        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
    }

    override fun getTheme(): Int {
        return R.style.BottomSheetDialogStyle
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.widget_picker_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedRecyclerView = view.findViewById(R.id.selected_recycler)
        pickerRecyclerView = view.findViewById(R.id.picker_recycler)
        confirmButton = view.findViewById(R.id.confirm_button)
        cancelButton = view.findViewById(R.id.cancel_button)
        resetButton = view.findViewById(R.id.reset_button)
        guideTextView = view.findViewById(R.id.selected_guide_text)

        selectedRecyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        selectedRecyclerView.adapter = selectedAdapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT or
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                selectedAdapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun isLongPressDragEnabled(): Boolean = true
        })
        itemTouchHelper.attachToRecyclerView(selectedRecyclerView)

        pickerRecyclerView.layoutManager = LinearLayoutManager(context)
        pickerRecyclerView.adapter = pickerAdapter

        workingWidgets.clear()
        val initial = arguments?.getStringArrayList("widgets") ?: arrayListOf()
        workingWidgets.addAll(initial)

        selectedAdapter.setWidgets(workingWidgets)
        pickerAdapter.setSelection(workingWidgets)

        confirmButton.setOnClickListener {
            Settings.System.putString(
                requireContext().contentResolver,
                "lockscreen_widgets_extras",
                workingWidgets.joinToString(",")
            )
            dismiss()
        }

        cancelButton.setOnClickListener { dismiss() }

        resetButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.reset_to_default)
                .setMessage(R.string.reset_confirmation)
                .setPositiveButton(R.string.yes) { _, _ -> performReset() }
                .setNegativeButton(R.string.no, null)
                .show()
        }

        updateGuideText()
    }

    private fun addWidget(widget: String) {
        if (!workingWidgets.contains(widget) && workingWidgets.size < maxWidgets) {
            workingWidgets.add(widget)
            selectedAdapter.setWidgets(workingWidgets)
            pickerAdapter.setSelection(workingWidgets)
            updateGuideText()
        }
    }

    private fun removeWidget(widget: String) {
        workingWidgets.remove(widget)
        selectedAdapter.setWidgets(workingWidgets)
        pickerAdapter.setSelection(workingWidgets)
        updateGuideText()
    }

    private fun performReset() {
        workingWidgets.clear()
        selectedAdapter.setWidgets(workingWidgets)
        pickerAdapter.setSelection(workingWidgets)
        updateGuideText()
    }

    private fun updateGuideText() {
        val targetText = if (workingWidgets.isNotEmpty()) {
            getString(R.string.selected_widgets_guide)
        } else {
            getString(R.string.selected_widgets_empty)
        }

        val textView = guideTextView as TextView
        if (textView.text == targetText) return

        textView.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction {
                textView.text = targetText
                textView.animate().alpha(1f).setDuration(150).start()
            }
            .start()
    }

    companion object {
        fun newInstance(current: List<String>): WidgetPickerBottomSheetDialog {
            val dialog = WidgetPickerBottomSheetDialog()
            dialog.arguments = bundleOf("widgets" to ArrayList(current))
            return dialog
        }
    }
}
