/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.miscellaneous

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.internal.util.evolution.PixelPropsUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.core.SubSettingLauncher
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable
import com.android.settingslib.spa.framework.theme.SettingsSpace

/**
 * Spoofing hub. Compose host for the same entries the old preference screen had: the two
 * Play Integrity switches (TrickyStore, PIF) with their own sub-pages, device identity pages,
 * Tensor targets (non-Tensor devices only), the Photos / Snapchat overrides (installed apps
 * only) and a reset action. `res/xml/spoofing.xml` is kept only as the search index source.
 */
@SearchIndexable
class Spoofing : SettingsPreferenceFragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingKill: Runnable? = null
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.spoofing_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            SpoofingTheme {
                SpoofingContent(
                    refreshKey = resumeTick,
                    onOpen = ::openPage,
                    onAppSpoofChanged = ::scheduleKill,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read the master switches: they can be flipped from the sub-pages.
        resumeTick++
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingKill?.let { handler.removeCallbacks(it) }
    }

    override fun getMetricsCategory(): Int = MetricsEvent.EVOLVER

    private fun openPage(destination: Class<*>, titleRes: Int) {
        SubSettingLauncher(requireContext())
            .setDestination(destination.name)
            .setTitleRes(titleRes)
            .setSourceMetricsCategory(getMetricsCategory())
            .launch()
    }

    private fun scheduleKill(pkg: String) {
        pendingKill?.let { handler.removeCallbacks(it) }
        Toast.makeText(requireContext(), R.string.spoofing_applying_changes, Toast.LENGTH_SHORT)
            .show()
        val activityManager = requireContext().getSystemService(ActivityManager::class.java)
        pendingKill = Runnable { stopPackage(activityManager, pkg) }.also {
            handler.postDelayed(it, KILL_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "Spoofing"
        private const val KILL_DELAY_MS = 500L

        private const val TRICKYSTORE_ENABLED_KEY = "spoof_trickystore_enabled"
        private const val PIF_ENABLED_KEY = "spoof_pif_enabled"
        private const val PI_PHOTOS_SPOOF = "pi_photos_spoof"
        private const val PI_SNAPCHAT_SPOOF = "pi_snapchat_spoof"
        private const val KEY_TENSOR_TARGETS = "tensor_targets_settings"

        private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"
        private const val SNAPCHAT_PACKAGE = "com.snapchat.android"

        private fun isInstalled(context: Context, pkg: String): Boolean = try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = object : BaseSearchIndexProvider(R.xml.spoofing) {
            override fun getNonIndexableKeys(context: Context): List<String> {
                val keys = super.getNonIndexableKeys(context).toMutableList()
                if (PixelPropsUtils.isTensorPixelDevice()) keys.add(KEY_TENSOR_TARGETS)
                if (!isInstalled(context, PHOTOS_PACKAGE)) keys.add(PI_PHOTOS_SPOOF)
                if (!isInstalled(context, SNAPCHAT_PACKAGE)) keys.add(PI_SNAPCHAT_SPOOF)
                return keys
            }
        }

        @Composable
        private fun SpoofingContent(
            refreshKey: Int,
            onOpen: (Class<*>, Int) -> Unit,
            onAppSpoofChanged: (String) -> Unit,
        ) {
            val context = LocalContext.current
            val resolver = context.contentResolver

            val isTensor = remember { PixelPropsUtils.isTensorPixelDevice() }
            val photosInstalled = remember { isInstalled(context, PHOTOS_PACKAGE) }
            val snapInstalled = remember { isInstalled(context, SNAPCHAT_PACKAGE) }

            var tsEnabled by remember(refreshKey) {
                mutableStateOf(Settings.System.getInt(resolver, TRICKYSTORE_ENABLED_KEY, 1) != 0)
            }
            var pifEnabled by remember(refreshKey) {
                mutableStateOf(Settings.System.getInt(resolver, PIF_ENABLED_KEY, 1) != 0)
            }
            var photosSpoof by remember(refreshKey) {
                mutableStateOf(Settings.Secure.getInt(resolver, PI_PHOTOS_SPOOF, 1) != 0)
            }
            var snapSpoof by remember(refreshKey) {
                mutableStateOf(Settings.Secure.getInt(resolver, PI_SNAPCHAT_SPOOF, 0) != 0)
            }
            var showResetDialog by remember { mutableStateOf(false) }

            if (showResetDialog) {
                SpoofingConfirmDialog(
                    title = stringResource(R.string.spoofing_reset_defaults_dialog_title),
                    message = stringResource(R.string.spoofing_reset_defaults_dialog_message),
                    confirmText = stringResource(R.string.spoofing_reset_defaults_confirm),
                    destructive = true,
                    onConfirm = {
                        try {
                            TrickyStore.resetAllSpoofDefaults(context)
                            Toast.makeText(
                                context, R.string.spoofing_reset_defaults_done, Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reset spoof defaults", e)
                        }
                    },
                    onDismiss = { showResetDialog = false },
                )
            }

            SpoofingScreen {
                SpoofingGroup(title = stringResource(R.string.spoofing_integrity_category)) {
                    item {
                        SpoofingSwitchNavRow(
                            title = stringResource(R.string.tricky_store_title),
                            summary = stringResource(R.string.tricky_store_summary),
                            checked = tsEnabled,
                            onCheckedChange = {
                                tsEnabled = it
                                Settings.System.putInt(
                                    resolver, TRICKYSTORE_ENABLED_KEY, if (it) 1 else 0)
                            },
                            onClick = { onOpen(TrickyStore::class.java, R.string.tricky_store_title) },
                        )
                    }
                    item {
                        SpoofingSwitchNavRow(
                            title = stringResource(R.string.play_integrity_fix_title),
                            summary = stringResource(R.string.play_integrity_fix_summary),
                            checked = pifEnabled,
                            onCheckedChange = {
                                pifEnabled = it
                                Settings.System.putInt(resolver, PIF_ENABLED_KEY, if (it) 1 else 0)
                            },
                            onClick = {
                                onOpen(PlayIntegrityFix::class.java, R.string.play_integrity_fix_title)
                            },
                        )
                    }
                }

                SpoofingGroup(title = stringResource(R.string.spoofing_identity_category)) {
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.user_selectable_app_spoofing_title),
                            summary = stringResource(R.string.user_selectable_app_spoofing_summary),
                            onClick = {
                                onOpen(
                                    UserSelectedAppSpoofSettings::class.java,
                                    R.string.user_selectable_app_spoofing_title,
                                )
                            },
                        )
                    }
                    item {
                        SpoofingNavRow(
                            title = stringResource(R.string.spoofing_pixel_props_title),
                            summary = stringResource(R.string.spoofing_pixel_props_summary),
                            onClick = {
                                onOpen(
                                    PixelPropsSettings::class.java,
                                    R.string.spoofing_pixel_props_title,
                                )
                            },
                        )
                    }
                }

                // Tensor targets only matter on non-Tensor devices
                if (!isTensor) {
                    SpoofingGroup(title = stringResource(R.string.spoofing_features_category)) {
                        item {
                            SpoofingNavRow(
                                title = stringResource(R.string.tensor_spoof_title),
                                summary = stringResource(R.string.tensor_spoof_summary),
                                onClick = {
                                    onOpen(
                                        TensorTargetsSettings::class.java,
                                        R.string.tensor_spoof_title,
                                    )
                                },
                            )
                        }
                    }
                }

                if (photosInstalled || snapInstalled) {
                    SpoofingGroup(title = stringResource(R.string.spoofing_app_specific_category)) {
                        if (photosInstalled) {
                            item {
                                SpoofingSwitchRow(
                                    titleText = stringResource(R.string.spoofing_photos_title),
                                    summaryText = stringResource(R.string.spoofing_photos_summary),
                                    isChecked = photosSpoof,
                                    onChange = {
                                        photosSpoof = it
                                        Settings.Secure.putInt(
                                            resolver, PI_PHOTOS_SPOOF, if (it) 1 else 0)
                                        onAppSpoofChanged(PHOTOS_PACKAGE)
                                    },
                                )
                            }
                        }
                        if (snapInstalled) {
                            item {
                                SpoofingSwitchRow(
                                    titleText = stringResource(R.string.spoofing_snap_title),
                                    summaryText = stringResource(R.string.spoofing_snap_summary),
                                    isChecked = snapSpoof,
                                    onChange = {
                                        snapSpoof = it
                                        Settings.Secure.putInt(
                                            resolver, PI_SNAPCHAT_SPOOF, if (it) 1 else 0)
                                        onAppSpoofChanged(SNAPCHAT_PACKAGE)
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(SettingsSpace.small1))
                SpoofingActionRow(
                    actions = listOf(
                        SpoofingAction(
                            text = stringResource(R.string.spoofing_reset_defaults_title),
                            icon = Icons.Rounded.History,
                            onClick = { showResetDialog = true },
                        ),
                    ),
                )
            }
        }
    }
}
