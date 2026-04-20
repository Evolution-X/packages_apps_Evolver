/*
 * Copyright (C) 2026 Evolution X
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

import androidx.preference.Preference;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GithubAvatarLoader {

    private static final String TAG = "GithubAvatarLoader";
    private static final String AVATAR_URL = "https://github.com/%s.png?size=256";

    private static final String PREFS_NAME = "github_avatar_cache";
    private static final String KEY_ETAG_PREFIX = "etag_";
    private static final String KEY_LAST_MOD_PREFIX = "last_mod_";
    private static final String KEY_LAST_CHECK_PREFIX = "last_check_";
    private static final String KEY_HASH_PREFIX = "hash_";

    // Revalidate once per day; still updates immediately when remote changed at revalidation time.
    private static final long CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public void load(Context context, Preference preference, String githubUsername) {
        if (context == null || preference == null) return;
        if (githubUsername == null || githubUsername.trim().isEmpty()) return;

        final String user = githubUsername.trim();
        final String userKey = user.toLowerCase(Locale.ROOT);

        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final File cacheFile = getAvatarFile(context, userKey);

        // 1) Immediate render from disk cache (persistent across reboot/process death).
        Bitmap cachedBitmap = decodeBitmap(cacheFile);
        if (cachedBitmap != null) {
            preference.setIcon(buildAdaptiveAvatar(context, cachedBitmap));
        }

        // 2) Revalidate when stale.
        long lastCheck = prefs.getLong(KEY_LAST_CHECK_PREFIX + userKey, 0L);
        long now = System.currentTimeMillis();
        if (now - lastCheck < CACHE_MAX_AGE_MS) {
            return;
        }

        mExecutor.execute(() -> revalidateAndUpdate(context, preference, user, userKey, cacheFile, prefs));
    }

    public void shutdown() {
        if (!mExecutor.isShutdown()) {
            mExecutor.shutdown();
        }
    }

    private void revalidateAndUpdate(Context context, Preference preference, String user, String userKey,
                                     File cacheFile, SharedPreferences prefs) {
        HttpURLConnection conn = null;
        final String etagKey = KEY_ETAG_PREFIX + userKey;
        final String lastModKey = KEY_LAST_MOD_PREFIX + userKey;
        final String hashKey = KEY_HASH_PREFIX + userKey;
        final String lastCheckKey = KEY_LAST_CHECK_PREFIX + userKey;

        String cachedEtag = trimToEmpty(prefs.getString(etagKey, ""));
        String cachedLastMod = trimToEmpty(prefs.getString(lastModKey, ""));
        String cachedHash = trimToEmpty(prefs.getString(hashKey, ""));

        try {
            String urlStr = String.format(AVATAR_URL, user);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (!cachedEtag.isEmpty()) conn.setRequestProperty("If-None-Match", cachedEtag);
            if (!cachedLastMod.isEmpty()) conn.setRequestProperty("If-Modified-Since", cachedLastMod);

            conn.connect();
            int code = conn.getResponseCode();

            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                prefs.edit().putLong(lastCheckKey, System.currentTimeMillis()).apply();
                return;
            }

            if (code != HttpURLConnection.HTTP_OK) {
                prefs.edit().putLong(lastCheckKey, System.currentTimeMillis()).apply();
                return;
            }

            byte[] bytes;
            try (InputStream is = conn.getInputStream()) {
                bytes = readBytes(is);
            }

            if (bytes == null || bytes.length == 0) {
                prefs.edit().putLong(lastCheckKey, System.currentTimeMillis()).apply();
                return;
            }

            String newHash = sha256(bytes);
            String newEtag = trimToEmpty(conn.getHeaderField("ETag"));
            String newLastMod = trimToEmpty(conn.getHeaderField("Last-Modified"));

            boolean changed = !newHash.equals(cachedHash) || !cacheFile.exists();
            if (changed) {
                writeFile(cacheFile, bytes);
            }

            prefs.edit()
                    .putString(etagKey, newEtag)
                    .putString(lastModKey, newLastMod)
                    .putString(hashKey, newHash)
                    .putLong(lastCheckKey, System.currentTimeMillis())
                    .apply();

            if (changed) {
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    Drawable icon = buildAdaptiveAvatar(context, bitmap);
                    mMainHandler.post(() -> preference.setIcon(icon));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to revalidate avatar for " + user, e);
            prefs.edit().putLong(lastCheckKey, System.currentTimeMillis()).apply();
        } finally {
            if (conn != null) conn.disconnect();
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

    private byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        return bos.toByteArray();
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

    private String trimToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
