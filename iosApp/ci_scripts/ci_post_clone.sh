#!/bin/sh
# Xcode Cloud post-clone: install JDK 17 so the iosApp Run Script can run
# `./gradlew :shared:embedAndSignAppleFrameworkForXcode`.
# Cloud images have no JDK; Homebrew + sudo is not available.
# Workflow JAVA_HOME should be /Volumes/workspace/DerivedData/JDK/Home
# (the Run Script also falls back to $CI_DERIVED_DATA_PATH/JDK/Home).
set -eu

echo "ci_post_clone: start $(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ -z "${CI_DERIVED_DATA_PATH:-}" ]; then
  echo "error: CI_DERIVED_DATA_PATH is unset (not Xcode Cloud?)" >&2
  exit 1
fi

jdk_dir="${CI_DERIVED_DATA_PATH}/JDK"
java_bin="${jdk_dir}/Home/bin/java"

install_jdk() {
  arch=$(uname -m)
  if [ "$arch" = "arm64" ]; then
    adoptium_arch="aarch64"
  else
    adoptium_arch="x64"
  fi
  url="https://api.adoptium.net/v3/binary/latest/17/ga/mac/${adoptium_arch}/jdk/hotspot/normal/eclipse?project=jdk"
  echo "ci_post_clone: downloading Temurin 17 (${adoptium_arch})"
  curl -L --fail --retry 3 --retry-delay 2 --progress-bar \
    -o /tmp/jdk17.tar.gz "$url"

  echo "ci_post_clone: extracting JDK"
  rm -rf /tmp/jdk17-extract
  mkdir -p /tmp/jdk17-extract
  tar -xzf /tmp/jdk17.tar.gz -C /tmp/jdk17-extract

  home=$(find /tmp/jdk17-extract -type d -path '*/Contents/Home' | head -n 1)
  if [ -z "$home" ]; then
    java_found=$(find /tmp/jdk17-extract -type f -path '*/bin/java' | head -n 1)
    if [ -n "$java_found" ]; then
      home=$(dirname "$(dirname "$java_found")")
    fi
  fi
  if [ -z "$home" ] || [ ! -x "${home}/bin/java" ]; then
    echo "error: could not find JDK Home inside Adoptium tarball" >&2
    find /tmp/jdk17-extract | head -n 50 >&2
    exit 1
  fi

  rm -rf "${jdk_dir}"
  mkdir -p "${jdk_dir}"
  mv "$home" "${jdk_dir}/Home"
  rm -rf /tmp/jdk17-extract /tmp/jdk17.tar.gz
}

if [ -x "$java_bin" ]; then
  echo "ci_post_clone: reusing JDK at ${jdk_dir}/Home"
else
  install_jdk
fi

export JAVA_HOME="${jdk_dir}/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "ci_post_clone: JAVA_HOME=${JAVA_HOME}"
java -version

repo_root="${CI_PRIMARY_REPOSITORY_PATH:-}"
if [ -n "$repo_root" ] && [ -f "${repo_root}/gradlew" ]; then
  chmod +x "${repo_root}/gradlew"
fi

# Xcode Cloud often cannot reach dl.google.com during the later xcodebuild Gradle
# invocation ("No route to host"). Prefetch Kotlin/Native + Compose iOS artifacts
# here, single-threaded, with retries, into DerivedData so embedAndSign can reuse them.
#
# Two limits keep a stalled prefetch from hitting Xcode Cloud's step watchdog
# ("No activity has been detected on stdout or stderr in 15 minutes"):
# - Heartbeat: while Gradle runs, a status line is printed every
#   EWM_PREFETCH_HEARTBEAT_SECONDS (60 s) so a slow but live run is never killed
#   by Xcode Cloud. It reports elapsed/idle time, Gradle CPU time and cache sizes,
#   so the log can tell a slow run from a stuck one.
# - Stall limit: an attempt is killed (whole process tree) and retried when for
#   EWM_PREFETCH_STALL_SECONDS (600 s) it shows no progress: no new output, under
#   EWM_PREFETCH_CPU_PROGRESS_SECONDS of CPU per poll, and no growth of
#   GRADLE_USER_HOME or the Kotlin/Native data dir. A healthy cold run compiles
#   (CPU) or downloads (growth), so silence alone does not trip it. A hard cap of
#   EWM_PREFETCH_ATTEMPT_MAX_SECONDS (2400 s, cold Archive runs take ~11 min)
#   bounds any single attempt. The thread dump printed before a kill is
#   abandoned after EWM_PREFETCH_DUMP_SECONDS (30 s): jstack can hang on a
#   stalled JVM.
# Gradle's own retries stay quiet below the stall limit. In Gradle 9.7 dependency
# resolution tries a repository request org.gradle.internal.repository.max.tentatives
# times (3, pinned below), backing off 1 s then 2 s, then disables that repository
# for the build. With 60 s connect/read timeouts one unreachable repository is
# quiet for about 3 min (up to about 6 min if each try connects, then stalls on
# a read).
export GRADLE_USER_HOME="${CI_DERIVED_DATA_PATH}/.gradle"
mkdir -p "$GRADLE_USER_HOME"
konan_dir="${KONAN_DATA_DIR:-${HOME}/.konan}"

