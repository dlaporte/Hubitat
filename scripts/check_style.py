#!/usr/bin/env python3
"""Check drivers, apps and manifests against STYLE.md.

Usage: check_style.py [--root DIR] [--strict]

A .groovy file counts as standardized once it declares
    @Field static final String VERSION = "x.y.z"
Files that don't yet are reported as SKIP (a FAIL with --strict). Package
(manifest) rules apply once every .groovy file in the package is standardized.
Prints one "FAIL <path>: <rule>: <detail>" line per problem; exits 1 if any.
"""
import argparse
import json
import os
import re
import sys
from collections import namedtuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from same_code import tokenize  # noqa: E402

Failure = namedtuple("Failure", "path rule detail")

RAW_BASE = "https://raw.githubusercontent.com/dlaporte/Hubitat/main/"
DOC_BASE = "https://github.com/dlaporte/Hubitat/blob/main/"
AUTHOR = "David LaPorte"
MIN_HE = (2, 2, 9)

# Directories that hold no published code (or are out of scope for the standard).
IGNORED_DIRS = {".git", ".github", "XiaomiVacuums", "RheemEcoNet", "BondHome", "scripts", "tests", "docs"}

VERSION_RE = re.compile(r'@Field\s+static\s+final\s+String\s+VERSION\s*=\s*"([^"]*)"')
THREE_PART_RE = re.compile(r"^\d+\.\d+\.\d+$")
CHANGELOG_ENTRY_RE = re.compile(r"^v(\d+\.\d+\.\d+) \(\d{4}-\d{2}-\d{2}\) - \S")
METADATA_RE = re.compile(r"^\s*metadata\s*\{", re.M)
PREFERENCES_RE = re.compile(r"^\s*preferences\s*[({]", re.M)
# Quoted strings are skipped whole so a name like "Smart Oil Gauge (Connect)" doesn't end the match.
DEFINITION_AUTHOR_RE = re.compile(r'\bdefinition\s*\((?:[^)"]|"[^"]*")*?\bauthor:\s*"([^"]*)"', re.S)
IMPORT_URL_RE = re.compile(r'\bimportUrl:\s*"([^"]*)"')
BLOCK_BEGIN = "// ---- BEGIN standard logging ("
BLOCK_END = "// ---- END standard logging ----"

LOG_ENABLE_INPUT = ('input name: "logEnable", type: "bool", title: "Enable debug logging", '
                    'description: "Turns itself off after 30 minutes", defaultValue: false')
TXT_ENABLE_INPUT = ('input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", '
                    'defaultValue: true')


def version_tuple(v):
    return tuple(int(p) for p in v.split("."))


