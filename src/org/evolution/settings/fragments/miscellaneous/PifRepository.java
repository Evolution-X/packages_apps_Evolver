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

import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PifRepository {

    private static final String TAG = "PifRepository";
    private static final String GOOGLE_URL = "https://developer.android.com";
    private static final String EVOLUTION_PIF_URL =
            "https://raw.githubusercontent.com/Evolution-X/.github/refs/heads/main/profile/pif.json";

    public abstract static class PifResult {
        private PifResult() {
        }

        public static final class Success extends PifResult {
            public final String model;
            public final JSONObject pifData;

            public Success(String model, JSONObject pifData) {
                this.model = model;
                this.pifData = pifData;
            }
        }

        public static final class Error extends PifResult {
            public final String message;
            public final Exception exception;

            public Error(String message) {
                this(message, null);
            }

            public Error(String message, Exception exception) {
                this.message = message != null ? message : "Unknown error";
                this.exception = exception;
            }
        }
    }

    public PifResult fetchBetaPif() {
        try {
            String versionsHtml = fetchUrl(GOOGLE_URL + "/about/versions");
            List<Integer> versions = extractVersionNumbers(versionsHtml);
            if (versions.isEmpty()) {
                return new PifResult.Error("Could not find Android version pages");
            }

            for (int version : versions) {
                String versionUrl = GOOGLE_URL + "/about/versions/" + version;
                try {
                    String versionHtml = fetchUrl(versionUrl);
                    List<String> qprPaths = extractQprPaths(versionHtml, version);
                    if (qprPaths.isEmpty()) {
                        continue;
                    }

                    for (String qprPath : qprPaths) {
                        try {
                            String otaHtml = fetchUrl(GOOGLE_URL + qprPath);
                            List<String[]> otaEntries = extractOtaUrls(otaHtml);
                            if (otaEntries.isEmpty()) {
                                continue;
                            }

                            List<String[]> devices = matchDevicesToOta(otaHtml, otaEntries);
                            if (devices.isEmpty()) {
                                continue;
                            }

                            String[] selected = devices.get(new Random().nextInt(devices.size()));
                            String model = selected[0];
                            String product = selected[1];
                            String otaUrl = selected[2];
                            String device = product.replace("_beta", "");

                            String partial = fetchPartialUrl(otaUrl, 4096);
                            String fingerprint = extractRegex(partial, "post-build=(.*)");
                            String securityPatch = extractRegex(partial, "security-patch-level=(.*)");

                            if (fingerprint == null || securityPatch == null) {
                                continue;
                            }

                            JSONObject pifJson = new JSONObject();
                            pifJson.put("MANUFACTURER", "Google");
                            pifJson.put("MODEL", model);
                            pifJson.put("PRODUCT", product);
                            pifJson.put("DEVICE", device);
                            pifJson.put("FINGERPRINT", fingerprint.trim());
                            pifJson.put("SECURITY_PATCH", securityPatch.trim());
                            pifJson.put("DEVICE_INITIAL_SDK_INT", "32");
                            return new PifResult.Success(model, pifJson);
                        } catch (Exception e) {
                            Log.d(TAG, "Failed OTA page: " + qprPath, e);
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Failed version page: " + versionUrl, e);
                }
            }

            return new PifResult.Error("No valid beta OTA pages found");
        } catch (Exception e) {
            Log.e(TAG, "Failed fetching beta PIF", e);
            return new PifResult.Error("Failed to fetch from Google: " + e.getMessage(), e);
        }
    }

    public PifResult fetchEvolutionPif() {
        try {
            String response = fetchUrl(EVOLUTION_PIF_URL);
            JSONObject json = new JSONObject(response);
            String model = json.optString("MODEL", "Evolution profile");
            return new PifResult.Success(model, json);
        } catch (Exception e) {
            Log.e(TAG, "Failed fetching Evolution PIF", e);
            return new PifResult.Error("Failed to fetch from Evolution: " + e.getMessage(), e);
        }
    }

    private List<Integer> extractVersionNumbers(String html) {
        List<Integer> versions = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "https://developer\\.android\\.com/about/versions/(\\d+)")
                .matcher(html);
        while (matcher.find()) {
            int version = Integer.parseInt(matcher.group(1));
            if (!versions.contains(version)) {
                versions.add(version);
            }
        }
        versions.sort((a, b) -> b - a);
        return versions;
    }

    private List<String> extractQprPaths(String html, int version) {
        List<String[]> entries = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "href=\"(/about/versions/" + version + "/qpr(\\d+)/download-ota)\"")
                .matcher(html);
        while (matcher.find()) {
            entries.add(new String[] {matcher.group(1), matcher.group(2)});
        }

        entries.sort((a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        List<String> paths = new ArrayList<>();
        for (String[] entry : entries) {
            paths.add(entry[0]);
        }
        return paths;
    }

    private List<String[]> extractOtaUrls(String html) {
        List<String[]> result = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "href=\"(https://dl\\.google\\.com/[^\"]*ota/([^/\"]+_beta)[^\"]*?)\"")
                .matcher(html);
        while (matcher.find()) {
            result.add(new String[] {matcher.group(1), matcher.group(2)});
        }
        return result;
    }

    private List<String[]> matchDevicesToOta(String html, List<String[]> otaUrls) {
        List<String[]> devices = new ArrayList<>();
        Pattern tableCellPattern = Pattern.compile("<td[^>]*>([^<]+)</td>");

        for (String[] entry : otaUrls) {
            String otaUrl = entry[0];
            String product = entry[1];
            int urlIndex = html.indexOf(otaUrl);
            if (urlIndex < 0) {
                continue;
            }

            String priorHtml = html.substring(0, urlIndex);
            Matcher matcher = tableCellPattern.matcher(priorHtml);
            String lastCell = null;
            while (matcher.find()) {
                lastCell = matcher.group(1).trim();
            }

            if (lastCell != null && !lastCell.isEmpty()) {
                devices.add(new String[] {lastCell, product, otaUrl});
            }
        }
        return devices;
    }

    private String fetchUrl(String urlString) throws Exception {
        URLConnection connection = new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36");
        try (InputStream inputStream = connection.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String fetchPartialUrl(String urlString, int maxBytes) throws Exception {
        URLConnection connection = new URL(urlString).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        byte[] buffer = new byte[512];
        StringBuilder builder = new StringBuilder();
        int totalRead = 0;

        try (InputStream inputStream = connection.getInputStream()) {
            while (totalRead < maxBytes) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    break;
                }
                builder.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
                totalRead += read;
            }
        }
        return builder.toString();
    }

    private String extractRegex(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
