#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATCH_ROOT = ROOT / "frameworks_base_patch"
APPLY_SCRIPT = PATCH_ROOT / "apply.sh"
CUTOUT_XML = ROOT / "res/xml/cutout_progress_settings.xml"
EVOLVER_FRAGMENT = ROOT / "src/org/evolution/settings/fragments/statusbar/CutoutProgressSettingsFragment.kt"
SYSUI_SETTINGS = PATCH_ROOT / "packages/SystemUI/src/com/android/systemui/cutoutprogress/CutoutProgressSettings.java"
SYSUI_MANIFEST = PATCH_ROOT / "packages/SystemUI/AndroidManifest.xml"
UPSTREAM_RAW = "https://raw.githubusercontent.com/Evolution-X/frameworks_base/cnb/"
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_KEY = f"{{{ANDROID_NS}}}key"
ANDROID_DEFAULT = f"{{{ANDROID_NS}}}defaultValue"
ANDROID_NAME = f"{{{ANDROID_NS}}}name"
ANDROID_DEPENDENCY = f"{{{ANDROID_NS}}}dependency"


class ValidationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise ValidationError(message)


def run(cmd: list[str], *, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)
    if check and proc.returncode != 0:
        detail = "\n".join(part for part in (proc.stdout.strip(), proc.stderr.strip()) if part)
        fail(f"Command failed ({' '.join(cmd)}):\n{detail}")
    return proc


def parse_patch_manifest() -> tuple[dict[str, str], list[str]]:
    text = APPLY_SCRIPT.read_text(encoding="utf-8")
    expected_block = re.search(r"declare -A EXPECTED=\((.*?)\)\s*NEW_FILES=", text, re.S)
    new_block = re.search(r"NEW_FILES=\((.*?)\)\s*echo ", text, re.S)
    if not expected_block or not new_block:
        fail("Could not parse EXPECTED/NEW_FILES from frameworks_base_patch/apply.sh")

    expected = dict(re.findall(r'\["([^"]+)"\]="([0-9a-f]{40})"', expected_block.group(1)))
    new_files = re.findall(r'"([^"]+)"', new_block.group(1))
    if not expected or not new_files:
        fail("Patch manifest is unexpectedly empty")
    if len(expected) != len(set(expected)) or len(new_files) != len(set(new_files)):
        fail("Duplicate paths found in patch manifest")
    overlap = set(expected) & set(new_files)
    if overlap:
        fail(f"Paths cannot be both replacement and new files: {sorted(overlap)}")
    return expected, new_files


def strip_code_for_braces(text: str) -> str:
    out: list[str] = []
    i = 0
    n = len(text)
    state = "normal"
    block_depth = 0
    while i < n:
        if state == "normal":
            if text.startswith("//", i):
                state = "line"
                i += 2
                continue
            if text.startswith("/*", i):
                state = "block"
                block_depth = 1
                i += 2
                continue
            if text.startswith('"""', i):
                state = "triple"
                i += 3
                continue
            ch = text[i]
            if ch == '"':
                state = "double"
                i += 1
                continue
            if ch == "'":
                state = "single"
                i += 1
                continue
            out.append(ch)
            i += 1
            continue

        if state == "line":
            if text[i] == "\n":
                out.append("\n")
                state = "normal"
            i += 1
            continue

        if state == "block":
            if text.startswith("/*", i):
                block_depth += 1
                i += 2
                continue
            if text.startswith("*/", i):
                block_depth -= 1
                i += 2
                if block_depth == 0:
                    state = "normal"
                continue
            i += 1
            continue

        if state == "triple":
            if text.startswith('"""', i):
                state = "normal"
                i += 3
            else:
                i += 1
            continue

        if state in {"double", "single"}:
            quote = '"' if state == "double" else "'"
            if text[i] == "\\":
                i += 2
                continue
            if text[i] == quote:
                state = "normal"
            i += 1
            continue

    return "".join(out)


def validate_braces(path: Path) -> None:
    stripped = strip_code_for_braces(path.read_text(encoding="utf-8"))
    depth = 0
    for ch in stripped:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth < 0:
                fail(f"Unbalanced braces in {path.relative_to(ROOT)}")
    if depth != 0:
        fail(f"Unbalanced braces in {path.relative_to(ROOT)}")


