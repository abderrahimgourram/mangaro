import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import check_baseline as policy


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run_git("init", "-q")
        self.run_git("config", "user.name", "Synthetic Policy Test")
        self.run_git("config", "user.email", "test@example.invalid")
        self.config = {".editorconfig", "build.gradle.kts"}
        self.write(".editorconfig", "root = true\n")
        self.write("build.gradle.kts", "// fixed original rules\n")
        self.legacy = "src/Legacy.kt"
        self.ad = "src/ads/Protected.kt"
        self.write(self.legacy, "fun broken( ){ }\n")
        self.write(self.ad, "fun existingAd( ){ }\n")
        self.inventory = "docs/inventory.md"
        self.write(self.inventory, f"| `{self.legacy}` | debt |\n| `{self.ad}` | debt |\n")
        self.commit("anchor")
        self.anchor = self.run_git("rev-parse", "HEAD").strip()
        self.manifest = {
            "schema": 1, "anchor": self.anchor, "reason": "Synthetic explicit approval",
            "inventory": self.inventory,
            "configuration": {p: policy.digest((self.root / p).read_bytes()) for p in self.config},
            "files": {p: policy.digest((self.root / p).read_bytes()) for p in [self.legacy, self.ad]},
        }

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.PIPE).decode()

    def write(self, name, text):
        p = self.root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def commit(self, message):
        self.run_git("add", ".")
        self.run_git("commit", "-qm", message)

    def check(self, manifest=None):
        return policy.validate(self.root, manifest or self.manifest, self.anchor, self.inventory, self.config)

    def test_frozen_exact_content_passes(self):
        self.assertEqual([self.legacy, self.ad], self.check())

    def test_modified_legacy_violation_fails(self):
        self.write(self.legacy, "fun broken( ){ } // new code\n")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.check()

    def test_modified_clean_file_requires_explicit_retirement(self):
        self.write(self.legacy, "fun clean() = Unit\n")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.check()
        del self.manifest["files"][self.legacy]
        self.manifest["retirements"] = {self.legacy: "Format entire modified file; original rules now apply"}
        self.assertNotIn(self.legacy, self.check())  # Now sent to original Spotless, no exemption.

    def test_retirement_requires_documented_reason(self):
        del self.manifest["files"][self.legacy]
        with self.assertRaisesRegex(ValueError, "retirement record"):
            self.check()
        self.manifest["retirements"] = {self.legacy: " "}
        with self.assertRaisesRegex(ValueError, "review reason"):
            self.check()

    def test_new_violating_file_never_exempt(self):
        self.write("src/New.kt", "fun broken( ){ }\n")
        self.assertNotIn("src/New.kt", self.check())

    def test_new_clean_file_never_exempt(self):
        self.write("src/New.kt", "fun clean() = Unit\n")
        self.assertNotIn("src/New.kt", self.check())

    def test_added_baseline_entry_forbidden(self):
        self.write("src/New.kt", "fun broken( ){ }\n")
        self.manifest["files"]["src/New.kt"] = policy.digest((self.root / "src/New.kt").read_bytes())
        with self.assertRaisesRegex(ValueError, "reintroduced|forbidden"):
            self.check()

    def test_hash_rewrite_cannot_bless_new_bytes(self):
        self.write(self.legacy, "fun changed( ){ }\n")
        self.manifest["files"][self.legacy] = policy.digest((self.root / self.legacy).read_bytes())
        with self.assertRaisesRegex(ValueError, "approved source"):
            self.check()

    def test_missing_baseline_file_fails(self):
        (self.root / self.legacy).unlink()
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.check()

    def test_deleted_recreated_identical_file_cannot_reuse_exemption(self):
        content = (self.root / self.legacy).read_text()
        (self.root / self.legacy).unlink()
        self.commit("delete legacy path")
        self.write(self.legacy, content)
        self.commit("replacement content with identical bytes")
        with self.assertRaisesRegex(ValueError, "Deleted/reintroduced"):
            self.check()

    def test_retired_entry_cannot_be_reintroduced(self):
        path = "tools/formatting/legacy-baseline.json"
        self.write(path, json.dumps(self.manifest))
        self.commit("approve initial baseline")
        retired = copy.deepcopy(self.manifest)
        del retired["files"][self.legacy]
        self.write(path, json.dumps(retired))
        self.commit("explicit retirement")
        with self.assertRaisesRegex(ValueError, "Retired"):
            self.check()

    def test_configuration_change_fails_even_if_manifest_hash_is_updated(self):
        self.write(".editorconfig", "root = false\n")
        self.manifest["configuration"][".editorconfig"] = policy.digest((self.root / ".editorconfig").read_bytes())
        with self.assertRaisesRegex(ValueError, "configuration changed"):
            self.check()

    def test_configuration_coverage_cannot_be_removed(self):
        del self.manifest["configuration"][".editorconfig"]
        with self.assertRaisesRegex(ValueError, "coverage changed"):
            self.check()

    def test_nested_editorconfig_fails(self):
        self.write("src/.editorconfig", "ktlint_standard = disabled\n")
        with self.assertRaisesRegex(ValueError, "Additional .editorconfig"):
            self.check()

    def test_new_build_script_cannot_override_frozen_rules(self):
        self.write("subproject/build.gradle.kts", "// unexpected format configuration\n")
        with self.assertRaisesRegex(ValueError, "configuration paths changed"):
            policy.validate(self.root, self.manifest, self.anchor, self.inventory)

    def test_actual_configuration_path_discovery_passes_unchanged(self):
        self.assertEqual([self.legacy, self.ad], policy.validate(
            self.root, self.manifest, self.anchor, self.inventory))

    def test_changed_advertising_not_exempt(self):
        self.write(self.ad, "fun changedAd( ){ }\n")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.check()

    def test_unsafe_path_fails(self):
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            policy.safe_path(self.root, "../outside.kt")

    def test_symlink_fails(self):
        (self.root / "link.kt").symlink_to(self.root / self.legacy)
        with self.assertRaisesRegex(ValueError, "Nonregular"):
            policy.safe_path(self.root, "link.kt")


if __name__ == "__main__":
    unittest.main()
