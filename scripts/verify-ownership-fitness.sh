#!/usr/bin/env bash
# E3 ownership fitness gates (issue 12 P4 + issue 35 + L0 evidence-independence).
# Fail-closed: never mask ownership regressions with `2>/dev/null || true`.
# Residuals are enforced by source checks only — no evidence-file backend.
# rg exit: 0 = matches, 1 = no match (OK for "must be absent"), 2+ = tool failure (always FAIL the gate).
#
# IMPORTANT: never call fail() from inside $(...) — subshell exit would collapse to outer 1
# and be misread as "no match". Hard failures must run in the main shell.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
ok() { printf 'OK: %s\n' "$1"; }

# Populate RG_OUT / RG_RC. On rg exit >=2, terminate the whole gate (main shell).
rg_scan() {
  set +e
  RG_OUT="$(rg "$@" 2>&1)"
  RG_RC=$?
  set -e
  if (( RG_RC >= 2 )); then
    printf '%s\n' "${RG_OUT}" >&2
    fail "rg failed with exit ${RG_RC} (pattern/path error — not a clean no-match)"
  fi
}

if [[ "${OWNERSHIP_FITNESS_ADVERSARIAL_ONLY:-0}" == "1" ]]; then
  rg_scan -n 'ownership_fitness_adversarial' \
    "${ROOT}/.__ownership_fitness_missing_path_do_not_create__"
  fail "adversarial missing-path scan unexpectedly continued"
fi

echo "=== E3 ownership fitness ($(date -u +%Y-%m-%dT%H:%MZ)) ==="

# --- G0: adversarial — the complete gate must reject an rg path error ---
set +e
ADV_OUT="$(OWNERSHIP_FITNESS_ADVERSARIAL_ONLY=1 "$0" 2>&1)"
ADV_RC=$?
set -e
if (( ADV_RC != 1 )); then
  printf '%s\n' "${ADV_OUT}" >&2
  fail "adversarial: expected the child gate to fail with exit 1, got ${ADV_RC}"
fi
if [[ "${ADV_OUT}" != *"rg failed with exit 2"* ]]; then
  printf '%s\n' "${ADV_OUT}" >&2
  fail "adversarial: child gate did not report the rg path error"
fi
ok "G0 adversarial missing-path makes the complete gate fail"

# --- G1: no parallel product-route host owner ---
rg_scan -n 'var productRoute by|mutableStateOf\(ProductShellNav\.Route' \
  app/src/main desktopApp/src shared/src/iosMain iosApp/iosApp \
  --glob '!**/*Test*' --glob '!**/*test*'
if (( RG_RC == 0 )); then
  printf '%s\n' "${RG_OUT}" >&2
  fail "host productRoute owner reintroduced"
fi
ok "G1 no host productRoute mirror"

# --- G2: no selectedSessionImage production mirror ---
rg_scan -n 'var selectedSessionImage|selectedSessionImage\s*=' \
  app/src/main desktopApp/src shared/src/iosMain iosApp/iosApp \
  --glob '!**/*Test*' --glob '!**/*test*'
if (( RG_RC == 0 )); then
  printf '%s\n' "${RG_OUT}" >&2
  fail "selectedSessionImage production mirror reintroduced"
fi
ok "G2 no selectedSessionImage"

# --- G3: legacy WaterMarkOffsetUpdateTest must be gone ---
if [[ -f shared/src/desktopTest/kotlin/me/rosuh/easywatermark/data/repo/WaterMarkOffsetUpdateTest.kt ]]; then
  fail "WaterMarkOffsetUpdateTest still present; SessionOffsetIdentityTest is the contract"
fi
rg_scan -n 'class WaterMarkOffsetUpdateTest' shared/src/desktopTest --glob '*.kt'
if (( RG_RC == 0 )); then
  printf '%s\n' "${RG_OUT}" >&2
  fail "WaterMarkOffsetUpdateTest class still present"
fi
ok "G3 WaterMarkOffsetUpdateTest retired"

# --- G4: product hosts must not call repo transient list/select/offset ---
rg_scan -n 'waterMarkRepo\.(updateOffset|updateImageList|select|imageInfoList|selectedImage)|configEditor\.updateOffset' \
  app/src/main/java desktopApp/src shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui \
  --glob '*.kt' --glob '*.swift'
if (( RG_RC == 0 )); then
  printf '%s\n' "${RG_OUT}" >&2
  fail "production host still calls repo transient list/select/offset API"
fi
ok "G4 host repo transient callers = 0"

# --- G5: product path must not call updateOffset on repo (Session applyOffset only) ---
rg_scan -n 'waterMarkRepo\.updateOffset|configEditor\.updateOffset' \
  shared/src app/src/main desktopApp/src iosApp \
  --glob '*.kt' --glob '!**/*Test*' --glob '!**/*test*'
if (( RG_RC == 0 )); then
  printf '%s\n' "${RG_OUT}" >&2
  fail "product path still routes offset through repo"
fi
ok "G5 product offset path is Session applyOffset (no repo.updateOffset callers)"

# --- G6: all production source — transient list/select/offset only in WatermarkSessionViewModel ---
rg_scan -n 'waterMarkRepo\.(updateImageList|select|imageInfoList|selectedImage|updateOffset)' \
  shared/src/commonMain shared/src/androidMain shared/src/desktopMain shared/src/iosMain \
  app/src/main desktopApp/src iosApp/iosApp \
  --glob '*.kt' --glob '*.swift' \
  --glob '!**/*Test*' --glob '!**/*test*' --glob '!**/*Tests*'
if (( RG_RC == 0 )); then
  set +e
  OUTSIDE="$(printf '%s\n' "${RG_OUT}" | rg -v 'session/WatermarkSessionViewModel\.kt')"
  OUTSIDE_RC=$?
  set -e
  if (( OUTSIDE_RC >= 2 )); then
    fail "rg filter failed while checking G6 confinement"
  fi
  if (( OUTSIDE_RC == 0 )) && [[ -n "${OUTSIDE// }" ]]; then
    printf '%s\n' "${RG_OUT}" >&2
    fail "repo transient list/select/offset callers outside WatermarkSessionViewModel.kt"
  fi
  ok "G6 dual-write residual confined to WatermarkSessionViewModel (full production scan)"
else
  ok "G6 no production repo transient callers (residual cleared)"
fi

# --- G7: focused Session unit gates ---
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home}"
export PATH="${JAVA_HOME}/bin:${PATH}"

./gradlew :shared:desktopTest \
  --tests 'me.rosuh.easywatermark.session.SessionReducerTest' \
  --tests 'me.rosuh.easywatermark.session.OffsetExportOrderingTest' \
  --tests 'me.rosuh.easywatermark.session.WatermarkSessionViewModelTest' \
  --tests 'me.rosuh.easywatermark.session.SessionOffsetIdentityTest' \
  --max-workers=8

./gradlew :app:testDebugUnitTest \
  --tests 'me.rosuh.easywatermark.session.*' \
  --max-workers=8

ok "G7 Session unit gates green"

echo "=== OWNERSHIP_FITNESS_PASS (source-checked; fail-closed; no evidence-file backend) ==="
exit 0
