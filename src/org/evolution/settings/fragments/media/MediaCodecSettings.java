/*
 * Copyright (C) 2026 The Evolution X Project
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
package org.evolution.settings.fragments.media;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.ImageDecoder;
import android.media.AudioManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.Spatializer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.view.Display;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.nano.MetricsProto;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settingslib.search.SearchIndexable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only report of what this device can actually decode and encode.
 *
 * <p>Everything is queried from the platform at runtime - {@link MediaCodecList} for media
 * codecs and {@link ImageDecoder#isMimeTypeSupported(String)} for still images - so the screen
 * stays correct across devices, vendor blobs and OTA updates without shipping a hardcoded table.
 */
@SearchIndexable
public class MediaCodecSettings extends SettingsPreferenceFragment {

    private static final String KEY_SUMMARY = "media_codec_summary";
    private static final String KEY_VIDEO_DECODERS = "media_codec_video_decoders";
    private static final String KEY_VIDEO_ENCODERS = "media_codec_video_encoders";
    private static final String KEY_AUDIO_DECODERS = "media_codec_audio_decoders";
    private static final String KEY_AUDIO_ENCODERS = "media_codec_audio_encoders";
    private static final String KEY_IMAGE_FORMATS = "media_codec_image_formats";
    private static final String KEY_DISPLAY_AUDIO = "media_display_audio";
    private static final String KEY_TRANSCODE_ENABLED = "media_transcode_enabled";
    private static final String KEY_TRANSCODE_ASSUME = "media_transcode_assume";
    private static final String KEY_TRANSCODE_OVERRIDE = "media_transcode_override";
    private static final String KEY_TRANSCODE_NOTIFICATION = "media_transcode_notification";
    private static final String KEY_TRANSCODE_CACHE = "media_transcode_cache";
    private static final String KEY_COPY_REPORT = "media_codec_copy_report";

    /**
     * The same system properties Settings > Developer options > Media transcoding writes.
     * They are read by MediaProvider and the transcoding service, so the switches below
     * change real behaviour rather than storing a duplicate preference.
     */
    private static final String PROP_TRANSCODE_ENABLED = "persist.sys.fuse.transcode_enabled";
    private static final String PROP_TRANSCODE_DEFAULT = "persist.sys.fuse.transcode_default";
    private static final String PROP_TRANSCODE_NOTIFICATION =
            "persist.sys.fuse.transcode_notification";
    private static final String PROP_TRANSCODE_USER_CONTROL =
            "persist.sys.fuse.transcode_user_control";
    private static final String PROP_DISABLE_TRANSCODE_CACHE =
            "persist.sys.fuse.disable_transcode_cache";

