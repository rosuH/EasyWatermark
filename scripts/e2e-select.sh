#!/usr/bin/env python3
"""ADR-0032 P2: informational e2e layer suggester. Never a CI gate (ADR-0031).

Always exits 0. Logic lives in scripts/e2e_select.py. This launcher is Python so
`python3 scripts/e2e-select.sh --help` and `scripts/e2e-select.sh --help` both work.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from e2e_select import main  # noqa: E402

if __name__ == "__main__":
    try:
        code = main(sys.argv[1:])
    except SystemExit as exc:
        code = exc.code if isinstance(exc.code, int) else 1
    if code not in (0, None):
        print(
            f"e2e-select: python exited {code} (forcing 0; informational only)",
            file=sys.stderr,
        )
    raise SystemExit(0)
