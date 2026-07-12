# 01 — A5a iOS production EditorScreenShell route

**What to build:** iOS production editor uses shared `EditorScreenShell` (`showPhotoStrip = false`) in **one** ComposeUIViewController with a flat scrollable options column (real shared option composables + real Skiko preview). System pickers, Share/Save, SwiftUI Templates, and `WatermarkWorkflow` stay outside shared state. Phase A route-of-record only — not v2.10.0 pixel parity. If a hard contract/runtime issue remains, produce an owner decision package rather than a silent permanent laundry-list.

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Production iOS editor chrome is `EditorScreenShell` (not multi-host control list), or owner-signed decision package is recorded in this ticket
- [ ] Controls remain user-equivalent: text, icon, degree, tile, alpha, color, size, gaps, typeface, style
- [ ] XCUITest contracts preserved or deliberately updated; full suite still meaningful
- [ ] Guardrails: Android native renderer untouched; no new deps; no shared VM/nav/IO layer; no TextConfirmGate workarounds