    /** Still-image MIME types worth reporting, in the order users meet them. */
    private static final String[] IMAGE_MIMES = {
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "image/heic", "image/heif", "image/avif", "image/jxl",
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ExecutorService mExecutor;
    private Preference mSummary;
    private Preference mCopy;
    private String mReport = "";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.media_codec_settings);

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) return;
        mSummary = screen.findPreference(KEY_SUMMARY);

        mCopy = screen.findPreference(KEY_COPY_REPORT);
        if (mCopy != null) {
            mCopy.setEnabled(false);
            mCopy.setOnPreferenceClickListener(preference -> {
                Context context = getActivity();
                if (context != null) {
                    copyText(context, mReport, R.string.media_codec_report_copied);
                }
                return true;
            });
        }

        bindTranscoding();
        bindDisplayAndAudio();

        mExecutor = Executors.newSingleThreadExecutor();
        mExecutor.execute(this::loadCodecs);
    }

    @Override
    public void onResume() {
        super.onResume();
        bindTranscoding();
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        super.onDestroy();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.EVOLVER;
    }

    // ------------------------------------------------------------------ loading

    private void loadCodecs() {
        final List<CodecEntry> entries = new ArrayList<>();
        final Set<String> decoders = new HashSet<>();
        final Set<String> encoders = new HashSet<>();
        final Set<String> hardware = new HashSet<>();
        final StringBuilder report = new StringBuilder();

        report.append("Evolution X media codec report\n")
                .append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n")
                .append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT)
                .append('\n');

        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) {
                    encoders.add(info.getName());
                } else {
                    decoders.add(info.getName());
                }
                if (info.isHardwareAccelerated()) {
                    hardware.add(info.getName());
                }
                for (String mime : info.getSupportedTypes()) {
                    CodecEntry entry = buildEntry(info, mime);
                    entries.add(entry);
                    report.append(entry.encoder ? "E " : "D ")
                            .append(entry.mime).append(" | ").append(entry.codecName)
                            .append(" | ").append(accelerationKey(entry)).append('\n');
                }
            }
        } catch (Exception e) {
            report.append("Failed to read MediaCodecList: ").append(e).append('\n');
        }

        Collections.sort(entries, (a, b) -> {
            int byTitle = titleOf(a.mime).compareToIgnoreCase(titleOf(b.mime));
            return byTitle != 0 ? byTitle : a.codecName.compareToIgnoreCase(b.codecName);
        });

        final int decoderCount = decoders.size();
        final int encoderCount = encoders.size();
        final int hardwareCount = hardware.size();
        final String reportText = report.toString();

        mHandler.post(() -> {
            if (getActivity() == null) return;
            mReport = reportText;
            if (mCopy != null) mCopy.setEnabled(true);
            bindSummary(decoderCount, encoderCount, hardwareCount);
            bindCodecs(entries);
            bindImageFormats();
        });
    }

    private CodecEntry buildEntry(MediaCodecInfo info, String mime) {
        CodecEntry entry = new CodecEntry();
        entry.mime = mime;
        entry.codecName = info.getName();
        entry.encoder = info.isEncoder();
        entry.hardware = info.isHardwareAccelerated();
        entry.software = info.isSoftwareOnly();
        entry.vendor = info.isVendor();
        entry.video = mime.startsWith("video/");
        entry.audio = mime.startsWith("audio/");
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            entry.profiles = caps.profileLevels == null ? 0 : caps.profileLevels.length;
            if (entry.video) {
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video != null) {
                    entry.maxWidth = video.getSupportedWidths().getUpper();
                    entry.maxHeight = video.getSupportedHeights().getUpper();
                    try {
                        entry.maxFps = (int) Math.round(video.getSupportedFrameRates().getUpper());
                    } catch (Exception ignored) {
                        entry.maxFps = 0;
                    }
                    entry.bitrateMin = video.getBitrateRange().getLower();
                    entry.bitrateMax = video.getBitrateRange().getUpper();
                }
                entry.hdr10 = supportsHdr10(caps, mime);
            } else if (entry.audio) {
                MediaCodecInfo.AudioCapabilities audio = caps.getAudioCapabilities();
                if (audio != null) {
                    entry.channels = audio.getMaxInputChannelCount();
                    entry.bitrateMin = audio.getBitrateRange().getLower();
                    entry.bitrateMax = audio.getBitrateRange().getUpper();
                    entry.sampleRate = audio.getSupportedSampleRates() == null
                            ? 0 : maxOf(audio.getSupportedSampleRates());
                }
            }
        } catch (Exception ignored) {
            // A vendor codec may advertise a type it cannot describe; keep the row.
        }
        return entry;
    }

    private static int maxOf(int[] values) {
        int max = 0;
        for (int value : values) max = Math.max(max, value);
        return max;
    }

    /**
     * A decoder is treated as HDR10-capable when it accepts a 1080p BT.2020 / PQ format.
     * This is the same probe the framework uses, and a codec that cannot describe itself
     * simply answers no.
     */
    private static boolean supportsHdr10(MediaCodecInfo.CodecCapabilities caps, String mime) {
        if (!"video/hevc".equals(mime) && !"video/av01".equals(mime)
                && !"video/vvc".equals(mime) && !"video/x-vnd.on2.vp9".equals(mime)) {
            return false;
        }
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            return caps.isFormatSupported(format);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ binding

    private void bindSummary(int decoders, int encoders, int hardware) {
        if (mSummary != null) {
            mSummary.setSummary(getString(R.string.media_codec_summary_value,
                    decoders, encoders, hardware));
        }
    }

    private void bindCodecs(List<CodecEntry> entries) {
        Context context = getActivity();
        if (context == null) return;

        List<CodecEntry> videoDecoders = new ArrayList<>();
        List<CodecEntry> videoEncoders = new ArrayList<>();
        List<CodecEntry> audioDecoders = new ArrayList<>();
        List<CodecEntry> audioEncoders = new ArrayList<>();
        for (CodecEntry entry : entries) {
            if (entry.video) {
                (entry.encoder ? videoEncoders : videoDecoders).add(entry);
            } else if (entry.audio) {
                (entry.encoder ? audioEncoders : audioDecoders).add(entry);
            }
        }
        fillCategory(context, KEY_VIDEO_DECODERS, videoDecoders);
        fillCategory(context, KEY_VIDEO_ENCODERS, videoEncoders);
        fillCategory(context, KEY_AUDIO_DECODERS, audioDecoders);
        fillCategory(context, KEY_AUDIO_ENCODERS, audioEncoders);
    }

    private void fillCategory(Context context, String key, List<CodecEntry> entries) {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) return;
        PreferenceCategory category = screen.findPreference(key);
        if (category == null) return;
        category.removeAll();

        if (entries.isEmpty()) {
            Preference empty = new Preference(context);
            empty.setTitle(R.string.media_codec_empty);
            empty.setSelectable(false);
            category.addPreference(empty);
            return;
        }

        for (CodecEntry entry : entries) {
            Preference preference = new Preference(context);
            preference.setTitle(titleOf(entry.mime));
            preference.setSummary(summaryOf(entry));
            preference.setOnPreferenceClickListener(pref -> {
                showDetails(entry);
                return true;
            });
            category.addPreference(preference);
        }
    }

    private void bindImageFormats() {
        Context context = getActivity();
        PreferenceScreen screen = getPreferenceScreen();
        if (context == null || screen == null) return;
        PreferenceCategory category = screen.findPreference(KEY_IMAGE_FORMATS);
        if (category == null) return;
        category.removeAll();

        for (String mime : IMAGE_MIMES) {
            boolean supported;
            try {
                supported = ImageDecoder.isMimeTypeSupported(mime);
            } catch (Exception e) {
                supported = false;
            }
            Preference preference = new Preference(context);
            preference.setTitle(imageLabel(mime));
            preference.setSummary(supported
                    ? R.string.media_codec_image_supported
                    : R.string.media_codec_image_unsupported);
            preference.setSelectable(false);
            category.addPreference(preference);
        }
    }

    private String summaryOf(CodecEntry entry) {
        StringBuilder builder = new StringBuilder(entry.codecName);
        builder.append(" \u00b7 ").append(getString(entry.hardware
                ? R.string.media_codec_badge_hardware
                : entry.software ? R.string.media_codec_badge_software
                : R.string.media_codec_badge_vendor));
        if (entry.hdr10) {
            builder.append(" \u00b7 ").append(getString(R.string.media_codec_badge_hdr10));
        }
        return builder.toString();
    }

    /**
     * The first switch is required by MediaProvider: while user control is off it reads the
     * platform defaults and ignores the two switches that follow, so they are disabled here
     * until it is on. Everything is re-read on resume, because Developer options can change
     * the same properties while this screen is in the back stack.
     */
    private void bindTranscoding() {
        bindPropertySwitch(KEY_TRANSCODE_OVERRIDE, PROP_TRANSCODE_USER_CONTROL, false, false);
        bindPropertySwitch(KEY_TRANSCODE_ENABLED, PROP_TRANSCODE_ENABLED, true, false);
        bindPropertySwitch(KEY_TRANSCODE_ASSUME, PROP_TRANSCODE_DEFAULT, false, true);
        bindPropertySwitch(KEY_TRANSCODE_NOTIFICATION, PROP_TRANSCODE_NOTIFICATION, false, false);
        bindPropertySwitch(KEY_TRANSCODE_CACHE, PROP_DISABLE_TRANSCODE_CACHE, false, false);
        syncTranscodeAvailability();
    }

    private void syncTranscodeAvailability() {
        Preference parent = findPreference(KEY_TRANSCODE_OVERRIDE);
        boolean available = parent instanceof SwitchPreferenceCompat
                && ((SwitchPreferenceCompat) parent).isChecked();
        Preference enabled = findPreference(KEY_TRANSCODE_ENABLED);
        Preference assume = findPreference(KEY_TRANSCODE_ASSUME);
        if (enabled != null) enabled.setEnabled(available);
        if (assume != null) assume.setEnabled(available);
    }

    private void bindPropertySwitch(String key, String property, boolean defaultOn,
            boolean inverted) {
        Preference preference = findPreference(key);
        if (!(preference instanceof SwitchPreferenceCompat)) return;
        SwitchPreferenceCompat toggle = (SwitchPreferenceCompat) preference;
        boolean stored = SystemProperties.getBoolean(property, defaultOn);
        toggle.setChecked(inverted ? !stored : stored);
        toggle.setOnPreferenceChangeListener((pref, value) -> {
            boolean checked = (Boolean) value;
            try {
                SystemProperties.set(property, String.valueOf(inverted ? !checked : checked));
                // The listener runs before the switch stores its new state.
                toggle.setChecked(checked);
                syncTranscodeAvailability();
                return true;
            } catch (RuntimeException e) {
                Context context = getActivity();
                if (context != null) {
                    Toast.makeText(context, R.string.media_codec_write_failed,
                            Toast.LENGTH_SHORT).show();
                }
                return false;
            }
        });
    }

    private void bindDisplayAndAudio() {
        Context context = getActivity();
        PreferenceScreen screen = getPreferenceScreen();
        if (context == null || screen == null) return;
        PreferenceCategory category = screen.findPreference(KEY_DISPLAY_AUDIO);
        if (category == null) return;
        category.removeAll();

        Display display = context.getDisplay();
        if (display != null && display.getHdrCapabilities() != null) {
            Display.HdrCapabilities hdr = display.getHdrCapabilities();
            List<String> types = new ArrayList<>();
            for (int type : hdr.getSupportedHdrTypes()) {
                String label = hdrTypeLabel(type);
                if (label != null) types.add(label);
            }
            addRow(context, category, getString(R.string.media_hdr_types),
                    types.isEmpty() ? getString(R.string.media_hdr_none)
                            : TextUtils.join(" \u00b7 ", types));
            if (hdr.getDesiredMaxLuminance() > 0) {
                addRow(context, category, getString(R.string.media_hdr_luminance),
                        getString(R.string.media_hdr_luminance_value,
                                hdr.getDesiredMaxLuminance(),
                                hdr.getDesiredMaxAverageLuminance()));
            }
        }

        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null) {
            addRow(context, category, getString(R.string.media_audio_output),
                    getString(R.string.media_audio_output_value,
                            audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE),
                            audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)));
            Spatializer spatializer = audio.getSpatializer();
            addRow(context, category, getString(R.string.media_spatial_audio),
                    spatializer != null && spatializer.isAvailable()
                            ? getString(R.string.media_spatial_available)
                            : getString(R.string.media_spatial_unavailable));
        }
    }

    private static String hdrTypeLabel(int type) {
        switch (type) {
            case Display.HdrCapabilities.HDR_TYPE_HDR10: return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS: return "HDR10+";
            case Display.HdrCapabilities.HDR_TYPE_HLG: return "HLG";
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: return "Dolby Vision";
            default: return null;
        }
    }

    private void addRow(Context context, PreferenceCategory category, String title,
            String summary) {
        Preference row = new Preference(context);
        row.setTitle(title);
        row.setSummary(summary);
        row.setSelectable(false);
        category.addPreference(row);
    }

    // ------------------------------------------------------------------ dialogs

    private void showDetails(CodecEntry entry) {
        Context context = getActivity();
        if (context == null) return;

        StringBuilder body = new StringBuilder();
        appendLine(body, R.string.media_codec_label_mime, entry.mime);
        appendLine(body, R.string.media_codec_label_codec, entry.codecName);
        appendLine(body, R.string.media_codec_label_type, getString(entry.encoder
                ? R.string.media_codec_details_type_encoder
                : R.string.media_codec_details_type_decoder));
        appendLine(body, R.string.media_codec_label_acceleration, accelerationOf(entry));
        if (entry.video) {
            if (entry.maxWidth > 0 && entry.maxHeight > 0) {
                appendLine(body, getString(R.string.media_codec_label_size),
                        getString(R.string.media_codec_details_size,
                                entry.maxWidth, entry.maxHeight));
            }
            if (entry.maxFps > 0) {
                appendLine(body, getString(R.string.media_codec_label_fps),
                        getString(R.string.media_codec_details_fps, entry.maxFps));
            }
            appendLine(body, getString(R.string.media_codec_label_hdr10),
                    getString(entry.hdr10 ? R.string.media_codec_value_yes
                            : R.string.media_codec_value_no));
        } else if (entry.audio) {
            if (entry.channels > 0) {
                appendLine(body, getString(R.string.media_codec_label_channels),
                        String.valueOf(entry.channels));
            }
            if (entry.sampleRate > 0) {
                appendLine(body, getString(R.string.media_codec_label_sample_rate),
                        String.format(Locale.US, "%d Hz", entry.sampleRate));
            }
        }
        if (entry.bitrateMax > 0) {
            appendLine(body, getString(R.string.media_codec_label_bitrate),
                    getString(R.string.media_codec_details_bitrate,
                            entry.bitrateMin, entry.bitrateMax));
        }
        if (entry.profiles > 0) {
            appendLine(body, getString(R.string.media_codec_label_profiles),
                    String.valueOf(entry.profiles));
        }

        final String details = body.toString();
        new MaterialAlertDialogBuilder(context)
                .setTitle(titleOf(entry.mime))
                .setMessage(details)
                .setNeutralButton(R.string.media_codec_copy_details,
                        (dialog, which) -> copyText(context, details,
                                R.string.media_codec_report_copied))
                .setPositiveButton(R.string.media_codec_close, null)
                .show();
    }

    private void appendLine(StringBuilder builder, int labelRes, String value) {
        appendLine(builder, getString(labelRes), value);
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        builder.append(label).append(": ").append(value).append('\n');
    }

    private String accelerationOf(CodecEntry entry) {
        if (entry.hardware) return getString(R.string.media_codec_badge_hardware);
        if (entry.software) return getString(R.string.media_codec_badge_software);
        return getString(R.string.media_codec_badge_vendor);
    }

    private static String accelerationKey(CodecEntry entry) {
        if (entry.hardware) return "hw";
        if (entry.software) return "sw";
        return "vendor";
    }

    private void copyText(Context context, String text, int toastRes) {
        ClipboardManager manager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("media codecs", text));
        }
        Toast.makeText(context, toastRes, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ labels

    private static String titleOf(String mime) {
        switch (mime) {
            case "video/avc": return "H.264 / AVC";
            case "video/hevc": return "H.265 / HEVC";
            case "video/av01": return "AV1";
            case "video/vvc": return "H.266 / VVC";
            case "video/apv": return "APV";
            case "video/x-vnd.on2.vp9": return "VP9";
            case "video/x-vnd.on2.vp8": return "VP8";
            case "video/mp4v-es": return "MPEG-4";
            case "video/mpeg2": return "MPEG-2";
            case "video/3gpp": return "H.263";
            case "audio/mp4a-latm": return "AAC";
            case "audio/opus": return "Opus";
            case "audio/flac": return "FLAC";
            case "audio/mpeg": return "MP3";
            case "audio/vorbis": return "Vorbis";
            case "audio/iamf": return "IAMF";
            case "audio/mp4a.40.42": return "xHE-AAC";
            default: return mime;
        }
    }

    private static String imageLabel(String mime) {
        switch (mime) {
            case "image/jpeg": return "JPEG";
            case "image/png": return "PNG";
            case "image/webp": return "WebP";
            case "image/gif": return "GIF";
            case "image/heic": return "HEIC";
            case "image/heif": return "HEIF";
            case "image/avif": return "AVIF";
            case "image/jxl": return "JPEG XL";
            default: return mime;
        }
    }

    private static final class CodecEntry {
        String mime;
        String codecName;
        boolean encoder;
        boolean hardware;
        boolean software;
        boolean vendor;
        boolean video;
        boolean audio;
        boolean hdr10;
        int maxWidth;
        int maxHeight;
        int maxFps;
        int bitrateMin;
        int bitrateMax;
        int profiles;
        int channels;
        int sampleRate;
    }

    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider(R.xml.media_codec_settings);
}