def collect_value_resources() -> tuple[set[str], set[str]]:
    strings: set[str] = set()
    arrays: set[str] = set()
    for path in sorted((ROOT / "res/values").glob("*.xml")):
        root = ET.parse(path).getroot()
        for child in root:
            name = child.attrib.get("name")
            if not name:
                continue
            tag = child.tag.rsplit("}", 1)[-1]
            if tag == "string":
                strings.add(name)
            elif tag in {"array", "string-array", "integer-array"}:
                arrays.add(name)
    return strings, arrays


def validate_resources() -> None:
    xml_paths = sorted((ROOT / "res").rglob("*.xml")) + [SYSUI_MANIFEST]
    for path in xml_paths:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            fail(f"Malformed XML: {path.relative_to(ROOT)}: {exc}")

    tree = ET.parse(CUTOUT_XML)
    xml_root = tree.getroot()
    keys = [elem.attrib[ANDROID_KEY] for elem in xml_root.iter() if ANDROID_KEY in elem.attrib]
    duplicates = sorted(key for key, count in Counter(keys).items() if count > 1)
    if duplicates:
        fail(f"Duplicate preference keys in cutout_progress_settings.xml: {duplicates}")

    strings, arrays = collect_value_resources()
    xml_text = CUTOUT_XML.read_text(encoding="utf-8")
    string_refs = set(re.findall(r"@string/([A-Za-z0-9_]+)", xml_text))
    array_refs = set(re.findall(r"@array/([A-Za-z0-9_]+)", xml_text))
    missing_strings = sorted(string_refs - strings)
    missing_arrays = sorted(array_refs - arrays)
    if missing_strings:
        fail(f"Missing default string resources referenced by Cutout Progress: {missing_strings}")
    if missing_arrays:
        fail(f"Missing default array resources referenced by Cutout Progress: {missing_arrays}")

    settings_text = SYSUI_SETTINGS.read_text(encoding="utf-8")
    sysui_keys = set(re.findall(r'"(cutout_progress_[a-z0-9_]+)"', settings_text))
    functional_keys = {key for key in keys if not key.startswith("cutout_progress_cat_")}
    missing_sysui_keys = sorted(functional_keys - sysui_keys)
    if missing_sysui_keys:
        fail(f"Evolver preference keys missing from staged SystemUI settings: {missing_sysui_keys}")

    stale_sysui_keys = sorted(sysui_keys - functional_keys)
    if stale_sysui_keys:
        fail(f"Staged SystemUI settings contain keys missing from Evolver XML: {stale_sysui_keys}")

    dependencies = {
        elem.attrib[ANDROID_DEPENDENCY]
        for elem in xml_root.iter()
        if ANDROID_DEPENDENCY in elem.attrib
        and elem.attrib[ANDROID_DEPENDENCY].startswith("cutout_progress_")
    }
    missing_dependencies = sorted(dependencies - set(keys))
    if missing_dependencies:
        fail(f"Cutout Progress dependencies reference missing preference keys: {missing_dependencies}")

    permissions = {
        elem.attrib.get(ANDROID_NAME)
        for elem in ET.parse(SYSUI_MANIFEST).getroot()
        if elem.tag.rsplit("}", 1)[-1] == "uses-permission"
    }
    if "android.permission.READ_PHONE_STATE" not in permissions:
        fail("Staged SystemUI manifest is missing android.permission.READ_PHONE_STATE")


def normalize_default(value: str) -> int:
    value = value.strip()
    if value == "true":
        return 1
    if value == "false":
        return 0
    return int(value, 0)


