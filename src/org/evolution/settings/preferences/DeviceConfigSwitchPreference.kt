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
    attrs?.let {
        val ta = context.obtainStyledAttributes(it, R.styleable.DeviceConfigSwitchPreference)
        try {
            namespace = ta.getString(R.styleable.DeviceConfigSwitchPreference_deviceConfigNamespace) ?: ""
            featureFlag = ta.getString(R.styleable.DeviceConfigSwitchPreference_deviceConfigFlag) ?: ""
            defaultValue = ta.getBoolean(R.styleable.DeviceConfigSwitchPreference_deviceConfigDefault, false)
        } finally {
            ta.recycle()
        }
    }

    if (namespace.isNotEmpty() && featureFlag.isNotEmpty()) {
        isChecked = DeviceConfig.getBoolean(namespace, featureFlag, defaultValue)
    }
}

    private val configListener = DeviceConfig.OnPropertiesChangedListener { properties ->
        if (properties.namespace == namespace) {
            val newValue = properties.getBoolean(featureFlag, defaultValue)
            if (isChecked != newValue) {
                isChecked = newValue
            }
        }
    }

    fun setDeviceConfig(namespace: String, flag: String, default: Boolean = false) {
        this.namespace = namespace
        this.featureFlag = flag
        this.defaultValue = default

        // Load initial value
        isChecked = DeviceConfig.getBoolean(namespace, flag, default)
    }

    override fun onAttached() {
        super.onAttached()
        DeviceConfig.addOnPropertiesChangedListener(
            namespace,
            { it.run() },
            configListener
        )
    }

    override fun onDetached() {
        super.onDetached()
        DeviceConfig.removeOnPropertiesChangedListener(configListener)
    }

    override fun onClick() {
        val newValue = !isChecked
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
}