stall_limit="${EWM_PREFETCH_STALL_SECONDS:-600}"
attempt_limit="${EWM_PREFETCH_ATTEMPT_MAX_SECONDS:-2400}"
heartbeat="${EWM_PREFETCH_HEARTBEAT_SECONDS:-60}"
poll="${EWM_PREFETCH_POLL_SECONDS:-15}"
cpu_progress="${EWM_PREFETCH_CPU_PROGRESS_SECONDS:-1}"
retry_sleep_unit="${EWM_PREFETCH_RETRY_SLEEP_SECONDS:-20}"
dump_limit="${EWM_PREFETCH_DUMP_SECONDS:-30}"

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/ewm-prefetch.XXXXXX")
gradle_pid=""
tail_pid=""
dump_pid=""

# Print a pid and all of its descendants.
tree_pids() {
  echo "$1"
  for _child in $(pgrep -P "$1" 2>/dev/null || true); do
    tree_pids "$_child"
  done
}

# Gradle's process tree, plus the detached Kotlin compile daemon on Xcode Cloud
# (only there: on a dev machine it may belong to another build).
watched_pids() {
  tree_pids "$1"
  if [ -n "${CI_BUILD_ID:-}" ]; then
    pgrep -f KotlinCompileDaemon 2>/dev/null || true
  fi
}

# Total CPU seconds of the given pids (ps TIME is [[dd-]hh:]mm:ss.cc).
cpu_seconds() {
  _list=$(echo "$*" | tr ' ' ',')
  if [ -z "$_list" ]; then
    echo 0
    return 0
  fi
  ps -o time= -p "$_list" 2>/dev/null | awk '
    { n = split($1, a, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + a[i]; t += s }
    END { printf "%.1f\n", t + 0 }'
}

dir_kb() {
  if [ -d "$1" ]; then
    du -sk "$1" 2>/dev/null | awk '{ print $1 + 0 }'
  else
    echo 0
  fi
}

file_bytes() {
  wc -c < "$1" 2>/dev/null | tr -d ' ' || echo 0
}

# Short thread dump of the Gradle JVMs, so a stall log shows where it hung.
dump_threads() {
  jstack_bin="${JAVA_HOME}/bin/jstack"
  [ -x "$jstack_bin" ] || return 0
  for _p in $(watched_pids "$1"); do
    case "$(ps -o comm= -p "$_p" 2>/dev/null)" in
      *java*) ;;
      *) continue ;;
    esac
    echo "ci_post_clone: threads of java pid ${_p} (main/worker threads, truncated)"
    "$jstack_bin" "$_p" 2>/dev/null | awk '
      BEGIN { RS = "" }
      /^"(main|Daemon worker|Execution worker|Build operations|included builds)/ {
        n = split($0, l, "\n"); for (i = 1; i <= n && i <= 25; i++) print l[i]; print ""
      }' | head -n 150 || true
  done
}

# Run dump_threads in the background and give up on it after dump_limit
# seconds; jstack has no timeout of its own.
dump_threads_bounded() {
  dump_threads "$1" &
  dump_pid=$!
  _dumped=0
  while kill -0 "$dump_pid" 2>/dev/null && [ "$_dumped" -lt "$dump_limit" ]; do
    sleep 1
    _dumped=$((_dumped + 1))
  done
  if kill -0 "$dump_pid" 2>/dev/null; then
    echo "ci_post_clone: thread dump abandoned after ${dump_limit}s"
    kill_dump
  fi
  wait "$dump_pid" 2>/dev/null || true
  dump_pid=""
}

# Kill the dump subshell and its jstack/awk/head children.
kill_dump() {
  if [ -n "$dump_pid" ]; then
    # shellcheck disable=SC2046
    kill -KILL $(tree_pids "$dump_pid") 2>/dev/null || true
  fi
}

kill_tree() {
  _pids=$(watched_pids "$1")
  # shellcheck disable=SC2086
  kill -TERM $_pids 2>/dev/null || true
  _waited=0
  while [ "$_waited" -lt 10 ] && kill -0 "$1" 2>/dev/null; do
    sleep 1
    _waited=$((_waited + 1))
  done
  # shellcheck disable=SC2086
  kill -KILL $_pids 2>/dev/null || true
}

