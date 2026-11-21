/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.themes;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Loads the previews of a picker in the background and caches them. Reading one can mean opening
 * an overlay's resources or decoding a big image, which stalls the list when it is done from
 * onBindViewHolder.
 */
final class PreviewLoader<K, V> {

    interface Loader<V> {
        /** Runs in the background. Returns null when there is nothing to show. */
        V load();
    }

    interface Binder<V> {
        /** Runs on the main thread. */
        void bind(V preview);
    }

    interface Sizer<V> {
        /** How much of the cache a preview takes. */
        int sizeOf(V preview);
    }

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final LruCache<K, V> mCache;

    /** A cache of [maxSize], in the units [sizer] counts previews in. */
    PreviewLoader(int maxSize, Sizer<V> sizer) {
        mCache = new LruCache<K, V>(maxSize) {
            @Override
            protected int sizeOf(K key, V preview) {
                return sizer.sizeOf(preview);
            }
        };
    }

    /** A cache of at most [maxEntries] previews. */
    PreviewLoader(int maxEntries) {
        this(maxEntries, preview -> 1);
    }

    /**
     * Calls [binder] with the preview of [key], at once when it is cached and otherwise when
     * [loader] has loaded it. The key is kept as [view]'s tag, so a view that was recycled for
     * another item in the meantime is not given this one's preview. Returns whether the preview
     * was cached, for the caller to clear the view when it was not.
     */
    boolean show(View view, K key, Loader<V> loader, Binder<V> binder) {
        view.setTag(key);
        V cached = mCache.get(key);
        if (cached != null) {
            binder.bind(cached);
            return true;
        }
        try {
            mExecutor.execute(() -> {
                V preview = loader.load();
                if (preview == null) return;
                mMainHandler.post(() -> {
                    mCache.put(key, preview);
                    if (key.equals(view.getTag())) binder.bind(preview);
                });
            });
        } catch (RejectedExecutionException e) {
            // The picker is gone.
        }
        return false;
    }

    void shutdown() {
        mExecutor.shutdownNow();
        mMainHandler.removeCallbacksAndMessages(null);
    }

    /** A copy of a cached drawable, as one drawable cannot be shown by two views. */
    static Drawable copy(Drawable drawable) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        return state != null ? state.newDrawable() : drawable;
    }
}
