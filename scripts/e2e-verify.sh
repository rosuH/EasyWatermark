#!/usr/bin/env python3
"""Pre-merge verify.md writer. Informational; never a CI gate. Always use from repo root."""
from __future__ import annotations

import sys
from pathlib import Path

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from e2e_verify import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
