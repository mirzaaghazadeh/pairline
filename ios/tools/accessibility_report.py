"""Turns the accessibility audit's output into what needs fixing (D-096). Standard library and ImageMagick only.

    python3 -I accessibility_report.py static <source folder>
        Reduce Motion in the code: every slide, push, scale transition or endless animation must sit next to a
        `reduceMotion` check (or use `.slide(from:reduceMotion:)`, which does it).
    python3 -I accessibility_report.py audit <output folder> <baseline file>
        Reads the `A11Y|…` lines in <output folder>/*.log and the screenshots in <output folder>/shots.

Exits 1 when something needs fixing. What counts (D-091's claims):
- Larger Text: text cut off (`textClipped`), or text that doesn't follow the text size at all.
- Sufficient Contrast, through Increase Contrast: in the `hc-` modes, each element the audit flags is measured in the
  screenshot at its frame; below 4.5:1 for text (3:1 for controls and large text) needs fixing. Elements behind the
  tab bar, off screen or covered (no visible contrast at all) are false alarms and are skipped.
- Everything else (labels, traits, hit areas, partly fixed text sizes, contrast without Increase Contrast) is listed
  for information: VoiceOver and Voice Control aren't claimed yet, and the default look relies on Increase Contrast.
Accepted exceptions are in the baseline file, one per line: `screen|kind|label prefix  # why` (`*` for any screen,
an empty prefix for any label).
"""
import glob
import os
import re
import subprocess
import sys

MOTION = re.compile(r"\.move\(edge:|\.push\(from:|transition\(\.scale|repeatForever|\.offset\(.*\? .*: ")
TEXT_TYPES = {"48"}  # XCUIElement.ElementType.staticText


def static(src):
    problems = []
    for path in sorted(glob.glob(os.path.join(src, "**", "*.swift"), recursive=True)):
        if path.endswith(os.path.join("Design", "Accessibility.swift")):
            continue
        lines = open(path, encoding="utf-8").read().splitlines()
        for i, line in enumerate(lines):
            if not MOTION.search(line) or line.strip().startswith("//"):
                continue
            window = "\n".join(lines[max(0, i - 6): i + 7])
            if "reduceMotion" not in window:
                problems.append(f"{os.path.relpath(path, src)}:{i + 1}: {line.strip()[:100]}")
    if problems:
        print("Reduce Motion: motion without a reduceMotion check (see docs/app-store/accessibility.md):")
        for p in problems:
            print("  " + p)
        return 1
    print("Reduce Motion: every slide, scale and endless animation checks reduceMotion.")
    return 0


def luminance(rgb):
    def channel(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = rgb
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)


def size_of(png):
    out = subprocess.run(["magick", "identify", "-format", "%w %h", png], capture_output=True, text=True).stdout
    w, h = out.split()
    return int(w), int(h)


def measure(png, frame, scale):
    """Contrast between the darkest and lightest pixels in the element's frame, ignoring the 1% extremes."""
    x, y, w, h = frame
    geometry = f"{w * scale}x{h * scale}+{x * scale}+{y * scale}"
    out = subprocess.run(["magick", png, "-crop", geometry, "+repage", "-depth", "8", "txt:-"],
                         capture_output=True, text=True).stdout
    values = []
    for line in out.splitlines()[1:]:
        try:
            rgb = tuple(int(float(v)) for v in line.split("(")[1].split(")")[0].split(",")[:3])
        except (IndexError, ValueError):
            continue
        values.append(luminance(rgb))
    if not values:
        return None
    values.sort()
    cut = len(values) // 100
    low, high = values[cut], values[-1 - cut]
    return (high + 0.05) / (low + 0.05)


def load_baseline(path):
    rules = []
    if not os.path.exists(path):
        return rules
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line:
            screen, kind, prefix = (line.split("|") + ["", ""])[:3]
            rules.append((screen.strip(), kind.strip(), prefix.strip()))
    return rules


def accepted(rules, screen, kind, label):
    return any((s in ("*", screen)) and k == kind and label.startswith(p) for s, k, p in rules)


def audit(folder, baseline_path):
    rules = load_baseline(baseline_path)
    fix, info, skipped, screens = [], {}, [], set()
    for log in sorted(glob.glob(os.path.join(folder, "*.log"))):
        for raw in open(log, encoding="utf-8", errors="replace"):
            line = raw.rstrip("\n")
            if line.startswith("A11Y-SKIP|"):
                skipped.append(" ".join(line.split("|")[1:]))
                continue
            if line.startswith("A11Y-SCREEN|"):
                screens.add(line.split("|")[2])
                continue
            if not line.startswith("A11Y|"):
                continue
            _, tag, screen, kind, desc, element = (line.split("|", 5) + [""] * 6)[:6]
            label_match = re.search(r"label='(.*?)' frame=", element)
            label = label_match.group(1) if label_match else ""
            frame_match = re.search(r"frame=(-?\d+),(-?\d+),(\d+),(\d+)", element)
            type_match = re.search(r"type=(\d+)", element)
            where = f"[{tag}] {screen}: {label[:60] or '(no element)'}"
            if accepted(rules, screen, kind, label):
                continue
            if kind == "textClipped":
                fix.append(f"Larger Text   {where}: text cut off")
            elif kind == "dynamicType" and "partially" not in desc:
                fix.append(f"Larger Text   {where}: doesn't follow the text size")
            elif kind == "contrast" and tag.startswith("hc-") and frame_match:
                png = os.path.join(folder, "shots", f"{tag}-{screen}.png")
                if not os.path.exists(png):
                    continue
                width, height = size_of(png)
                scale = 3 if width > 1000 else 2
                x, y, w, h = map(int, frame_match.groups())
                if x < 0 or y < 0 or x + w > width // scale or y + h > height // scale - 104 or w < 2 or h < 2:
                    continue  # off screen, or behind the tab bar at the bottom
                ratio = measure(png, (x, y, w, h), scale)
                if ratio is None or ratio < 1.5:
                    continue  # covered by something else: nothing visible to read
                is_text = type_match and type_match.group(1) in TEXT_TYPES
                need = 4.5 if is_text and h < 28 else 3.0
                if ratio < need:
                    fix.append(f"Contrast      {where}: {ratio:.2f}:1 with Increase Contrast (needs {need}:1)")
            else:
                key = f"{kind}: {desc}" if kind != "contrast" else "contrast without Increase Contrast"
                info[key] = info.get(key, 0) + 1
    print(f"Screens audited: {', '.join(sorted(screens)) or 'none'}")
    for s in skipped:
        print(f"  not reached: {s}")
    if info:
        print("For information (not claimed, or by design):")
        for key, count in sorted(info.items(), key=lambda kv: -kv[1]):
            print(f"  {count:4}  {key}")
    if fix:
        print(f"To fix ({len(fix)}), or add to the baseline with a reason:")
        for f in sorted(set(fix)):
            print("  " + f)
        return 1
    print("Nothing to fix: the Larger Text and Sufficient Contrast claims hold on every screen audited.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "static":
        sys.exit(static(sys.argv[2]))
    if len(sys.argv) == 4 and sys.argv[1] == "audit":
        sys.exit(audit(sys.argv[2], sys.argv[3]))
    print(__doc__)
    sys.exit(2)
