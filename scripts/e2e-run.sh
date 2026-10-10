#!/bin/bash
# ADR-0032 foreground testmap runner. Informational only. Never a CI gate.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$ROOT/scripts/testmap_run.py" "$@"