def validate_calibrated_defaults() -> None:
    text = EVOLVER_FRAGMENT.read_text(encoding="utf-8")
    key_constants = dict(re.findall(r'private const val (KEY_[A-Z0-9_]+)\s*=\s*"([^"]+)"', text))
    default_constants = dict(re.findall(r"private const val (DEFAULT_[A-Z0-9_]+) = ([^\r\n]+)", text))

    pairs = {
        "KEY_COMPLETION_PULSE": "DEFAULT_COMPLETION_PULSE",
        "KEY_AUTO_GEOMETRY": "DEFAULT_AUTO_GEOMETRY",
        "KEY_PATH_MODE": "DEFAULT_PATH_MODE",
        "KEY_RING_SCALE_X": "DEFAULT_RING_SCALE_X",
        "KEY_RING_SCALE_Y": "DEFAULT_RING_SCALE_Y",
        "KEY_RING_OFFSET_X": "DEFAULT_RING_OFFSET_X",
        "KEY_RING_OFFSET_Y": "DEFAULT_RING_OFFSET_Y",
        "KEY_RING_GAP": "DEFAULT_RING_GAP",
        "KEY_DOWNLOAD_PRESENTATION": "DEFAULT_DOWNLOAD_PRESENTATION",
        "KEY_MUSIC_PRESENTATION": "DEFAULT_MUSIC_PRESENTATION",
        "KEY_PRIMARY_PRIORITY": "DEFAULT_PRIMARY_PRIORITY",
        "KEY_MULTI_RING_SPACING": "DEFAULT_MULTI_RING_SPACING",
        "KEY_MUSIC_WAVE_ENABLED": "DEFAULT_MUSIC_WAVE_ENABLED",
        "KEY_MUSIC_WAVE_AMPLITUDE": "DEFAULT_MUSIC_WAVE_AMPLITUDE",
        "KEY_MUSIC_WAVE_DENSITY": "DEFAULT_MUSIC_WAVE_DENSITY",
        "KEY_MUSIC_WAVE_SPEED": "DEFAULT_MUSIC_WAVE_SPEED",
        "KEY_TIMER_PRESENTATION": "DEFAULT_TIMER_PRESENTATION",
        "KEY_TIMER_COLOR_MODE": "DEFAULT_TIMER_COLOR_MODE",
        "KEY_AURORA_COLOR_MODE": "DEFAULT_AURORA_COLOR_MODE",
        "KEY_AURORA_NOTIFICATION_COLOR_MODE": "DEFAULT_AURORA_NOTIFICATION_COLOR_MODE",
    }

    xml_defaults: dict[str, str] = {}
    for elem in ET.parse(CUTOUT_XML).getroot().iter():
        key = elem.attrib.get(ANDROID_KEY)
        default = elem.attrib.get(ANDROID_DEFAULT)
        if key and default is not None:
            xml_defaults[key] = default

    for key_const, default_const in pairs.items():
        if key_const not in key_constants or default_const not in default_constants:
            fail(f"Missing calibrated constant pair: {key_const}/{default_const}")
        key = key_constants[key_const]
        if key not in xml_defaults:
            fail(f"Calibrated key has no XML default: {key}")
        kotlin_default = normalize_default(default_constants[default_const])
        xml_default = normalize_default(xml_defaults[key])
        if kotlin_default != xml_default:
            fail(f"Default mismatch for {key}: Kotlin={kotlin_default}, XML={xml_default}")


def validate_sources() -> None:
    expected, new_files = parse_patch_manifest()
    for rel in [*expected, *new_files]:
        staged = PATCH_ROOT / rel
        if not staged.is_file():
            fail(f"Staged patch file is missing: {rel}")

    run(["bash", "-n", str(APPLY_SCRIPT)])

    focus_files = [EVOLVER_FRAGMENT, *sorted(PATCH_ROOT.rglob("*.java"))]
    for path in focus_files:
        validate_braces(path)

    conflict_markers = re.compile(r"^(<<<<<<<|=======|>>>>>>>)", re.M)
    text_extensions = {".java", ".kt", ".xml", ".sh", ".md", ".yml", ".yaml"}
    for path in ROOT.rglob("*"):
        if path.is_file() and path.suffix in text_extensions:
            text = path.read_text(encoding="utf-8")
            if conflict_markers.search(text):
                fail(f"Merge conflict marker found in {path.relative_to(ROOT)}")

    parent = run(["git", "rev-parse", "HEAD^"], cwd=ROOT, check=False)
    if parent.returncode == 0:
        run(["git", "diff", "--check", "HEAD^", "HEAD"], cwd=ROOT)

    # Check all fork-local changes against the current Evolution-X cnb tree, not only HEAD^.
    # This catches whitespace defects hidden by later merge-only commits.
    run([
        "git", "fetch", "--no-tags", "--depth=1",
        "https://github.com/Evolution-X/packages_apps_Evolver.git",
        "cnb:refs/remotes/evolution-upstream/cnb",
    ], cwd=ROOT)
    run(["git", "diff", "--check", "refs/remotes/evolution-upstream/cnb", "HEAD"], cwd=ROOT)


