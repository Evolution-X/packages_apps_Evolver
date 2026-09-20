#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FORCE=0

if [[ "${1:-}" == "--force" ]]; then
    FORCE=1
    shift
fi

TARGET="${1:-}"
if [[ -z "$TARGET" ]]; then
    echo "Usage: bash frameworks_base_patch/apply.sh [--force] /path/to/frameworks/base" >&2
    exit 2
fi

if ! git -C "$TARGET" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Error: '$TARGET' is not a Git worktree." >&2
    exit 2
fi

if [[ "$(git -C "$TARGET" rev-parse --show-toplevel)" != "$(cd "$TARGET" && pwd -P)" ]]; then
    echo "Error: '$TARGET' is not the root of the frameworks/base Git worktree." >&2
    exit 2
fi

declare -A EXPECTED=(
    ["packages/SystemUI/AndroidManifest.xml"]="2da0be7f4cbdd7cc192284e198c5f5b88d0fdc56"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/CutoutProgressSettings.java"]="98068237026dd7b0c4a449f5a165f9208eb9262d"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/DownloadStateTracker.java"]="be2e4af909329bb55616d9e9a377346c1344c4bc"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/CutoutProgressController.java"]="53a7a81cc8866b167c4ddba2797188a2287cf70b"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/MusicRingController.java"]="01a827761f7891d65d9e932b48a5fde280a44b23"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/MusicRingColorManager.java"]="8f4a35dc22a2ae4f876d17308f6d83e8e99775e7"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/MusicProgressTracker.java"]="e7d0f4889476f8be9ae8cbd44dffb3e98e5ba860"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/CutoutRingView.java"]="bf86929ae363d2895421d65ed8fd50a79e62c70e"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/OverlayAnimationHelper.java"]="f20b562ba6f4ee449cffd9af3af39529b0e47762"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/RingViewRenderer.java"]="26ef233598b088413ca4e25118fce1cea0e7cb93"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/CircleRingRenderer.java"]="1cda0ec70db67d068ca1848a27a86384d59cc7e8"
    ["packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/CapsuleRingRenderer.java"]="e100dde1ba48a124a378dbba34c5bd3ad577e368"
)

NEW_FILES=(
    "packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/CameraCutoutGeometryResolver.java"
    "packages/SystemUI/src/com/android/systemui/cutoutprogress/ring/PathRingRenderer.java"
)

echo "Checking frameworks/base cutout-progress baseline..."
for rel in "${!EXPECTED[@]}"; do
    staged="$SCRIPT_DIR/$rel"
    if [[ ! -f "$staged" ]]; then
        echo "Error: missing staged file: $rel" >&2
        exit 1
    fi
    if [[ ! -f "$TARGET/$rel" ]]; then
        echo "Error: missing target file: $rel" >&2
        exit 1
    fi

    current="$(git -C "$TARGET" hash-object "$rel")"
    desired="$(git hash-object "$staged")"

    # Safe re-runs are allowed when the target already equals this staged patch. Any third state
    # still aborts unless --force was explicitly requested.
    if [[ "$current" != "${EXPECTED[$rel]}" && "$current" != "$desired" && "$FORCE" -ne 1 ]]; then
        echo "Error: upstream/local file changed: $rel" >&2
        echo "  baseline: ${EXPECTED[$rel]}" >&2
        echo "  staged  : $desired" >&2
        echo "  current : $current" >&2
        echo "Rebase/review the staged implementation first, or use --force only after manual review." >&2
        exit 1
    fi
done

for rel in "${NEW_FILES[@]}"; do
    staged="$SCRIPT_DIR/$rel"
    if [[ ! -f "$staged" ]]; then
        echo "Error: missing staged file: $rel" >&2
        exit 1
    fi

    if [[ -e "$TARGET/$rel" ]]; then
        if [[ ! -f "$TARGET/$rel" ]]; then
            echo "Error: target path exists but is not a regular file: $rel" >&2
            exit 1
        fi
        current="$(git -C "$TARGET" hash-object "$rel")"
        desired="$(git hash-object "$staged")"
        if [[ "$current" != "$desired" && "$FORCE" -ne 1 ]]; then
            echo "Error: staged-new target already exists with different content: $rel" >&2
            echo "  staged  : $desired" >&2
            echo "  current : $current" >&2
            echo "Review/rebase the integration, or use --force only after manual review." >&2
            exit 1
        fi
    fi
done

echo "Applying staged Cutout Progress Pro files..."
for rel in "${!EXPECTED[@]}"; do
    install -D -m 0644 "$SCRIPT_DIR/$rel" "$TARGET/$rel"
done
for rel in "${NEW_FILES[@]}"; do
    install -D -m 0644 "$SCRIPT_DIR/$rel" "$TARGET/$rel"
done

echo "Checking resulting diff for whitespace errors..."
git -C "$TARGET" diff --check -- \
    packages/SystemUI/AndroidManifest.xml \
    packages/SystemUI/src/com/android/systemui/cutoutprogress

echo
echo "Applied successfully. Review with:"
echo "  git -C '$TARGET' diff -- packages/SystemUI/AndroidManifest.xml packages/SystemUI/src/com/android/systemui/cutoutprogress"
echo
git -C "$TARGET" diff --stat -- \
    packages/SystemUI/AndroidManifest.xml \
    packages/SystemUI/src/com/android/systemui/cutoutprogress
