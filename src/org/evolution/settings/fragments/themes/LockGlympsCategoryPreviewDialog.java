/*
 * Copyright (C) 2026 Evolution X contributors
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

package org.evolution.settings.fragments.themes;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.AdapterView;

import com.android.settings.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On-demand, read-only category examples. Unlike the installed wallpaper preview, these
 * previews do not apply any image, do not prefetch full-size wallpapers, and do not use
 * API keys. Metadata and thumbnail responses are strictly bounded and kept in memory.
 */
final class LockGlympsCategoryPreviewDialog {
    private static final int MAX_EXAMPLES = 4;
    private static final int MAX_METADATA_BYTES = 256 * 1024;
    private static final int MAX_THUMBNAIL_BYTES = 384 * 1024;
    private static final int MAX_PREVIEW_SIDE_PX = 256;
    private static final int MAX_DAILY_PREVIEW_REQUESTS = 48;
    private static final long MAX_DAILY_PREVIEW_BYTES = 16L * 1024 * 1024;
    private static final String PREVIEW_BUDGET_PREFS = "glymps_preview_budget";
    private static final Object PREVIEW_BUDGET_LOCK = new Object();

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final AtomicInteger mGeneration = new AtomicInteger();
    private final List<String> mCategories = new ArrayList<>();
    private final List<String> mLabels = new ArrayList<>();
    private final Set<String> mProviders = new HashSet<>();
    private final Map<String, String> mKnownLabels = new HashMap<>();

    private AlertDialog mDialog;
    private TextView mStatus;
    private GridLayout mGallery;
    private volatile boolean mPreviewLimitExceeded;
    private volatile boolean mPreviewPausedByPolicy;

    static void show(Context context) {
        new LockGlympsCategoryPreviewDialog(context).open();
    }

    private LockGlympsCategoryPreviewDialog(Context context) {
        mContext = context;
        String[] values = context.getResources().getStringArray(
                R.array.lock_glymps_category_values);
        String[] entries = context.getResources().getStringArray(
                R.array.lock_glymps_category_entries);
        for (int i = 0; i < values.length && i < entries.length; i++) {
            mKnownLabels.put(values[i], entries[i]);
        }

        String categories = Settings.System.getString(
                context.getContentResolver(), "lock_glymps_categories");
        if (categories == null || categories.trim().isEmpty()) {
            categories = "nature,amoled,space";
        }
        for (String token : categories.split(",")) {
            String value = token.trim();
            if (!value.isEmpty() && !mCategories.contains(value)
                    && mCategories.size() < 512) {
                mCategories.add(value);
                String title = mKnownLabels.get(value);
                mLabels.add("✓ " + (title != null ? title : value.replace('_', ' ')));
            }
        }

        // Let people inspect themes before selecting them. Custom user topics
        // appear first, then every built-in catalog category.
        for (String value : values) {
            if (!mCategories.contains(value) && mCategories.size() < 512) {
                mCategories.add(value);
                mLabels.add(mKnownLabels.get(value));
            }
        }

        String providers = Settings.System.getString(
                context.getContentResolver(), "lock_glymps_providers");
        if (providers == null || providers.trim().isEmpty()) {
            providers = "wallhaven,openverse";
        }
        mProviders.addAll(Arrays.asList(providers.split(",")));
    }

    private int dp(float value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }

    private void open() {
        LinearLayout layout = new LinearLayout(mContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(10), dp(16), dp(12));

        TextView intro = new TextView(mContext);
        intro.setText(R.string.lock_glymps_category_preview_notice);
        layout.addView(intro);

        AutoCompleteTextView selector = new AutoCompleteTextView(mContext);
        selector.setSingleLine(true);
        selector.setThreshold(1);
        selector.setHint(R.string.lock_glymps_category_preview_search_hint);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                mContext, android.R.layout.simple_dropdown_item_1line, mLabels);
        selector.setAdapter(adapter);
        if (!mLabels.isEmpty()) selector.setText(mLabels.get(0), false);
        layout.addView(selector);

