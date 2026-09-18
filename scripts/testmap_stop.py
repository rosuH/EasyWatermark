#!/usr/bin/env python3
"""Unified stop protocol for the testmap runner and Artemis suite.

SIGTERM must reach the owned process group, children must stop, then the
suite finally-block (fixture restore / evidence) must run before the runner
may SIGKILL. Numbers are paired invariants, not independent timeouts.

    RUNNER_TERM_S >= CHILD_TERM_S + RESTORE_BUDGET_S
"""

from __future__ import annotations

import os
import signal
import subprocess

# Suite: after SIGTERM, give the SDK/agent child this long before SIGKILL.
CHILD_TERM_S = 5
# Suite: wall-clock deadline for fixture restore after children are dead.
# Independent restore steps share this deadline; they are not N × per-call timeout.
RESTORE_BUDGET_S = 8
# Per-command cap inside the restore/evidence deadline. Never the 45s interactive
# timeout. A hung screencap cannot skip restore because cancel restores first.
CLEANUP_CMD_TIMEOUT_S = 2
# Runner: wait this long after SIGTERM before SIGKILL so finally can finish.
RUNNER_TERM_S = CHILD_TERM_S + RESTORE_BUDGET_S + 2
# Runner: last wait after SIGKILL.
RUNNER_KILL_S = 3


def protocol_holds() -> bool:
    """Ownership pairing: runner wait covers child drain + one restore deadline.

    Restore is deadline-bounded (RESTORE_BUDGET_S), not a multiple of per-call
    caps. Evidence/assessment are not on the cancel-restore critical path.
    """
    return (
        RUNNER_TERM_S >= CHILD_TERM_S + RESTORE_BUDGET_S
        and 0 < CLEANUP_CMD_TIMEOUT_S < RESTORE_BUDGET_S
        and CLEANUP_CMD_TIMEOUT_S < 45
    )


def terminate_process_group(
    proc: subprocess.Popen | None,
    *,
    term_s: float = RUNNER_TERM_S,
    kill_s: float = RUNNER_KILL_S,
) -> None:
    """SIGTERM the session, wait term_s, then SIGKILL. Never shuts a device."""
    if proc is None or proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.terminate()
        except OSError:
            return
    try:
        proc.wait(timeout=term_s)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.kill()
        except OSError:
            pass
    try:
        proc.wait(timeout=kill_s)
    except subprocess.TimeoutExpired:
        pass
