#!/usr/bin/env bash
# Pin R8 pg-map-id on the unsigned GitHub/Build-packages APK (ADR-0036).
# Fetches fdroid/reproducible-apk-tools at a pinned commit; do not vendor it.
set -euo pipefail

PINNED_PG_MAP_ID=0000000000000000000000000000000000000000000000000000000000000000
TOOLS_REPO=https://gitlab.com/fdroid/reproducible-apk-tools.git
TOOLS_REF=v0.3.2
TOOLS_COMMIT=ca728486d42a79f1c9ec0c6ec755c39f251911a8

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <unsigned-apk-directory>" >&2
  exit 1
fi

if [[ ! -d "$1" ]]; then
  echo "ERROR: not a directory: $1" >&2
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
if ! python3 -c 'import zipfile, sys; zipfile.ZipFile(sys.argv[1]).close()' "${apk}" 2>/dev/null; then
  echo "ERROR: not a zip APK: ${apk}" >&2
  exit 1
fi
echo "Pinning pg-map-id on ${apk}"

tools_dir=""
aligned=""
cleanup() {
  if [[ -n "${aligned}" && -f "${aligned}" ]]; then
    rm -f "${aligned}"
  fi
  if [[ -n "${tools_dir}" && -d "${tools_dir}" ]]; then
    rm -rf "${tools_dir}"
  fi
}
trap cleanup EXIT

tools_dir="$(mktemp -d)"
tools="${tools_dir}/reproducible-apk-tools"
mkdir -p "${tools}"
git -C "${tools}" init -q
git -C "${tools}" remote add origin "${TOOLS_REPO}"
GIT_TERMINAL_PROMPT=0 git -C "${tools}" fetch --depth 1 origin "${TOOLS_COMMIT}"
git -C "${tools}" -c advice.detachedHead=false checkout --detach FETCH_HEAD
got="$(git -C "${tools}" rev-parse HEAD)"
if [[ "${got}" != "${TOOLS_COMMIT}" ]]; then
  echo "ERROR: expected ${TOOLS_REF} commit ${TOOLS_COMMIT}, got ${got}" >&2
  exit 1
fi
if [[ ! -f "${tools}/inplace-fix.py" || ! -f "${tools}/zipalign.py" ]]; then
  echo "ERROR: ${TOOLS_COMMIT} checkout missing inplace-fix.py or zipalign.py" >&2
  exit 1
fi

# 1. fix-pg-map-id in place (unsigned). Do not zipalign here.
python3 "${tools}/inplace-fix.py" fix-pg-map-id "${apk}" "${PINNED_PG_MAP_ID}"

# 2. Python zipalign with apksigner-style extra field; --replace is alignment extra, not in-place.
aligned="$(mktemp "${apk_dir}/ewm-aligned.XXXXXX")"
python3 "${tools}/zipalign.py" --page-size 16 --pad-like-apksigner --replace "${apk}" "${aligned}"
mv -f "${aligned}" "${apk}"
aligned=""

# Fail unless classes.dex has exactly two copies (~~R8 + r8-map-id-).
python3 - "${apk}" "${PINNED_PG_MAP_ID}" <<'PY'
import sys
import zipfile

apk, needle = sys.argv[1], sys.argv[2].encode("ascii")
with zipfile.ZipFile(apk) as zf:
    try:
        data = zf.read("classes.dex")
    except KeyError:
        sys.stderr.write("ERROR: classes.dex missing from APK\n")
        sys.exit(1)
count = data.count(needle)
if count != 2:
    sys.stderr.write(
        "ERROR: classes.dex contains %d copies of pinned pg-map-id, expected exactly 2 (~~R8 + r8-map-id-)\n"
        % count
    )
    sys.exit(1)
print("OK: classes.dex contains exactly 2 copies of pinned pg-map-id")
PY
