# 09 — B3 iOS/Desktop alignment to signed Android baseline + exception registry

**What to build:** After Android Phase B owner sign-off (07 + 08), align iOS and Desktop to that **signed** baseline—not branch WIP aesthetics—with an **explicit** exception registry (system pickers, packaging, Skiko text differences, PHPicker automation residual, etc.). Platform edges stay narrow; do not grow long-term non-CMP product UI.

**Blocked by:** 07 B1 launch/gallery parity sign-off; 08 B2 editor/save/export parity sign-off.

**Status:** ready-for-agent

- [ ] Exception registry published (one-line why per exception)
- [ ] iOS/Desktop verification green for in-scope behaviors against signed Android archive
- [ ] No byte-parity claims for StaticLayout vs MultiParagraph CJK; no endless PHPicker grid re-proof
