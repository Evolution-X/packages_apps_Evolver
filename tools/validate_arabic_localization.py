#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_STRINGS = ROOT / "res/values/evolution_strings.xml"
ARABIC_STRINGS = ROOT / "res/values-ar/evolution_strings.xml"
SOURCE_PLURALS = ROOT / "res/values/evolution_plurals.xml"
ARABIC_PLURALS = ROOT / "res/values-ar/evolution_plurals.xml"
SOURCE_ARRAYS = ROOT / "res/values/evolution_arrays.xml"
ARABIC_ARRAYS = ROOT / "res/values-ar/evolution_arrays.xml"

FORMAT_RE = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")
XLIFF_RE = re.compile(r'<xliff:g\s+id="([^"]+)"[^>]*>')
STRING_RE = re.compile(
    r'<string\s+name="([^"]+)"([^>]*)>([\s\S]*?)</string>'
)
ARRAY_RE = re.compile(
    r'<string-array\s+name="([^"]+)"[^>]*>([\s\S]*?)</string-array>'
)
ITEM_RE = re.compile(r"<item(?:\s+[^>]*)?>([\s\S]*?)</item>")

LEGACY_XML_ATTRS = (
    "android:layout_marginLeft",
    "android:layout_marginRight",
    "android:paddingLeft",
    "android:paddingRight",
    "android:drawableLeft",
    "android:drawableRight",
    "android:layout_alignParentLeft",
    "android:layout_alignParentRight",
    "android:layout_toLeftOf",
    "android:layout_toRightOf",
)

ABSOLUTE_CODE_TOKENS = (
    "Gravity.LEFT",
    "Gravity.RIGHT",
    "LAYOUT_DIRECTION_LTR",
    "TEXT_DIRECTION_LTR",
    ".leftMargin",
    ".rightMargin",
)


class ValidationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise ValidationError(message)


def parse_xml(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"Malformed XML: {path.relative_to(ROOT)}: {exc}")


def parse_strings(path: Path, *, source: bool) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    result: dict[str, str] = {}
    duplicates: list[str] = []
    for match in STRING_RE.finditer(text):
        name, attrs, value = match.groups()
        if source and 'translatable="false"' in attrs:
            continue
        if name in result:
            duplicates.append(name)
        result[name] = value
    if duplicates:
        fail(f"Duplicate strings in {path.relative_to(ROOT)}: {sorted(set(duplicates))}")
    return result


def normalized_formats(value: str) -> list[str]:
    return sorted(FORMAT_RE.findall(value))


def normalized_xliff_ids(value: str) -> list[str]:
    return sorted(XLIFF_RE.findall(value))


def validate_strings() -> None:
    parse_xml(SOURCE_STRINGS)
    parse_xml(ARABIC_STRINGS)

    source = parse_strings(SOURCE_STRINGS, source=True)
    arabic = parse_strings(ARABIC_STRINGS, source=False)

    missing = sorted(set(source) - set(arabic))
    extra = sorted(set(arabic) - set(source))
    if missing:
        fail(f"Arabic strings are missing {len(missing)} keys: {missing}")
    if extra:
        fail(f"Arabic strings contain {len(extra)} unexpected keys: {extra}")

    empty = sorted(key for key, value in arabic.items() if not value.strip())
    if empty:
        fail(f"Arabic strings contain empty values: {empty}")

    format_mismatches = []
    xliff_mismatches = []
    for key, source_value in source.items():
        arabic_value = arabic[key]
        if normalized_formats(source_value) != normalized_formats(arabic_value):
            format_mismatches.append(key)
        if normalized_xliff_ids(source_value) != normalized_xliff_ids(arabic_value):
            xliff_mismatches.append(key)

    if format_mismatches:
        fail(f"Arabic format placeholders differ from source: {format_mismatches}")
    if xliff_mismatches:
        fail(f"Arabic XLIFF placeholders differ from source: {xliff_mismatches}")

    arabic_count = sum(
        1 for value in arabic.values()
        if re.search(r"[\u0600-\u06FF]", value)
    )
    if arabic_count < int(len(arabic) * 0.80):
        fail(
            f"Arabic localization coverage looks suspiciously low: "
            f"{arabic_count}/{len(arabic)} values contain Arabic text"
        )


