/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Shared network I/O helpers used across About, HardwareInfoPreferenceController,
 * and GithubAvatarLoader.
 *
 * All methods are static and stateless. Callers are responsible for threading.
 */
public final class NetworkUtils {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS    = 8000;

    private NetworkUtils() {}

    // -------------------------------------------------------------------------
    // Core fetch
    // -------------------------------------------------------------------------

    /**
     * Opens a GET connection to {@code urlString}, sets optional request headers,
     * and returns the raw response bytes.
     *
     * Returns {@code null} if the server returns any non-200 status.
     * Disconnects the connection in all cases.
     *
     * @param urlString  fully-qualified http/https URL
     * @param headers    optional extra request headers (e.g. If-None-Match); may be null
     * @return response body bytes, or {@code null} on non-200 or error
     * @throws IOException on network or I/O failure
     */
    public static byte[] fetchBytes(String urlString, Map<String, String> headers)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlString, headers);
            conn.connect();
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return readBytes(in);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Same as {@link #fetchBytes} but also exposes the response code so callers
     * can distinguish 304 Not Modified from 404 / errors.
     *
     * Returns a {@link FetchResult} whose {@code bytes} field is null when the
     * status is not 200.
     */
    public static FetchResult fetchWithStatus(String urlString, Map<String, String> headers)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(urlString, headers);
            conn.connect();
            int code = conn.getResponseCode();
            String etag    = trimToEmpty(conn.getHeaderField("ETag"));
            String lastMod = trimToEmpty(conn.getHeaderField("Last-Modified"));

            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new FetchResult(code, null, etag, lastMod);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return new FetchResult(code, null, etag, lastMod);
            }
            byte[] bytes;
            try (InputStream in = conn.getInputStream()) {
                bytes = readBytes(in);
            }
            return new FetchResult(code, bytes, etag, lastMod);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Convenience wrapper: fetches {@code urlString} and decodes the body as a
     * UTF-8 string. Returns an empty string on non-200 or error.
     */
    public static String fetchString(String urlString, Map<String, String> headers)
            throws IOException {
        byte[] bytes = fetchBytes(urlString, headers);
        if (bytes == null || bytes.length == 0) return "";
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Fetches an image from {@code imageUrl} and decodes it as a {@link Bitmap}.
     * Follows a single redirect if the server returns 301/302/307/308.
     * Returns {@code null} on failure or non-200.
     */
    public static Bitmap fetchBitmap(String imageUrl) {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(imageUrl, null);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) EvolutionX/HardwareInfo");
            conn.connect();
            int status = conn.getResponseCode();

            // Manual redirect fallback for cases setInstanceFollowRedirects misses
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == 307 || status == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) return null;
                conn = openConnection(location, null);
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) EvolutionX/HardwareInfo");
                conn.connect();
                status = conn.getResponseCode();
            }

            if (status != HttpURLConnection.HTTP_OK) return null;

            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = readBytes(in);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Conditional-request header builder
    // -------------------------------------------------------------------------

    /**
     * Builds a headers map pre-populated with {@code If-None-Match} and
     * {@code If-Modified-Since} when the supplied cached values are non-empty.
     * Pass the result directly to {@link #fetchWithStatus} or {@link #fetchString}.
     *
     * @param acceptHeader  value for the Accept header, or null to omit
     * @param cachedEtag    previously stored ETag, or empty/null
     * @param cachedLastMod previously stored Last-Modified, or empty/null
     */
    public static java.util.LinkedHashMap<String, String> buildConditionalHeaders(
            String acceptHeader, String cachedEtag, String cachedLastMod) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        if (acceptHeader != null && !acceptHeader.isEmpty()) {
            headers.put("Accept", acceptHeader);
        }
        if (!trimToEmpty(cachedEtag).isEmpty()) {
            headers.put("If-None-Match", cachedEtag.trim());
        }
        if (!trimToEmpty(cachedLastMod).isEmpty()) {
            headers.put("If-Modified-Since", cachedLastMod.trim());
        }
        return headers;
    }

    // -------------------------------------------------------------------------
    // Low-level I/O
    // -------------------------------------------------------------------------

    /**
     * Reads all bytes from {@code in} into a byte array.
     * Does NOT close the stream — callers should use try-with-resources.
     */
    public static byte[] readBytes(InputStream in) throws IOException {
        try (BufferedInputStream bis = new BufferedInputStream(in);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static HttpURLConnection openConnection(String urlString,
            Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        return conn;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /** Carries the HTTP status code, response body, and caching headers together. */
    public static final class FetchResult {
        public final int    statusCode;
        public final byte[] bytes;      // null when status != 200
        public final String etag;
        public final String lastModified;

        public FetchResult(int statusCode, byte[] bytes, String etag, String lastModified) {
            this.statusCode   = statusCode;
            this.bytes        = bytes;
            this.etag         = etag;
            this.lastModified = lastModified;
        }

        public boolean isNotModified() {
            return statusCode == HttpURLConnection.HTTP_NOT_MODIFIED;
        }

        public boolean isOk() {
            return statusCode == HttpURLConnection.HTTP_OK;
        }

        public String bodyAsString() {
            if (bytes == null || bytes.length == 0) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
