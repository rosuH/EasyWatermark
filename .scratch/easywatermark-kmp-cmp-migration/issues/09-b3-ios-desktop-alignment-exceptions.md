# 09 — B3 iOS/Desktop alignment to signed Android baseline + exception registry

**What to build:** After Android Phase B owner sign-off (07 + 08), align iOS and Desktop to that **signed** baseline—not branch WIP aesthetics—with an **explicit** exception registry (system pickers, packaging, Skiko text differences, PHPicker automation residual, etc.). Platform edges stay narrow; do not grow long-term non-CMP product UI.

**Blocked by:** 07 B1 launch/gallery parity sign-off; 08 B2 editor/save/export parity sign-off — **both complete**.

**Status:** **complete** (2026-07-12)  
**Does not complete:** §9 DoD / PR #358 graduation (ticket 10).

## Acceptance checklist

- [x] Exception registry published (one-line why per exception)
- [x] iOS/Desktop verification green for in-scope behaviors against signed Android archive
- [x] No byte-parity claims for StaticLayout vs MultiParagraph CJK; no endless PHPicker grid re-proof

## Deliverables

| Artifact | Role |
|----------|------|
| `docs/parity/v2.10.0/alignment/ios-desktop-exception-registry.md` | **Canonical** B3 registry (E01–E16) + signed Android truths + non-claims |
| `build/s4d383-b3-align/` | Verification logs |

## Verification (this ticket)

| Gate | Result | Log |
|------|--------|-----|
| `:shared:compileKotlinIosSimulatorArm64` + `:shared:desktopTest` (`--rerun-tasks`) | **EXIT 0** · desktopTest **132/0** | `01-shared.log` |
| `:desktopApp:run --args='--headless'` | **EXIT 0** · save/templates witnesses OK | `02-desktop-headless.log` |
| `./gradlew --stop` | daemon stopped | `03-stop.log` |
| iOS XCUITest full re-run | **Not re-run** (no product code change this ticket); cite prior A5a **19/0** + retained text Confirm sheet contract | `build/s4d383-a5a-final/` |
| Android signed archive | Tickets **07** + **08** owner approved | captures + tickets |

## Alignment summary

- **Android signed baseline** (07/08) is the product truth for behavior intent.  
- **iOS/Desktop** share editor CMP route of record where present; **explicit exceptions** for pick/about/launch/nav/renderer engine/share-save mechanisms (E01–E16).  
- **No** CJK StaticLayout↔MultiParagraph byte claims; **no** new PHPicker grid-cell automation campaign.

## Next

Ticket **10** — §9 DoD audit + PR #358 graduation **proposal** (still Draft; no auto-merge).
