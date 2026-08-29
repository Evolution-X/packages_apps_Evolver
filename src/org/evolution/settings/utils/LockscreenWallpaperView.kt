/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileDescriptor

class LockscreenWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var lastWallpaperId: Int = -1
    private var pendingJob: Job? = null

    private val wallpaperChecker = object : Runnable {
        override fun run() {
            updateLockscreenWallpaper()
            handler.postDelayed(this, 2000)
        }
    }

    init {
        post {
            updateLockscreenWallpaper()
            handler.postDelayed(wallpaperChecker, 2000)
        }
    }

    private fun updateLockscreenWallpaper() {
        if (width == 0 || height == 0) return

        val wallpaperManager = WallpaperManager.getInstance(context)
        val wallpaperId = try {
            wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
                .let { if (it >= 0) it else wallpaperManager.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }
        } catch (e: Exception) {
            -1
        }

        if (wallpaperId == lastWallpaperId && wallpaperId != -1) return

        val targetW = width
        val targetH = height

        pendingJob?.cancel()
        pendingJob = scope.launch {
            val drawable = withContext(Dispatchers.IO) {
                decodeSampledWallpaper(wallpaperManager, targetW, targetH)
            }
            if (drawable != null) {
                lastWallpaperId = wallpaperId
                setImageDrawable(drawable)
            }
        }
    }

    private fun decodeSampledWallpaper(
        wm: WallpaperManager,
        reqWidth: Int,
        reqHeight: Int
    ): Drawable? {
        val pfd = try {
            wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
                ?: wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
        } catch (e: Exception) {
            null
        } ?: return fallbackDrawable(wm)

        pfd.use { descriptor ->
            val fd: FileDescriptor = descriptor.fileDescriptor

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fd, null, boundsOpts)

            val sampleSize = calculateInSampleSize(boundsOpts, reqWidth, reqHeight)


            val fd2 = descriptor.fileDescriptor
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeFileDescriptor(fd2, null, decodeOpts)
                ?: return fallbackDrawable(wm)

            return BitmapDrawable(resources, bitmap)
        }
    }

    private fun fallbackDrawable(wm: WallpaperManager): Drawable? {
        return try {
            wm.drawable
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight &&
                halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(wallpaperChecker)
        pendingJob?.cancel()
    }
}
