#!/usr/bin/env python3
"""Checks that a refactor only touched comments, string literals and backticked names.

For every Kotlin/Java/C++/Gradle/JS/Python/shell/CMake/properties/XML file that differs between a base
revision and the working tree, both versions are tokenized with comments removed, string-literal contents
replaced by a placeholder and backticked Kotlin names (test names) replaced by a placeholder. Any difference
left in the token streams is reported as a FAILURE (exit status 1). Changed string literals and backticked
names are listed separately so that they can be reviewed by hand (they are the only allowed code changes).

Usage: scripts/check-comments-only.py [--base REV] [--strings] [paths...]
  --base REV  revision to compare against (default HEAD~1 ... pass the pre-refactor commit)
  --strings   also print every changed string literal / backticked name
"""
import argparse
import re
import subprocess
import sys
import io
import tokenize

C_LIKE = {".kt", ".kts", ".java", ".cpp", ".hpp", ".h", ".c", ".cc", ".mjs", ".js", ".gradle"}
HASH_LIKE = {".sh", ".cmake", ".properties", ".yml", ".yaml", ".toml", ".txt"}
XML_LIKE = {".xml"}
PLACEHOLDER = "\0S"


def ext(path):
    if path.endswith("CMakeLists.txt"):
        return ".cmake"
    m = re.search(r"(\.[A-Za-z0-9]+)$", path)
    return m.group(1) if m else ""


def lex_c_like(src, kotlin):
    """Returns (tokens, strings): tokens without comments, strings replaced by PLACEHOLDER."""
    toks, strs = [], []
    i, n = 0, len(src)
    word = re.compile(r"[A-Za-z_0-9$]+")

    def read_string(i, quote, triple, raw):
        """Reads a string body starting after the opening quote(s); returns (end_index, body)."""
        buf = []
        depth = 0
        while i < n:
            c = src[i]
            if triple:
                if src.startswith(quote * 3, i) and depth == 0:
                    # Kotlin allows extra quotes at the end of a raw string
                    j = i + 3
                    while j < n and src[j] == '"':
                        buf.append('"')
                        j += 1
                    return j, "".join(buf)
            elif c == quote and depth == 0:
                return i + 1, "".join(buf)
            if not raw and c == "\\" and depth == 0:
                buf.append(src[i:i + 2])
                i += 2
                continue
            if kotlin and c == "$" and i + 1 < n and src[i + 1] == "{" and depth == 0:
                depth = 1
                buf.append("${")
                i += 2
                continue
            if depth > 0:
                if c == "{":
                    depth += 1
                elif c == "}":
                    depth -= 1
                elif c == '"':  # nested string inside a template
                    j, body = read_string(i + 1, '"', False, False)
                    buf.append('"' + body + '"')
                    i = j
                    continue
            buf.append(c)
            i += 1
        return i, "".join(buf)

    while i < n:
        c = src[i]
        if c.isspace():
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            # Kotlin block comments nest
            depth, i = 1, i + 2
            while i < n and depth:
                if kotlin and src.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif src.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
        elif c == "R" and src.startswith('R"', i) and not kotlin:
            m = re.match(r'R"([^(\s]*)\(', src[i:])
            if m:
                end = src.find(")" + m.group(1) + '"', i)
                end = n if end < 0 else end
                strs.append(src[i + len(m.group(0)):end])
                toks.append(PLACEHOLDER)
                i = end + len(m.group(1)) + 2
            else:
                toks.append(c)
                i += 1
        elif c == '"':
            if src.startswith('"""', i) and kotlin:
                j, body = read_string(i + 3, '"', True, True)
            else:
                j, body = read_string(i + 1, '"', False, False)
            strs.append(body)
            toks.append(PLACEHOLDER)
            i = j
        elif c == "`" and kotlin:
            j = src.find("`", i + 1)
            strs.append("`" + src[i + 1:j] + "`")
            toks.append("`ID`")
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            body = src[i + 1:j]
            # C++ digit separators (1'000) are not char literals
            if i > 0 and src[i - 1].isalnum() and not kotlin:
                toks.append("'")
                i += 1
                continue
            strs.append("'" + body + "'")
            toks.append(PLACEHOLDER)
            i = j + 1
        else:
            m = word.match(src, i)
            if m:
                toks.append(m.group(0))
                i = m.end()
            else:
                toks.append(c)
                i += 1
    return toks, strs