def parse_arrays(path: Path) -> dict[str, list[str]]:
    text = path.read_text(encoding="utf-8")
    result: dict[str, list[str]] = {}
    for match in ARRAY_RE.finditer(text):
        name, body = match.groups()
        if name in result:
            fail(f"Duplicate array in {path.relative_to(ROOT)}: {name}")
        result[name] = [item.strip() for item in ITEM_RE.findall(body)]
    return result


def validate_arrays() -> None:
    parse_xml(SOURCE_ARRAYS)
    parse_xml(ARABIC_ARRAYS)
    source = parse_arrays(SOURCE_ARRAYS)
    arabic = parse_arrays(ARABIC_ARRAYS)

    user_facing_literal_arrays = {
        name
        for name, items in source.items()
        if (
            (name.endswith("_entries") or name == "perapp_spoof_profile_labels")
            and any(item and not item.startswith("@") for item in items)
        )
    }

    missing = sorted(user_facing_literal_arrays - set(arabic))
    if missing:
        fail(f"Arabic overrides missing for user-facing literal arrays: {missing}")

    unexpected = sorted(set(arabic) - set(source))
    if unexpected:
        fail(f"Arabic arrays do not exist in source: {unexpected}")

    for name, items in arabic.items():
        if len(items) != len(source[name]):
            fail(
                f"Arabic array item-count mismatch for {name}: "
                f"source={len(source[name])}, Arabic={len(items)}"
            )


def validate_plurals() -> None:
    source_root = parse_xml(SOURCE_PLURALS)
    arabic_root = parse_xml(ARABIC_PLURALS)

    source_names = {
        elem.attrib["name"]
        for elem in source_root.findall("plurals")
        if "name" in elem.attrib
    }
    arabic = {
        elem.attrib["name"]: {
            item.attrib.get("quantity")
            for item in elem.findall("item")
        }
        for elem in arabic_root.findall("plurals")
        if "name" in elem.attrib
    }

    if set(arabic) != source_names:
        fail(
            f"Arabic plural resources differ from source: "
            f"source={sorted(source_names)}, Arabic={sorted(arabic)}"
        )

    required = {"zero", "one", "two", "few", "many", "other"}
    for name, quantities in arabic.items():
        if quantities != required:
            fail(
                f"Arabic plural {name} must provide all Arabic quantities; "
                f"found {sorted(quantities)}"
            )


def validate_rtl_safety() -> None:
    offenders: list[str] = []
    for folder in (ROOT / "res/layout", ROOT / "res/xml"):
        if not folder.exists():
            continue
        for path in folder.rglob("*.xml"):
            text = path.read_text(encoding="utf-8")
            found = [token for token in LEGACY_XML_ATTRS if token in text]
            if found:
                offenders.append(f"{path.relative_to(ROOT)}: {found}")

    for folder in (ROOT / "src",):
        if not folder.exists():
            continue
        for path in folder.rglob("*"):
            if path.suffix not in {".java", ".kt"}:
                continue
            text = path.read_text(encoding="utf-8")
            found = [token for token in ABSOLUTE_CODE_TOKENS if token in text]
            if found:
                offenders.append(f"{path.relative_to(ROOT)}: {found}")

    if offenders:
        fail("RTL-unsafe absolute-direction usage found:\n" + "\n".join(offenders))


def main() -> int:
    try:
        validate_strings()
        validate_arrays()
        validate_plurals()
        validate_rtl_safety()
    except (ValidationError, OSError) as exc:
        print(f"ARABIC/RTL VALIDATION FAILED: {exc}", file=sys.stderr)
        return 1

    strings = parse_strings(ARABIC_STRINGS, source=False)
    arrays = parse_arrays(ARABIC_ARRAYS)
    print("Arabic localization and RTL validation passed.")
    print(f"- Arabic strings: {len(strings)} / {len(strings)}")
    print("- Format/XLIFF placeholders: OK")
    print(f"- Localized literal arrays: {len(arrays)}")
    print("- Arabic plural quantities: zero/one/two/few/many/other")
    print("- Legacy absolute RTL attributes/tokens: none")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