        Button previewButton = new Button(mContext);
        previewButton.setText(R.string.lock_glymps_category_preview_search);
        layout.addView(previewButton);
        previewButton.setOnClickListener(view -> {
            String text = selector.getText().toString().trim();
            if (text.isEmpty()) return;
            int index = mLabels.indexOf(text);
            if (index < 0 && (text.length() > 64
                    || !text.matches("[\\p{L}\\p{N} _-]+"))) {
                mStatus.setText(R.string.lock_glymps_invalid_category);
                return;
            }
            String value = index >= 0 ? mCategories.get(index)
                    : text.replace(' ', '_');
            load(value);
        });

        mStatus = new TextView(mContext);
        mStatus.setPadding(0, dp(12), 0, dp(8));
        layout.addView(mStatus);

        mGallery = new GridLayout(mContext);
        mGallery.setColumnCount(2);
        layout.addView(mGallery);

        mDialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.lock_glymps_category_preview_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        mDialog.setOnDismissListener(ignored -> {
            mGeneration.incrementAndGet();
            mWorker.shutdownNow();
            mGallery.removeAllViews();
        });

        selector.setOnItemClickListener((parent, view, position, id) -> {
            String chosen = String.valueOf(parent.getItemAtPosition(position));
            int index = mLabels.indexOf(chosen);
            if (index >= 0) load(mCategories.get(index));
        });
        selector.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                previewButton.performClick();
                return true;
            }
            return false;
        });
        selector.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        mDialog.show();
        if (!mCategories.isEmpty()) {
            load(mCategories.get(0));
        }
        if (mCategories.isEmpty()) {
            mStatus.setText(R.string.lock_glymps_category_preview_empty);
        }
    }

    private void load(String category) {
        final int generation = mGeneration.incrementAndGet();
        mPreviewLimitExceeded = false;
        mPreviewPausedByPolicy = false;
        mGallery.removeAllViews();
        mStatus.setText(R.string.lock_glymps_category_preview_loading);
        if (!mProviders.contains("wallhaven") && !mProviders.contains("openverse")) {
            mStatus.setText(R.string.lock_glymps_category_preview_no_search_provider);
            return;
        }
        if (!allowedByDownloadPolicy()) {
            mStatus.setText(R.string.lock_glymps_category_preview_network_paused);
            return;
        }
        try {
            mWorker.execute(() -> {
                if (generation != mGeneration.get()) return;
                List<Example> examples = new ArrayList<>();
                String query = queryForCategory(category);

                if (mProviders.contains("wallhaven")) {
                    try {
                        appendWallhaven(query, examples);
                    } catch (Exception ignored) {
                        // Continue with Openverse even if Wallhaven fails.
                    }
                }
                if (generation != mGeneration.get()
                        || Thread.currentThread().isInterrupted()) return;
                if (examples.size() < MAX_EXAMPLES && mProviders.contains("openverse")) {
                    try {
                        appendOpenverse(query, examples);
                    } catch (Exception ignored) {
                        // Keep previously retrieved examples.
                    }
                }
                for (Example example : examples) {
                    if (generation != mGeneration.get()
                            || Thread.currentThread().isInterrupted()) return;
                    try {
                        example.preview = decodeSmallThumbnail(
                                fetchBytes(example.thumbnailUrl, MAX_THUMBNAIL_BYTES));
                    } catch (Exception ignored) {
                        // A missing thumbnail must not crash Settings.
                    }
                }
                mMain.post(() -> {
                    if (generation != mGeneration.get() || mDialog == null
                            || !mDialog.isShowing()) return;
                    int displayed = 0;
                    for (Example example : examples) {
                        if (example.preview == null) continue;
                        addExample(example);
                        displayed++;
                    }
                    int emptyLabel = mPreviewPausedByPolicy
                            ? R.string.lock_glymps_category_preview_paused
                            : mPreviewLimitExceeded
                                    ? R.string.lock_glymps_category_preview_budget_reached
                                    : R.string.lock_glymps_category_preview_empty;
                    mStatus.setText(displayed > 0
                            ? mContext.getString(R.string.lock_glymps_category_preview_count,
                                    displayed)
                            : mContext.getString(emptyLabel));
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Dialog closed before the pending selection was dispatched.
        }
    }

    private String queryForCategory(String category) {
        String query;
        switch (category) {
            case "amoled": query = "AMOLED pure black wallpaper"; break;
            case "pure_black": query = "pure black dark wallpaper"; break;
            case "oled": query = "OLED black wallpaper"; break;
            case "dark_minimal": query = "dark minimal wallpaper"; break;
            case "dark_abstract": query = "dark abstract wallpaper"; break;
            case "night_nature": query = "night nature landscape"; break;
            case "night_cities": query = "night city skyline"; break;
            case "cyberpunk_cities": query = "cyberpunk futuristic city"; break;
            case "cosmic_abstract": query = "cosmic space abstract"; break;
            case "milky_way": query = "Milky Way night sky"; break;
            case "ai": query = "artificial intelligence technology"; break;
            case "jdm": query = "JDM cars"; break;
            case "quranic_art": query = "Quran Islamic art"; break;
            case "islamic": query = "Islamic art architecture"; break;
            case "islamic_architecture": query = "Islamic mosque architecture"; break;
            case "islamic_geometry": query = "Islamic geometric art"; break;
            case "islamic_patterns": query = "Islamic geometric patterns"; break;
            case "kaaba": query = "Kaaba Mecca"; break;
            case "mecca": query = "Mecca mosque"; break;
            case "medina": query = "Medina mosque"; break;
            case "ramadan": query = "Ramadan Islamic"; break;
            case "eid": query = "Eid Islamic"; break;
            case "middle_east": query = "Middle East landscape architecture"; break;
            case "abstract_3d": query = "3D abstract art"; break;
            case "art_3d": query = "3D digital art"; break;
            case "scifi_gaming": query = "science fiction gaming"; break;
            default: query = category.replace('_', ' ');
        }
        String tone = Settings.System.getString(
                mContext.getContentResolver(), "lock_glymps_tone");
        if ("dark".equals(tone)) return query + " dark";
        if ("amoled".equals(tone)) return query + " AMOLED black dark";
        if ("light".equals(tone)) return query + " bright light";
        if ("colorful".equals(tone)) return query + " colorful vibrant";
        if ("monochrome".equals(tone)) return query + " monochrome black and white";
        return query;
    }

    private int[] minimumBounds() {
        String value = Settings.System.getString(
                mContext.getContentResolver(), "lock_glymps_min_resolution");
        if ("4320".equals(value)) return new int[]{4320, 7680};
        if ("2160".equals(value)) return new int[]{2160, 3840};
        if ("1440".equals(value)) return new int[]{1440, 2560};
        if ("1080".equals(value)) return new int[]{1080, 1920};
        return new int[]{0, 0};
    }

    private boolean allowedDimensions(int width, int height) {
        int[] min = minimumBounds();
        if (width <= 0 || height <= 0) return false;
        if (Math.min(width, height) < min[0] || Math.max(width, height) < min[1])
            return false;
        String orientation = Settings.System.getString(
                mContext.getContentResolver(), "lock_glymps_orientation");
        if ("portrait".equals(orientation) && height <= width) return false;
        if ("landscape".equals(orientation) && width <= height) return false;
        if ("square".equals(orientation)
                && (width * 10L < height * 9L || height * 10L < width * 9L))
            return false;
        return true;
    }

    private void appendWallhaven(String query, List<Example> out) throws Exception {
        int[] min = minimumBounds();
        StringBuilder url = new StringBuilder(
                "https://wallhaven.cc/api/v1/search?q=")
                .append(android.net.Uri.encode(query))
                .append("&categories=111&purity=100&sorting=relevance");
        if (min[0] > 0) {
            String orientation = Settings.System.getString(
                    mContext.getContentResolver(), "lock_glymps_orientation");
            int w = "landscape".equals(orientation) ? min[1] : min[0];
            int h = "landscape".equals(orientation) ? min[0] : min[1];
            url.append("&atleast=").append(w).append("x").append(h);
        }
        JSONObject root = new JSONObject(new String(
                fetchBytes(url.toString(), MAX_METADATA_BYTES), StandardCharsets.UTF_8));
        JSONArray results = root.optJSONArray("data");
        if (results == null) return;
        for (int i = 0; i < results.length() && out.size() < 2; i++) {
            JSONObject image = results.optJSONObject(i);
            if (image == null || !allowedDimensions(
                    image.optInt("dimension_x"), image.optInt("dimension_y"))) continue;
            JSONObject thumbs = image.optJSONObject("thumbs");
            String thumbnail = thumbs == null ? "" : thumbs.optString("small", "");
            if (thumbnail.startsWith("https://th.wallhaven.cc/")) {
                out.add(new Example(thumbnail, "Wallhaven",
                        image.optInt("dimension_x"), image.optInt("dimension_y")));
            }
        }
    }

    private void appendOpenverse(String query, List<Example> out) throws Exception {
        String endpoint = "https://api.openverse.org/v1/images/?q="
                + android.net.Uri.encode(query)
                + "&license=cc0,pdm&mature=false&size=large&page_size=20";
        JSONObject root = new JSONObject(new String(
                fetchBytes(endpoint, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
        JSONArray results = root.optJSONArray("results");
        if (results == null) return;
        for (int i = 0; i < results.length() && out.size() < MAX_EXAMPLES; i++) {
            JSONObject image = results.optJSONObject(i);
            if (image == null || !allowedDimensions(
                    image.optInt("width"), image.optInt("height"))) continue;
            String license = image.optString("license", "");
            if (!"cc0".equalsIgnoreCase(license) && !"pdm".equalsIgnoreCase(license))
                continue;
            String id = image.optString("id", "");
            if (!id.matches("[0-9a-fA-F-]{32,40}")) continue;
            String thumbnail = "https://api.openverse.org/v1/images/"
                    + id + "/thumb/?full_size=false";
            out.add(new Example(thumbnail, "Openverse · " + license,
                    image.optInt("width"), image.optInt("height")));
        }
    }

    /** Apply the same network, charging and Battery Saver preferences as the service. */
    private boolean allowedByDownloadPolicy() {
        PowerManager power = mContext.getSystemService(PowerManager.class);
        if (Settings.System.getInt(mContext.getContentResolver(),
                "lock_glymps_pause_battery_saver", 1) != 0
                && power != null && power.isPowerSaveMode()) {
            return false;
        }
        if (Settings.System.getInt(mContext.getContentResolver(),
                "lock_glymps_charging_only", 0) != 0) {
            BatteryManager battery = mContext.getSystemService(BatteryManager.class);
            if (battery == null || !battery.isCharging()) return false;
        }

        ConnectivityManager connectivity = mContext.getSystemService(
                ConnectivityManager.class);
        if (connectivity == null) return false;
        Network active = connectivity.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(active);
        if (caps == null
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return false;
        }
        if (connectivity.isActiveNetworkMetered()
                && connectivity.getRestrictBackgroundStatus()
                    == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) {
            return false;
        }
        if (Settings.System.getInt(mContext.getContentResolver(),
                "lock_glymps_wifi_only", 1) != 0) {
            return !connectivity.isActiveNetworkMetered()
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        }
        return true;
    }

    private boolean reservePreviewRequest(int maximumBytes) {
        if (maximumBytes <= 0 || maximumBytes > MAX_THUMBNAIL_BYTES) return false;
        synchronized (PREVIEW_BUDGET_LOCK) {
            SharedPreferences prefs = mContext.getSharedPreferences(
                    PREVIEW_BUDGET_PREFS, Context.MODE_PRIVATE);
            long today = System.currentTimeMillis() / (24L * 60 * 60 * 1000);
            long day = prefs.getLong("day", -1L);
            int requests = day == today ? prefs.getInt("requests", 0) : 0;
            long bytes = day == today ? prefs.getLong("reserved_bytes", 0L) : 0L;
            // A manual clock rollback must not reset a previously consumed allowance.
            if (day > today) return false;
            if (requests >= MAX_DAILY_PREVIEW_REQUESTS
                    || bytes > MAX_DAILY_PREVIEW_BYTES - maximumBytes) {
                return false;
            }
            return prefs.edit().putLong("day", today)
                    .putInt("requests", requests + 1)
                    .putLong("reserved_bytes", bytes + maximumBytes).commit();
        }
    }

    private byte[] fetchBytes(String url, int maxBytes) throws Exception {
        // No redirects, arbitrary user hosts, remote file writes or full-size images.
        if (!(url.startsWith("https://wallhaven.cc/api/v1/")
                || url.startsWith("https://api.openverse.org/v1/images/")
                || url.startsWith("https://th.wallhaven.cc/"))) {
            throw new java.io.IOException("Untrusted thumbnail host");
        }
        // Charge the maximum possible response bytes *before* opening a socket.
        // The allowance persists between Settings restarts and across dialogs.
        if (!allowedByDownloadPolicy()) {
            mPreviewPausedByPolicy = true;
            throw new java.io.IOException("Wallpaper preview blocked by power/network policy");
        }
        if (!reservePreviewRequest(maxBytes)) {
            mPreviewLimitExceeded = true;
            throw new java.io.IOException("Daily Glymps preview budget exhausted");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6500);
        connection.setReadTimeout(6500);
        connection.setRequestProperty("Accept", "application/json,image/*");
        connection.setRequestProperty("User-Agent", "EvolutionX-Glymps-Preview/1.0");
        try {
            if (connection.getResponseCode() != 200
                    || connection.getContentLengthLong() > maxBytes) {
                throw new java.io.IOException("Unavailable or oversized preview");
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (Thread.currentThread().isInterrupted()
                            || buffer.size() + count > maxBytes) {
                        throw new java.io.IOException("Preview size or cancellation limit");
                    }
                    buffer.write(chunk, 0, count);
                }
                return buffer.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Bitmap decodeSmallThumbnail(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > 16000 || bounds.outHeight > 16000
                || (long) bounds.outWidth * bounds.outHeight > 32_000_000L) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_PREVIEW_SIDE_PX
                && sample < 128) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private void addExample(Example example) {
        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(3), dp(3), dp(3), dp(3));

        ImageView image = new ImageView(mContext);
        image.setImageBitmap(example.preview);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new LinearLayout.LayoutParams(dp(132), dp(114)));

        TextView details = new TextView(mContext);
        details.setGravity(Gravity.CENTER);
        details.setText(example.provider + "\n" + example.width + " × " + example.height);
        details.setTextSize(11);
        card.addView(details, new LinearLayout.LayoutParams(dp(132),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        GridLayout.LayoutParams position = new GridLayout.LayoutParams();
        position.width = dp(138);
        position.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        mGallery.addView(card, position);
    }

    private static final class Example {
        final String thumbnailUrl;
        final String provider;
        final int width;
        final int height;
        Bitmap preview;

        Example(String thumbnailUrl, String provider, int width, int height) {
            this.thumbnailUrl = thumbnailUrl;
            this.provider = provider;
            this.width = width;
            this.height = height;
        }
    }
}