def lex_python(src):
    toks, strs = [], []
    for t in tokenize.generate_tokens(io.StringIO(src).readline):
        if t.type in (tokenize.COMMENT, tokenize.NL, tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT,
                      tokenize.ENCODING, tokenize.ENDMARKER):
            continue
        if t.type == tokenize.STRING:
            strs.append(t.string)
            toks.append(PLACEHOLDER)
        elif t.type == getattr(tokenize, "FSTRING_START", -1) or t.type in (
                getattr(tokenize, "FSTRING_MIDDLE", -1), getattr(tokenize, "FSTRING_END", -1)):
            strs.append(t.string)
            if t.type == tokenize.FSTRING_START:
                toks.append(PLACEHOLDER)
        else:
            toks.append(t.string)
    return toks, strs


def lex_hash(src):
    """Shell/CMake/properties/yaml: drops `#` comments (outside quotes) and blanks quoted contents."""
    toks, strs = [], []
    for line in src.split("\n"):
        out, i, n = [], 0, len(line)
        while i < n:
            c = line[i]
            if c == "#" and (i == 0 or line[i - 1] in " \t;"):
                break
            if c in "\"'":
                j = i + 1
                while j < n and line[j] != c:
                    j += 2 if (line[j] == "\\" and c == '"') else 1
                strs.append(line[i + 1:j])
                out.append(PLACEHOLDER)
                i = j + 1
            else:
                out.append(c)
                i += 1
        toks.extend("".join(out).split())
    return toks, strs


def lex_xml(src):
    src = re.sub(r"<!--.*?-->", "", src, flags=re.S)
    return src.split(), []


def lex(path, src):
    e = ext(path)
    if e in C_LIKE:
        return lex_c_like(src, kotlin=e in (".kt", ".kts"))
    if e == ".py":
        return lex_python(src)
    if e in HASH_LIKE or e == ".cmake":
        if e in (".yml", ".yaml"):  # step/job `name:` values are display text, allowed to change
            names = re.findall(r"^\s*(?:-\s+)?name:[ \t]*(.*)$", src, flags=re.M)
            src = re.sub(r"^(\s*(?:-\s+)?name:)[ \t]*.*$", r"\1 NAME", src, flags=re.M)
            toks, strs = lex_hash(src)
            return toks, strs + names
        return lex_hash(src)
    if e in XML_LIKE:
        return lex_xml(src)
    return None


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="HEAD~1")
    ap.add_argument("--strings", action="store_true")
    ap.add_argument("paths", nargs="*")
    a = ap.parse_args()
    files = a.paths or [f for f in git("diff", "--name-only", "--diff-filter=M", a.base).split("\n") if f]
    failures = checked = 0
    for f in files:
        if lex(f, "") is None:
            continue
        try:
            old = git("show", f"{a.base}:{f}")
        except subprocess.CalledProcessError:
            continue
        new = open(f, encoding="utf-8").read()
        if old == new:
            continue
        checked += 1
        ot, os_ = lex(f, old)
        nt, ns = lex(f, new)
        changed = [(x, y) for x, y in zip(os_, ns) if x != y] if len(os_) == len(ns) else None
        if ot != nt:
            failures += 1
            k = next((i for i, (x, y) in enumerate(zip(ot, nt)) if x != y), min(len(ot), len(nt)))
            print(f"FAIL {f}: token streams differ at token #{k}: "
                  f"before ...{' '.join(ot[max(0, k - 4):k + 4])}... after ...{' '.join(nt[max(0, k - 4):k + 4])}...")
            continue
        if changed is None:
            failures += 1
            print(f"FAIL {f}: number of string literals changed ({len(os_)} -> {len(ns)})")
            continue
        print(f"ok   {f}: code tokens identical, {len(changed)} string/name literal(s) changed")
        if a.strings:
            for x, y in changed:
                print(f"       - {x!r}\n       + {y!r}")
    print(f"checked {checked} changed file(s), {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
