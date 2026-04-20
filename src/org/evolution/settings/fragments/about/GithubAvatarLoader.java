/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.fragments.about;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.Preference;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GithubAvatarLoader {

    private static final String TAG = "GithubAvatarLoader";
    private static final String AVATAR_URL = "https://github.com/%s.png?size=96";

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public void load(Context context, Preference preference, String githubUsername) {
        mExecutor.execute(() -> {
            Bitmap bitmap = fetchAndCrop(githubUsername);
            if (bitmap != null) {
                BitmapDrawable drawable =
                        new BitmapDrawable(context.getResources(), bitmap);
                mMainHandler.post(() -> preference.setIcon(drawable));
            }
        });
    }

    private Bitmap fetchAndCrop(String username) {
        try {
            String urlStr = String.format(AVATAR_URL, username);
            HttpURLConnection conn =
                    (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.connect();
            InputStream is = conn.getInputStream();
            Bitmap raw = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            return raw != null ? toCircle(raw) : null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load avatar for " + username, e);
            return null;
        }
    }

    private Bitmap toCircle(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(
                src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return output;
    }
}
