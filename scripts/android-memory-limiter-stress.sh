#!/usr/bin/env bash
# WP-F: Android 17 memory-limiter stress helpers for EasyWatermark (debug).
# Safe when limiter is unsupported: commands no-op with a friendly message.
# Does NOT upload dumps. Does NOT kill live emulators unless you pass --force-kill.
set -euo pipefail

PKG_DEBUG="${PKG_DEBUG:-me.rosuh.easywatermark.debug}"
ACTIVITY="${ACTIVITY:-me.rosuh.easywatermark.ui.MainActivity}"
ADB="${ADB:-adb}"
MAX_WORKERS="${MAX_WORKERS:-8}"

usage() {
  cat <<'U'
Usage: scripts/android-memory-limiter-stress.sh <command> [args]

Commands:
  devices              List adb devices
  status               am memory-limiter status (no-op friendly)
  install-debug        ./gradlew :app:assembleDebug + adb install -r
  launch               Start debug MainActivity
  meminfo              dumpsys meminfo for debug package
  exits                dumpsys activity exit-info (MemoryLimiter tag)
  manual-limit <mb>    adb shell am memory-limiter manual <pid> <mb>
  manual-none          clear manual limit for package pid
  ignore-uid           am memory-limiter ignore <uid>
  ignore-none          am memory-limiter ignore none
  checklist            Print multi-image export manual dogfood steps
  dry-run              status + meminfo + exits (no install)
  dogfood              install+launch+meminfo+trim+meminfo+exits+logcat (device)
  trim <LEVEL>         am send-trim-memory (e.g. HIDDEN|BACKGROUND|COMPLETE)
  logcat-ewm           recent EwmMemoryLimiter lines

Env:
  PKG_DEBUG  default me.rosuh.easywatermark.debug
  ADB        default adb
U
}

need_device() {
  local n
  n="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {c++} END{print c+0}')"
  if [[ "$n" -lt 1 ]]; then
    echo "No adb device in 'device' state. Connect a phone/emulator and retry." >&2
    return 1
  fi
}

limiter() {
  # shellcheck disable=SC2068
  if ! out="$("$ADB" shell am memory-limiter "$@" 2>&1)"; then
    echo "memory-limiter unsupported or failed (OK on pre-17 / OEM off): $out"
    return 0
  fi
  printf '%s\n' "$out"
}

pkg_pid() {
  "$ADB" shell pidof -s "$PKG_DEBUG" 2>/dev/null | tr -d '\r' || true
}

pkg_uid() {
  "$ADB" shell dumpsys package "$PKG_DEBUG" 2>/dev/null \
    | awk '/userId=/{print $1; exit}' | sed 's/userId=//' | tr -d '\r' || true
}

cmd="${1:-}"
shift || true

