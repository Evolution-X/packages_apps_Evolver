/*
 * Copyright (C) 2023-2024 The risingOS Android Project
 * Copyright (C) 2024-2025 Project Infinity X
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
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager

import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import org.evolution.settings.fragments.OptimizedSettingsFragment
import java.lang.ref.WeakReference
import org.evolution.settings.utils.SystemRestartUtils

import com.android.internal.util.android.ThemeUtils
import org.evolution.settings.fragments.themes.fonts.FontArrayAdapter
import org.evolution.settings.fragments.themes.fonts.FontManager
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class LockClockFontsPickerPreview : OptimizedSettingsFragment() {

    companion object {
        private const val TAG = "LockClockFontsPickerPreview"
        private const val PREF_FIRST_TIME = "first_time_clock_face_access"

        private val mCenterClocks = intArrayOf(2, 3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27)

        private val CLOCK_LAYOUTS = intArrayOf(
            R.layout.keyguard_clock_default,      // 0
            R.layout.keyguard_clock_oos,          // 1
            R.layout.keyguard_clock_center,       // 2
            R.layout.keyguard_clock_simple,       // 3
            R.layout.keyguard_clock_miui,         // 4
            R.layout.keyguard_clock_ide,          // 5
            R.layout.keyguard_clock_moto,         // 6
            R.layout.keyguard_clock_stylish,      // 7
            R.layout.keyguard_clock_stylish2,     // 8
            R.layout.keyguard_clock_stylish3,     // 9
            R.layout.keyguard_clock_stylish4,     // 10
            R.layout.keyguard_clock_stylish5,     // 11
            R.layout.keyguard_clock_stylish6,     // 12
            R.layout.keyguard_clock_stylish7,     // 13
            R.layout.keyguard_clock_stylish8,     // 14
            R.layout.keyguard_clock_stylish9,     // 15
            R.layout.keyguard_clock_stylish10,    // 16
            R.layout.keyguard_clock_small_cute,     // 17 - SmallCute
            R.layout.keyguard_clock_thin_long,     // 18 - ThinLong
            R.layout.keyguard_clock_more_more_thin,     // 19 - MoreMoreThin
            R.layout.keyguard_clock_normal_time,     // 20 - NormalTime
            R.layout.keyguard_clock_guoguo_type2,     // 21 - Guoguo2
            R.layout.keyguard_clock_guoguo_type3,     // 22 - Guoguo3
            R.layout.keyguard_clock_guoguo_type4,     // 23 - Guoguo4
            R.layout.keyguard_clock_ntype,     // 24 - NType
            R.layout.keyguard_clock_ndot,     // 25 - NDot
            R.layout.keyguard_clock_graphic,     // 26 - Graphic
            R.layout.keyguard_clock_london_ug      // 27 - LondonUG
        )

        // Clock positions that don't support lockscreen customizations
        private val LIMITED_CUSTOMIZATION_CLOCKS = intArrayOf(17, 18, 19, 20, 21, 22, 23)
    }

    private lateinit var viewPager: ViewPager
    private lateinit var pagerAdapter: ClockPagerAdapter
    private lateinit var fontManager: FontManager
    private lateinit var applyFab: ExtendedFloatingActionButton
    private lateinit var highlightGuide: View
    private lateinit var clockNameTextView: TextView

    private var mCurrentFontPosition = -1
    private var mClockPosition = 0

    override var mThemeUtils: ThemeUtils? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fontManager = FontManager(requireActivity(), true)
        activity?.title = activity?.getString(R.string.theme_customization_lock_clock_title)
        activity?.let {
            mThemeUtils = ThemeUtils.getInstance(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.lockscreen_font_picker_preview, container, false)
        clockNameTextView = rootView.findViewById(R.id.clock_name)

        viewPager = rootView.findViewById(R.id.view_pager)
        pagerAdapter = ClockPagerAdapter()
        viewPager.adapter = pagerAdapter
        mClockPosition = Settings.Secure.getIntForUser(context?.contentResolver, "clock_style", 0, UserHandle.USER_CURRENT)
        if (mClockPosition < 0 || mClockPosition >= CLOCK_LAYOUTS.size) {
            mClockPosition = 0
            Settings.Secure.putIntForUser(context?.contentResolver, "clock_style", 0, UserHandle.USER_CURRENT)
        }
        viewPager.currentItem = mClockPosition

        val fontMessage = rootView.findViewById<TextView>(R.id.font_message)
        val fontPackageNames = fontManager.allFontPackages
        val fontSelector = rootView.findViewById<TextView>(R.id.font_selector)
        val backgroundColor = ContextCompat.getColor(context!!,
            if (isNightMode()) R.color.font_drop_down_bg_dark else R.color.font_drop_down_bg_light)
        fontSelector.setTextColor(ContextCompat.getColor(context!!,
            if (isNightMode()) R.color.font_drop_down_bg_light else R.color.font_drop_down_bg_dark))
        fontSelector.backgroundTintList = ColorStateList.valueOf(backgroundColor)

        fontSelector.setOnClickListener { v ->
            val popupView = LayoutInflater.from(activity).inflate(R.layout.popup_font_selector, null)
            val popupWindow = PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)

            val fontListView = popupView.findViewById<ListView>(R.id.font_list_view)
            val fontAdapter = FontArrayAdapter(
                requireActivity(),
                android.R.layout.simple_list_item_1,
                fontPackageNames,
                fontManager,
                isNightMode()
            )
            fontListView.adapter = fontAdapter

            fontListView.setOnItemClickListener { _, _, position, _ ->
                mCurrentFontPosition = position
                val fontPackage = fontPackageNames[mCurrentFontPosition]
                applyFontToAllPreviews(fontPackage)
                fontSelector.text = fontManager.getLabel(requireContext(), fontPackage)
                popupWindow.dismiss()
            }

            popupView.setBackgroundResource(R.drawable.custom_background)
            val backgroundDrawable = popupView.background
            backgroundDrawable?.setColorFilter(backgroundColor, PorterDuff.Mode.SRC_ATOP)
            popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            popupWindow.isOutsideTouchable = true
            popupWindow.isFocusable = true
            popupWindow.showAsDropDown(v, 0, 10)
        }

        if (isStaticClockStyle(mClockPosition)) {
            fontMessage.visibility = View.VISIBLE
        } else {
            fontMessage.visibility = View.GONE
        }

        val currentFontPackage = fontManager.currentFontPackage
        mCurrentFontPosition = fontPackageNames.indexOf(currentFontPackage)
        if (mCurrentFontPosition != -1) {
            if (!isStaticClockStyle(mClockPosition)) {
                val fontPackage = fontPackageNames[mCurrentFontPosition]
                fontSelector.text = fontManager.getLabel(requireContext(), fontPackage)
                applyFontToAllPreviews(fontPackage)
            }
        }

        applyFab = rootView.findViewById(R.id.apply_extended_fab)
        setupApplyButton(fontPackageNames)

        highlightGuide = rootView.findViewById(R.id.highlight_guide)
        if (isFirstTime()) {
            highlightGuide.visibility = View.VISIBLE
            highlightGuide.setOnClickListener {
                highlightGuide.visibility = View.GONE
                disableHighlight()
            }
        } else {
            highlightGuide.visibility = View.GONE
        }

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
            override fun onPageSelected(position: Int) {
                mClockPosition = position
                viewPager.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                updateClockName(position)

                if (isStaticClockStyle(mClockPosition)) {
                    fontMessage.visibility = View.VISIBLE
                } else {
                    fontMessage.visibility = View.GONE
                    if (mCurrentFontPosition >= 0 && mCurrentFontPosition < fontPackageNames.size) {
                        val fontPackage = fontPackageNames[mCurrentFontPosition]
                        applyFontToAllPreviews(fontPackage)
                    }
                }
            }
        })
        return rootView
    }

    private fun updateClockName(position: Int) {
        val clockNames = arrayOf(
            "Default Clock",         // 0
            "OnePlus Clock",         // 1
            "IOS Clock",             // 2
            "Simple Clock",          // 3
            "MIUI Clock",            // 4
            "IDE Clock",             // 5
            "Moto Clock",            // 6
            "Stylish Clock",         // 7
            "Stylish Clock 2",       // 8
            "Stylish Clock 3",       // 9
            "Stylish Clock 4",       // 10
            "Stylish Clock 5",       // 11
            "Stylish Clock 6",       // 12
            "Stylish Clock 7",       // 13
            "Stylish Clock 8",       // 14
            "Stylish Clock 9",       // 15
            "Stylish Clock 10",      // 16
            "SmallCute",             // 17
            "ThinLong",              // 18
            "MoreMoreThin",          // 19
            "NormalTime",            // 20
            "Guoguo2",               // 21
            "Guoguo3",               // 22
            "Guoguo4",               // 23
            "NType",                 // 24
            "NDot",                  // 25
            "Graphic",               // 26
            "LondonUG"               // 27
        )
        if (position >= 0 && position < clockNames.size) {
            clockNameTextView.text = clockNames[position]
        }
    }

    private fun setupApplyButton(fontPackageNames: List<String>) {
        applyFab.setOnClickListener(object : View.OnClickListener {
            private var lastClickTime: Long = 0

            override fun onClick(view: View) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime < 2000) {
                    return
                }
                lastClickTime = currentTime

                if (mCurrentFontPosition >= 0 && mCurrentFontPosition < fontPackageNames.size) {
                    val fontPackage = fontPackageNames[mCurrentFontPosition]

                    if (!isStaticClockStyle(mClockPosition)) {
                        applyFontToAllPreviews(fontPackage)
                        fontManager.enableFontPackage(mCurrentFontPosition)
                    }
                }

                Settings.Secure.putIntForUser(context?.contentResolver,
                    "clock_style", mClockPosition, UserHandle.USER_CURRENT)
                Settings.Secure.putIntForUser(context?.contentResolver,
                    "lock_screen_custom_clock_face", 0, UserHandle.USER_CURRENT)

                applyChangesAndRestart()
            }
        })
    }

    private fun applyChangesAndRestart() {
        applyFab.isEnabled = false
        applyFab.text = "Applying..."

        updateClockOverlays(mClockPosition)

        val appContext = activity?.applicationContext
        val fragmentContext = context

        if (appContext != null && fragmentContext != null && isAdded && activity != null && !activity!!.isFinishing) {
            postDelayedSafe({
                try {
                    SystemRestartUtils.restartSystemUI(appContext)
                    showSuccessMessage()
                } catch (e: Exception) {
                    if (isAdded && context != null && activity != null && !activity!!.isFinishing) {
                        try {
                            SystemRestartUtils.restartSystemUI(context!!)
                            showSuccessMessage()
                        } catch (ex: Exception) {
                            showFailureMessage()
                        }
                    } else {
                        showFailureMessage()
                    }
                }
            }, 1000)
        } else {
            showFailureMessage()
        }
    }

    private fun showSuccessMessage() {
        if (activity != null && !activity!!.isFinishing) {
            activity!!.runOnUiThread {
                context?.let {
                    Toast.makeText(it,
                        "Settings applied successfully!",
                        Toast.LENGTH_SHORT).show()
                }
                applyFab.isEnabled = true
                applyFab.text = "Apply"
            }
        }
    }

    private fun showFailureMessage() {
        if (activity != null && !activity!!.isFinishing) {
            activity!!.runOnUiThread {
                applyFab.isEnabled = true
                applyFab.text = "Apply"
                context?.let {
                    Toast.makeText(it,
                        "Settings saved. Please restart SystemUI manually if changes don't appear.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isNightMode(): Boolean {
        val nightModeFlags = context?.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateClockOverlays(clockStyle: Int) {
        mThemeUtils?.setOverlayEnabled(
            "android.theme.customization.hideclock",
            if (clockStyle != 0) "com.android.systemui.clocks.hideclock" else "android",
            "android")
        mThemeUtils?.setOverlayEnabled(
            "android.theme.customization.smartspace",
            if (clockStyle != 0) "com.android.systemui.hide.smartspace" else "com.android.systemui",
            "com.android.systemui")
        mThemeUtils?.setOverlayEnabled(
            "android.theme.customization.smartspace_offset",
            if (clockStyle != 0 && isCenterClock(clockStyle))
                "com.android.systemui.smartspace_offset.smartspace"
            else
                "com.android.systemui",
            "com.android.systemui")
    }

    private fun isCenterClock(clockStyle: Int): Boolean {
        return mCenterClocks.contains(clockStyle)
    }

    private fun isStaticClockStyle(clockStyle: Int): Boolean {
        if (clockStyle < 0 || clockStyle >= CLOCK_LAYOUTS.size) {
            return false
        }
        return false
    }

    private fun hasLimitedCustomization(clockPosition: Int): Boolean {
        return LIMITED_CUSTOMIZATION_CLOCKS.contains(clockPosition)
    }

    private fun shouldScaleDown(position: Int): Boolean {
        val layoutId = CLOCK_LAYOUTS[position]
        return layoutId == R.layout.keyguard_clock_stylish ||
               layoutId == R.layout.keyguard_clock_stylish2 ||
               layoutId == R.layout.keyguard_clock_stylish3 ||
               layoutId == R.layout.keyguard_clock_stylish4 ||
               layoutId == R.layout.keyguard_clock_stylish5 ||
               layoutId == R.layout.keyguard_clock_stylish6 ||
               layoutId == R.layout.keyguard_clock_stylish7 ||
               layoutId == R.layout.keyguard_clock_stylish8 ||
               layoutId == R.layout.keyguard_clock_stylish9 ||
               layoutId == R.layout.keyguard_clock_stylish10
    }

    private fun isFirstTime(): Boolean {
        return Settings.System.getIntForUser(
            context?.contentResolver, PREF_FIRST_TIME, 1, UserHandle.USER_CURRENT) != 0
    }

    private fun disableHighlight() {
        Settings.System.putIntForUser(context?.contentResolver, PREF_FIRST_TIME, 0, UserHandle.USER_CURRENT)
    }

    private inner class ClockPagerAdapter : PagerAdapter() {
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val inflater = LayoutInflater.from(activity)
            val layout = inflater.inflate(CLOCK_LAYOUTS[position], container, false)

            if (!isStaticClockStyle(position) && mCurrentFontPosition >= 0) {
                val fontPackage = fontManager.allFontPackages[mCurrentFontPosition]
                applyFontToPreview(fontPackage, layout, position)
            }

            val bottomPadding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                150f,
                resources.displayMetrics
            ).toInt()
            layout.setPadding(
                layout.paddingLeft,
                layout.paddingTop,
                layout.paddingRight,
                bottomPadding
            )

            if (shouldScaleDown(position)) {
                var scaleFactor = 0.70f
                if (position == 0 ||
                    position == 1 ||
                    position == 2 ||
                    position == 5 ||
                    position == 6 ||
                    position == 7 ||
                    position == 14) {
                    scaleFactor = 0.35f
                }
                layout.scaleX = scaleFactor
                layout.scaleY = scaleFactor
            }

            container.addView(layout)
            return layout
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }

        override fun getCount(): Int {
            return CLOCK_LAYOUTS.size
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view == `object`
        }
    }

    private fun applyFontToAllPreviews(font: String) {
        val typeface = fontManager.getTypeface(context, font)
        val childCount = viewPager.childCount
        if (typeface != null) {
            for (i in 0 until childCount) {
                val currentLayout = viewPager.getChildAt(i)
                val currentPosition = viewPager.currentItem
                if (currentLayout != null) {
                    if (!isStaticClockStyle(currentPosition)) {
                        updateAllTextViews(currentLayout, typeface)
                    }
                }
            }
        }
    }

    private fun applyFontToPreview(font: String, layout: View, position: Int) {
        if (isStaticClockStyle(position)) {
            return
        }
        val typeface = fontManager.getTypeface(context, font)
        if (typeface != null) {
            updateAllTextViews(layout, typeface)
        }
    }

    private fun updateAllTextViews(view: View, typeface: Typeface) {
        when (view) {
            is TextView, is TextClock -> {
                (view as TextView).typeface = typeface
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    updateAllTextViews(child, typeface)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateClockName(mClockPosition)
    }

    override fun onResume() {
        super.onResume()
        updateClockName(mClockPosition)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cleanup handled by OptimizedSettingsFragment
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup ThemeUtils
        mThemeUtils = null
    }

    override fun getMetricsCategory(): Int {
        return MetricsProto.MetricsEvent.VIEW_UNKNOWN
    }
}
