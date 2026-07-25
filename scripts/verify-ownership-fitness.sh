#!/usr/bin/env bash
# E3 ownership fitness gates (issue 12 P4 + issue 35).
# Fail-closed: never uses `|| true` to mask ownership regressions.
# Residual dual-write is allowed only when documented in evidence/e3/residuals.md
# and host-side repo list callers remain zero.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
ok() { printf 'OK: %s\n' "$1"; }

echo "=== E3 ownership fitness ($(date -u +%Y-%m-%dT%H:%MZ)) ==="

# --- G1: no parallel product-route host owner ---
if rg -n 'var productRoute by|mutableStateOf\(ProductShellNav\.Route' \
  app/src/main desktopApp/src shared/src/iosMain iosApp/iosApp \
  --glob '!**/*Test*' --glob '!**/*test*' 2>/dev/null; then
  fail "host productRoute owner reintroduced"
fi
ok "G1 no host productRoute mirror"

# --- G2: no selectedSessionImage production mirror ---
if rg -n 'var selectedSessionImage|selectedSessionImage\s*=' \
  app/src/main desktopApp/src shared/src/iosMain iosApp/iosApp \
  --glob '!**/*Test*' --glob '!**/*test*' 2>/dev/null; then
  fail "selectedSessionImage production mirror reintroduced"
fi
ok "G2 no selectedSessionImage"

# --- G3: legacy WaterMarkOffsetUpdateTest must be gone ---
if [[ -f shared/src/desktopTest/kotlin/me/rosuh/easywatermark/data/repo/WaterMarkOffsetUpdateTest.kt ]]; then
  fail "WaterMarkOffsetUpdateTest still present; SessionOffsetIdentityTest is the contract"
fi
if rg -n 'class WaterMarkOffsetUpdateTest' shared/src/desktopTest --glob '*.kt' 2>/dev/null; then
  fail "WaterMarkOffsetUpdateTest class still present"
fi
ok "G3 WaterMarkOffsetUpdateTest retired"

# --- G4: product hosts must not call repo transient list/select/offset ---
HOST_HITS="$(
  rg -n 'waterMarkRepo\.(updateOffset|updateImageList|select|imageInfoList|selectedImage)|configEditor\.updateOffset' \
    app/src/main/java desktopApp/src shared/src/iosMain/kotlin/me/rosuh/easywatermark/ui \
    --glob '*.kt' --glob '*.swift' 2>/dev/null || true
)"
# `|| true` only for empty capture into variable — non-empty HOST_HITS still fails below.
if [[ -n "${HOST_HITS}" ]]; then
  printf '%s\n' "${HOST_HITS}" >&2
  fail "production host still calls repo transient list/select/offset API"
fi
ok "G4 host repo transient callers = 0"

# --- G5: product path must not call updateOffset on repo (Session applyOffset only) ---
# Hosts may call viewModel.updateOffset / session.applyOffset; never waterMarkRepo/configEditor.
OFFSET_HITS="$(
  rg -n 'waterMarkRepo\.updateOffset|configEditor\.updateOffset' \
    shared/src app/src/main desktopApp/src iosApp \
    --glob '*.kt' --glob '!**/*Test*' --glob '!**/*test*' 2>/dev/null || true
)"
if [[ -n "${OFFSET_HITS// }" ]]; then
  printf '%s\n' "${OFFSET_HITS}" >&2
  fail "product path still routes offset through repo"
fi
ok "G5 product offset path is Session applyOffset (no repo.updateOffset callers)"

# --- G6: residual inventory must exist for remaining Session dual-write ---
RESIDUALS=".scratch/easywatermark-kmp-cmp-migration/evidence/e3/residuals.md"
[[ -f "${RESIDUALS}" ]] || fail "missing residual inventory ${RESIDUALS}"
if ! rg -q 'WatermarkSessionViewModel' "${RESIDUALS}"; then
  fail "residuals.md must document Session dual-write residual"
fi
if ! rg -q 'WaterMarkRepository' "${RESIDUALS}"; then
  fail "residuals.md must document WaterMarkRepository transient API residual"
fi
# Ensure residual Session callers still only live under session package (not hosts)
SESSION_ONLY="$(
  rg -n 'waterMarkRepo\.(updateImageList|select|imageInfoList|selectedImage)' \
    shared/src/commonMain/kotlin \
    --glob '*.kt' 2>/dev/null || true
)"
if printf '%s\n' "${SESSION_ONLY}" | rg -v 'session/WatermarkSessionViewModel\.kt' | rg -q .; then
  printf '%s\n' "${SESSION_ONLY}" >&2
  fail "repo transient callers outside WatermarkSessionViewModel"
fi
ok "G6 residual inventory present; dual-write confined to Session VM"

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

echo "=== OWNERSHIP_FITNESS_PASS (residuals documented in evidence/e3/residuals.md) ==="
exit 0
