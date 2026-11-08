#!/usr/bin/env python3
"""Add the Flux Build attribution header to modified source files.

Flux Build is a derivative work of MYSOREZ/GeneralsZH-Android-Port.  This
script inserts the attribution header *after* any existing leading licence
comment, so the original EA/GPL notice stays at the top of the file.  It is
idempotent: a file that already contains the Flux Build copyright marker is
left untouched.

Usage:
    python3 scripts/tooling/legal/add_flux_header.py
    python3 scripts/tooling/legal/add_flux_header.py path/to/file.cpp ...
    python3 scripts/tooling/legal/add_flux_header.py --dry-run

With no file arguments it processes every modified tracked C/C++/Java/Kotlin
file reported by ``git diff HEAD --name-only``.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

HEADER = (
    "// Copyright (C) 2026 Flux Build Team.\n"
    "// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.\n"
    "// Licensed under GPLv3 with additional EA terms.\n"
)
MARKER = "Copyright (C) 2026 Flux Build Team."
SOURCE_SUFFIXES = {
    ".c",
    ".cc",
    ".cpp",
    ".cxx",
    ".h",
    ".hh",
    ".hpp",
    ".hxx",
    ".java",
    ".kt",
}
# Main launcher file that is part of Flux Build's own work even when its
# current diff is empty.  Kept explicit so the attribution does not depend on
# which branch the script happens to run from.
ALWAYS_INCLUDE = (
    "Core/GameEngineDevice/Source/SDL3Device/GameClient/TouchInput.cpp",
    "Core/GameEngineDevice/Include/SDL3Device/GameClient/TouchInput.h",
    "android/app/src/main/java/com/generalsx/zerohour/SetupActivity.java",
    "android/app/src/main/java/com/generalsx/zerohour/GeneralsZHActivity.java",
)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def changed_source_files(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "diff", "HEAD", "--name-only", "--diff-filter=ACMRT"],
        cwd=root,
        check=True,
        text=True,
        capture_output=True,
    )
    paths = [root / line for line in result.stdout.splitlines() if line]
    paths.extend(root / name for name in ALWAYS_INCLUDE)
    seen: set[Path] = set()
    unique: list[Path] = []
    for path in paths:
        resolved = path.resolve()
        if resolved in seen:
            continue
        seen.add(resolved)
        if path.is_file() and path.suffix in SOURCE_SUFFIXES:
            unique.append(path)
    return unique


def add_header(text: str) -> tuple[str, bool]:
    if MARKER in text:
        return text, False

    # Keep an existing top-of-file licence block first.  This is the EA/GPL
    # notice on most engine files, and preserving it is required by the
    # additional terms.
    if text.startswith("/*"):
        end = text.find("*/")
        if end != -1:
            pos = end + 2
            while pos < len(text) and text[pos] in "\r\n":
                pos += 1
            return text[:pos] + HEADER + "\n" + text[pos:], True

    return HEADER + "\n" + text, True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="*", help="files to update (default: git diff)")
    parser.add_argument("--dry-run", action="store_true", help="report without writing")
    args = parser.parse_args()

    root = repo_root()
    if args.files:
        files = [Path(name) for name in args.files]
        files = [path if path.is_absolute() else root / path for path in files]
    else:
        files = changed_source_files(root)

    changed = 0
    for path in files:
        if not path.is_file():
            print(f"skip (missing): {path}", file=sys.stderr)
            continue
        if path.suffix not in SOURCE_SUFFIXES:
            print(f"skip (not source): {path}", file=sys.stderr)
            continue
        text = path.read_text(encoding="utf-8")
        updated, did_change = add_header(text)
        if not did_change:
            print(f"ok   {path.relative_to(root)}")
            continue
        if not args.dry_run:
            path.write_text(updated, encoding="utf-8")
        print(f"{'would add' if args.dry_run else 'added'} {path.relative_to(root)}")
        changed += 1

    action = "would update" if args.dry_run else "updated"
    print(f"{action} {changed} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
