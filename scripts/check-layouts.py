#!/usr/bin/env python3
"""Structural check for rows of weighted buttons in the layouts.

Why it exists: on a 360dp-wide display a button label can wrap to two lines. A
horizontal LinearLayout baseline-aligns its children by default, so the taller button
is then pushed down and out of its row. Rows of weighted buttons must therefore turn
baseline alignment off, give the buttons equal (match_parent) heights, and keep the
gap between two buttons on the one that can be hidden.

Layouts cannot be rendered without an Android SDK, so this guards the structure.
Standard library only.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"


def attr(el, name):
    return el.get("{%s}%s" % (ANDROID, name))


def label(el):
    ident = attr(el, "id")
    return ident.split("/")[-1] if ident else "<no id>"


def check_file(path, shown):
    problems = []
    rows = 0
    root = ET.parse(path).getroot()
    for row in root.iter("LinearLayout"):
        if attr(row, "orientation") != "horizontal":
            continue
        buttons = [
            c for c in row
            if c.tag == "Button" and attr(c, "layout_weight") is not None
        ]
        if len(buttons) < 2:
            continue
        rows += 1
        names = ", ".join(label(b) for b in buttons)
        if attr(row, "baselineAligned") != "false":
            problems.append(
                "%s: row [%s]: missing android:baselineAligned=\"false\"" % (shown, names))
        for b in buttons:
            height = attr(b, "layout_height")
            if height != "match_parent":
                problems.append(
                    "%s: %s: layout_height is %s, must be match_parent"
                    % (shown, label(b), height))
        for b in buttons:
            if attr(b, "visibility") != "gone":
                continue
            for other in buttons:
                if other is b:
                    continue
                if attr(other, "layout_marginStart") or attr(other, "layout_marginEnd"):
                    problems.append(
                        "%s: %s: carries the gap although %s can be hidden; the gap "
                        "belongs to the hideable button"
                        % (shown, label(other), label(b)))
    return rows, problems


def main():
    total = 0
    problems = []
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pattern = os.path.join(repo, "app", "src", "main", "res", "layout", "*.xml")
    for path in sorted(glob.glob(pattern)):
        rows, found = check_file(path, os.path.relpath(path, repo))
        total += rows
        problems.extend(found)
    if problems:
        print("\n".join(problems))
        return 1
    print("layouts ok (%d button rows checked)" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
