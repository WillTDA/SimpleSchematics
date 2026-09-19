"""Check Print translations and GUI layout without launching Minecraft.

Run with: python scripts/check_print_ui.py
This checks layout arithmetic, not font rendering or live server behaviour.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java/dev/willtda/simpleschematics"
LANG = ROOT / "src/main/resources/assets/simpleschematics/lang"
STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, f"Duplicate translation: {key}"
        result[key] = value
    return result


def call_args(source: str, start: int) -> list[str]:
    """Split Java arguments while retaining nested calls and lambda bodies."""
    depth = 0
    quoted = False
    escaped = False
    pieces = []
    begin = start
    for index in range(start, len(source)):
        char = source[index]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "([{":
            depth += 1
        elif char == ")" and depth == 0:
            pieces.append(source[begin:index].strip())
            return pieces
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            pieces.append(source[begin:index].strip())
            begin = index + 1
    raise AssertionError("Unterminated Java call")


def check_language() -> int:
    us = (LANG / "en_us.json").read_text(encoding="utf-8")
    assert us == (LANG / "en_gb.json").read_text(encoding="utf-8"), "English catalogues differ"
    entries = json.loads(us, object_pairs_hook=unique_object)
    sources = "\n".join(path.read_text(encoding="utf-8") for path in JAVA.rglob("*.java"))
    used = set(re.findall(r'"((?:key\.)?simpleschematics\.[A-Za-z0-9_.]*[A-Za-z0-9_])"', sources))
    manager = (JAVA / "printing/PrintManager.java").read_text(encoding="utf-8")
    supplied = {}
    for match in re.finditer(r"\b(tr|info|error|finish|confirm)\(", manager):
        args = call_args(manager, match.end())
        key_index = 1 if match[1] == "confirm" else 0
        if len(args) <= key_index:
            continue
        for key in STRING.findall(args[key_index]):
            if not re.fullmatch(r"[a-z_]+", key):
                continue
            full = "simpleschematics.print." + key
            used.add(full)
            count = len(args) - (3 if match[1] == "confirm" else 1)
            supplied.setdefault(full, []).append(count)
    missing = used - entries.keys()
    orphaned = {key for key in entries if key.startswith("simpleschematics.")} - used
    assert not missing, "Missing translations: " + ", ".join(sorted(missing))
    assert not orphaned, "Orphan translations: " + ", ".join(sorted(orphaned))
    for key, counts in supplied.items():
        placeholders = len(re.findall(r"%(?:\d+\$)?[sd]", entries[key]))
        # A conditional call may supply an unused argument to the shorter message.
        assert all(count >= placeholders for count in counts), f"Missing arguments for {key}: {counts}"
        assert min(counts) == placeholders, f"Unused message arguments for {key}: {counts}"
    warning = entries["simpleschematics.print.survival_warning"]
    assert "Automated building is bannable on most servers." in warning
    assert "Double-check the server rules before proceeding." in warning
    return len(entries)


def number(source: str, name: str) -> int:
    match = re.search(rf"\b{name}\s*=\s*(\d+);", source)
    assert match, f"Missing layout constant: {name}"
    return int(match[1])


@dataclass(frozen=True)
class Rect:
    name: str
    x: int
    y: int
    width: int
    height: int

    def overlaps(self, other: Rect) -> bool:
        return (self.x < other.x + other.width and self.x + self.width > other.x
                and self.y < other.y + other.height and self.y + self.height > other.y)


def check_rects(rects: list[Rect], width: int, height: int, label: str) -> None:
    for index, rect in enumerate(rects):
        assert rect.width > 0 and rect.height > 0, (label, rect)
        assert 0 <= rect.x <= rect.x + rect.width <= width, (label, rect)
        assert 0 <= rect.y <= rect.y + rect.height <= height, (label, rect)
        for other in rects[index + 1:]:
            assert not rect.overlaps(other), (label, rect, other)


def check_library() -> int:
    source = (JAVA / "gui/LibraryScreen.java").read_text(encoding="utf-8")
    row = number(source, "ROW_HEIGHT")
    button = number(source, "BUTTON_HEIGHT")
    gap = number(source, "GAP")
    threshold = number(source, "TWO_COLUMN_WIDTH")
    margin_match = re.search(r"return this.width < (\d+) \? (\d+) : (\d+);", source)
    assert margin_match, "Library margin arithmetic changed; update the layout model"
    margin_at, small_margin, large_margin = map(int, margin_match.groups())
    footer = source.split("private int footerRows()", 1)[1].split("private int hintHeight()", 1)[0]
    counts = re.findall(r"return compact\(\) \? (\d+) : (\d+);", footer)
    assert len(counts) == 2, "Library footer arithmetic changed; update the layout model"
    placement_rows, library_rows = [tuple(map(int, pair)) for pair in counts]
    cases = 0
    # Include both sides of each responsive breakpoint, at GUI scales 1 through 6.
    dimensions = {(w, h) for w in (320, 359, 360, 419, 420, 427, 640, 854, 1280)
                  for h in (240, 270, 300, 360, 480)}
    dimensions |= {(max(320, w // scale), max(240, h // scale))
                   for w, h in ((1280, 720), (1920, 1080), (2560, 1440), (3840, 2160))
                   for scale in range(1, 7)}
    for width, height in sorted(dimensions):
        compact = width < threshold
        margin = small_margin if width < margin_at else large_margin
        list_width = width - margin * 2 if compact else max(150, min(320, int(width * .42)))
        details_x = margin + list_width + gap * 2
        for tab, rows_pair in (("placements", placement_rows), ("library", library_rows)):
            rows = rows_pair[0 if compact else 1]
            footer_top = height - margin - rows * button - (rows - 1) * gap
            bottom = height - margin - button
            action_x = margin if compact else details_x
            action_span = width - margin * 2 if compact else width - details_x - margin
            action_width = min(150, (action_span - gap) // 2)
            nav_span = width - margin * 2 if compact else list_width
            nav_width = min(120, (nav_span - gap) // 2)
            rects = [Rect("Print" if tab == "placements" else "Convert", action_x, footer_top, action_width, button),
                     Rect("Delete", action_x + action_width + gap, footer_top, action_width, button),
                     Rect("Open Folder", margin, bottom, nav_width, button),
                     Rect("Done", margin + nav_width + gap, bottom, nav_width, button)]
            if tab == "library":
                rects += [Rect("Place", action_x, footer_top + button + gap, action_width, button),
                          Rect("Resources", action_x + action_width + gap,
                               footer_top + button + gap, action_width, button)]
            tabs_y = margin - 4 + 9 + 6
            list_top = tabs_y + button + gap + (18 + gap if tab == "library" else 0)
            reserved_hint = 13 if tab == "placements" else 0
            list_bottom = max(list_top + row, footer_top - gap - reserved_hint)
            assert list_bottom <= footer_top - gap - reserved_hint, (tab, width, height, "List/footer overlap")
            rects.append(Rect("List", margin, list_top,
                              width - margin * 2 if tab == "placements" else list_width,
                              list_bottom - list_top))
            check_rects(rects, width, height, (tab, width, height))
            cases += 1
    return cases


def check_config() -> int:
    source = (JAVA / "config/ConfigScreen.java").read_text(encoding="utf-8")
    row = number(source, "ROW_HEIGHT")
    cases = 0
    for width in (320, 360, 427, 640, 854, 1280):
        for height in (240, 270, 300, 360, 480):
            content = max(80, min(width - 24, 420))
            x = (width - content) // 2
            field_width = max(36, content // 2 - 8)
            field_x = x + content - field_width
            label_width = content - field_width - 12
            button_y = height - 28
            notice_y = button_y - 6 - 9
            bottom = max(40 + row, notice_y - 4)
            assert x >= 0 and field_x + field_width <= width
            assert x + label_width < field_x
            assert bottom < notice_y and notice_y + 9 < button_y and button_y + 20 <= height
            cases += 1
    return cases


if __name__ == "__main__":
    print(f"Translations passed: {check_language()} keys, identical English catalogues, matching placeholders.")
    print(f"Library layout passed: {check_library()} tab/window combinations.")
    print(f"Settings layout passed: {check_config()} window sizes.")