def git_blob_sha(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def fetch_upstream(rel: str) -> bytes:
    url = UPSTREAM_RAW + rel
    req = urllib.request.Request(url, headers={"User-Agent": "Evolver-validation"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def require_upstream_tokens(rel: str, tokens: list[str]) -> None:
    text = fetch_upstream(rel).decode("utf-8")
    missing = [token for token in tokens if token not in text]
    if missing:
        fail(f"Upstream API contract changed for {rel}: missing {missing}")


def validate_upstream_api_contracts() -> None:
    contracts: dict[str, list[str]] = {
        "packages/SystemUI/Android.bp": [
            'name: "SystemUI-core"',
            '"src/**/*.java"',
            '"src/**/*.kt"',
        ],
        "packages/SystemUI/src/com/android/systemui/cutoutprogress/dagger/CutoutProgressModule.java": [
            "@ClassKey(CutoutProgressController.class)",
            "CoreStartable bindCutoutProgressController",
        ],
        "packages/SystemUI/src/com/android/systemui/dagger/SystemUIModule.java": [
            "import com.android.systemui.cutoutprogress.dagger.CutoutProgressModule;",
            "CutoutProgressModule.class,",
        ],
        "packages/SystemUI/src/com/android/systemui/settings/UserTracker.kt": [
            "val userId: Int",
            "val userProfiles: List<UserInfo>",
            "fun addCallback(callback: Callback, executor: Executor)",
            "fun onUserChanged(newUser: Int, userContext: Context)",
        ],
        "packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/NotifPipeline.kt": [
            "override fun getAllNotifs(): Collection<NotificationEntry>",
            "override fun addCollectionListener(listener: NotifCollectionListener)",
            "override fun removeCollectionListener(listener: NotifCollectionListener)",
        ],
        "packages/SystemUI/src/com/android/systemui/statusbar/notification/collection/notifcollection/NotifCollectionListener.java": [
            "default void onEntryAdded(@NonNull NotificationEntry entry)",
            "default void onEntryUpdated(@NonNull NotificationEntry entry)",
            "default void onEntryRemoved(@NonNull NotificationEntry entry, @CancellationReason int reason)",
        ],
        "packages/SystemUI/src/com/android/systemui/util/MediaSessionManagerHelper.kt": [
            "fun addMediaMetadataListener(listener: MediaMetadataListener)",
            "fun removeMediaMetadataListener(listener: MediaMetadataListener)",
            "fun getMediaBitmap(): Bitmap?",
            "fun getCurrentMediaMetadata(): MediaMetadata?",
            "fun isMediaPlaying()",
            "fun getMediaControllerPlaybackState(): PlaybackState?",
            "fun getInstance(context: Context): MediaSessionManagerHelper",
        ],
        "packages/SystemUI/src/com/android/systemui/CameraProtectionLoader.kt": [
            "R.string.config_frontBuiltInDisplayCutoutProtection",
            "R.string.config_innerBuiltInDisplayCutoutProtection",
            "R.string.config_protectedScreenUniqueId",
            "R.string.config_protectedInnerScreenUniqueId",
        ],
        "core/java/android/view/Display.java": [
            "public boolean getDisplayInfo(DisplayInfo outDisplayInfo)",
            "public Mode getMode()",
            "public @Nullable String getUniqueId()",
            "public int getRotation()",
        ],
        "core/java/android/view/DisplayCutout.java": [
            "public @Nullable Path getCutoutPath()",
            "public CutoutPathParserInfo getCutoutPathParserInfo()",
            "public float getPhysicalPixelDisplaySizeRatio()",
            "public int getRotation()",
        ],
        "core/java/android/view/WindowManager.java": [
            "TYPE_NAVIGATION_BAR_PANEL",
            "SYSTEM_FLAG_SHOW_FOR_ALL_USERS",
            "PRIVATE_FLAG_NO_MOVE_ANIMATION",
            "PRIVATE_FLAG_TRUSTED_OVERLAY",
            "PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC",
            "public void setFitInsetsTypes(@InsetsType int types)",
        ],
        "core/java/android/app/Notification.java": [
            "EXTRA_CHRONOMETER_COUNT_DOWN",
            "EXTRA_SHOW_CHRONOMETER",
            "EXTRA_PROGRESS_INDETERMINATE",
            "CATEGORY_CALL",
            "CATEGORY_PROGRESS",
        ],
        "media/java/android/media/AudioManager.java": [
            "registerAudioRecordingCallback(@NonNull AudioRecordingCallback cb,",
            "getActiveRecordingConfigurations()",
        ],
        "media/java/android/media/AudioRecordingConfiguration.java": [
            "getClientAudioSource()",
        ],
    }

    for rel, tokens in contracts.items():
        require_upstream_tokens(rel, tokens)


def validate_upstream_and_installer() -> None:
    expected, new_files = parse_patch_manifest()
    baseline: dict[str, bytes] = {}

    for rel, expected_sha in expected.items():
        data = fetch_upstream(rel)
        actual_sha = git_blob_sha(data)
        if actual_sha != expected_sha:
            fail(f"Upstream baseline changed for {rel}: expected {expected_sha}, got {actual_sha}")
        baseline[rel] = data

    for rel in new_files:
        try:
            fetch_upstream(rel)
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise
        else:
            fail(f"Staged-new path now exists upstream and requires review: {rel}")

    with tempfile.TemporaryDirectory(prefix="evolver-frameworks-base-") as tmp:
        target = Path(tmp)
        for rel, data in baseline.items():
            path = target / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

        run(["git", "init", "-q"], cwd=target)
        run(["git", "config", "user.name", "Evolver CI"], cwd=target)
        run(["git", "config", "user.email", "evolver-ci@example.invalid"], cwd=target)
        run(["git", "add", "."], cwd=target)
        run(["git", "commit", "-qm", "Synthetic upstream baseline"], cwd=target)

        first = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if first.returncode != 0:
            detail = "\n".join(part for part in (first.stdout.strip(), first.stderr.strip()) if part)
            fail(f"Patch installer failed against the current upstream baseline:\n{detail}")

        second = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if second.returncode != 0:
            detail = "\n".join(part for part in (second.stdout.strip(), second.stderr.strip()) if part)
            fail(f"Patch installer is not idempotent:\n{detail}")

        for rel in [*expected, *new_files]:
            target_bytes = (target / rel).read_bytes()
            staged_bytes = (PATCH_ROOT / rel).read_bytes()
            if target_bytes != staged_bytes:
                fail(f"Installed file differs from staged source: {rel}")

        victim = next(iter(expected))
        with (target / victim).open("ab") as handle:
            handle.write(b"\n# deliberate guard-test mutation\n")
        guarded = run(["bash", str(APPLY_SCRIPT), str(target)], cwd=ROOT, check=False)
        if guarded.returncode == 0:
            fail("Patch installer did not reject a deliberately modified upstream file")


def main() -> int:
    try:
        validate_resources()
        validate_calibrated_defaults()
        validate_sources()
        validate_upstream_api_contracts()
        validate_upstream_and_installer()
    except (ValidationError, OSError, subprocess.SubprocessError, urllib.error.URLError) as exc:
        print(f"VALIDATION FAILED: {exc}", file=sys.stderr)
        return 1

    print("All Evolver Cutout Progress validations passed.")
    print("- XML/resources: OK")
    print("- Preference/SystemUI key parity: OK")
    print("- Calibrated defaults: OK")
    print("- Source structure/conflict checks: OK")
    print("- Upstream Dagger/API contracts: OK")
    print("- frameworks/base baseline hashes: OK")
    print("- Patch install + idempotency + guard behavior: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
