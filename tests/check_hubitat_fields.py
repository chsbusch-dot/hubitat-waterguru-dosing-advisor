#!/usr/bin/env python3
"""Reject an @Field initializer that refers to another @Field.

The hub's compiler refuses it ("Apparent variable 'X' was found in a static scope but doesn't refer
to a local variable, static field or class"), while the groovy:4 image the execution tests run in
compiles it without complaint. That is how Pump Power Profiler 1.1.0 passed every test and then
failed to save on the hub on 2026-10-05 (SUMMARY_KEYS = LEGACY_SUMMARY_KEYS + [...]).

    python3 tests/check_hubitat_fields.py      # also run by tests/groovy/run.sh

No dependencies; nothing here touches a hub.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FILES = sorted([*ROOT.glob("apps/*.groovy"), *ROOT.glob("drivers/*.groovy")])
DECLARATION = re.compile(r"^\s*@Field\b[^=\n]*?\b([A-Za-z_]\w*)\s*=(?!=)", re.M)


def initializer(text: str, start: int) -> str:
    """The initializer from `start` to the end of its statement: the first newline at bracket depth 0."""
    depth = 0
    for i in range(start, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "\n" and depth <= 0:
            return text[start:i]
    return text[start:]


def offenders(path: pathlib.Path) -> list[str]:
    text = path.read_text()
    fields = [(m.group(1), m.end()) for m in DECLARATION.finditer(text)]
    names = {name for name, _ in fields}
    out = []
    for name, end in fields:
        init = initializer(text, end)
        used = sorted(n for n in names - {name} if re.search(rf"\b{re.escape(n)}\b", init))
        if used:
            line = text.count("\n", 0, end) + 1
            out.append(f"{path.relative_to(ROOT)}:{line}: @Field {name} is initialised from @Field {', '.join(used)}")
    return out


def main() -> int:
    found = [o for path in FILES for o in offenders(path)]
    for o in found:
        print(f"FAIL  {o}")
    print(f"hubitat @Field check: {len(FILES)} files, {len(found)} problem(s)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
