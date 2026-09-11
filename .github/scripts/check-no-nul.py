#!/usr/bin/env python3
"""Reject raw NUL (0x00) bytes in tracked text sources.

Why a script instead of grep: grep classifies any file containing a NUL byte as
binary and silently skips it ("Binary file ... matches"), so a guard built on grep
can miss the exact byte it is meant to catch. This reads raw bytes and scans them.

Scope: only tracked text files with the extensions listed below. Binary assets
(images, archives, model weights, databases, APKs) are out of scope by construction.

A textual escape such as `\u0000` inside a Kotlin string is six ordinary ASCII bytes
and is NOT a raw NUL, so it is accepted.

Usage:
    check-no-nul.py [ROOT]        scan tracked text files under ROOT (default '.')
    check-no-nul.py --self-test   verify the scanner detects a real 0x00 and accepts \u0000
Exit code: 1 when a raw 0x00 is found (or the self-test fails), 0 when clean.
"""
import os
import subprocess
import sys
import tempfile

TEXT_EXTENSIONS = (
    ".kt", ".kts", ".java", ".xml", ".yml", ".yaml", ".toml", ".properties",
    ".gradle", ".sh", ".py", ".json", ".md", ".js", ".ts", ".tsx", ".css", ".html",
)

RAW_NUL = b"\x00"
# The six ASCII bytes of a Kotlin/Java/JS escape sequence: backslash, u, 0, 0, 0, 0
TEXTUAL_ESCAPE = b"\\u0000"


def is_text_candidate(path):
    return path.lower().endswith(TEXT_EXTENSIONS)


def tracked_files(root):
    """git ls-files under ROOT; None when root is not a git work tree."""
    try:
        proc = subprocess.run(
            ["git", "-C", root, "ls-files", "-z"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    except OSError:
        return None
    if proc.returncode != 0:
        return None
    names = proc.stdout.decode("utf-8", "surrogateescape").split("\0")
    return [n for n in names if n]


def nul_offsets(data):
    return [i for i, byte in enumerate(data) if byte == 0]


def scan_path(path):
    with open(path, "rb") as handle:
        return nul_offsets(handle.read())


def scan_repository(root):
    """Return [(relative_path, [offsets])] for tracked text files containing a raw NUL."""
    names = tracked_files(root)
    if names is None:
        print("check-no-nul: %s is not a git work tree" % root, file=sys.stderr)
        return None
    hits = []
    for name in names:
        if not is_text_candidate(name):
            continue
        full = os.path.join(root, name)
        if not os.path.isfile(full):
            continue
        offsets = scan_path(full)
        if offsets:
            hits.append((name, offsets))
    return hits


def self_test():
    with tempfile.TemporaryDirectory() as tmp:
        real = os.path.join(tmp, "real.kt")
        escaped = os.path.join(tmp, "escaped.kt")
        with open(real, "wb") as handle:
            handle.write(b'val s = "a' + RAW_NUL + b'b"\n')
        with open(escaped, "wb") as handle:
            handle.write(b'val s = "a' + TEXTUAL_ESCAPE + b'b"\n')

        real_offsets = scan_path(real)
        escaped_offsets = scan_path(escaped)
        if not real_offsets:
            print("self-test FAILED: a real 0x00 was not detected", file=sys.stderr)
            return 1
        if escaped_offsets:
            print("self-test FAILED: textual \\u0000 was reported as a raw NUL", file=sys.stderr)
            return 1
        if not is_text_candidate(real) or not is_text_candidate(escaped):
            print("self-test FAILED: .kt not recognised as a text extension", file=sys.stderr)
            return 1
        print("self-test passed: real 0x00 detected at offsets %s, textual \\u0000 accepted"
              % real_offsets)
        return 0


def main(argv):
    args = [a for a in argv[1:]]
    if "--self-test" in args:
        return self_test()
    root = args[0] if args else "."
    hits = scan_repository(root)
    if hits is None:
        return 1
    if not hits:
        print("check-no-nul: no raw 0x00 bytes in tracked text sources")
        return 0
    for name, offsets in hits:
        print("raw 0x00 in %s at offsets %s" % (name, offsets))
    print("check-no-nul: %d file(s) contain raw 0x00" % len(hits))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
