#!/usr/bin/env python3
"""Rebrand the AsyncFabricLoader clone from net.fabricmc to net.asyncfabric.

Runs OUTSIDE the repo. Pass the repo root as argv[1].

Rules are ordered: longer / more specific first. Third-party namespaces
(net.fabricmc.loom|mappingio|tinyremapper|classtweaker|installer) are preserved
because they are foreign libraries, not our sources.
"""
import os
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve()

SKIP_DIRS = {"build", ".git", ".gradle", "run", "out"}

TEXT_EXT = {".java", ".gradle", ".json", ".properties", ".conf", ".md",
            ".txt", ".xml", ".yml", ".yaml", ".kts", ".toml", ".mapping",
            ".classtweaker", ".accesswidener", ".cfg"}

# --- package replacements (dot form) ---------------------------------------
PKG_RULES = [
    ("net.fabricmc.fabric.api", "net.asyncfabric.api"),
    ("net.fabricmc.fabric.impl", "net.asyncfabric.impl"),
    ("net.fabricmc.fabric.mixin", "net.asyncfabric.mixin"),
    ("net.fabricmc.fabric.test", "net.asyncfabric.test"),
    ("net.fabricmc.fabric", "net.asyncfabric"),
    ("net.fabricmc.loader", "net.asyncfabric.loader"),
    ("net.fabricmc.api", "net.asyncfabric.api"),
    ("net.fabricmc.test", "net.asyncfabric.test"),
    ("net.fabricmc.minecraft", "net.asyncfabric.minecraft"),
    ("net.fabricmc.example", "net.asyncfabric.example"),
]

# --- internal name (slash form) replacements -------------------------------
SLASH_RULES = [
    ("net/fabricmc/loader", "net/asyncfabric/loader"),
    ("net/fabricmc/api", "net/asyncfabric/api"),
    ("net/fabricmc/test", "net/asyncfabric/test"),
    ("net/fabricmc/minecraft", "net/asyncfabric/minecraft"),
]

# --- class identifier renames (ours only; FabricUtil is sponge-mixin) ------
CLASS_RULES = [
    ("FabricLoaderLauncherSessionListener", "AsyncFabricLoaderLauncherSessionListener"),
    ("FabricGlobalPropertyService", "AsyncFabricGlobalPropertyService"),
    ("FabricMixinBootstrap", "AsyncFabricMixinBootstrap"),
    ("FabricMixinVersions", "AsyncFabricMixinVersions"),
    ("FabricClassTransformer", "AsyncFabricClassTransformer"),
    ("FabricServerLauncher", "AsyncFabricServerLauncher"),
    ("FabricServerTweaker", "AsyncFabricServerTweaker"),
    ("FabricClientTweaker", "AsyncFabricClientTweaker"),
    ("FabricTweaker", "AsyncFabricTweaker"),
    ("FabricLoaderImpl", "AsyncFabricLoaderImpl"),
    ("FabricLoader", "AsyncFabricLoader"),
    ("FabricLauncherBase", "AsyncFabricLauncherBase"),
    ("FabricLauncher", "AsyncFabricLauncher"),
    ("FabricTransformer", "AsyncFabricTransformer"),
    ("FabricGuiEntry", "AsyncFabricGuiEntry"),
    ("FabricMainWindow", "AsyncFabricMainWindow"),
    ("FabricStatusTree", "AsyncFabricStatusTree"),
    ("FabricStatusButton", "AsyncFabricStatusButton"),
    ("FabricStatusTab", "AsyncFabricStatusTab"),
    ("FabricStatusNode", "AsyncFabricStatusNode"),
    ("FabricTreeWarningLevel", "AsyncFabricTreeWarningLevel"),
    ("FabricBasicButtonType", "AsyncFabricBasicButtonType"),
]

# --- identifiers / literals / branding ------------------------------------
# Applied as guarded regexes so a renamed token is never rewritten twice and
# foreign "fabricmc" domains/coordinates stay intact.
LITERAL_REGEX = [
    (re.compile(r"(?<!Async)Fabric"), "AsyncFabric"),
    (re.compile(r"(?<!async)fabric(?!mc)"), "asyncfabric"),
]

PROTECT = re.compile(
    r"(net\.fabricmc\.(loom|mappingio|tinyremapper|classtweaker|installer|fabric-api|intermediary|yarn)"
    r"|net/fabricmc/(loom|mappingio|tinyremapper|classtweaker|installer)"
    r"|Fabric-Loom-[A-Za-z-]+|fabric-loom|fabric-loom-remap"
    r"|org\.spongepowered\.asm\.mixin\.FabricUtil|\bFabricUtil\b"
    # Runtime format/wiring contracts: these file names are the loader's public
    # contract with mods and the installer, not branding. Renaming them would
    # make the loader unable to read any mod manifest.
    r"|fabric\.mod\.json|fabric\.mod"
    r"|fabric-server-launch\.properties|fabric-server-launcher\.properties"
    r"|fabric-installer\.json|fabric-installer\.launchwrapper\.json"
    # System property names are a contract with external tooling (Loom injects
    # -Dfabric.development, -Dfabric.side, ... into dev runs). Keeping the keys
    # means dev launches keep working; only branding moves.
    r'|"fabric\.[A-Za-z0-9_.]+"'
    # Real upstream URLs must keep pointing at FabricMC; they are references,
    # not our branding.
    r"|(?:https?://)?(?:maven|repo)\.fabricmc\.net[A-Za-z0-9_./-]*"
    r"|(?:https?://)?(?:www\.)?fabricmc\.net[A-Za-z0-9_./-]*"
    r"|(?:https?://)?github\.com/FabricMC[A-Za-z0-9_./-]*"
    r"|FabricMC)"  # license headers / authorship must survive
)


def rewrite(text: str) -> str:
    # Protect foreign namespaces with placeholders, then restore.
    saved = []

    def stash(m):
        saved.append(m.group(0))
        return f"\x00{len(saved) - 1}\x00"

    text = PROTECT.sub(stash, text)

    for old, new in PKG_RULES:
        text = text.replace(old, new)
    for old, new in SLASH_RULES:
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
        if any(p in SKIP_DIRS for p in path.relative_to(ROOT).parts):
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
