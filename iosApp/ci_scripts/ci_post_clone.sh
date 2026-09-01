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

echo "ci_post_clone: done $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "ci_post_clone: set Xcode Cloud workflow JAVA_HOME to ${JAVA_HOME}"
