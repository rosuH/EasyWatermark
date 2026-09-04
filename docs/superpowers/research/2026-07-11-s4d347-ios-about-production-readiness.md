# S4d-347 — iOS About production-root readiness (read-only)

**Date:** 2026-07-11
**Type:** consumer-first ready / no-go (no code)
**Question:** Can iOS ship a production About root that consumes commonMain `AboutScreenShell` as a narrow A3 slice without S4d-338 APIs?

**Verdict: NO-GO (consumer-first / product-scope)** — not an S4d-338 technical crash of the shell itself.

---

## 1. Exact conclusion

1. **iOS has no production About surface today** — only a **DEBUG** CMP witness gated by `-sharedComposeWitnesses` / `showSharedComposeWitnesses`.
2. **commonMain `AboutScreenShell` avoids S4d-338 APIs** (no `ModalBottomSheet`, no `AlertDialog`/`Dialog`, no `OutlinedTextField` / focused text field). Layout is scroll + pager + `Switch` + icon rows + injected logo slot.
3. **Adding a production About route / root is an owner-level product-scope decision**, not a pure “wire existing consumer” migration. There is **no** existing production navigation root or workflow state to attach the shell to.
4. Under **consumer-first** rules: do **not** implement iOS About production code until the owner explicitly orders a product About entry (and which links/toggles ship on iOS).
5. **Next lane after this NO-GO: A1 Android wrapper-thinning readiness** — not A2/A3 About work. Not Phase A/B/parity complete.

**Kimi + OpenCode read-only reviews: PASS (corroborated).**
**Verification:** `git diff --check` on this note; **no build** applies (read-only evidence).

---

## 2. Production vs DEBUG evidence

| Surface | Evidence | Production? |
|---|---|---|
| Android About | `ComposeMainActivity` `AboutRoute` → `AboutScreen` → `AboutScreenShell` | **Yes** |
| Desktop About | No About root in `DesktopWindow` | **No** |
| iOS production UI | `ContentView` product scroll: launch, icon, text field, templates, sliders, preview, output actions — **no About** | **No** |
| iOS DEBUG witness | `SharedComposeAboutShellWitness` → `IosSharedComposeHost.aboutScreenShellWitness()`; shown only under `#if DEBUG` + witness launch flag | **Witness only** |
| XCUITest | `testSharedComposeAboutWitnessVisible` exercises witness flag path, not a product route | **Not a production consumer** |

References:
- `iosApp/iosApp/ContentView.swift` — `SharedComposeAboutShellWitness` + DEBUG witness block
- `shared/iosMain/.../IosSharedComposeHost.kt` — `aboutScreenShellWitness()`
- `app/.../ComposeMainActivity.kt` — `composable<AboutRoute> { AboutScreen(...) }`
- `app/.../about/AboutScreen.kt` — production Android wrapper

---

## 3. S4d-338 API absence in shared About shell

`shared/.../ui/about/AboutScreenShell.kt` uses (among others): `Column` / `verticalScroll`, `HorizontalPager`, `Card`, `Switch`, `IconButton`, `Image`, Material text/colors.

**Absent (S4d-338 families):**
- `ModalBottomSheet`
- Compose `Dialog` / `AlertDialog`
- `OutlinedTextField` / focused text input

So the shell is **technically plausible** on iOS CMP once a product host exists; the block is **lack of production consumer + product-scope**, not the template-sheet class of crashes.

Platform edges that would still apply if owner later approves production About:
- Open URL / mail / store rating (UIKit / `UIApplication`)
- Dynamic color toggle may no-op off Android (`DynamicColorCapability` false)
- “Show bounds” / privacy links need product policy for iOS
- Strings/painters injected at host edge (no compose-resources)

---

## 4. Required owner decision (before any code)

Owner must explicitly approve **product** scope, including at least:

1. Is About a **required iOS product** surface for this release track?
2. Entry point: from launch chrome, settings gear, or other?
3. Which rows ship: version, rating, feedback, changelog, OSS, privacy ZH/EN, dynamic color, show bounds?
4. Open-source / recovery subflows in or out?

Until that decision: **no** iosMain production host, **no** ContentView About route, **no** AboutViewModel migration.

---

## 5. Consumer-first / A4 note

- Android alone already consumes `AboutScreenShell`.
- iOS DEBUG witness does **not** count as a production consumer (§6.12 / S4d-345).
- Promoting About on iOS would create a second production UI consumer of the **shell**, not automatically a pure-state A4 extraction (`AboutViewModel` remains Android-side until a real dual consumer of pure prefs/state exists).

---

## 6. Next lane (explicit)

| Do | Do not |
|---|---|
| **A1 Android wrapper-thinning readiness** next | Jump to A2 Desktop About or A3 iOS About implementation |
| Keep S4d-338 blocks for text/sheet/dialog surfaces | Claim shell “ready to ship” without product route |
| Leave DEBUG About witness as-is | Treat witness XCUITest as production proof |

---

## 7. Overclaim guard

- Not Phase A complete
- Not Phase B / 1:1 parity
- Not “About blocked by S4d-338 crashes” — blocked by **no production surface + owner product scope**
- Shell avoids S4d-338 APIs; product adoption still gated

---

*End of S4d-347 readiness note*
