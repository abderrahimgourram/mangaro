#!/usr/bin/env python3
"""Real installed-app acceptance on emulator-5554; no overlay UI or fake responses.

Requires a signed Mangaro APK already installed, JDK 17 and Android SDK API 34.
Changes only emulator foreground/search state. Never installs, clears app data,
opens ads, signs in, or targets a physical device. Live source failure is a failure
or an explicit blocked result, never a fabricated PASS.
"""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

SERIAL = "emulator-5554"
PACKAGE = "app.manhwaar.reader"
ARABIC = "سولو ليفلينج"
SEED = "The Regressed Son of a Duke is an Assassin"


def adb(*args, binary=False):
    result = subprocess.run(
        ["adb", "-s", SERIAL, *args], check=True, capture_output=True, timeout=45
    )
    return result.stdout if binary else result.stdout.decode(errors="replace")


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/mangaro-search-viewport.xml")
    raw = adb("shell", "cat", "/sdcard/mangaro-search-viewport.xml")
    return raw, ET.fromstring(raw)


def rect(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def tap(node):
    left, top, right, bottom = rect(node)
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def body_title(root, text):
    fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
    if len(fields) != 1:
        return None
    floor = rect(fields[0])[3]
    return next(
        (n for n in root.iter("node") if n.get("text", "").casefold() == text.casefold()
         and n.get("class") != "android.widget.EditText" and rect(n)[1] > floor),
        None,
    )


def capture(directory, name):
    raw, root = dump()
    (directory / f"{name}.xml").write_text(raw)
    (directory / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return root


def search(query):
    encoded = base64.b64encode(query.encode()).decode()
    result = adb("shell", "CLASSPATH=/data/local/tmp/mangaro-search-ui-input.dex "
                 f"app_process /system/bin MangaroSearchUiInput {encoded}")
    if "ACTUAL_UI_TEXT_SET" not in result:
        raise AssertionError("Actual accessibility text action failed")
    _, root = dump()
    fields = [n for n in root.iter("node") if n.get("class") == "android.widget.EditText"]
    if [n.get("text") for n in fields] != [query]:
        raise AssertionError("Actual Search input does not match requested query")
    icons = [n for n in root.iter("node") if n.get("content-desc") == "بحث"]
    if len(icons) != 1:
        raise AssertionError("Actual Search submit icon is unavailable")
    tap(icons[0])


def prepare_helper(directory):
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))
    android_jar = sdk / "platforms/android-34/android.jar"
    candidates = sorted((sdk / "build-tools").glob("*/d8"), reverse=True)
    if not android_jar.is_file() or not candidates:
        raise RuntimeError("Android SDK API 34 and D8 are required")
    javac = (str(Path(os.environ["JAVA_HOME"]) / "bin/javac")
             if os.environ.get("JAVA_HOME") else shutil.which("javac"))
    if not javac:
        raise RuntimeError("JDK 17 javac is required")
    classes = directory / "helper-classes"
    dex = directory / "helper-dex"
    classes.mkdir()
    dex.mkdir()
    subprocess.run([javac, "--release", "8", "-cp", str(android_jar), "-d", str(classes),
                    str(Path(__file__).with_name("MangaroSearchUiInput.java"))], check=True)
    subprocess.run([str(candidates[0]), "--min-api", "26", "--lib", str(android_jar),
                    "--output", str(dex), str(classes / "MangaroSearchUiInput.class")], check=True)
    adb("push", str(dex / "classes.dex"), "/data/local/tmp/mangaro-search-ui-input.dex")
    adb("shell", "chmod", "444", "/data/local/tmp/mangaro-search-ui-input.dex")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--settle-seconds", type=int, default=30, choices=range(15, 46))
    args = parser.parse_args()
    directory = args.evidence_dir.expanduser().resolve()
    repo = Path(__file__).resolve().parents[2]
    if directory == repo or repo in directory.parents:
        parser.error("Keep runtime evidence outside the Git repository")
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    receipt = {"serial": SERIAL, "startedUTC": datetime.datetime.now(datetime.timezone.utc).isoformat(),
               "status": "FAIL", "scope": "actual installed production UI / live source responses"}
    try:
        if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Refusing non-emulator target")
        if f"package:{PACKAGE}" not in adb("shell", "pm", "list", "packages", PACKAGE).splitlines():
            raise RuntimeError("Install the signed candidate on emulator-5554 first")
        receipt["sdk"] = adb("shell", "getprop", "ro.build.version.sdk").strip()
        prepare_helper(directory)
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", f"{PACKAGE}/eu.kanade.tachiyomi.ui.main.MainActivity")
        time.sleep(3)
        _, root = dump()
        tabs = [n for n in root.iter("node") if n.get("text") == "البحث"]
        if not tabs:
            raise AssertionError("Actual Mangaro Search tab is unavailable")
        tap(tabs[-1])
        time.sleep(1)
        search(SEED)
        time.sleep(args.settle_seconds)
        seeded = capture(directory, "01-seed")
        if not any("The Regressed Son" in n.get("text", "")
                   and n.get("class") != "android.widget.EditText" for n in seeded.iter("node")):
            receipt["status"] = "BLOCKED"
            raise RuntimeError("Live seed work unavailable; viewport regression not exercised")
        search(ARABIC)
        time.sleep(args.settle_seconds)
        root = capture(directory, "02-late-arabic")
        target = body_title(root, ARABIC)
        if target is None:
            raise AssertionError("Late exact Arabic match is not visible at the query's top; inspect XML/runtime source evidence")
        old = body_title(root, SEED)
        if old is not None and rect(old)[1] < rect(target)[1]:
            raise AssertionError("Irrelevant old anchor remains above the late exact match")
        receipt["lateExactVisible"] = True
        adb("shell", "input", "swipe", "80", "1850", "80", "650", "650")
        time.sleep(2)
        scrolled = capture(directory, "03-user-scrolled")
        if body_title(scrolled, ARABIC) is None:
            search(ARABIC)
            time.sleep(args.settle_seconds)
            retained = capture(directory, "04-refresh-retains-user-position")
            if body_title(retained, ARABIC) is not None:
                raise AssertionError("Refresh unexpectedly reset the user's scrolled viewport")
            receipt["userScrollPreserved"] = "PASS"
        else:
            receipt["userScrollPreserved"] = "NOT TESTED — live results do not permit this scroll assertion"
        receipt["status"] = "PASS"
    except Exception as error:
        receipt["error"] = f"{type(error).__name__}: {error}"
        try:
            capture(directory, "failure")
        except Exception:
            pass
        raise
    finally:
        (directory / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2))
        print(json.dumps(receipt, ensure_ascii=False))


if __name__ == "__main__":
    main()
