import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from same_code import same  # noqa: E402


BASE = '''/*
 *  Example driver
 *  v0.1 - initial
 */
metadata {
	definition(name: "X", namespace: "dlaporte") {
		capability "Switch"
	}
}

def on() {
	sendEvent(name: "switch", value: "on") // turn on
}
'''


class SameCodeTest(unittest.TestCase):
    def assertSame(self, old, new):
        ok, detail = same(old, new)
        self.assertTrue(ok, detail)

    def assertDifferent(self, old, new):
        ok, _ = same(old, new)
        self.assertFalse(ok)

    def test_reindent_is_same(self):
        self.assertSame(BASE, BASE.replace("\t", "    "))

    def test_comment_edit_is_same(self):
        new = BASE.replace(" *  v0.1 - initial", " *  Changelog:\n *  v0.2.0 (2026-11-20) - more\n *  v0.1 - initial")
        new = new.replace("// turn on", "// switch the light on")
        self.assertSame(BASE, new)

    def test_added_blank_lines_and_comment_lines_are_same(self):
        new = BASE.replace("def on() {", "// ---- Commands ----\n\n\ndef on() {")
        self.assertSame(BASE, new)

    def test_code_edit_is_different(self):
        self.assertDifferent(BASE, BASE.replace('value: "on"', 'value: "off"'))

    def test_identifier_edit_is_different(self):
        self.assertDifferent(BASE, BASE.replace("sendEvent", "sendEvents"))

    def test_joining_code_lines_is_different(self):
        old = "def f() {\n    return\n    x\n}\n"
        new = "def f() {\n    return x\n}\n"
        self.assertDifferent(old, new)

    def test_whitespace_inside_triple_string_is_different(self):
        old = 'def css = """\n\tbody { color: red }\n"""\n'
        new = 'def css = """\n    body { color: red }\n"""\n'
        self.assertDifferent(old, new)

    def test_whitespace_inside_plain_string_is_different(self):
        self.assertDifferent('def s = "a b"\n', 'def s = "a  b"\n')

    def test_slashes_inside_string_are_not_a_comment(self):
        old = 'def u = "http://example.com" ; def y = 1\n'
        new = 'def u = "http://example.com" ; def y = 2\n'
        self.assertDifferent(old, new)

    def test_gstring_interpolation_with_nested_quotes(self):
        old = 'def s = "a ${m["k"]} b" ; def z = 1\n'
        self.assertSame(old, old.replace("def z", "def  z"))
        self.assertDifferent(old, old.replace('m["k"]', 'm["j"]'))

    def test_escaped_quote_in_string(self):
        old = 'def s = "say \\"hi\\" // not a comment" ; def q = 1\n'
        self.assertDifferent(old, old.replace("def q = 1", "def q = 2"))

    def test_slashy_regex_containing_double_slash_and_quotes(self):
        old = 'def a = s.replaceAll(/x\\/\\/y/, "") ; def b = 1\ndef c = s.replaceAll(/^"|"$/, "")\n'
        self.assertDifferent(old, old.replace("def b = 1", "def b = 2"))
        self.assertDifferent(old, old.replace('/^"|"$/', '/^"|"x$/'))
        self.assertSame(old, old.replace("def b", "def  b"))

    def test_division_is_not_a_slashy_string(self):
        old = "def r = a / b / c\ndef d = 1\n"
        self.assertDifferent(old, old.replace("def d = 1", "def d = 2"))
        self.assertSame(old, old.replace("a / b", "a/b"))

    def test_single_quoted_and_triple_single_quoted(self):
        old = "def a = 'x // y'\ndef b = '''\n  z\n'''\n"
        self.assertSame(old, old.replace("def a", "def  a"))
        self.assertDifferent(old, old.replace("  z", "    z"))


if __name__ == "__main__":
    unittest.main()