case "$cmd" in
  ""|-h|--help) usage ;;
  devices) "$ADB" devices -l ;;
  status)
    need_device
    limiter status
    ;;
  install-debug)
    ./gradlew :app:assembleDebug --max-workers="$MAX_WORKERS"
    need_device
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
    ;;
  launch)
    need_device
    "$ADB" shell am start -n "${PKG_DEBUG}/${ACTIVITY}"
    ;;
  meminfo)
    need_device
    "$ADB" shell dumpsys meminfo "$PKG_DEBUG" | head -80
    ;;
  exits)
    need_device
    echo "=== ApplicationExitInfo (look for MemoryLimiter:AnonSwap) ==="
    "$ADB" shell dumpsys activity exit-info "$PKG_DEBUG" 2>/dev/null | head -120 \
      || "$ADB" logcat -d -s EwmMemoryLimiter:I EwmMemoryLimiter:W | tail -40
    ;;
  manual-limit)
    need_device
    mb="${1:?mb required}"
    pid="$(pkg_pid)"
    if [[ -z "$pid" ]]; then
      echo "Package not running; launch first." >&2
      exit 1
    fi
    limiter manual "$pid" "$mb"
    ;;
  manual-none)
    need_device
    pid="$(pkg_pid)"
    if [[ -z "$pid" ]]; then
      echo "Package not running." >&2
      exit 1
    fi
    limiter manual "$pid" none
    ;;
  ignore-uid)
    need_device
    uid="$(pkg_uid)"
    if [[ -z "$uid" ]]; then
      echo "Could not resolve uid for $PKG_DEBUG" >&2
      exit 1
    fi
    limiter ignore "$uid"
    ;;
  ignore-none)
    need_device
    limiter ignore none
    ;;
  dry-run)
    need_device || exit 0
    echo "=== status ==="; limiter status || true
    echo "=== meminfo ==="; "$ADB" shell dumpsys meminfo "$PKG_DEBUG" 2>/dev/null | head -40 || true
    echo "=== exits / log tag ==="; "$ADB" logcat -d -s EwmMemoryLimiter | tail -20 || true
    ;;
  trim)
    need_device
    level="${1:?LEVEL required (HIDDEN|BACKGROUND|COMPLETE|...)}"
    pid="$(pkg_pid)"
    if [[ -z "$pid" ]]; then
      echo "Package not running; launch first." >&2
      exit 1
    fi
    echo "send-trim-memory pid=$pid level=$level"
    "$ADB" shell am send-trim-memory "$pid" "$level" || \
      "$ADB" shell am send-trim-memory "$PKG_DEBUG" "$level"
    ;;
  logcat-ewm)
    need_device
    "$ADB" logcat -d -s EwmMemoryLimiter:I EwmMemoryLimiter:W | tail -60
    ;;
  dogfood)
    need_device
    echo "=== dogfood: install-debug ==="
    ./gradlew :app:assembleDebug --max-workers="$MAX_WORKERS"
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
    echo "=== clear logcat + launch ==="
    "$ADB" logcat -c 2>/dev/null || true
    "$ADB" shell am start -n "${PKG_DEBUG}/${ACTIVITY}"
    sleep 3
    echo "=== cold-start EwmMemoryLimiter (exit + Profiling) ==="
    # Prefer tag filter; fall back to grep (some OEM log buffers drop -s early).
    "$ADB" logcat -d -s EwmMemoryLimiter:I EwmMemoryLimiter:W 2>/dev/null | tail -40 \
      || "$ADB" logcat -d 2>/dev/null | grep EwmMemoryLimiter | tail -40 || true
    echo "=== meminfo BEFORE background trim ==="
    "$ADB" shell dumpsys meminfo "$PKG_DEBUG" 2>/dev/null | head -50 || true
    pid="$(pkg_pid)"
    echo "pid=$pid"
    # BACKGROUND/COMPLETE/HIDDEN cannot be forced while process is FOREGROUND.
    echo "=== HOME then send-trim-memory HIDDEN / BACKGROUND / COMPLETE ==="
    "$ADB" shell input keyevent KEYCODE_HOME || true
    sleep 2
    pid="$(pkg_pid)"
    if [[ -n "$pid" ]]; then
      "$ADB" shell am send-trim-memory "$pid" HIDDEN || true
      sleep 1
      "$ADB" shell am send-trim-memory "$pid" BACKGROUND || true
      sleep 1
      "$ADB" shell am send-trim-memory "$pid" COMPLETE || true
    else
      echo "pid empty after HOME (process may have been killed)"
    fi
    sleep 1
    echo "=== meminfo AFTER background trim ==="
    "$ADB" shell dumpsys meminfo "$PKG_DEBUG" 2>/dev/null | head -50 || true
    echo "=== memory-limiter status ==="
    limiter status || true
    echo "=== EwmMemoryLimiter logcat ==="
    "$ADB" logcat -d -s EwmMemoryLimiter:I EwmMemoryLimiter:W 2>/dev/null | tail -40 \
      || "$ADB" logcat -d 2>/dev/null | grep EwmMemoryLimiter | tail -40 || true
    echo "=== exit-info (snippet) ==="
    "$ADB" shell dumpsys activity exit-info "$PKG_DEBUG" 2>/dev/null | head -40 || true
    echo
    echo "Manual residual: open export sheet with multi-select (bounded thumbs),"
    echo "then export batch. Optional: manual-limit 256 and re-export."
    ;;
  checklist)
    cat <<'C'
Manual multi-image export memory checklist (Android 17 limiter)
==============================================================
1. install-debug && launch
2. Pick 5–15 large photos (gallery or system picker)
3. Open export sheet — thumbs must stay snappy (MediaStore bounded)
4. Export JPEG batch; watch logcat: adb logcat -s EwmMemoryLimiter
5. Background app (home); confirm onTrimMemory soft/evict logs in DEBUG
6. Optional: manual-limit <pid> 256  (or tighter) then re-export
7. If process dies: relaunch; cold start should log historical exit with
   [MemoryLimiter:AnonSwap] when applicable
8. dumpsys meminfo before/after BACKGROUND — Java heap / Graphics drop
9. Never expect network dump upload (product privacy)
10. Clear manual limit: manual-none

Notes:
- Limiter may be OEM-disabled; status/manual will no-op friendly.
- Do not kill already-live emulators used for migration unless ordered.
C
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    usage
    exit 2
    ;;
esac
