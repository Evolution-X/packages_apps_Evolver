/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.preferences

import android.content.Context
import android.provider.DeviceConfig
import android.util.AttributeSet
import androidx.preference.SwitchPreferenceCompat

import com.android.settings.R

class DeviceConfigSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreferenceCompat(context, attrs) {

    private var namespace: String = ""
    private var featureFlag: String = ""
    private var defaultValue: Boolean = false

    constructor(
        context: Context,
        namespace: String,
        flag: String,
        default: Boolean = false
    ) : this(context, null) {
        setDeviceConfig(namespace, flag, default)
    }

    init {
        // The source of truth is DeviceConfig, so the preference must not keep an independent
        // SharedPreferences copy that can drift from the backing flag.
        isPersistent = false

        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.DeviceConfigSwitchPreference)
            try {
                namespace =
                    ta.getString(R.styleable.DeviceConfigSwitchPreference_deviceConfigNamespace) ?: ""
                featureFlag =
                    ta.getString(R.styleable.DeviceConfigSwitchPreference_deviceConfigFlag) ?: ""
                defaultValue =
                    ta.getBoolean(R.styleable.DeviceConfigSwitchPreference_deviceConfigDefault, false)
            } finally {
                ta.recycle()
            }
        }

        syncFromDeviceConfig()
    }

    private val configListener = DeviceConfig.OnPropertiesChangedListener { properties ->
        if (properties.namespace != namespace) {
            return@OnPropertiesChangedListener
        }

        if (properties.keyset.isNotEmpty() && !properties.keyset.contains(featureFlag)) {
            return@OnPropertiesChangedListener
        }

        syncFromDeviceConfig()
    }

    fun setDeviceConfig(namespace: String, flag: String, default: Boolean = false) {
        this.namespace = namespace
        this.featureFlag = flag
        this.defaultValue = default

        syncFromDeviceConfig()
    }

    override fun onAttached() {
        super.onAttached()
        syncFromDeviceConfig()

        if (!hasValidDeviceConfig()) {
            return
        }

        DeviceConfig.addOnPropertiesChangedListener(
            namespace,
            context.mainExecutor,
            configListener
        )
    }

    override fun onDetached() {
        super.onDetached()
        DeviceConfig.removeOnPropertiesChangedListener(configListener)
    }

    override fun onClick() {
        if (!hasValidDeviceConfig()) {
            return
        }

        val newValue = !isChecked
        if (!callChangeListener(newValue)) {
            return
        }

        val success = DeviceConfig.setProperty(
            namespace,
            featureFlag,
            newValue.toString(),
            false // makeDefault
        )
        if (success) {
            isChecked = newValue
        }
    }

    private fun hasValidDeviceConfig(): Boolean = namespace.isNotEmpty() && featureFlag.isNotEmpty()

    private fun syncFromDeviceConfig() {
        if (!hasValidDeviceConfig()) {
            return
        }

        val currentValue = DeviceConfig.getBoolean(namespace, featureFlag, defaultValue)
        if (isChecked != currentValue) {
            isChecked = currentValue
        }
    }
}
