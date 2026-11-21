#!/usr/bin/env python3
"""Prove that a Groovy file's code is unchanged apart from comments and whitespace.

Usage: same_code.py <git-rev> <path>

The file at <git-rev> and the working copy are both tokenized. Comments and
whitespace outside string literals are dropped, so only blank-line and
comment-line edits are ignored; a change inside a string literal counts as a
difference, and so does joining or splitting code lines (newlines can end a
Groovy statement). Exit 0 when the code is identical, 1 when it differs (the
first difference is printed), 2 on a usage or git error.

This is the check for every "whitespace and header only" commit; see STYLE.md.
"""
import subprocess
import sys

NL = ("NL", "\n")


def _scan_string(text, i):
    """Return the end index (exclusive) of the string literal starting at text[i]."""
    if text.startswith('"""', i) or text.startswith("'''", i):
        quote = text[i:i + 3]
    else:
        quote = text[i]
    interpolates = quote[0] == '"'
    j = i + len(quote)
    n = len(text)
    while j < n:
        c = text[j]
        if c == "\\":
            j += 2
            continue
        if interpolates and text.startswith("${", j):
            j = _scan_interpolation(text, j + 2)
            continue
        if text.startswith(quote, j):
            return j + len(quote)
        j += 1
    raise ValueError(f"unterminated string starting at offset {i}")


def _scan_interpolation(text, j):
    """Skip a GString ${...} body (text[j] is just past '${'); return index past '}'."""
    depth = 1
    n = len(text)
    while j < n:
        c = text[j]
        if c in "\"'":
            j = _scan_string(text, j)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    raise ValueError("unterminated ${...} interpolation")


def tokenize(text):
    """Groovy-ish token stream: words, single punctuation, raw strings, collapsed NLs."""
    tokens = []
    i = 0
    n = len(text)

    def newline():
        if tokens and tokens[-1] != NL:
            tokens.append(NL)

    while i < n:
        c = text[i]
        if c == "\n":
            newline()
            i += 1
        elif c.isspace():
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end == -1 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end == -1:
                raise ValueError(f"unterminated block comment at offset {i}")
            if "\n" in text[i:end]:
                newline()
            i = end + 2
        elif c in "\"'":
            end = _scan_string(text, i)
            tokens.append(("STR", text[i:end]))
            i = end
        elif c.isalnum() or c in "_$":
            j = i
            while j < n and (text[j].isalnum() or text[j] in "_$"):
                j += 1
            tokens.append(("WORD", text[i:j]))
            i = j
        else:
            tokens.append(("PUNCT", c))
            i += 1
    while tokens and tokens[0] == NL:
        tokens.pop(0)
    while tokens and tokens[-1] == NL:
        tokens.pop()
    return tokens


def same(old_text, new_text):
    """Return (True, "") if the code matches, else (False, description of first difference)."""
    old_tokens = tokenize(old_text)
    new_tokens = tokenize(new_text)
    for idx, (a, b) in enumerate(zip(old_tokens, new_tokens)):
        if a != b:
            ctx = " ".join(t[1].replace("\n", "\\n") for t in new_tokens[max(0, idx - 5):idx + 5])
            return False, f"token {idx}: was {a[1]!r}, now {b[1]!r} (near: {ctx})"
    if len(old_tokens) != len(new_tokens):
        return False, f"token count differs: was {len(old_tokens)}, now {len(new_tokens)}"
    return True, ""


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    rev, path = argv[1], argv[2]
    try:
        old_text = subprocess.run(["git", "show", f"{rev}:{path}"], check=True,
                                  capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as e:
        print(f"git show {rev}:{path} failed: {e.stderr.strip()}", file=sys.stderr)
        return 2
    with open(path, encoding="utf-8") as f:
        new_text = f.read()
    ok, detail = same(old_text, new_text)
    if ok:
        print(f"same code: {path} matches {rev} apart from comments and whitespace")
        return 0
    print(f"code differs: {path} vs {rev}: {detail}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
