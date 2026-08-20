/*
 * Copyright (C) 2024-2026 Lunaris OS
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

package org.evolution.settings.utils

import android.app.Service
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.res.Configuration
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import kotlin.math.roundToInt

import org.evolution.settings.utils.PortraitSegmenter

private const val TAG = "WallpaperSubjectExtractorService"

private const val SETTING_AUTO_SUBJECT = "depth_wallpaper_auto_subject"
private const val SETTING_SUBJECT_URI = "depth_wallpaper_subject_image_uri"
private const val SETTING_DEPTH_ENABLED = "depth_wallpaper_enabled"

private const val DE_PHOTO_FILE = "wallpaper.jpg"
private const val FILE_PREFIX = "DEPTH_WALLPAPER_SUBJECT"

class WallpaperSubjectExtractorService : Service() {

    companion object {
        @JvmField
        val ACTION_EXTRACT_NOW = "com.android.intent.action.EXTRACT_DEPTH_SUBJECT_NOW"
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var currentGeneration = 0
    private var pendingExtraction: Runnable? = null
    private var lastScreenW = 0
    private var lastScreenH = 0

    private val DEBOUNCE_DELAY_MS = 500L

    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isAutoSubjectEnabled() && isDepthEnabled()) scheduleExtraction()
        }
    }

    private val colorsListener =
        WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) != 0) {
                if (isAutoSubjectEnabled()) scheduleExtraction()
            }
        }

    private val depthEnabledObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (isAutoSubjectEnabled() && isDepthEnabled()) scheduleExtraction()
        }
    }

    private val autoSubjectObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (isAutoSubjectEnabled() && isDepthEnabled()) scheduleExtraction()
        }
    }

    override fun onCreate() {
        super.onCreate()
        lastScreenW = resources.displayMetrics.widthPixels
        lastScreenH = resources.displayMetrics.heightPixels
        WallpaperManager.getInstance(this).addOnColorsChangedListener(colorsListener, handler)
        registerReceiver(
            wallpaperChangedReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            Context.RECEIVER_NOT_EXPORTED)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_DEPTH_ENABLED), false, depthEnabledObserver)
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(SETTING_AUTO_SUBJECT), false, autoSubjectObserver)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w != lastScreenW || h != lastScreenH) {
            lastScreenW = w; lastScreenH = h
            if (isAutoSubjectEnabled() && isDepthEnabled()) scheduleExtraction()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXTRACT_NOW) {
            scheduleExtraction(force = true)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener)
        unregisterReceiver(wallpaperChangedReceiver)
        contentResolver.unregisterContentObserver(depthEnabledObserver)
        contentResolver.unregisterContentObserver(autoSubjectObserver)
        pendingExtraction?.let { handler.removeCallbacks(it) }
        currentGeneration++
        super.onDestroy()
    }

    private fun isDepthEnabled() =
        Settings.System.getInt(contentResolver, SETTING_DEPTH_ENABLED, 0) != 0

    private fun isAutoSubjectEnabled() =
        Settings.System.getInt(contentResolver, SETTING_AUTO_SUBJECT, 0) != 0

    private fun scheduleExtraction(force: Boolean = false) {
        pendingExtraction?.let { handler.removeCallbacks(it) }
        val gen = ++currentGeneration
        val runnable = Runnable {
            Thread {
                try {
                    runExtraction(force, gen)
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction threw unexpected exception", e)
                }
            }.start()
        }
        pendingExtraction = runnable
        handler.postDelayed(runnable, if (force) 0L else DEBOUNCE_DELAY_MS)
    }

    private fun runExtraction(force: Boolean, gen: Int) {
        if (!force) {
            if (!isAutoSubjectEnabled() || !isDepthEnabled()) return
        }
        if (gen != currentGeneration) return

        val storageState = Environment.getExternalStorageState()
        if (storageState != Environment.MEDIA_MOUNTED) {
            Log.e(TAG, "External storage not mounted: $storageState")
            return
        }

        val wm = WallpaperManager.getInstance(this)
        val isLive = wm.wallpaperInfo != null

        val wallpaperBitmap = loadWallpaperBitmap(wm, isLive)
        if (wallpaperBitmap == null) {
            Log.e(TAG, "loadWallpaperBitmap returned null — cannot extract")
            return
        }
        if (gen != currentGeneration) {
            wallpaperBitmap.recycle()
            return
        }

        val (cropped, _) = centerCropToDisplay(wallpaperBitmap)
        if (cropped !== wallpaperBitmap && !wallpaperBitmap.isRecycled) wallpaperBitmap.recycle()
        if (gen != currentGeneration) {
            if (!cropped.isRecycled) cropped.recycle()
            return
        }

        val segmenter = PortraitSegmenter(this)
        segmenter.init()

        if (!segmenter.isReady()) {
            Log.e(TAG, "Segmenter not ready after init() — models likely missing from assets/")
            if (!cropped.isRecycled) cropped.recycle()
            return
        }

        try {
            val foreground = segmenter.segment(cropped)
            if (foreground == null) {
                Log.w(TAG, "segment() returned null — no subject detected in wallpaper")
                return
            }
            if (gen != currentGeneration) {
                foreground.recycle()
                return
            }

            val savedPath = saveForeground(foreground)
            foreground.recycle()

            if (savedPath == null) {
                Log.e(TAG, "saveForeground() failed — check storage permissions and path")
                return
            }
            if (gen != currentGeneration) return

            Settings.System.putStringForUser(
                contentResolver,
                SETTING_SUBJECT_URI,
                savedPath,
                UserHandle.USER_CURRENT,
            )
        } finally {
            segmenter.release()
            if (!cropped.isRecycled) cropped.recycle()
        }
    }

    private fun saveForeground(foreground: Bitmap): String? {
        return try {
            val baseDir = Environment.getExternalStorageDirectory()
            val dir = File(baseDir, "RisingOS/depthwallpaper")

            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.e(TAG, "Failed to create save directory")
                    return null
                }
            }

            dir.listFiles { _, name ->
                name.startsWith(FILE_PREFIX) && name.endsWith(".png")
            }?.forEach { it.delete() }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "${FILE_PREFIX}_${stamp}.png")

            FileOutputStream(file).use { out ->
                if (!foreground.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    Log.e(TAG, "Bitmap.compress() returned false")
                    return null
                }
            }

            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveForeground exception", e)
            null
        }
    }

    private fun loadWallpaperBitmap(wm: WallpaperManager, isLive: Boolean): Bitmap? {
        if (isLive) {
            loadFromDeStorage()?.let { return it }
        }

        try {
            val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
            if (pfd != null) {
                val bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                pfd.close()
                if (bmp != null) return bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lock wallpaper load failed", e)
        }

        try {
            val drawable = wm.drawable
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WM drawable load failed", e)
        }

        if (!isLive) {
            loadFromDeStorage()?.let { return it }
        }

        Log.e(TAG, "All wallpaper loading strategies failed")
        return null
    }

    private fun loadFromDeStorage(): Bitmap? {
        return try {
            val file = File(createDeviceProtectedStorageContext().filesDir, DE_PHOTO_FILE)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (e: Exception) {
            Log.w(TAG, "DE storage load failed", e)
            null
        }
    }

    private fun centerCropToDisplay(bitmap: Bitmap): Pair<Bitmap, Rect> {
        val wm = getSystemService(WindowManager::class.java)
        val maxBounds = wm?.maximumWindowMetrics?.bounds
        val dm = resources.displayMetrics
        val dstW = maxBounds?.width() ?: dm.widthPixels
        val dstH = maxBounds?.height() ?: dm.heightPixels

        val srcW = bitmap.width; val srcH = bitmap.height
        val fullRect = Rect(0, 0, srcW, srcH)

        if (dstW <= 0 || dstH <= 0) return Pair(bitmap, fullRect)

        val srcAR = srcW.toFloat() / srcH
        val dstAR = dstW.toFloat() / dstH

        val cropRect = if (srcAR > dstAR) {
            val cropW = (srcH * dstAR).roundToInt().coerceAtMost(srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstAR).roundToInt().coerceAtMost(srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
        }

        if (cropRect.width() >= srcW - 2 && cropRect.height() >= srcH - 2) {
            return Pair(bitmap, fullRect)
        }

        return try {
            Pair(
                Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()),
                cropRect
            )
        } catch (e: Exception) {
            Log.w(TAG, "Center-crop failed", e)
            Pair(bitmap, fullRect)
        }
    }
}
