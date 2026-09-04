# Resolve the rosuH/goldie checkout. Source from EWM capture wrappers.
# Caller must set ROOT to the EasyWatermark repo.
goldie_home() {
  if [ -n "${GOLDIE_HOME:-}" ] && [ -d "${GOLDIE_HOME}/scripts" ]; then
    printf '%s\n' "$GOLDIE_HOME"
    return 0
  fi
  if [ -d "${ROOT}/../goldie/scripts" ]; then
    (CDPATH= cd -- "${ROOT}/../goldie" && pwd)
    return 0
  fi
  echo "FAIL set GOLDIE_HOME to the rosuH/goldie checkout (expected scripts/pad-posters.py)" >&2
  return 1
}

resolve_goldie_engine() {
  GOLDIE_HOME="$(goldie_home)"
  export GOLDIE_HOME
  export GOLDIE_APP_ROOT="${GOLDIE_APP_ROOT:-$ROOT}"
}