def find_groovy_files(root):
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        rel_dir = os.path.relpath(dirpath, root)
        if rel_dir == ".":
            dirnames[:] = sorted(d for d in dirnames if d not in IGNORED_DIRS)
        else:
            dirnames.sort()
        for name in sorted(filenames):
            if name.endswith(".groovy"):
                found.append(os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/"))
    return found


def read(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8") as f:
        return f.read()


def header_comment(text):
    if not text.startswith("/*"):
        return None
    end = text.find("*/")
    return text[:end] if end != -1 else None


def first_changelog_entry(header):
    lines = header.splitlines()
    for i, line in enumerate(lines):
        if line.strip().lstrip("*").strip() == "Changelog:":
            for nxt in lines[i + 1:]:
                content = nxt.strip().lstrip("*").strip()
                if content:
                    return content
            return None
    return None


def extract_block(text):
    """Return (block_text, text_without_block) or (None, text)."""
    start = text.find(BLOCK_BEGIN)
    if start == -1:
        return None, text
    end = text.find(BLOCK_END, start)
    if end == -1:
        return text[start:], text[:start]
    end += len(BLOCK_END)
    return text[start:end], text[:start] + text[end:]


def check_file(root, rel, canon):
    """Return (version, import_url, failures) for a standardized file."""
    text = read(root, rel)
    pkg = rel.split("/")[0]
    fails = []

    def fail(rule, detail):
        fails.append(Failure(rel, rule, detail))

    version = VERSION_RE.search(text).group(1)
    if not THREE_PART_RE.match(version):
        fail("version", f'VERSION "{version}" is not three-part x.y.z')

    header = header_comment(text)
    if header is None:
        fail("header", "file must start with a /* ... */ header comment")
    else:
        doc = f"Documentation: {DOC_BASE}{pkg}/README.md"
        for needle, what in ((doc, f"'{doc}'"),
                             ("Changelog:", "'Changelog:'"),
                             (f"Copyright ", "a 'Copyright <year> David LaPorte' line"),
                             ("Licensed under the Apache License, Version 2.0", "the Apache 2.0 license text")):
            if needle not in header:
                fail("header", f"header is missing {what}")
        if "Changelog:" in header:
            entry = first_changelog_entry(header)
            m = CHANGELOG_ENTRY_RE.match(entry or "")
            if not m:
                fail("changelog", f"first changelog entry must look like 'vX.Y.Z (YYYY-MM-DD) - text', got {entry!r}")
            elif m.group(1) != version:
                fail("changelog", f"first changelog entry is v{m.group(1)} but VERSION is {version}")

    for n, line in enumerate(text.splitlines(), 1):
        if line.startswith("\t"):
            fail("tabs", f"line {n} is indented with a tab (use 4 spaces)")
            break

    if "import groovy.transform.Field" not in text:
        fail("field-import", "missing 'import groovy.transform.Field' (needed for @Field VERSION)")

    m = DEFINITION_AUTHOR_RE.search(text)
    if not m or m.group(1) != AUTHOR:
        fail("author", f'definition() must have author: "{AUTHOR}"')

    expected_url = RAW_BASE + rel
    m = IMPORT_URL_RE.search(text)
    import_url = m.group(1) if m else None
    if import_url != expected_url:
        fail("import-url", f'definition() needs importUrl: "{expected_url}" (found {import_url!r})')

    is_driver = bool(METADATA_RE.search(text))
    block, rest = extract_block(text)
    if PREFERENCES_RE.search(text):
        variant = "logging-driver.groovy" if is_driver else "logging-app.groovy"
        expected = canon[variant].strip("\n")
        if block is None:
            fail("logging-block", f"missing the standard logging block (copy scripts/style/{variant})")
        elif block.strip("\n") != expected:
            fail("logging-block", f"logging block differs from scripts/style/{variant}")
        stripped_lines = {line.strip() for line in text.splitlines()}
        required = [LOG_ENABLE_INPUT, TXT_ENABLE_INPUT] if is_driver else [LOG_ENABLE_INPUT]
        for line in required:
            if line not in stripped_lines:
                fail("logging-inputs", f"missing preference line: {line}")

    # Look at code tokens only, so comments and strings that mention log.debug don't count.
    toks = [t[1] for t in tokenize(rest)]
    for i in range(len(toks) - 2):
        if toks[i] == "log" and toks[i + 1] == "." and toks[i + 2] == "debug":
            fail("raw-debug", "log.debug outside the logging block (use logDebug)")
            break

    return version, import_url, fails


def check_package(root, pkg, versions, import_urls):
    rel = f"{pkg}/packageManifest.json"
    fails = []

    def fail(rule, detail):
        fails.append(Failure(rel, rule, detail))

    raw = read(root, rel)
    m = json.loads(raw)
    if m.get("author") != AUTHOR:
        fail("manifest-author", f'author must be "{AUTHOR}" (found {m.get("author")!r})')
    mv = str(m.get("version", ""))
    if not THREE_PART_RE.match(mv):
        fail("manifest-version", f'version "{mv}" is not three-part x.y.z')
    for path, v in sorted(versions.items()):
        if v != mv:
            fail("manifest-version", f"manifest version {mv} != {path} VERSION {v} "
                                     "(expected until the release commit bumps the manifest)")
    mhe = str(m.get("minimumHEVersion", ""))
    try:
        ok = version_tuple(mhe) >= MIN_HE
    except ValueError:
        ok = False
    if not ok:
        fail("manifest-min-he", f'minimumHEVersion must be at least 2.2.9 (found "{mhe}")')
    doc = f"{DOC_BASE}{pkg}/README.md"
    if m.get("documentationLink") != doc:
        fail("manifest-doc-link", f'documentationLink must be "{doc}"')
    if "refs/heads" in raw:
        fail("manifest-refs-heads", "use .../Hubitat/main/... URLs, not refs/heads")
    locations = {e.get("location") for e in m.get("drivers", []) + m.get("apps", [])}
    expected = set(import_urls.values())
    if locations != expected:
        fail("manifest-location", f"manifest locations {sorted(locations)} must equal the files' "
                                  f"importUrls {sorted(expected)}")
    return fails


def load_canonical(root):
    canon = {}
    for name in ("logging-driver.groovy", "logging-app.groovy"):
        canon[name] = read(root, f"scripts/style/{name}")
    return canon


def run(root, strict=False):
    """Return (failures, skipped_paths)."""
    canon = load_canonical(root)
    failures, skips = [], []
    by_pkg = {}
    for rel in find_groovy_files(root):
        pkg = rel.split("/")[0]
        entry = by_pkg.setdefault(pkg, {"std": {}, "urls": {}, "unstd": []})
        if not VERSION_RE.search(read(root, rel)):
            entry["unstd"].append(rel)
            if strict:
                failures.append(Failure(rel, "not-standardized", "file has no VERSION constant yet"))
            else:
                skips.append(rel)
            continue
        version, url, fails = check_file(root, rel, canon)
        failures.extend(fails)
        entry["std"][rel] = version
        entry["urls"][rel] = url
    for pkg, entry in sorted(by_pkg.items()):
        if not entry["std"]:
            continue
        if entry["unstd"]:
            failures.append(Failure(f"{pkg}/packageManifest.json", "package-partial",
                                    f"standardized: {sorted(entry['std'])}; not yet: {entry['unstd']}"))
            continue
        if os.path.exists(os.path.join(root, pkg, "packageManifest.json")):
            failures.extend(check_package(root, pkg, entry["std"], entry["urls"]))
    return failures, skips


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--strict", action="store_true", help="treat unstandardized files as failures")
    args = parser.parse_args(argv)
    failures, skips = run(args.root, strict=args.strict)
    for rel in skips:
        print(f"SKIP {rel}: not standardized yet")
    for f in failures:
        print(f"FAIL {f.path}: {f.rule}: {f.detail}")
    print(f"{len(failures)} failure(s), {len(skips)} file(s) skipped")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
