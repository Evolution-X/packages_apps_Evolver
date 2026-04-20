/*
 * SPDX-FileCopyrightText: Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils;

import android.net.Uri;
import android.text.TextUtils;

/**
 * Shared URL and string helpers used across About, HardwareInfoPreferenceController,
 * and GithubAvatarLoader.
 *
 * All methods are static and stateless.
 */
public final class UrlUtils {

    private UrlUtils() {}

    // -------------------------------------------------------------------------
    // String helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code s} trimmed, or an empty string if {@code s} is null.
     * Replaces both {@code trimToEmpty()} in About and {@code trim()} in
     * HardwareInfoPreferenceController / GithubAvatarLoader.
     */
    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    // -------------------------------------------------------------------------
    // URL validation
    // -------------------------------------------------------------------------

    /**
     * Returns true if {@code url} is a non-empty string with an http or https scheme.
     * Used in About (isValidHttpUrl) and as the core check inside
     * HardwareInfoPreferenceController (sanitizeUrl).
     */
    public static boolean isValidHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * Sanitizes a donate / PayPal URL:
     * <ul>
     *   <li>Returns the URL as-is if it already has an http/https scheme.</li>
     *   <li>Treats a bare token with no spaces, slashes, or colons as a PayPal.me
     *       username and returns {@code https://paypal.me/<token>}.</li>
     *   <li>Returns {@code null} for anything else (empty, malformed, etc.).</li>
     * </ul>
     *
     * Replaces the inline sanitizeUrl() in HardwareInfoPreferenceController
     * and is also consistent with the paypal field handling in About.
     */
    public static String sanitizeUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        String trimmed = url.trim();
        if (isValidHttpUrl(trimmed)) return trimmed;
        // Bare PayPal.me username — no spaces, slashes, or colons
        if (!trimmed.contains(" ") && !trimmed.contains("/") && !trimmed.contains(":")) {
            return "https://paypal.me/" + trimmed;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // GitHub URL builder
    // -------------------------------------------------------------------------

    /**
     * Returns {@code "https://github.com/<username>"} for a non-empty username,
     * or {@code null} if the username is blank.
     *
     * Replaces buildGithubUrlOrNull() in About and the inline string concatenation
     * in HardwareInfoPreferenceController.
     */
    public static String buildGithubUrl(String username) {
        String clean = trimToEmpty(username);
        return clean.isEmpty() ? null : "https://github.com/" + clean;
    }
}
