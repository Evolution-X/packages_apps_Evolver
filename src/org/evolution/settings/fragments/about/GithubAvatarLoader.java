/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import androidx.preference.Preference;

import org.evolution.settings.utils.HttpCachePrefs;
import org.evolution.settings.utils.NetworkUtils;
import org.evolution.settings.utils.UrlUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GithubAvatarLoader {

    private static final String TAG = "GithubAvatarLoader";
    private static final String AVATAR_URL = "https://github.com/%s.png?size=256";

    private static final String PREFS_NAME = "github_avatar_cache";
    private static final String KEY_HASH_PREFIX = "hash_";

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private static volatile GithubAvatarLoader sInstance;

    private GithubAvatarLoader() {}

    public static GithubAvatarLoader getInstance() {
        if (sInstance == null) {
            synchronized (GithubAvatarLoader.class) {
                if (sInstance == null) sInstance = new GithubAvatarLoader();
            }
        }
        return sInstance;
    }

    public void load(Context context, Preference preference, String githubUsername) {
        if (context == null || preference == null) return;
        if (githubUsername == null || githubUsername.trim().isEmpty()) return;

        final String user = githubUsername.trim();
        final String userKey = user.toLowerCase(Locale.ROOT);

        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final File cacheFile = getAvatarFile(context, userKey);
        final HttpCachePrefs cache = new HttpCachePrefs(prefs, userKey);

        // 1) Immediate render from disk cache (persistent across reboot/process death).
        Bitmap cachedBitmap = decodeBitmap(cacheFile);
        if (cachedBitmap != null) {
            preference.setIcon(buildAdaptiveAvatar(context, cachedBitmap));
        }

        // 2) Revalidate when stale.
        if (!cache.isStale()) return;

        mExecutor.execute(() -> revalidateAndUpdate(context, preference, user, userKey, cacheFile, prefs));
    }

    /**
     * Fetches (or revalidates) the avatar for {@code user} from GitHub, writes it to
     * {@code cacheFile}, updates {@code prefs}, and returns the decoded Bitmap if the
     * remote content changed (or the file didn't exist yet). Returns null if unchanged
     * or on error.
     */
    private Bitmap fetchAndCacheAvatar(String user, String userKey,
            File cacheFile, SharedPreferences prefs) {
        final String hashKey = KEY_HASH_PREFIX + userKey;
        final HttpCachePrefs cache = new HttpCachePrefs(prefs, userKey);
        String cachedHash = UrlUtils.trimToEmpty(prefs.getString(hashKey, ""));

        try {
            NetworkUtils.FetchResult result = NetworkUtils.fetchWithStatus(
                    String.format(AVATAR_URL, user),
                    cache.buildHeaders(null));

            if (result.isNotModified()) {
                cache.touchLastCheck();
                return null;
            }
            if (!result.isOk() || result.bytes == null || result.bytes.length == 0) {
                cache.touchLastCheck();
                return null;
            }

            String newHash = sha256(result.bytes);
            boolean changed = !newHash.equals(cachedHash) || !cacheFile.exists();
            if (changed) writeFile(cacheFile, result.bytes);

            cache.write(result.etag, result.lastModified);
            prefs.edit().putString(hashKey, newHash).apply();

            if (!changed) return null;
            return BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.length);

        } catch (Exception e) {
            Log.w(TAG, "fetchAndCacheAvatar failed for " + user, e);
            cache.touchLastCheck();
            return null;
        }
    }

    public void loadIntoImageView(Context context, ImageView imageView, String githubUsername) {
        if (context == null || imageView == null) return;
        if (githubUsername == null || githubUsername.trim().isEmpty()) return;

        final String user = githubUsername.trim();
        final String userKey = user.toLowerCase(Locale.ROOT);

        final SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final File cacheFile = getAvatarFile(context, userKey);
        final HttpCachePrefs cache = new HttpCachePrefs(prefs, userKey);

        // 1) Instant render from disk cache
        Bitmap cachedBitmap = decodeBitmap(cacheFile);
        if (cachedBitmap != null) {
            applyCircleClip(imageView);
            imageView.setImageBitmap(cachedBitmap);
        }

        // 2) Revalidate if stale
        if (!cache.isStale()) return;

        mExecutor.execute(() -> {
            Bitmap bitmap = fetchAndCacheAvatar(user, userKey, cacheFile, prefs);
            if (bitmap != null) {
                mMainHandler.post(() -> {
                    applyCircleClip(imageView);
                    imageView.setImageBitmap(bitmap);
                });
            }
        });
    }

    private void revalidateAndUpdate(Context context, Preference preference, String user,
            String userKey, File cacheFile, SharedPreferences prefs) {
        Bitmap bitmap = fetchAndCacheAvatar(user, userKey, cacheFile, prefs);
        if (bitmap != null) {
            Drawable icon = buildAdaptiveAvatar(context, bitmap);
            mMainHandler.post(() -> preference.setIcon(icon));
        }
    }

    private Drawable buildAdaptiveAvatar(Context context, Bitmap bitmap) {
        Bitmap square = centerCropSquare(bitmap);
        Drawable fg = new BitmapDrawable(context.getResources(), square);
        return new AdaptiveIconDrawable(
                new ColorDrawable(Color.TRANSPARENT),
                fg
        );
    }

    private Bitmap centerCropSquare(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        int left = (src.getWidth() - size) / 2;
        int top = (src.getHeight() - size) / 2;
        return Bitmap.createBitmap(src, left, top, size, size);
    }

    private File getAvatarFile(Context context, String userKey) {
        File dir = new File(context.getCacheDir(), "github_avatars");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create avatar cache dir: " + dir.getAbsolutePath());
        }
        return new File(dir, userKey + ".png");
    }

    private Bitmap decodeBitmap(File file) {
        try {
            if (!file.exists()) return null;
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(data);
            fos.flush();
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void applyCircleClip(ImageView view) {
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(android.view.View v, android.graphics.Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        view.setClipToOutline(true);
    }
}
