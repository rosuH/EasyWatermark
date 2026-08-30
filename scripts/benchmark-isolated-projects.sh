#!/usr/bin/env bash
# ROS-100: compare Gradle configuration / assemble with and without Isolated Projects.
# Usage: scripts/benchmark-isolated-projects.sh [outdir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-"$ROOT/build/isolated-projects-bench"}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export JAVA_HOME
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$OUT"
cd "$ROOT"

# Daemon stays up within a mode so config-cache hits are realistic.
# Stop between modes so Isolated Projects / baseline do not share a tainted daemon.
GRADLEW=(./gradlew --max-workers=8 --console=plain)
# Drop noisy progress; keep BUILD SUCCESSFUL / configuration-cache / isolated lines.
FILTER='BUILD SUCCESSFUL|BUILD FAILED|Configuration cache|Isolated projects|Calculating task graph|Reusing configuration cache|Configuring|FAILURE|problems were found'

log() { printf '%s\n' "$*" | tee -a "$OUT/run.log"; }

run_one() {
  local mode="$1"   # baseline | isolated
  local scenario="$2" # help-miss | help-hit | dryrun-miss | assemble-miss | assemble-hit
  local idx="$3"
  local extra_flag
  case "$mode" in
    baseline) extra_flag="--no-isolated-projects" ;;
    isolated) extra_flag="--isolated-projects" ;;
    *) echo "bad mode: $mode" >&2; return 1 ;;
  esac

  local task_args=()
  case "$scenario" in
    help-miss|help-hit) task_args=(help) ;;
    dryrun-miss) task_args=(:app:assembleDebug --dry-run) ;;
    assemble-miss|assemble-hit) task_args=(:app:assembleDebug) ;;
    *) echo "bad scenario: $scenario" >&2; return 1 ;;
  esac

  if [[ "$scenario" == *-miss ]]; then
    rm -rf "$ROOT/.gradle/configuration-cache" "$ROOT/.gradle/configuration-cache-report"
  fi

  local stamp
  stamp="$(date +%Y%m%dT%H%M%S)"
  local raw="$OUT/${mode}-${scenario}-${idx}-${stamp}.log"
  local start end elapsed
  start="$(date +%s.%N)"
  set +e
  "${GRADLEW[@]}" "$extra_flag" "${task_args[@]}" >"$raw" 2>&1
  local rc=$?
  set -e
  end="$(date +%s.%N)"
  elapsed="$(awk -v s="$start" -v e="$end" 'BEGIN { printf "%.3f", e - s }')"

  local gradle_line
  gradle_line="$(grep -E 'BUILD (SUCCESSFUL|FAILED) in' "$raw" | tail -n1 || true)"
  local cc_line
  cc_line="$(grep -E 'Configuration cache entry|Reusing configuration cache|Calculating task graph' "$raw" | tail -n1 || true)"
  local problems
  problems="$(grep -E 'problems were found|Isolated Projects' "$raw" | head -n5 || true)"

  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$mode" "$scenario" "$idx" "$elapsed" "$rc" "$gradle_line" \
    | tee -a "$OUT/summary.tsv"
  {
    echo "---- $mode $scenario #$idx  wall=${elapsed}s  rc=$rc"
    echo "$gradle_line"
    echo "$cc_line"
    echo "$problems"
  } | tee -a "$OUT/run.log"

  grep -E "$FILTER" "$raw" >>"$OUT/filtered.log" || true
  return 0
}

{
  echo "mode	scenario	idx	wall_s	rc	gradle"
} >"$OUT/summary.tsv"
: >"$OUT/run.log"
: >"$OUT/filtered.log"

log "JAVA_HOME=$JAVA_HOME"
log "ANDROID_HOME=$ANDROID_HOME"
log "started $(date -Is)"

# Warm wrapper + dependency caches once (not timed).
log "warmup: help --no-isolated-projects"
"${GRADLEW[@]}" --no-isolated-projects help >"$OUT/warmup-help.log" 2>&1 || true

for mode in baseline isolated; do
  ./gradlew --stop >/dev/null 2>&1 || true
  for i in 1 2 3; do
    run_one "$mode" help-miss "$i"
    run_one "$mode" help-hit "$i"
  done
  run_one "$mode" dryrun-miss 1
done

# Full assemble: one warmup then miss/hit per mode.
log "warmup: assembleDebug --no-isolated-projects"
"${GRADLEW[@]}" --no-isolated-projects :app:assembleDebug >"$OUT/warmup-assemble.log" 2>&1 || true

for mode in baseline isolated; do
  ./gradlew --stop >/dev/null 2>&1 || true
  run_one "$mode" assemble-miss 1
  run_one "$mode" assemble-hit 1
done

log "finished $(date -Is)"
log "summary: $OUT/summary.tsv"
