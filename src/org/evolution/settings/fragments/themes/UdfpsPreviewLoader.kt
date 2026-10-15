/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors

/**
 * Loads the UDFPS icon and animation previews for the pickers. They are big mdpi images, so they
 * are decoded in the background at the size they are shown and cached, instead of decoding each
 * one on the main thread while scrolling.
 */
internal class UdfpsPreviewLoader(
    private val res: Resources,
    private val theme: Resources.Theme?
) {

    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<Int, Any>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Any): Int =
            if (value is Bitmap) value.allocationByteCount else 1024
    }

    /** Shows the drawable [id] in [view], from the cache or once it is decoded at [size] px. */
    fun show(view: ImageView, id: Int, size: Int) {
        view.setTag(id)
        val cached = cache.get(id)
        if (cached != null) {
            display(view, cached)
            return
        }
        view.setImageDrawable(null)
        if (id == 0) return
        try {
            executor.execute {
                val preview = load(id, size) ?: return@execute
                mainHandler.post {
                    cache.put(id, preview)
                    if (view.getTag() == id) display(view, preview)
                }
            }
        } catch (_: RejectedExecutionException) {
            // The picker is gone.
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    /** A bitmap scaled down to about [size] px, or a drawable's state for vectors. */
    private fun load(id: Int, size: Int): Any? {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inJustDecodeBounds = true
        }
        try {
            BitmapFactory.decodeResource(res, id, options)
            if (options.outWidth <= 0) {
                return res.getDrawable(id, theme)?.constantState
            }
            var sample = 1
            while (options.outWidth / (sample * 2) >= size) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            return BitmapFactory.decodeResource(res, id, options)
        } catch (_: Resources.NotFoundException) {
            return null
        }
    }

    private fun display(view: ImageView, preview: Any) {
        if (preview is Bitmap) {
            view.setImageBitmap(preview)
        } else {
            view.setImageDrawable((preview as Drawable.ConstantState).newDrawable(res, theme))
        }
    }

    private companion object {
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
