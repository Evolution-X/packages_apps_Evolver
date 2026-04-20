/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils;

import android.content.SharedPreferences;

/**
 * Encapsulates the repeated stale-check / ETag-revalidation pattern that
 * appears identically in About, HardwareInfoPreferenceController, and
 * GithubAvatarLoader.
 *
 * Each caller passes its own {@link SharedPreferences} instance and a
 * string key prefix so that caches for different data sources remain
 * independent while sharing the same logic.
 *
 * Usage example:
 * <pre>
 *   HttpCachePrefs cache = new HttpCachePrefs(prefs, "avatar_joeyhuab");
 *   if (!cache.isStale()) return; // still fresh, skip network
 *
 *   Map&lt;String,String&gt; headers = cache.buildHeaders("application/vnd.github+json");
 *   NetworkUtils.FetchResult r = NetworkUtils.fetchWithStatus(url, headers);
 *
 *   if (r.isNotModified()) { cache.touchLastCheck(); return; }
 *   if (!r.isOk())         { cache.touchLastCheck(); return; }
 *
 *   // … process r.bytes …
 *   cache.write(r.etag, r.lastModified);
 * </pre>
 */
public final class HttpCachePrefs {

    /** Default max age: 24 hours. Matches the constant used in all three original files. */
    public static final long DEFAULT_MAX_AGE_MS = 24L * 60L * 60L * 1000L;

    private static final String SUFFIX_LAST_CHECK = "_last_check";
    private static final String SUFFIX_ETAG        = "_etag";
    private static final String SUFFIX_LAST_MOD    = "_last_mod";

    private final SharedPreferences mPrefs;
    private final String            mPrefix;
    private final long              mMaxAgeMs;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param prefs    the SharedPreferences store to read/write
     * @param prefix   a unique key prefix for this cache entry (e.g. "avatar_joeyhuab")
     * @param maxAgeMs how long (ms) a cached entry is considered fresh
     */
    public HttpCachePrefs(SharedPreferences prefs, String prefix, long maxAgeMs) {
        mPrefs    = prefs;
        mPrefix   = prefix;
        mMaxAgeMs = maxAgeMs;
    }

    /** Convenience constructor using {@link #DEFAULT_MAX_AGE_MS}. */
    public HttpCachePrefs(SharedPreferences prefs, String prefix) {
        this(prefs, prefix, DEFAULT_MAX_AGE_MS);
    }

    // -------------------------------------------------------------------------
    // Stale check
    // -------------------------------------------------------------------------

    /**
     * Returns true if the entry should be revalidated against the network
     * (i.e. the last successful check is older than {@code maxAgeMs}).
     */
    public boolean isStale() {
        long lastCheck = mPrefs.getLong(mPrefix + SUFFIX_LAST_CHECK, 0L);
        return (System.currentTimeMillis() - lastCheck) >= mMaxAgeMs;
    }

    /** Returns the timestamp (ms) of the last check, or 0 if never checked. */
    public long getLastCheck() {
        return mPrefs.getLong(mPrefix + SUFFIX_LAST_CHECK, 0L);
    }

    // -------------------------------------------------------------------------
    // Header helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a headers map for a conditional HTTP request, pre-populated with
     * the stored ETag / Last-Modified values when present.
     *
     * @param acceptHeader value for the Accept header, or null to omit
     */
    public java.util.LinkedHashMap<String, String> buildHeaders(String acceptHeader) {
        return NetworkUtils.buildConditionalHeaders(
                acceptHeader,
                mPrefs.getString(mPrefix + SUFFIX_ETAG,     ""),
                mPrefs.getString(mPrefix + SUFFIX_LAST_MOD, "")
        );
    }

    // -------------------------------------------------------------------------
    // Write-back helpers
    // -------------------------------------------------------------------------

    /**
     * Updates the stored ETag, Last-Modified, and last-check timestamp in one
     * atomic commit. Call this after a successful 200 response.
     *
     * @param etag        new ETag from the response headers (may be empty)
     * @param lastModified new Last-Modified from the response headers (may be empty)
     */
    public void write(String etag, String lastModified) {
        mPrefs.edit()
                .putString(mPrefix + SUFFIX_ETAG,     UrlUtils.trimToEmpty(etag))
                .putString(mPrefix + SUFFIX_LAST_MOD, UrlUtils.trimToEmpty(lastModified))
                .putLong(mPrefix   + SUFFIX_LAST_CHECK, System.currentTimeMillis())
                .apply();
    }

    /**
     * Updates only the last-check timestamp. Call this after a 304 Not Modified
     * or a non-200 error to prevent hammering the server.
     */
    public void touchLastCheck() {
        mPrefs.edit()
                .putLong(mPrefix + SUFFIX_LAST_CHECK, System.currentTimeMillis())
                .apply();
    }

    /**
     * Removes all keys for this entry so the next call to {@link #isStale()}
     * returns true and no conditional headers are sent.
     * Equivalent to the forceRefresh logic in About.forceRefreshMaintainers().
     */
    public void invalidate() {
        mPrefs.edit()
                .remove(mPrefix + SUFFIX_ETAG)
                .remove(mPrefix + SUFFIX_LAST_MOD)
                .putLong(mPrefix + SUFFIX_LAST_CHECK, 0L)
                .apply();
    }
}
