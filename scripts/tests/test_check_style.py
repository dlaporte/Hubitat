import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
sys.path.insert(0, SCRIPTS)

import check_style  # noqa: E402

RAW = "https://raw.githubusercontent.com/dlaporte/Hubitat/main/"
DOC = "https://github.com/dlaporte/Hubitat/blob/main/"


def canonical(name):
    with open(os.path.join(SCRIPTS, "style", name), encoding="utf-8") as f:
        return f.read()


HEADER = """/*
 *  {name}
 *
 *  Talks to a thing over the LAN.
 *  Documentation: {doc}Pkg/README.md
 *
 *  Changelog:
 *  v{ver} (2026-11-20) - Standardized.
 *  v1.2 - an older entry in the old format.
 *
 *  Copyright 2026 David LaPorte
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 */

import groovy.transform.Field

@Field static final String VERSION = "{ver}"
"""

DRIVER_BODY = """
metadata {{
    definition(
        name: "Thing",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "{raw}Pkg/device/thing.groovy"
    ) {{
        capability "Switch"
    }}

    preferences {{
        input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }}
}}

def on() {{
    logDebug "on"
}}

"""

CHILD_BODY = """
metadata {{
    definition(
        name: "Thing Child",
        namespace: "dlaporte",
        author: "David LaPorte",
        importUrl: "{raw}Pkg/device/child.groovy"
    ) {{
        capability "Notification"
    }}
}}

def deviceNotification(String text) {{
    parent?.notify(text)
}}
"""

APP_BODY = """
definition(
    name: "Thing (Connect)",
    namespace: "dlaporte",
    author: "David LaPorte",
    importUrl: "{raw}Pkg/app/app.groovy"
)

preferences {{
    page(name: "main")
}}

def main() {{
    dynamicPage(name: "main") {{
        section {{
            input name: "logEnable", type: "bool", title: "Enable debug logging", description: "Turns itself off after 30 minutes", defaultValue: false
        }}
    }}
}}

"""


def driver_text(ver="1.2.3"):
    return (HEADER.format(name="Thing", doc=DOC, ver=ver)
            + DRIVER_BODY.format(raw=RAW)
            + canonical("logging-driver.groovy"))


def manifest(**overrides):
    m = {
        "packageName": "Thing",
        "author": "David LaPorte",
        "version": "1.2.3",
        "minimumHEVersion": "2.2.9",
        "dateReleased": "2026-11-20",
        "documentationLink": DOC + "Pkg/README.md",
        "releaseNotes": "Standardized.",
        "drivers": [
            {"id": "11111111-1111-1111-1111-111111111111", "name": "Thing", "namespace": "dlaporte",
             "location": RAW + "Pkg/device/thing.groovy", "required": True},
        ],
    }
    m.update(overrides)
    return m


class CheckStyleTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.write("scripts/style/logging-driver.groovy", canonical("logging-driver.groovy"))
        self.write("scripts/style/logging-app.groovy", canonical("logging-app.groovy"))
        self.write("Pkg/README.md", "# Thing\n")
        self.write("Pkg/device/thing.groovy", driver_text())
        self.write_manifest(manifest())

    def tearDown(self):
        shutil.rmtree(self.root)

    # helpers -------------------------------------------------------------

    def write(self, rel, text):
        path = os.path.join(self.root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)

    def write_manifest(self, m):
        self.write("Pkg/packageManifest.json", json.dumps(m, indent=2))

    def edit(self, rel, old, new):
        path = os.path.join(self.root, rel)
        with open(path, encoding="utf-8") as f:
            text = f.read()
        self.assertIn(old, text, "test setup: text to replace not found")
        self.write(rel, text.replace(old, new, 1))

    def run_check(self, strict=False):
        return check_style.run(self.root, strict=strict)

    def assertRuleFails(self, rule):
        failures, _ = self.run_check()
        rules = [f.rule for f in failures]
        self.assertIn(rule, rules, f"expected a {rule} failure, got {failures}")

    # baseline ------------------------------------------------------------

    def test_good_package_passes(self):
        failures, skips = self.run_check()
        self.assertEqual(failures, [])
        self.assertEqual(skips, [])

    def test_unstandardized_file_is_skipped(self):
        self.write("Other/device/old.groovy", "metadata {\n\tdefinition(name: 'Old') {}\n}\n")
        failures, skips = self.run_check()
        self.assertEqual(failures, [])
        self.assertEqual(skips, ["Other/device/old.groovy"])

    def test_strict_fails_unstandardized_file(self):
        self.write("Other/device/old.groovy", "metadata {}\n")
        failures, _ = self.run_check(strict=True)
        self.assertEqual([f.rule for f in failures], ["not-standardized"])

    def test_forks_and_tooling_dirs_are_ignored(self):
        self.write("XiaomiVacuums/device/x.groovy", "\tanything\n")
        self.write("RheemEcoNet/device/x.groovy", "\tanything\n")
        self.write("tests/harness/Harness.groovy", "\tclass Harness {}\n")
        failures, skips = self.run_check(strict=True)
        self.assertEqual(failures, [])
        self.assertEqual(skips, [])

    # per-file rules ------------------------------------------------------

    def test_header_needs_documentation_link(self):
        self.edit("Pkg/device/thing.groovy", " *  Documentation: " + DOC + "Pkg/README.md\n", "")
        self.assertRuleFails("header")

    def test_header_documentation_link_must_point_at_package_readme(self):
        self.edit("Pkg/device/thing.groovy", DOC + "Pkg/README.md", DOC + "Elsewhere/README.md")
        self.assertRuleFails("header")

    def test_header_needs_copyright_and_license(self):
        self.edit("Pkg/device/thing.groovy", " *  Copyright 2026 David LaPorte\n", "")
        self.assertRuleFails("header")

    def test_first_changelog_entry_format(self):
        self.edit("Pkg/device/thing.groovy", "v1.2.3 (2026-11-20) - Standardized.", "v1.2.3 - Standardized.")
        self.assertRuleFails("changelog")

    def test_first_changelog_entry_matches_version(self):
        self.edit("Pkg/device/thing.groovy", "v1.2.3 (2026-11-20)", "v1.2.4 (2026-11-20)")
        self.assertRuleFails("changelog")

    def test_version_must_have_three_parts(self):
        self.write("Pkg/device/thing.groovy", driver_text(ver="1.2"))
        self.write_manifest(manifest(version="1.2"))
        self.assertRuleFails("version")

    def test_no_tab_indentation(self):
        self.edit("Pkg/device/thing.groovy", '    logDebug "on"', '\tlogDebug "on"')
        self.assertRuleFails("tabs")

    def test_field_import_required(self):
        self.edit("Pkg/device/thing.groovy", "import groovy.transform.Field\n", "")
        self.assertRuleFails("field-import")

    def test_author_must_be_full_name(self):
        self.edit("Pkg/device/thing.groovy", 'author: "David LaPorte"', 'author: "dlaporte"')
        self.assertRuleFails("author")

    def test_import_url_must_be_own_raw_main_url(self):
        self.edit("Pkg/device/thing.groovy", RAW + "Pkg/device/thing.groovy",
                  "https://raw.githubusercontent.com/dlaporte/Hubitat/refs/heads/main/Pkg/device/thing.groovy")
        self.assertRuleFails("import-url")

    def test_import_url_required(self):
        self.edit("Pkg/device/thing.groovy", ',\n        importUrl: "' + RAW + 'Pkg/device/thing.groovy"', "")
        self.assertRuleFails("import-url")

    def test_logging_block_must_match_canonical(self):
        self.edit("Pkg/device/thing.groovy", 'log.info "Debug logging turned off"', 'log.info "debug off"')
        self.assertRuleFails("logging-block")

    def test_logging_block_required_when_preferences_exist(self):
        self.write("Pkg/device/thing.groovy",
                   HEADER.format(name="Thing", doc=DOC, ver="1.2.3") + DRIVER_BODY.format(raw=RAW))
        self.assertRuleFails("logging-block")

    def test_logging_inputs_required(self):
        self.edit("Pkg/device/thing.groovy",
                  '        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true\n',
                  "")
        self.assertRuleFails("logging-inputs")

    def test_raw_log_debug_outside_block(self):
        self.edit("Pkg/device/thing.groovy", 'logDebug "on"', 'log.debug "on"')
        self.assertRuleFails("raw-debug")

    def test_log_debug_in_comments_or_strings_is_fine(self):
        self.edit("Pkg/device/thing.groovy", " *  v1.2 - an older entry in the old format.",
                  " *  v1.2 - gated all log.debug calls behind a preference.")
        self.edit("Pkg/device/thing.groovy", 'logDebug "on"',
                  'logDebug "on" // was log.debug\n    String hint = "log.debug is not used"')
        failures, _ = self.run_check()
        self.assertEqual(failures, [])

    def test_child_driver_without_preferences_needs_no_block(self):
        self.write("Pkg/device/child.groovy",
                   HEADER.format(name="Thing Child", doc=DOC, ver="1.2.3") + CHILD_BODY.format(raw=RAW))
        m = manifest()
        m["drivers"].append({"id": "22222222-2222-2222-2222-222222222222", "name": "Thing Child",
                             "namespace": "dlaporte", "location": RAW + "Pkg/device/child.groovy",
                             "required": True})
        self.write_manifest(m)
        failures, _ = self.run_check()
        self.assertEqual(failures, [])

    def test_app_variant(self):
        self.write("Pkg/app/app.groovy",
                   HEADER.format(name="Thing (Connect)", doc=DOC, ver="1.2.3")
                   + APP_BODY.format(raw=RAW) + canonical("logging-app.groovy"))
        m = manifest()
        m["apps"] = [{"id": "33333333-3333-3333-3333-333333333333", "name": "Thing (Connect)",
                      "namespace": "dlaporte", "location": RAW + "Pkg/app/app.groovy", "required": True}]
        self.write_manifest(m)
        failures, _ = self.run_check()
        self.assertEqual(failures, [])

    def test_app_must_use_app_block(self):
        self.write("Pkg/app/app.groovy",
                   HEADER.format(name="Thing (Connect)", doc=DOC, ver="1.2.3")
                   + APP_BODY.format(raw=RAW) + canonical("logging-driver.groovy"))
        m = manifest()
        m["apps"] = [{"id": "33333333-3333-3333-3333-333333333333", "name": "Thing (Connect)",
                      "namespace": "dlaporte", "location": RAW + "Pkg/app/app.groovy", "required": True}]
        self.write_manifest(m)
        self.assertRuleFails("logging-block")

    # package rules -------------------------------------------------------

    def test_manifest_version_must_match_code(self):
        self.write_manifest(manifest(version="1.2.2"))
        self.assertRuleFails("manifest-version")

    def test_manifest_version_must_have_three_parts(self):
        self.write_manifest(manifest(version="1.2"))
        self.assertRuleFails("manifest-version")

    def test_manifest_minimum_firmware(self):
        self.write_manifest(manifest(minimumHEVersion="2.2.0"))
        self.assertRuleFails("manifest-min-he")

    def test_manifest_newer_minimum_firmware_is_fine(self):
        self.write_manifest(manifest(minimumHEVersion="2.5.3"))
        failures, _ = self.run_check()
        self.assertEqual(failures, [])

    def test_manifest_author(self):
        self.write_manifest(manifest(author="dlaporte"))
        self.assertRuleFails("manifest-author")

    def test_manifest_documentation_link(self):
        m = manifest()
        del m["documentationLink"]
        self.write_manifest(m)
        self.assertRuleFails("manifest-doc-link")

    def test_manifest_refs_heads(self):
        m = manifest()
        m["drivers"][0]["location"] = ("https://raw.githubusercontent.com/dlaporte/Hubitat/refs/heads/main/"
                                       "Pkg/device/thing.groovy")
        self.write_manifest(m)
        self.assertRuleFails("manifest-refs-heads")

    def test_manifest_location_must_equal_import_url(self):
        m = manifest()
        m["drivers"][0]["location"] = RAW + "Pkg/device/renamed.groovy"
        self.write_manifest(m)
        self.assertRuleFails("manifest-location")

    def test_partly_standardized_package(self):
        self.write("Pkg/device/second.groovy", "metadata {}\n")
        self.assertRuleFails("package-partial")

    # CLI -----------------------------------------------------------------

    def test_cli_exit_codes(self):
        script = os.path.join(SCRIPTS, "check_style.py")
        ok = subprocess.run([sys.executable, script, "--root", self.root], capture_output=True, text=True)
        self.assertEqual(ok.returncode, 0, ok.stdout + ok.stderr)
        self.edit("Pkg/device/thing.groovy", 'logDebug "on"', 'log.debug "on"')
        bad = subprocess.run([sys.executable, script, "--root", self.root], capture_output=True, text=True)
        self.assertEqual(bad.returncode, 1)
        self.assertIn("FAIL Pkg/device/thing.groovy: raw-debug:", bad.stdout)


if __name__ == "__main__":
    unittest.main()
