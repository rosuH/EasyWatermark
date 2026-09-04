#!/usr/bin/env bash
# G3 lifecycle / fault matrix runner (issue 40 / issue 13 §G3).
# Fail-closed: no silent skips. Residuals are documented in evidence/g3/matrix.md only.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home}"
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "=== G3 fault matrix suites ($(date -u +%Y-%m-%dT%H:%MZ)) ==="

./gradlew :shared:desktopTest \
  --tests 'me.rosuh.easywatermark.session.ExportCancellationSessionTest' \
  --tests 'me.rosuh.easywatermark.session.TypedExportSessionTest' \
  --tests 'me.rosuh.easywatermark.session.TypedExportOutcomeTest' \
  --tests 'me.rosuh.easywatermark.session.G3LifecycleFaultMatrixTest' \
  --tests 'me.rosuh.easywatermark.session.OffsetExportOrderingTest' \
  --tests 'me.rosuh.easywatermark.render.DesktopAtomicFileWriteTest' \
  --tests 'me.rosuh.easywatermark.data.db.TemplateSeedAtomicInstallTest' \
  --tests 'me.rosuh.easywatermark.data.datastore.DataStoreCorruptionQuarantineTest' \
  --tests 'me.rosuh.easywatermark.session.DesktopExportPipelinePortTest' \
  --max-workers=8

./gradlew :app:testDebugUnitTest \
  --tests 'me.rosuh.easywatermark.session.*' \
  --tests 'me.rosuh.easywatermark.platform.AndroidShareInDirectUriBehaviorTest' \
  --max-workers=8

./gradlew :shared:iosSimulatorArm64Test \
  --tests 'me.rosuh.easywatermark.data.db.IosSeedAtomicInstallTest' \
  --tests 'me.rosuh.easywatermark.ui.IosPhotosPersistTest' \
  --max-workers=8

echo "=== G3_FAULT_MATRIX_PASS ==="
exit 0
