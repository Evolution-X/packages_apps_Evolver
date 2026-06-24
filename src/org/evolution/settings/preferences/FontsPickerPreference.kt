/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.util.evolution.ThemeUtils
import com.android.internal.util.evolution.ThemeUtils.FONT_KEY
import com.android.settings.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evolution.settings.fragments.themes.fonts.ExternalFontInstaller
import org.evolution.settings.utils.SystemUtils
import java.util.concurrent.Executors
import kotlin.math.min

class FontsPickerPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr) {

    private var dialog: AlertDialog? = null
    private var recyclerView: RecyclerView? = null

    private val themeUtils = ThemeUtils.getInstance(context)
    private val pkgs: List<String> = themeUtils.getOverlayPackagesForCategory(CATEGORY, "android")

    private var fontPickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingCustomFontTypeface: Typeface? = null
    private var pendingCustomFontUri: Uri? = null
    private var customFontName: String = ""
    private var hasCustomFont: Boolean = false

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        loadCustomFontState()
        updateSummary()

        val activity = context as? FragmentActivity ?: return
        fontPickerLauncher = activity.activityResultRegistry.register(
            "font_picker_${key}",
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { handleFontUriSelected(it, activity) }
        }
    }

    override fun onDetached() {
        dialog?.dismiss()
        dialog = null
        recyclerView = null
        fontPickerLauncher?.unregister()
        fontPickerLauncher = null
        super.onDetached()
    }

    override fun onClick() {
        val ctx = context

        val content = LayoutInflater.from(ctx).inflate(R.layout.selector_item_view, null)

        recyclerView = content.findViewById<RecyclerView>(R.id.recycler_view).apply {
            setHasFixedSize(true)

            val isLandscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val span = if (isLandscape) 2 else 1
            layoutManager = GridLayoutManager(ctx, span)
            adapter = FontsAdapter(ctx, pkgs, themeUtils, hasCustomFont, customFontName)

            post {
                val fraction = if (isLandscape) 0.75f else 0.6f
                val maxHeight = (ctx.resources.displayMetrics.heightPixels * fraction).toInt()
                layoutParams.height = maxHeight
                requestLayout()
            }
        }

        dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dlg ->
                dlg.setOnDismissListener {
                    dialog = null
                    recyclerView = null
                }
                dlg.show()
                applyDialogWidth(dlg)
                tintDialogAccent(dlg)
            }
    }

    private fun applyDialogWidth(dlg: AlertDialog) {
        val w = dlg.window ?: return
        val ctx = context

        val wm = ctx.getSystemService(WindowManager::class.java)

        val boundsWidthPx = try {
            wm.currentWindowMetrics.bounds.width()
        } catch (_: Throwable) {
            ctx.resources.displayMetrics.widthPixels
        }

        val density = ctx.resources.displayMetrics.density

        val maxDp = if (ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            DIALOG_MAX_WIDTH_DP_LANDSCAPE
        } else {
            DIALOG_MAX_WIDTH_DP
        }
        val maxWidthPx = (maxDp * density + 0.5f).toInt()

        val targetPx = (boundsWidthPx * 0.90f).toInt()
        w.setLayout(min(maxWidthPx, targetPx), WindowManager.LayoutParams.WRAP_CONTENT)
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun tintDialogAccent(dlg: AlertDialog) {
        val tv = TypedValue()
        val theme = context.theme
        if (!theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) return

        val accent = if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        if (accent == 0) return

        val w = dlg.window
        if (w != null) {
            val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
            val titleView = if (titleId != 0) w.decorView.findViewById<TextView>(titleId) else null
            titleView?.setTextColor(accent)
        }

        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
    }

    private fun getAppliedPkg(): String {
        return themeUtils.getOverlayInfos(CATEGORY)
            .firstOrNull { it.isEnabled }?.packageName ?: "android"
    }

    private fun loadCustomFontState() {
        customFontName = Settings.Secure.getString(
            context.contentResolver, "custom_font_name"
        ) ?: ""
        hasCustomFont = customFontName.isNotEmpty()
    }

    private fun updateSummary() {
        summary = when {
            hasCustomFont -> customFontName
            else -> {
                val applied = getAppliedPkg()
                if (applied == "android") "Default" else getLabelSafe(context, applied)
            }
        }
    }

    private fun handleFontUriSelected(uri: Uri, activity: FragmentActivity) {
        val installer = ExternalFontInstaller(context)
        activity.lifecycleScope.launch {
            val typeface = installer.loadTypefaceFromUri(uri)
            if (typeface == null) {
                Toast.makeText(context, R.string.toast_invalid_font_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            pendingCustomFontTypeface = typeface
            pendingCustomFontUri = uri
            showCustomFontPreviewDialog(uri, typeface, installer, activity)
        }
    }

    private fun showCustomFontPreviewDialog(
        uri: Uri,
        typeface: Typeface,
        installer: ExternalFontInstaller,
        activity: FragmentActivity
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_font_preview, null)
        dialogView.findViewById<TextView>(R.id.preview_text).typeface = typeface

        AlertDialog.Builder(context)
            .setTitle(R.string.font_preview_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.add_font) { _, _ ->
                activity.lifecycleScope.launch {
                    val postScriptName = installer.installFontFromUri(uri)
                    if (postScriptName != null) {
                        Settings.Secure.putString(
                            context.contentResolver, "custom_font_name", postScriptName
                        )
                        customFontName = postScriptName
                        hasCustomFont = true
                        updateSummary()
                        AlertDialog.Builder(context)
                            .setTitle(R.string.reboot_required_title)
                            .setMessage(R.string.reboot_required_custom_font_title)
                            .setPositiveButton(R.string.action_reboot_now) { _, _ ->
                                ExternalFontInstaller.rebootDevice()
                            }
                            .setNegativeButton(R.string.action_later, null)
                            .show()
                    }
                    pendingCustomFontUri = null
                    pendingCustomFontTypeface = null
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingCustomFontUri = null
                pendingCustomFontTypeface = null
            }
            .show()
    }

    private inner class FontsAdapter(
        private val ctx: Context,
        private val pkgs: List<String>,
        private val themeUtils: ThemeUtils,
        private var hasCustomFont: Boolean,
        private var customFontName: String
    ) : RecyclerView.Adapter<FontsAdapter.FontViewHolder>() {

        private val ITEM_TYPE_SYSTEM = 0
        private val ITEM_TYPE_CUSTOM_ACTIVE = 1
        private val ITEM_TYPE_ADD_CUSTOM = 2
        private val CUSTOM_PKG_KEY = "__custom_font__"

        private var selectedPkg: String = if (hasCustomFont) CUSTOM_PKG_KEY else getApplied(themeUtils)
        private val overlayExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FontViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.fonts_option, parent, false)
            return FontViewHolder(v)
        }

        override fun onBindViewHolder(holder: FontViewHolder, position: Int) {
            when (getItemTypeForPosition(position)) {
                ITEM_TYPE_ADD_CUSTOM -> {
                    holder.title.text = ctx.getString(R.string.add_custom_font)
                    holder.title.typeface = Typeface.DEFAULT_BOLD
                    holder.title.setTextColor(resolveAccentColor(holder.title.context))
                    holder.itemView.isActivated = false
                    holder.itemView.setOnClickListener {
                        dialog?.dismiss()
                        fontPickerLauncher?.launch(arrayOf("font/ttf", "font/otf"))
                    }
                }
                ITEM_TYPE_CUSTOM_ACTIVE -> {
                    holder.title.text = customFontName
                    try {
                        holder.title.typeface = Typeface.create(
                            ExternalFontInstaller.DEFAULT_FONT_FAMILY, Typeface.NORMAL
                        )
                    } catch (_: Exception) { }
                    holder.title.setTextColor(resolveTextColorPrimary(holder.title.context))
                    holder.itemView.isActivated = (selectedPkg == CUSTOM_PKG_KEY)
                    holder.itemView.setOnClickListener {
                        showResetCustomFontDialog()
                    }
                }
                else -> {
                    val pkg = pkgForPosition(position)
                    val label = if (pkg == "android") "Default" else getLabelSafe(ctx, pkg)
                    val tf = getTypefaceSafe(ctx, pkg)

                    holder.title.text = label
                    if (tf != null) holder.title.typeface = tf
                    holder.title.setTextColor(resolveTextColorPrimary(holder.title.context))
                    holder.itemView.isActivated = (pkg == selectedPkg)

                    holder.itemView.setOnClickListener {
                        if (pkg == selectedPkg) return@setOnClickListener

                        val old = selectedPkg
                        selectedPkg = pkg
                        notifyDataSetChanged()
                        dialog?.dismiss()

                        showSystemUiRestartDialogWithAction(
                            ctx,
                            onConfirm = {
                                applyOverlayInBackground(
                                    if (old == CUSTOM_PKG_KEY) getApplied(themeUtils) else old,
                                    pkg
                                ) {
                                    if (old == CUSTOM_PKG_KEY) clearCustomFontState()
                                    updateSummary()
                                    SystemUtils.restartSystemUI(ctx)
                                }
                            },
                            onLater = {
                                mainHandler.post {
                                    if (old == CUSTOM_PKG_KEY) clearCustomFontState()
                                    themeUtils.setOverlayEnabled(CATEGORY,
                                        if (old == CUSTOM_PKG_KEY) getApplied(themeUtils) else old, old)
                                    themeUtils.setOverlayEnabled(CATEGORY, pkg, "android")
                                    updateSummary()
                                }
                            }
                        )
                    }
                }
            }
        }

        override fun getItemCount(): Int =
            pkgs.size + (if (hasCustomFont) 1 else 0) + 1

        private fun clearCustomFontState() {
            customFontName = ""
            hasCustomFont = false
            this@FontsPickerPreference.customFontName = ""
            this@FontsPickerPreference.hasCustomFont = false
            Settings.Secure.putString(ctx.contentResolver, "custom_font_name", "")
        }

        private fun getItemTypeForPosition(position: Int): Int {
            if (hasCustomFont && position == 0) return ITEM_TYPE_CUSTOM_ACTIVE
            if (position == itemCount - 1) return ITEM_TYPE_ADD_CUSTOM
            return ITEM_TYPE_SYSTEM
        }

        private fun pkgForPosition(position: Int): String {
            val offset = if (hasCustomFont) 1 else 0
            return pkgs[position - offset]
        }

        private fun applyOverlayInBackground(
            oldPkg: String,
            newPkg: String,
            onDone: () -> Unit
        ) {
            overlayExecutor.execute {
                try {
                    themeUtils.setOverlayEnabled(CATEGORY, oldPkg, oldPkg)
                    themeUtils.setOverlayEnabled(CATEGORY, newPkg, "android")
                } finally {
                    mainHandler.post { onDone() }
                }
            }
        }

        private fun showSystemUiRestartDialogWithAction(
            ctx: Context,
            onConfirm: () -> Unit,
            onLater: () -> Unit
        ) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.systemui_restart_title)
                .setMessage(R.string.systemui_restart_message)
                .setPositiveButton(R.string.action_yes) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.systemui_restart_not_now) { _, _ -> onLater() }
                .show()
        }

        private fun resolveTextColorPrimary(ctx: Context): Int {
            val tv = TypedValue()
            return if (ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
                if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
            } else {
                0xff000000.toInt()
            }
        }

        private fun resolveAccentColor(ctx: Context): Int {
            val tv = TypedValue()
            return if (ctx.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
            } else 0xff3DDC84.toInt()
        }

        private fun showResetCustomFontDialog() {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.reset_default_title)
                .setMessage(R.string.reset_default_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    (context as? FragmentActivity)?.lifecycleScope?.launch {
                        withContext(Dispatchers.IO) {
                            Settings.Secure.putString(
                                ctx.contentResolver, "custom_font_name", ""
                            )
                            ExternalFontInstaller(ctx).resetFontUpdates()
                        }
                        customFontName = ""
                        hasCustomFont = false
                        selectedPkg = getApplied(themeUtils)
                        this@FontsPickerPreference.customFontName = ""
                        this@FontsPickerPreference.hasCustomFont = false
                        updateSummary()
                        notifyDataSetChanged()
                        showSystemUiRestartDialogWithAction(
                            ctx,
                            onConfirm = { ExternalFontInstaller.rebootDevice() },
                            onLater = { }
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        inner class FontViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.option_title)
        }

        private fun getTypefaceSafe(context: Context, pkg: String): Typeface? {
            return try {
                val pm = context.packageManager
                val res = if (pkg == "android") Resources.getSystem() else pm.getResourcesForApplication(pkg)
                val id = res.getIdentifier("config_bodyFontFamily", "string", pkg)
                if (id == 0) return null
                Typeface.create(res.getString(id), Typeface.NORMAL)
            } catch (e: Exception) {
                Log.e(TAG, "Typeface load failed for pkg: $pkg", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "FontsPickerPreference"
        private const val CATEGORY = FONT_KEY
        private const val CUSTOM_PKG_KEY = "__custom_font__"

        private const val DIALOG_MAX_WIDTH_DP = 360
        private const val DIALOG_MAX_WIDTH_DP_LANDSCAPE = 640

        private fun getApplied(themeUtils: ThemeUtils): String {
            return themeUtils.getOverlayInfos(CATEGORY)
                .firstOrNull { it.isEnabled }?.packageName ?: "android"
        }

        private fun getLabelSafe(context: Context, pkg: String): String {
            return try {
                val pm = context.packageManager
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg
            }
        }
    }
}
