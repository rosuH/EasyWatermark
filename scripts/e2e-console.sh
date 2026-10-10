#!/bin/bash
# ADR-0032 local testmap console. Informational only. Never a CI gate.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/testmap_console.py" "$@"
