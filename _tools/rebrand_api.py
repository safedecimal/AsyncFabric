#!/usr/bin/env python3
"""Rebrand AsyncFabricApi (fabric-api clone) to net.asyncfabric.

Runs OUTSIDE the repo. argv[1] = repo root.

Module directory/project names (fabric-* -> asyncfabric-*) are NOT renamed:
they define the Maven artifact ids that dependent code and the version
catalog reference. Only namespaces, plugin ids, branding strings change.
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve()
SKIP = {"build", ".git", ".gradle", "run", "out", ".idea"}
TEXT_EXT = {".java", ".gradle", ".json", ".properties", ".toml", ".md", ".txt",
            ".xml", ".yml", ".yaml", ".kts", ".mapping", ".classtweaker",
            ".accesswidener", ".cfg"}

PKG_RULES = [
    ("net.fabricmc.fabric.impl", "net.asyncfabric.impl"),
    ("net.fabricmc.fabric.mixin", "net.asyncfabric.mixin"),
    ("net.fabricmc.fabric.test", "net.asyncfabric.test"),
    ("net.fabricmc.fabric.api", "net.asyncfabric.api"),
    ("net.fabricmc.fabric", "net.asyncfabric"),
    ("net.fabricmc.loader", "net.asyncfabric.loader"),
    ("net.fabricmc.api", "net.asyncfabric.api"),
]

SLASH_RULES = [
    ("net/fabricmc/fabric", "net/asyncfabric"),
    ("net/fabricmc/loader", "net/asyncfabric/loader"),
    ("net/fabricmc/api", "net/asyncfabric/api"),
]

PLUGIN_RULES = [
    ("fabric-api.", "asyncfabric-api."),
]

PROTECT = re.compile(
    r"(net\.fabricmc\.(loom|mappingio|tinyremapper|classtweaker|installer|intermediary|yarn)\b"
    r"|net/fabricmc/(loom|mappingio|tinyremapper|classtweaker|installer)"
    r"|net\.fabricmc:fabric-(loader|loom|installer)"
    r"|net\.fabricmc\.fabric-api:[a-z0-9-]+"          # published upstream coordinates
    r"|Fabric-Loom-[A-Za-z-]+|fabric-loom\b"
    r"|\bFabricUtil\b"
    # Runtime contracts with mods, installer and Loom:
    r"|fabric\.mod\.json|fabric\.mod\b"
    r"|fabric-api:module-lifecycle"
    r"|fabric-server-launch(?:er)?\.properties"
    r"|fabric-installer\.json|fabric-installer\.launchwrapper\.json"
    r'|"fabric\.[A-Za-z0-9_.]+"'
    r"|fabric\.api\.module-lifecycle"
    # Our own module/artifact ids keep the upstream "fabric-" prefix so the
    # version catalog, settings.gradle includes and POM dependencies line up.
    r"|\bfabric-(?:api|api-base|advancement|biome|block|client|command|content"
    r"|convention|crash|creative|data|debug|dimensions|entity|events|game"
    r"|item|key|loot|menu|message|model|networking|object|particles|permission"
    r"|recipe|registry|renderer|rendering|resource|screen|serialization|sound"
    r"|tag|transfer|transitive)[a-z0-9-]*"
    r"|(?:https?://)?(?:www\.)?fabricmc\.net[A-Za-z0-9_./-]*"
    r"|(?:https?://)?(?:maven|repo)\.fabricmc\.net[A-Za-z0-9_./-]*"
    r"|(?:https?://)?github\.com/FabricMC[A-Za-z0-9_./-]*"
    r"|FabricMC)"
)

CLASS_RULES = [
    ("FabricLoader", "AsyncFabricLoader"),
    ("FabricLauncher", "AsyncFabricLauncher"),
]

LITERAL_REGEX = [
    (re.compile(r"(?<!Async)\bFabric API\b"), "AsyncFabric API"),
    (re.compile(r"(?<!async)\bfabric-api\b"), "asyncfabric-api"),
    (re.compile(r"(?<!Async)Fabric(?=\s)"), "AsyncFabric"),
]


def rewrite(text: str) -> str:
    saved = []

    def stash(m):
        saved.append(m.group(0))
        return f"\x00{len(saved) - 1}\x00"

    text = PROTECT.sub(stash, text)

    for old, new in PKG_RULES:
        text = text.replace(old, new)
    for old, new in SLASH_RULES:
        text = text.replace(old, new)
    for old, new in PLUGIN_RULES:
        text = text.replace(old, new)
    for old, new in CLASS_RULES:
        text = re.sub(rf"\b{old}\b", new, text)
    for pattern, repl in LITERAL_REGEX:
        text = pattern.sub(repl, text)

    for i, orig in enumerate(saved):
        text = text.replace(f"\x00{i}\x00", orig)
    return text


def main():
    changed = 0
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        if any(p in SKIP for p in path.relative_to(ROOT).parts):
            continue
        if path.suffix.lower() not in TEXT_EXT:
            continue
        try:
            src = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        out = rewrite(src)
        if out != src:
            path.write_text(out, encoding="utf-8", newline="")
            changed += 1
    print(f"rewrote {changed} files under {ROOT}")


if __name__ == "__main__":
    main()