cleanup() {
  kill_dump
  if [ -n "$gradle_pid" ] && kill -0 "$gradle_pid" 2>/dev/null; then
    kill_tree "$gradle_pid"
  fi
  if [ -n "$tail_pid" ]; then
    kill "$tail_pid" 2>/dev/null || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Run one Gradle attempt under the heartbeat and stall watchdog.
# Returns Gradle's exit code, or 124 when the watchdog killed it.
run_gradle_attempt() {
  label="$1"
  shift
  log="${work_dir}/gradle-${label%%/*}.log"
  : > "$log"
  "$@" > "$log" 2>&1 &
  gradle_pid=$!
  tail -n +1 -f "$log" &
  tail_pid=$!

  started=$(date +%s)
  last_progress=$started
  last_beat=$started
  last_bytes=0
  last_cpu=0
  home_kb=$(dir_kb "$GRADLE_USER_HOME")
  konan_kb=$(dir_kb "$konan_dir")
  last_kb=$((home_kb + konan_kb))
  verdict=""

  while kill -0 "$gradle_pid" 2>/dev/null; do
    sleep "$poll"
    kill -0 "$gradle_pid" 2>/dev/null || break
    now=$(date +%s)

    bytes=$(file_bytes "$log")
    # shellcheck disable=SC2046
    cpu=$(cpu_seconds $(watched_pids "$gradle_pid"))
    progressed=""
    if [ "$bytes" != "$last_bytes" ] \
      || awk -v c="$cpu" -v l="$last_cpu" -v t="$cpu_progress" 'BEGIN { exit !(c - l >= t) }'; then
      progressed=1
    fi
    # Cache sizes (a large ~/.konan takes seconds to scan) only when output and
    # CPU are flat, or for the heartbeat line.
    if [ -z "$progressed" ] || [ $((now - last_beat)) -ge "$heartbeat" ]; then
      home_kb=$(dir_kb "$GRADLE_USER_HOME")
      konan_kb=$(dir_kb "$konan_dir")
      kb=$((home_kb + konan_kb))
      [ "$kb" != "$last_kb" ] && progressed=1
      last_kb=$kb
    fi
    if [ -n "$progressed" ]; then
      last_progress=$now
    fi
    last_bytes=$bytes
    last_cpu=$cpu

    elapsed=$((now - started))
    idle=$((now - last_progress))
    if [ $((now - last_beat)) -ge "$heartbeat" ]; then
      echo "ci_post_clone: heartbeat attempt ${label} elapsed=${elapsed}s idle=${idle}s cpu=${cpu}s log=${bytes}B gradle_home=$((home_kb / 1024))MB konan=$((konan_kb / 1024))MB"
      last_beat=$now
    fi

    if [ "$idle" -ge "$stall_limit" ]; then
      verdict="stalled: no output, CPU or cache growth for ${idle}s"
    elif [ "$elapsed" -ge "$attempt_limit" ]; then
      verdict="over the ${attempt_limit}s attempt budget"
    fi
    if [ -n "$verdict" ]; then
      echo "ci_post_clone: attempt ${label} ${verdict}; killing Gradle process tree"
      dump_threads_bounded "$gradle_pid"
      kill_tree "$gradle_pid"
      break
    fi
  done

  if wait "$gradle_pid"; then
    rc=0
  else
    rc=$?
  fi
  gradle_pid=""
  sleep 1
  kill "$tail_pid" 2>/dev/null || true
  wait "$tail_pid" 2>/dev/null || true
  tail_pid=""
  if [ -n "$verdict" ]; then
    rc=124
  fi
  return "$rc"
}

prefetch_ios_deps() {
  if [ -z "$repo_root" ] || [ ! -x "${repo_root}/gradlew" ]; then
    echo "ci_post_clone: skip gradle prefetch (no gradlew)"
    return 0
  fi
  cd "$repo_root"
  echo "ci_post_clone: prefetch limits stall=${stall_limit}s attempt=${attempt_limit}s heartbeat=${heartbeat}s poll=${poll}s"
  attempt=1
  max=6
  while [ "$attempt" -le "$max" ]; do
    echo "ci_post_clone: gradle prefetch ${attempt}/${max} $(date -u +%H:%M:%SZ)"
    if run_gradle_attempt "${attempt}/${max}" \
      ./gradlew :shared:compileKotlinIosArm64 \
      --no-daemon \
      --max-workers=1 \
      --console=plain \
      -Dorg.gradle.internal.http.connectionTimeout=60000 \
      -Dorg.gradle.internal.http.socketTimeout=60000 \
      -Dorg.gradle.internal.repository.max.tentatives=3; then
      echo "ci_post_clone: gradle prefetch ok"
      return 0
    else
      rc=$?
    fi
    echo "ci_post_clone: prefetch attempt ${attempt} failed (exit ${rc})"
    if [ "$attempt" -lt "$max" ]; then
      echo "ci_post_clone: sleeping $((attempt * retry_sleep_unit))s before retry"
      sleep $((attempt * retry_sleep_unit))
    fi
    attempt=$((attempt + 1))
  done
  echo "error: gradle prefetch failed after ${max} attempts" >&2
  return 1
}

prefetch_ios_deps

echo "ci_post_clone: done $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "ci_post_clone: set Xcode Cloud workflow JAVA_HOME to ${JAVA_HOME}"
