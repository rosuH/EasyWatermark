#!/bin/sh
# Confirm JDK 17 is on PATH before xcodebuild runs the Kotlin embedAndSign phase.
set -eu

echo "ci_pre_xcodebuild: start $(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ -n "${CI_DERIVED_DATA_PATH:-}" ] && [ -x "${CI_DERIVED_DATA_PATH}/JDK/Home/bin/java" ]; then
  export JAVA_HOME="${CI_DERIVED_DATA_PATH}/JDK/Home"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

echo "ci_pre_xcodebuild: JAVA_HOME=${JAVA_HOME:-unset}"
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  echo "error: java not found. ci_post_clone.sh must install JDK 17, or set workflow JAVA_HOME=/Volumes/workspace/DerivedData/JDK/Home" >&2
  exit 1
fi
"${JAVA_HOME}/bin/java" -version

echo "ci_pre_xcodebuild: done $(date -u +%Y-%m-%dT%H:%M:%SZ)"
