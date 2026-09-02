#!/bin/sh
# Copy shared/PrivacyInfo.xcprivacy into Shared.framework.
# Apple requires each executable / dylib that uses required-reason APIs to ship
# its own manifest. The app target already has iosApp/PrivacyInfo.xcprivacy in
# Resources; CMP's Shared.framework does not inherit that file.
set -eu

repo_root="${SRCROOT:-}/.."
if [ ! -f "${repo_root}/shared/PrivacyInfo.xcprivacy" ]; then
  repo_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
fi

privacy_src="${repo_root}/shared/PrivacyInfo.xcprivacy"
if [ ! -f "$privacy_src" ]; then
  echo "error: missing $privacy_src" >&2
  exit 1
fi

copied=0
copy_into() {
  dest_dir="$1"
  if [ -d "$dest_dir" ]; then
    cp -f "$privacy_src" "${dest_dir}/PrivacyInfo.xcprivacy"
    echo "note: copied PrivacyInfo.xcprivacy into ${dest_dir}"
    copied=1
  fi
}

configuration="${CONFIGURATION:-}"
sdk_name="${SDK_NAME:-}"
if [ -n "$configuration" ] && [ -n "$sdk_name" ]; then
  copy_into "${repo_root}/shared/build/xcode-frameworks/${configuration}/${sdk_name}/Shared.framework"
fi

# Cover a prebuilt / already-embedded framework when embedAndSign was skipped.
if [ -n "${TARGET_BUILD_DIR:-}" ] && [ -n "${FRAMEWORKS_FOLDER_PATH:-}" ]; then
  copy_into "${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}/Shared.framework"
fi

if [ "$copied" -eq 0 ]; then
  echo "error: Shared.framework not found; PrivacyInfo.xcprivacy was not embedded" >&2
  echo "  looked under shared/build/xcode-frameworks/${configuration:-?}/${sdk_name:-?}/Shared.framework" >&2
  if [ -n "${TARGET_BUILD_DIR:-}" ] && [ -n "${FRAMEWORKS_FOLDER_PATH:-}" ]; then
    echo "  and ${TARGET_BUILD_DIR}/${FRAMEWORKS_FOLDER_PATH}/Shared.framework" >&2
  fi
  exit 1
fi
