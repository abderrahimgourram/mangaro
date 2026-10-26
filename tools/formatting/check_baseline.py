#!/usr/bin/env python3
"""Fail-closed, exact-content legacy exemptions for the original Spotless rules."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


ANCHOR = "bd0e8b575dd00460cf376295a9c4313f7181ea39"
INVENTORY = "docs/stabilization/pr4-formatting-inventory.md"
# Reviewed release-only build changes; formatter rules/targets are unchanged.
REVIEWED_CONFIGURATION = {"app/build.gradle.kts": "36a911ea85a6ceda32c7566762906ff60cbd29a2"}


def is_configuration(name):
    return (name.endswith((".gradle", ".gradle.kts", ".editorconfig"))
            or name in {"gradle/libs.versions.toml", "gradle/mihon.versions.toml"}
            or name.startswith("gradle/build-logic/src/") and name.endswith(".kt"))


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(root, name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or str(path) != name:
        raise ValueError(f"Unsafe baseline path: {name}")
    candidate = root / name
    if candidate.is_symlink() or root not in candidate.resolve().parents:
        raise ValueError(f"Nonregular baseline path: {name}")
    return candidate


def validate(root, manifest, anchor=ANCHOR, inventory=INVENTORY, configuration=None):
    root = root.resolve()
    if manifest.get("schema") != 1 or manifest.get("anchor") != anchor:
        raise ValueError("Unapproved baseline schema/anchor; requires explicit policy review")
    if manifest.get("inventory") != inventory or not manifest.get("reason", "").strip():
        raise ValueError("Missing approved inventory or review reason")
    git(root, "merge-base", "--is-ancestor", anchor, "HEAD")
    approved = git(root, "show", f"{anchor}:{inventory}").decode()
    allowed = set(re.findall(r"^\| `([^`]+)` \|", approved, re.MULTILINE))
    if not allowed:
        raise ValueError("Approved inventory is empty")
    tracked = set(git(root, "ls-files", "-z").decode().split("\0"))
    untracked = set(git(root, "ls-files", "--others", "--exclude-standard", "-z").decode().split("\0"))
    if configuration is None:
        original_paths = set(git(root, "ls-tree", "-r", "--name-only", anchor).decode().splitlines())
        configuration = {p for p in original_paths if is_configuration(p)}
        current = {p for p in tracked | untracked if p and is_configuration(p)
                   and p != "tools/formatting/legacy-baseline.init.gradle"}
        if current != configuration:
            raise ValueError("Build/formatter configuration paths changed; explicit policy review required")
    if set(manifest.get("configuration", {})) != configuration:
        raise ValueError("Formatter configuration coverage changed; explicit review required")
    for name, expected in manifest["configuration"].items():
        configuration_anchor = REVIEWED_CONFIGURATION.get(name, anchor)
        git(root, "merge-base", "--is-ancestor", configuration_anchor, "HEAD")
        original = git(root, "show", f"{configuration_anchor}:{name}")
        if digest(original) != expected or digest(safe_path(root, name).read_bytes()) != expected:
            raise ValueError(f"Formatter configuration changed: {name}; review/invalidate baseline")
    # Nested .editorconfig files must not override the frozen rules unnoticed.
    if any(p and p != ".editorconfig" and p.endswith(".editorconfig") for p in tracked | untracked):
        raise ValueError("Additional .editorconfig requires explicit policy review")
    deleted = set(git(root, "log", "--format=", "--name-only", "--diff-filter=D",
                      "--no-renames", f"{anchor}..HEAD").decode().splitlines())
    entries = manifest.get("files")
    if not isinstance(entries, dict):
        raise ValueError("Invalid baseline file map")
    # Once retired, an exemption cannot be reintroduced, even with identical bytes.
    manifest_path = "tools/formatting/legacy-baseline.json"
    revisions = git(root, "log", "--format=%H", "--reverse", "HEAD", "--", manifest_path).decode().splitlines()
    previous = allowed
    for revision in revisions:
        prior = json.loads(git(root, "show", f"{revision}:{manifest_path}"))
        names = set(prior["files"])
        if not names <= previous:
            raise ValueError("Retired baseline exemptions cannot be reintroduced")
        previous = names
    if not set(entries) <= previous:
        raise ValueError("Retired baseline exemptions cannot be reintroduced")
    retirements = manifest.get("retirements", {})
    if not isinstance(retirements, dict) or set(retirements) != allowed - set(entries):
        raise ValueError("Every retired exemption requires an explicit retirement record")
    if any(not isinstance(reason, str) or not reason.strip() for reason in retirements.values()):
        raise ValueError("Every retirement requires a documented review reason")
    for name, expected in entries.items():
        if name not in allowed:
            raise ValueError(f"New legacy exemption forbidden: {name}")
        if name in deleted or name not in tracked:
            raise ValueError(f"Deleted/reintroduced baseline entry must be retired: {name}")
        configuration_anchor = REVIEWED_CONFIGURATION.get(name, anchor)
        git(root, "merge-base", "--is-ancestor", configuration_anchor, "HEAD")
        original = git(root, "show", f"{configuration_anchor}:{name}")
        if expected != digest(original):
            raise ValueError(f"Baseline hash differs from approved source: {name}")
        path = safe_path(root, name)
        if not path.is_file() or digest(path.read_bytes()) != expected:
            raise ValueError(f"Baseline hash mismatch: {name}; format file and retire exemption in reviewed commit")
    return sorted(entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--emit-exclusions", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    output = root / "build/reports/formatting/baseline.json"
    try:
        manifest = json.loads((root / "tools/formatting/legacy-baseline.json").read_text())
        files = validate(root, manifest)
        if args.emit_exclusions:
            print(json.dumps([str(root / name) for name in files]))
            return 0
        report = {"policy": "exact-content legacy baseline", "anchor": ANCHOR,
                  "unchanged_legacy_violations": len(files), "files": files,
                  "full_spotless_status": "NOT CLEAN: documented legacy debt",
                  "blocking_gate": "not executed"}
        output.parent.mkdir(parents=True, exist_ok=True)
        if not args.validate_only:
            env = dict(os.environ, MANGARO_FORMAT_ROOT=str(root))
            command = [str(root / "gradlew"), "--no-configuration-cache", "--no-configure-on-demand",
                       "-I", str(root / "tools/formatting/legacy-baseline.init.gradle"),
                       "spotlessCheck", "--max-workers=2", "--console=plain"]
            result = subprocess.run(command, cwd=root, env=env)
            report["blocking_gate"] = "PASS" if result.returncode == 0 else "FAIL"
            output.write_text(json.dumps(report, indent=2) + "\n")
            return result.returncode
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Frozen baseline validated: {len(files)} unchanged legacy violations; full Spotless remains non-clean")
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        if not args.emit_exclusions:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps({"blocking_gate": "FAIL", "error": str(exc),
                                         "unchanged_legacy_violations": "unverified"}, indent=2) + "\n")
        print(f"Formatting policy FAIL: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
