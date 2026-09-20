# Cutout Progress Pro — SystemUI integration

This directory contains the SystemUI half of the Cutout Progress Pro work developed together with
the Evolver settings UI in this branch.

## Baseline

The staged files target `Evolution-X/frameworks_base` branch `cnb` at:

```
d9b63b2f4753a38b3f4e63378f5267e8e7ed9f29
```

The guarded installer verifies the original Git blob SHA of every replaced upstream file before
copying anything. If upstream changes, the install aborts instead of silently overwriting newer
SystemUI code.

## Apply

From the root of this Evolver checkout:

```bash
bash frameworks_base_patch/apply.sh /path/to/android/frameworks/base
```

Do not use `--force` unless every upstream mismatch has been manually reviewed.

## Multi-ring architecture

Download, music and countdown timer sources can independently use:

- **Primary ring**
- **Independent outer ring**
- **Disabled**

Independent rings are automatically assigned stable outward lanes. The source-priority preference
supports Downloads, Music and Timer and is also used to keep independently stacked rings in a
predictable order. Charging/battery can use the primary ring when it is free.

## Aurora effect

Aurora is a separate perimeter effect, so it can appear even when no progress source currently owns
a ring. It is rendered outside the outermost active independent ring, or directly around the
camera geometry when no other ring is active.

Supported Aurora triggers are independently configurable:

- phone calls;
- music playback;
- active audio recording;
- newly arriving notifications.

Aurora supports:

- animated full-spectrum color;
- event/source color;
- fixed custom color;
- notification color or the configured Aurora color policy;
- configurable spread/size, opacity, animation speed and notification duration.

The effect uses a cached sweep gradient plus multiple anti-aliased falloff layers instead of
full-screen bitmap blur. While music-triggered Aurora is active, the optional outer music waveform
is suspended automatically and resumes when Aurora is no longer active.

Call state is driven from the platform phone-state broadcast and resolved through
`TelecomManager.isInCall()`, so the effect follows overall device call state rather than one
default SIM subscription. The staged SystemUI manifest adds `READ_PHONE_STATE`; the manifest itself
is included in the installer's upstream hash guard.

Recording state uses `AudioManager.AudioRecordingCallback`. No extra audio waveform capture is
performed for Aurora.

## Countdown timer ring

Timer notifications are detected from the current user's notification pipeline. The implementation
supports both:

1. standard Android countdown notifications using the notification chronometer extras; and
2. Clock/DeskClock-style custom `RemoteViews` containing a countdown `Chronometer`.

The timer ring:

- shrinks with remaining time;
- can use the primary ring or an automatically stacked independent ring;
- supports accent, rainbow or custom ring color;
- has configurable opacity, stroke width and direction;
- has an optional animated burning-fuse endpoint;
- exposes independent flame color and flame size;
- freezes visually when the timer is paused and resumes from the same progress;
- preserves progress when time is added or removed while a timer is running;
- selects the earliest-expiring timer when multiple supported timers are active.

If SystemUI is started or the feature is enabled in the middle of an already-running third-party
timer, Android's notification API may expose only the remaining time and not the timer's original
duration. In that case the current remaining duration becomes the visual 100% baseline; the
countdown from that point forward is accurate.

## Camera geometry and rotation stability

- Automatic camera fit prefers SystemUI camera-protection geometry.
- It falls back to the current DisplayCutout path, then a compact bounds-derived camera shape.
- Camera-protection paths use Android physical-pixel scaling and logical-display rotation handling.
- Manual X/Y calibration rotates with the hardware axes at 90/180/270 degrees.
- Runtime density and display-size changes request fresh geometry.
- No synthetic camera hole is fabricated on devices without a reported physical cutout.
- Circle geometry uses the cutout's minor axis instead of the full safe-area rectangle.
- Calibrated manual defaults remain: pill mode, X 1.050, Y 0.600, X offset 0, Y offset 1.5dp.

## Existing progress and media hardening

- Download progress does not disappear merely because the percentage is unchanged for 10 seconds
  while the transfer notification is still active.
- Download tracking uses the unique StatusBarNotification key and 64-bit progress arithmetic.
- Indeterminate transitions do not fake completion.
- Stale transfer state is cleaned conservatively.
- Music artwork color extraction is race-safe and does not recycle app-owned Bitmaps.
- Music palette work is shut down when no longer needed.
- Music progress updates at approximately 30 Hz instead of the full display refresh rate.
- Music AOD preference is honored.
- Battery receiver work is registered only while needed.
- Settings observers ignore unrelated Secure settings changes.

## Staged files

The directory mirrors `frameworks/base`. The installer copies the modified SystemUI manifest,
Cutout Progress controller/settings/tracking/rendering files and the new adaptive geometry/path
renderer files.

## Validation performed

Static consistency checks cover:

- duplicate Evolver preference keys;
- missing string and array resources;
- Timer/Aurora settings-to-SystemUI key parity;
- renderer/controller API parity;
- Java/Kotlin brace balance;
- timer priority and independent-ring paths;
- music-wave/Aurora mutual exclusion;
- notification, call and recording event wiring;
- manifest permission staging and hash guarding;
- current `frameworks_base/cnb` blob SHAs for every replaced upstream file.

A full Android/SystemUI build and on-device hardware validation are still required before merging.
Recommended device tests include rotation/resolution changes, circular and pill cutouts, concurrent
download/music/timer rings, timer pause/resume and +/- time, incoming/active calls, recording start/
stop, notification colors, music Aurora/waveform handoff, AOD behavior and SystemUI restart.
