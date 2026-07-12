# 03 — A5c iOS About production scope or owner-signed absence

**What to build:** Either production shared `AboutScreenShell` on iOS **if the product needs About**, **or** an **owner-signed** “no About on iOS Phase A” absence exception. Do not invent an About screen only to fill the matrix. Do not move `AboutViewModel` to commonMain without a real multi-platform consumer (§6.12).

**Blocked by:** None — can start immediately (parallel with 01, 02, 04).

**Status:** **complete** (S4d-383 / A5c accepted 2026-07-12)  
**Disposition:** **No About on iOS Phase A** (absence exception).

## Acceptance checklist

- [x] Production About route **or** owner-signed absence recorded on this ticket
- [x] No speculative About feature expansion beyond shared shell content already used on Android
- [x] No new deps; not Phase B pixel work; not Desktop About (see 04)

## Owner sign-off (recorded)

**Signed:** 2026-07-12 — owner selected *Sign both 02 + 03* via commander prompt.  
**Text:** no About on iOS Phase A — **approved**.

---

## Evidence pack (commander review)

### What Android product has

- `ComposeMainActivity` → `AboutScreenAndroid` → shared `AboutScreenShell`.
- Live content: version, rate, feedback, update log, open source, privacy (zh/en), developer/designer cards, dynamic-color toggle via `DynamicColorCapability` / `AboutViewModel` (Android-side).
- URL / store / Intent edges stay Android.

### What iOS production has today (after S4d-383 / ticket 01)

| Surface | Production? | Notes |
|---------|-------------|--------|
| Shared `AboutScreenShell` | **No** | DEBUG witness only (`aboutScreenShellWitness` / `-sharedComposeWitness about`) |
| Editor / Launch About entry | **No** | Production `IosEditorScreenHost` does not wire `onGoAboutScreen` into a production About route |
| `AboutViewModel` commonMain | **No** | Correct — §6.12 (no second real consumer) |

### Why inventing About on iOS Phase A is speculative

1. Ticket forbids inventing About only for matrix symmetry.
2. About on Android is heavily edge-bound (Play store rating, intents, dynamic color platform actual, localized string resources). iOS would need App Store / Mail / Safari edges + string packaging — real product work, not a shell host swap.
3. Moving `AboutViewModel` to commonMain without a real multi-platform consumer violates §6.12 (S4d-99 readiness already owner-gated).

### Proposed owner-signed absence (Phase A)

| Item | Decision |
|------|----------|
| iOS production About route | **Absent** for Phase A |
| Shared `AboutScreenShell` on iOS | DEBUG witness / link proof only |
| `AboutViewModel` KMP move | **Not** in this ticket; blocked until real iOS+Desktop consumers exist |
| Reopen condition | Explicit owner request for production About (version + legal links + store/rate) as a **new** ticket after Phase A closeout or as Phase B alignment exception |

### Guardrails confirmed

- No new deps.
- No speculative About expansion.
- Desktop About is ticket **04** (also absent by design).
- Not Phase B pixel work.

---

## Owner sign-off

**Reply to accept:**  
`OWNER SIGN-OFF 03: no About on iOS Phase A — approved`

**Reply to reject / re-scope:**  
State the minimum About rows required on iOS (e.g. version + privacy only) so a follow-up implement ticket can be opened.

Until signed, ticket **05 (A5e)** cannot claim A5 PASS for the About matrix cell.
