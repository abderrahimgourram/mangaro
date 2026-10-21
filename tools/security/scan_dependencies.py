#!/usr/bin/env python3
"""Fail-closed OSV scan of actual Gradle-selected Maven versions; no credentials needed."""
import argparse
import datetime
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request


class ScanError(Exception):
    pass


def post_queries(queries):
    request = urllib.request.Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "Mangaro-dependency-audit/1"},
        method="POST",
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                if response.geturl() != request.full_url:
                    raise ScanError("Unexpected OSV redirect")
                body = response.read(8 * 1024 * 1024 + 1)
                if len(body) > 8 * 1024 * 1024:
                    raise ScanError("OSV response exceeded the bounded size")
                return json.loads(body)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                raise ScanError(f"OSV HTTP {error.code}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 2:
                raise ScanError("OSV unavailable; security scan did not complete") from error
        time.sleep(2 ** attempt)
    raise ScanError("OSV request did not complete")


def load_packages(paths):
    packages = {}
    coverage = []
    for path in paths:
        inventory = json.loads(path.read_text())
        if inventory.get("schema") != 1 or inventory.get("unresolved") != []:
            raise ScanError("Incomplete or unsupported Gradle inventory")
        if not inventory.get("configurations") or not inventory.get("packages"):
            raise ScanError("Empty Gradle inventory")
        coverage.append({"build": inventory["build"], "configurations": inventory["configurations"]})
        for item in inventory["packages"]:
            values = tuple(item.get(key) for key in ("group", "name", "version"))
            if any(not isinstance(value, str) or not value.strip() for value in values):
                raise ScanError("Invalid selected Maven coordinate")
            if not item.get("scopes"):
                raise ScanError("Maven coordinate has no configuration provenance")
            packages.setdefault(values, set()).update(item["scopes"])
    return packages, coverage


def scan(packages, request=post_queries):
    findings = []
    ordered = sorted(packages)
    for offset in range(0, len(ordered), 100):
        pending = [(coordinate, None) for coordinate in ordered[offset:offset + 100]]
        seen_tokens = set()
        for _ in range(20):
            queries = []
            for (group, name, version), token in pending:
                query = {"package": {"ecosystem": "Maven", "name": f"{group}:{name}"}, "version": version}
                if token:
                    query["page_token"] = token
                queries.append(query)
            response = request(queries)
            results = response.get("results") if isinstance(response, dict) else None
            if not isinstance(results, list) or len(results) != len(pending):
                raise ScanError("OSV returned an incomplete batch")
            next_page = []
            for (coordinate, _), result in zip(pending, results):
                if not isinstance(result, dict) or set(result) - {"vulns", "next_page_token"}:
                    raise ScanError("OSV rejected a package query")
                vulnerabilities = result.get("vulns", [])
                if not isinstance(vulnerabilities, list):
                    raise ScanError("Malformed OSV vulnerability list")
                for vulnerability in vulnerabilities:
                    identity = vulnerability.get("id") if isinstance(vulnerability, dict) else None
                    if not isinstance(identity, str) or not identity:
                        raise ScanError("Malformed OSV vulnerability identity")
                    findings.append({"coordinate": ":".join(coordinate), "id": identity,
                                     "scopes": sorted(packages[coordinate])})
                token = result.get("next_page_token")
                if token:
                    if not isinstance(token, str) or (coordinate, token) in seen_tokens:
                        raise ScanError("OSV pagination repeated or malformed")
                    seen_tokens.add((coordinate, token))
                    next_page.append((coordinate, token))
            if not next_page:
                break
            pending = next_page
        else:
            raise ScanError("OSV pagination exceeded the bounded limit")
    return sorted({(item["coordinate"], item["id"]): item for item in findings}.values(),
                  key=lambda item: (item["coordinate"], item["id"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inventories", nargs="+", type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    report = {"checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "status": "ERROR"}
    try:
        packages, coverage = load_packages(args.inventories)
        findings = scan(packages)
        report.update(status="VULNERABILITIES" if findings else "PASS", packages=len(packages),
                      coverage=coverage, findings=findings)
        code = 1 if findings else 0
    except (ScanError, ValueError, OSError) as error:
        report["error"] = str(error)
        code = 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key not in ("coverage", "findings")}))
    return code


if __name__ == "__main__":
    sys.exit(main())
