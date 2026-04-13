/*
 * Copyright (C) 2024 the risingOS Android Project
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

package org.evolution.settings.fragments

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import com.android.settings.SettingsPreferenceFragment
import com.android.internal.util.evolution.ThemeUtils
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Optimized base class for Settings fragments with performance improvements:
 * - Memory leak prevention
 * - Resource caching
 * - Proper lifecycle management
 * - Background task handling
 */
abstract class OptimizedSettingsFragment : SettingsPreferenceFragment() {

    // Safe handler to prevent memory leaks
    protected class SafeHandler(fragment: OptimizedSettingsFragment) : Handler(Looper.getMainLooper()) {
        private val mFragmentRef = WeakReference(fragment)
        
        override fun handleMessage(msg: android.os.Message) {
            val fragment = mFragmentRef.get()
            if (fragment != null && fragment.isAdded) {
                super.handleMessage(msg)
            }
        }
    }
    
    protected var mHandler: SafeHandler? = null
    protected open var mThemeUtils: ThemeUtils? = null
    
    // Cache for preference states to prevent redundant operations
    protected val mPreferenceCache = ConcurrentHashMap<String, Any>()
    
    // Cache for expensive operations
    protected val mOperationCache = ConcurrentHashMap<String, Any>()
    
    // Track if fragment is properly initialized
    private var mIsInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize handler with weak reference
        mHandler = SafeHandler(this)
        
        // Initialize ThemeUtils with null check
        activity?.let {
            mThemeUtils = ThemeUtils.getInstance(it)
        }
        
        mIsInitialized = true
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Cleanup to prevent memory leaks
        mHandler?.let {
            it.removeCallbacksAndMessages(null)
            mHandler = null
        }
        
        mThemeUtils = null
        mPreferenceCache.clear()
        mOperationCache.clear()
        mIsInitialized = false
    }

    override fun onDetach() {
        super.onDetach()
        
        // Additional cleanup
        mHandler?.removeCallbacksAndMessages(null)
        
        mPreferenceCache.clear()
        mOperationCache.clear()
    }

    /**
     * Safe method to check if fragment is properly initialized and attached
     */
    fun isFragmentReady(): Boolean {
        return mIsInitialized && isAdded && activity != null && activity?.isFinishing == false
    }

    /**
     * Safe method to get context with null checks
     */
    protected fun getSafeContext(): Context? {
        return if (!isFragmentReady()) null else context
    }

    /**
     * Cached preference lookup to avoid repeated findViewById calls
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T : Preference> findCachedPreference(key: String): T? {
        var preference = mPreferenceCache[key] as? T
        if (preference == null) {
            preference = findPreference<T>(key)
            if (preference != null) {
                mPreferenceCache[key] = preference
            }
        }
        return preference
    }

    /**
     * Safe method to post delayed tasks with fragment lifecycle checks
     */
    protected fun postDelayedSafe(runnable: Runnable, delayMillis: Long) {
        if (mHandler != null && isFragmentReady()) {
            mHandler?.postDelayed({
                if (isFragmentReady()) {
                    runnable.run()
                }
            }, delayMillis)
        }
    }

    /**
     * Safe method to post delayed tasks with fragment lifecycle checks (Kotlin lambda version)
     */
    protected inline fun postDelayedSafe(delayMillis: Long, crossinline action: () -> Unit) {
        if (mHandler != null && isFragmentReady()) {
            mHandler?.postDelayed({
                if (isFragmentReady()) {
                    action()
                }
            }, delayMillis)
        }
    }

    /**
     * Cache expensive operations to prevent redundant execution
     */
    protected fun cacheOperation(key: String, result: Any) {
        mOperationCache[key] = result
    }

    /**
     * Get cached operation result
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <T> getCachedOperation(key: String): T? {
        return mOperationCache[key] as? T
    }

    /**
     * Check if operation result is cached
     */
    protected fun isOperationCached(key: String): Boolean {
        return mOperationCache.containsKey(key)
    }

    /**
     * Safe ThemeUtils access with lazy initialization
     */
    protected fun getSafeThemeUtils(): ThemeUtils? {
        if (mThemeUtils == null && getSafeContext() != null) {
            mThemeUtils = ThemeUtils.getInstance(getSafeContext())
        }
        return mThemeUtils
    }

    /**
     * Optimized overlay operation with caching
     */
    protected fun setOverlayEnabledCached(category: String, packageName: String, target: String) {
        val cacheKey = "$category:$packageName:$target"
        
        // Check if this operation was already performed
        if (isOperationCached(cacheKey)) {
            return
        }
        
        val themeUtils = getSafeThemeUtils()
        if (themeUtils != null) {
            themeUtils.setOverlayEnabled(category, packageName, target)
            cacheOperation(cacheKey, true)
        }
    }

    /**
     * Clear operation cache when needed (e.g., on preference changes)
     */
    protected fun clearOperationCache() {
        mOperationCache.clear()
    }

    /**
     * Safe method to remove callbacks and messages
     */
    protected fun removeCallbacks() {
        mHandler?.removeCallbacksAndMessages(null)
    }

    /**
     * Fix crash: RecyclerView is sometimes null when setDivider() is called.
     * We retry after view is attached instead of crashing.
     */
    override fun setDivider(divider: Drawable?) {
        val list = listView
        if (list == null) {
            view?.post {
                if (isAdded) setDivider(divider)
            }
            return
        }

        super.setDivider(divider)
    }
}
