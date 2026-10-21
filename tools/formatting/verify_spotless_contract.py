#!/usr/bin/env python3
"""Exercise clean/new-violating files through the actual production Spotless gate."""

import json
from pathlib import Path
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[2]
    probe = root / "app/src/test/java/eu/kanade/tachiyomi/FormattingPolicyProbe.kt"
    evidence = root / "build/reports/formatting"
    evidence.mkdir(parents=True, exist_ok=True)
    if probe.exists():
        raise RuntimeError("Refusing to overwrite existing probe path")
    results = []
    try:
        for name, content, expected in [
            ("new-clean", "package eu.kanade.tachiyomi\n\ninternal object FormattingPolicyProbe\n", 0),
            ("new-violating", "package eu.kanade.tachiyomi\n\ninternal  object FormattingPolicyProbe  \n", 1),
        ]:
            probe.write_text(content)
            result = subprocess.run([sys.executable, "tools/formatting/check_baseline.py"],
                                    cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            (evidence / f"{name}.log").write_text(result.stdout)
            if result.returncode != expected:
                raise AssertionError(f"{name}: expected exit {expected}, got {result.returncode}; see formatting evidence")
            if expected and ("FormattingPolicyProbe.kt" not in result.stdout or "spotlessKotlinCheck FAILED" not in result.stdout):
                raise AssertionError("Expected actual Spotless violation, not an unrelated preflight/network failure")
            results.append({"case": name, "exit": result.returncode, "passed": True})
    finally:
        probe.unlink(missing_ok=True)
        (evidence / "spotless-contract.json").write_text(json.dumps(results, indent=2) + "\n")
    print("Actual original Spotless: clean new file PASS; violating new file correctly FAIL")


if __name__ == "__main__":
    main()
