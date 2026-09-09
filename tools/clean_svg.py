#!/usr/bin/env python3
"""Script to remove unnecessary attributes (e.g. data-name) from SVG files.

Default target directory: `src/main/resources`
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys


DEFAULT_ATTRIBUTES = ["data-name"]


def clean_svg_content(content: str, attributes: list[str]) -> tuple[str, int]:
    """Remove specified attributes from SVG content.

    Returns:
        tuple[str, int]: (modified content, number of removed attributes)
    """
    total_removed = 0
    modified = content

    for attr in attributes:
        # Match attribute with double or single quotes: e.g. data-name="Layer 1" or data-name='Layer 1'
        pattern = re.compile(rf"""\s+{re.escape(attr)}=(?:"[^"]*"|'[^']*')""")
        matches = len(pattern.findall(modified))
        if matches > 0:
            modified = pattern.sub("", modified)
            total_removed += matches

    return modified, total_removed


def clean_svg_files(
    target_path: Path,
    attributes: list[str],
    dry_run: bool = False,
) -> tuple[int, int]:
    """Scan and clean SVG files under the given target path.

    Returns:
        tuple[int, int]: (total files modified, total attributes removed)
    """
    if target_path.is_file() and target_path.suffix.lower() == ".svg":
        svg_files = [target_path]
    elif target_path.is_dir():
        svg_files = sorted(target_path.rglob("*.svg"))
    else:
        print(f"Error: Target path not found: {target_path}", file=sys.stderr)
        return 0, 0

    if not svg_files:
        print(f"No SVG files found in: {target_path}")
        return 0, 0

    modified_files_count = 0
    total_attrs_removed = 0

    for svg_file in svg_files:
        try:
            content = svg_file.read_text(encoding="utf-8")
        except Exception as e:
            print(f"Failed to read {svg_file}: {e}", file=sys.stderr)
            continue

        new_content, count = clean_svg_content(content, attributes)
        if count > 0:
            modified_files_count += 1
            total_attrs_removed += count
            action_label = "[DRY-RUN] Would update" if dry_run else "Updated"
            print(f"{action_label}: {svg_file} ({count} attribute(s) removed)")
            if not dry_run:
                svg_file.write_text(new_content, encoding="utf-8")

    return modified_files_count, total_attrs_removed


def main() -> int:
    # Resolve default target directory relative to the repository root
    repo_root = Path(__file__).resolve().parent.parent
    default_target = repo_root / "src" / "main" / "resources"

    parser = argparse.ArgumentParser(
        description="Remove unnecessary attributes (like data-name) from SVG files."
    )
    parser.add_argument(
        "targets",
        nargs="*",
        type=Path,
        default=[default_target],
        help=f"Target file(s) or directory (default: {default_target})",
    )
    parser.add_argument(
        "-a",
        "--attribute",
        action="append",
        dest="attributes",
        help="Attribute to remove (can be specified multiple times; default: data-name)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Simulate the cleanup without modifying files",
    )

    args = parser.parse_args()
    attributes = args.attributes if args.attributes else DEFAULT_ATTRIBUTES

    total_files = 0
    total_removed = 0

    print(f"Target attributes to remove: {', '.join(attributes)}")
    for target in args.targets:
        files, removed = clean_svg_files(target, attributes, dry_run=args.dry_run)
        total_files += files
        total_removed += removed

    mode_str = " (dry-run)" if args.dry_run else ""
    print(f"\nDone{mode_str}: Removed {total_removed} attribute(s) across {total_files} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
