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
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

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
    private static final String KEY_COPY_REPORT = "media_codec_copy_report";

    /** Still-image MIME types worth reporting, in the order users meet them. */
    private static final String[] IMAGE_MIMES = {
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "image/heic", "image/heif", "image/avif", "image/jxl",
    };

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ExecutorService mExecutor;
    private Preference mSummary;
    private String mReport = "";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.media_codec_settings);

        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) return;
        mSummary = screen.findPreference(KEY_SUMMARY);

        Preference copy = screen.findPreference(KEY_COPY_REPORT);
        if (copy != null) {
            copy.setOnPreferenceClickListener(preference -> {
                Context context = getActivity();
                if (context != null) {
                    copyText(context, mReport, R.string.media_codec_report_copied);
                }
                return true;
            });
        }

        mExecutor = Executors.newSingleThreadExecutor();
        mExecutor.execute(this::loadCodecs);
    }

    @Override
    public void onDestroy() {
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
