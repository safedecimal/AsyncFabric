#!/usr/bin/env python3
"""Regroup and sort Java imports to match this repo's checkstyle ImportOrder:
groups = java, javax, *, net.minecraft, net.fabricmc, net.asyncfabric
static imports on top, one blank line between groups.
"""
import re
import sys
from pathlib import Path

GROUPS = [
    ("java", lambda n: n.startswith("java.")),
    ("javax", lambda n: n.startswith("javax.")),
    ("*", lambda n: not (n.startswith("java.") or n.startswith("javax.")
                         or n.startswith("net.minecraft") or n.startswith("net.fabricmc")
                         or n.startswith("net.asyncfabric"))),
    ("net.minecraft", lambda n: n.startswith("net.minecraft")),
    ("net.fabricmc", lambda n: n.startswith("net.fabricmc")),
    ("net.asyncfabric", lambda n: n.startswith("net.asyncfabric")),
]

IMPORT_RE = re.compile(r"^import\s+(static\s+)?([\w.]+(?:\.\*)?)\s*;")


def process(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()

    idx = [i for i, line in enumerate(lines) if IMPORT_RE.match(line.strip())]
    if not idx:
        return False

    start, end = idx[0], idx[-1]
    block = [lines[i].strip() for i in idx]

    static, normal = [], []
    for line in block:
        m = IMPORT_RE.match(line)
        (static if m.group(1) else normal).append((m.group(2), line))

    out = [line for _, line in sorted(static)]

    # Build normal groups in declared order.
    for _, matcher in GROUPS:
        members = [line for name, line in normal if matcher(name)]
        if not members:
            continue
        if out:
            out.append("")
        out.extend(sorted(members))

    new_lines = lines[:start] + out + lines[end + 1:]
    new_text = "\n".join(new_lines)
    if text.endswith("\n"):
        new_text += "\n"
    if new_text != text:
        path.write_text(new_text, encoding="utf-8", newline="")
        return True
    return False


def main():
    root = Path(sys.argv[1]).resolve()
    changed = 0
    for path in root.rglob("*.java"):
        if any(p in {"build", ".git", ".gradle"} for p in path.relative_to(root).parts):
            continue
        try:
            if process(path):
                changed += 1
        except UnicodeDecodeError:
            continue
    print(f"regrouped imports in {changed} files")


if __name__ == "__main__":
    main()
