/*
 * Copyright (C) 2026 AxionOS
 * Copyright (C) 2026 VoltageOS
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
package org.evolution.settings.fragments.miscellaneous;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class KeyboxManager {

    private static final String TAG = "KeyboxManager";
    private static final String TRICKY_DIR = "/data/adb/tricky_store";
    private static final String KEYBOX_FILE = "keybox.xml";
    private static final String TARGET_FILE = "target.txt";
    private static final String VENDING_PKG = "com.android.vending";

    private final Context mContext;

    public KeyboxManager(Context context) {
        mContext = context.getApplicationContext();
        ensureDir();
    }

    public boolean keyboxExists() {
        File file = new File(TRICKY_DIR, KEYBOX_FILE);
        return file.exists() && file.canRead();
    }

    public void importKeybox(Uri uri) throws Exception {
        copyUriToFile(uri, new File(TRICKY_DIR, KEYBOX_FILE));
        killVending();
    }

    public void deleteKeybox() {
        File file = new File(TRICKY_DIR, KEYBOX_FILE);
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete keybox");
        }
        killVending();
    }

    public int getTargetAppCount() {
        return readTargetLines().size();
    }

    public void importTargetFile(Uri uri) throws Exception {
        copyUriToFile(uri, new File(TRICKY_DIR, TARGET_FILE));
        killVending();
    }

    public void saveTargetLines(List<String> lines) {
        ensureDir();
        File file = new File(TRICKY_DIR, TARGET_FILE);
        try (FileWriter writer = new FileWriter(file)) {
            for (String line : lines) {
                writer.write(line);
                writer.write("\n");
            }
            file.setReadable(true, false);
            killVending();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save target lines", e);
        }
    }

    public List<String> readTargetLines() {
        List<String> result = new ArrayList<>();
        File file = new File(TRICKY_DIR, TARGET_FILE);
        if (!file.exists()) {
            return result;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    result.add(trimmed);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read target lines", e);
        }
        return result;
    }

    private void ensureDir() {
        File dir = new File(TRICKY_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void copyUriToFile(Uri uri, File destination) throws Exception {
        ensureDir();
        try (InputStream inputStream = mContext.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to open file");
            }

            byte[] bytes = inputStream.readAllBytes();
            try (FileOutputStream outputStream = new FileOutputStream(destination)) {
                outputStream.write(bytes);
            }
            destination.setReadable(true, false);
        }
    }

    private void killVending() {
        try {
            ActivityManager activityManager = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                activityManager.forceStopPackage(VENDING_PKG);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop Play Store", e);
        }
    }
}
