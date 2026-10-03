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
import time

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
    """Stop the owned session, even after its leader exits. Never shuts a device.

    Callers must create this process with start_new_session=True. Waiting only
    for the leader leaks descendants (and leaves inherited stdout pipes open).
    """
    if proc is None:
        return

    def signal_group(sig: int) -> bool:
        try:
            os.killpg(proc.pid, sig)
            return True
        except ProcessLookupError:
            return False
        except OSError:
            # A process without a session still gets the old direct-child fallback.
            if proc.poll() is None and sig:
                proc.send_signal(sig)
            return proc.poll() is None

    if not signal_group(signal.SIGTERM):
        proc.poll()
        return
    deadline = time.monotonic() + term_s
    while time.monotonic() < deadline:
        proc.poll()  # Reap the leader without treating its exit as group exit.
        if not signal_group(0):
            return
        time.sleep(min(0.05, max(0, deadline - time.monotonic())))
    signal_group(signal.SIGKILL)
    try:
        proc.wait(timeout=kill_s)
    except subprocess.TimeoutExpired:
        pass


def run_captured(
    cmd: list[str], *, timeout: float, text: bool = True, cwd=None, should_stop=None,
    stop_grace_s: float = CHILD_TERM_S,
) -> subprocess.CompletedProcess:
    """Bound captured CLI commands and their owned descendants, including pipe EOF."""
    if should_stop and should_stop():
        empty = "" if text else b""
        return subprocess.CompletedProcess(cmd, 130, empty, empty)
    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        start_new_session=True, text=text, cwd=cwd,
    )
    try:
        expires = time.monotonic() + timeout
        while True:
            if should_stop and should_stop():
                terminate_process_group(proc, term_s=stop_grace_s, kill_s=min(stop_grace_s, RUNNER_KILL_S))
                try:
                    stdout, stderr = proc.communicate(timeout=min(stop_grace_s, RUNNER_KILL_S))
                except subprocess.TimeoutExpired as exc:
                    stdout, stderr = exc.output or b"", exc.stderr or b""
                    if text:
                        stdout = stdout.decode(errors="replace") if isinstance(stdout, bytes) else stdout
                        stderr = stderr.decode(errors="replace") if isinstance(stderr, bytes) else stderr
                return subprocess.CompletedProcess(cmd, 130, stdout, stderr)
            remaining = expires - time.monotonic()
            if remaining <= 0:
                raise subprocess.TimeoutExpired(cmd, timeout)
            try:
                stdout, stderr = proc.communicate(timeout=min(.25, remaining) if should_stop else remaining)
                return subprocess.CompletedProcess(cmd, proc.returncode, stdout, stderr)
            except subprocess.TimeoutExpired:
                if not should_stop:
                    raise
    except subprocess.TimeoutExpired as exc:
        terminate_process_group(proc)
        try:
            exc.output, exc.stderr = proc.communicate(timeout=RUNNER_KILL_S)
        except subprocess.TimeoutExpired:
            # A detached descendant can retain a pipe but cannot extend this deadline.
            proc.stdout.close()
            proc.stderr.close()
        raise
    except BaseException:
        terminate_process_group(proc)
        raise
    finally:
        proc.stdout.close()
        proc.stderr.close()
