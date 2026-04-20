/*
 * Copyright (C) 2026 Evolution X
 * SPDX-License-Identifier: Apache-2.0
 */

package org.evolution.settings.utils;

/**
 * Immutable value object representing a single entry from the Evolution-X OTA JSON.
 *
 * Replaces the private {@code AggregatedMaintainer} inner class in About and the
 * private {@code OtaData} inner class in HardwareInfoPreferenceController, which
 * both modelled the same JSON fields under different names.
 */
public final class OtaEntry {

    /** Display name of the maintainer. Never null; may be empty. */
    public final String  maintainer;

    /** GitHub username (without the URL prefix). May be empty. */
    public final String  github;

    /**
     * Sanitized donate URL (http/https) or null.
     * Use {@link UrlUtils#sanitizeUrl} when reading raw OTA JSON values.
     */
    public final String  donateUrl;

    /** OEM / brand string as it appears in the OTA JSON, e.g. "Google". May be empty. */
    public final String  oem;

    /**
     * Device codename, e.g. "shiba". Populated when a single-device entry is parsed;
     * may be empty in the aggregated-maintainer context used by About (where one
     * maintainer can cover multiple devices).
     */
    public final String  device;

    /** Whether this entry is marked as actively maintained in the OTA JSON. */
    public final boolean currentlyMaintained;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public OtaEntry(String maintainer, String github, String donateUrl,
                    String oem, String device, boolean currentlyMaintained) {
        this.maintainer          = UrlUtils.trimToEmpty(maintainer);
        this.github              = UrlUtils.trimToEmpty(github);
        this.donateUrl           = donateUrl; // already sanitized by caller
        this.oem                 = UrlUtils.trimToEmpty(oem);
        this.device              = UrlUtils.trimToEmpty(device);
        this.currentlyMaintained = currentlyMaintained;
    }

    /** Convenience constructor without a device field (used in aggregated contexts). */
    public OtaEntry(String maintainer, String github, String donateUrl,
                    String oem, boolean currentlyMaintained) {
        this(maintainer, github, donateUrl, oem, "", currentlyMaintained);
    }

    // -------------------------------------------------------------------------
    // Derived helpers
    // -------------------------------------------------------------------------

    /** Returns the full GitHub profile URL or null if {@link #github} is empty. */
    public String githubUrl() {
        return UrlUtils.buildGithubUrl(github);
    }

    /** True when {@link #maintainer} is non-empty. */
    public boolean hasMaintainer() {
        return !maintainer.isEmpty();
    }

    /** True when {@link #donateUrl} is non-null and passes {@link UrlUtils#isValidHttpUrl}. */
    public boolean hasDonateUrl() {
        return UrlUtils.isValidHttpUrl(donateUrl);
    }

    /** True when {@link #github} is non-empty. */
    public boolean hasGithub() {
        return !github.isEmpty();
    }
}
