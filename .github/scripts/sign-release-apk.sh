#!/usr/bin/env bash
# Sign the pinned unsigned GitHub/Build-packages APK (ADR-0036 step 3).
# Secrets: SIGNING_KEY (base64), ALIAS, KEY_STORE_PASSWORD, KEY_PASSWORD.
set -euo pipefail

BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-37.0.0}"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <unsigned-apk-directory>" >&2
  exit 1
fi

if [[ ! -d "$1" ]]; then
  echo "ERROR: not a directory: $1" >&2
  exit 1
fi

: "${SIGNING_KEY:?SIGNING_KEY is required}"
: "${ALIAS:?ALIAS is required}"
: "${KEY_STORE_PASSWORD:?KEY_STORE_PASSWORD is required}"
: "${KEY_PASSWORD:?KEY_PASSWORD is required}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?ANDROID_HOME or ANDROID_SDK_ROOT is required}"

build_tools="${ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}"
apksigner="${build_tools}/apksigner"
zipalign="${build_tools}/zipalign"
if [[ ! -f "${apksigner}" ]]; then
  echo "ERROR: apksigner not found: ${apksigner}" >&2
  exit 1
fi
if [[ ! -f "${zipalign}" ]]; then
  echo "ERROR: zipalign not found: ${zipalign}" >&2
  exit 1
fi

apk_dir="$(cd -- "$1" && pwd)"
apks=()
for f in "$apk_dir"/*.apk; do
  [[ -f "$f" ]] || continue
  [[ "$f" == *-signed.apk ]] && continue
  apks+=("$f")
done
if [[ ${#apks[@]} -ne 1 ]]; then
  echo "ERROR: expected exactly one unsigned APK in ${apk_dir}, found ${#apks[@]}" >&2
  exit 1
fi
apk="${apks[0]}"
signed="${apk%.apk}-signed.apk"

jks=""
cleanup() {
  if [[ -n "${jks}" && -f "${jks}" ]]; then
    rm -f "${jks}"
  fi
}
trap cleanup EXIT

jks="$(mktemp)"
if ! printf '%s' "${SIGNING_KEY}" | base64 --decode >"${jks}"; then
  echo "ERROR: failed to decode SIGNING_KEY as base64" >&2
  exit 1
fi

"${apksigner}" sign \
  --alignment-preserved true \
  --ks "${jks}" \
  --ks-key-alias "${ALIAS}" \
  --ks-pass "pass:${KEY_STORE_PASSWORD}" \
  --key-pass "pass:${KEY_PASSWORD}" \
  --out "${signed}" \
  "${apk}"

rm -f "${jks}"
jks=""

"${apksigner}" verify "${signed}"
"${zipalign}" -c -P 16 -v 4 "${signed}"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'signedReleaseFile=%s\n' "${signed}" >>"${GITHUB_OUTPUT}"
fi
echo "Signed ${signed}"
