# S4d-354 — Android AndroMeld mirror evidence backfill (docs only)

**Date:** 2026-07-11 / closeout 2026-07-12
**Type:** environment evidence backfill — **no product code**
**Question:** Can deferred About (S4d-348) and SaveExport (S4d-350) AndroMeld visual gates be closed with current HEAD debug APK + live Phone Screen?

**Verdict: NOT CLOSED.** Install and MCP control succeeded; Phone Screen frames were **unusable** (stale then black; hierarchy/metadata mismatch). **Do not claim About or save/export visual gates passed.**

---

## 1. Verified true

### AndroMeld control

- `andromeld.devices.list` **succeeded** for **`emulator-5554`** (SDK **36**).
- MCP control **enabled**.
- A **Phone Screen** session **existed**.

### Debug APK install (Gradle only — no adb)

Command:

```text
./gradlew :app:installDebug --max-workers=8
```

Log: `build/s4d354-install-debug.log`

| Fact | Value |
|---|---|
| APK | `EasyWatermark-2.10.0-21000.apk` |
| Device | Pixel_9_Pro_XL (AVD) - 16 |
| Result | Installed on 1 device |
| Build | **BUILD SUCCESSFUL in 12s** |
| Tasks | **83** actionable: **2** executed, **81** up-to-date |
| Daemon | `./gradlew --stop` succeeded after install |
| adb | **not used** |
| HEAD at install | `ef4028be` (`Record Compose iOS decision gate`) |

### Post-install AndroMeld observation

- App launch path reported **`foregroundVerified`**.
- UI hierarchy reported **Settings / Launcher** while session metadata claimed the **debug app**.
- Screen frames were **stale**, then **black**.
- **Home key** returned the external Google setup/launcher state **without stopping the device**.

---

## 2. Explicit non-claims

| Claim | Status |
|---|---|
| About screen visual gate (S4d-348 residual) | **NOT passed** |
| Save/export sheet visual gate (S4d-350 residual) | **NOT passed** |
| Pre-install / stale screenshots as proof | **Forbidden** — do not use |
| Raw adb interaction as substitute | **Not used / not allowed** as visual substitute |
| Phase A/B/parity complete | **Not claimed** |

---

## 3. Interpretation

- **MCP socket + devices.list + install** are no longer the primary block for this debt.
- The residual block is **mirror/session pixel health**: hierarchy and metadata can disagree with visible frames; black/stale frames are non-evidence (same class of defect as earlier Photos-mirror staleness notes in findings).
- Device left running (Home only); do not treat setup/launcher UI as product proof.

---

## 4. Next

1. **Wait for a fresh/healthy AndroMeld Phone Screen frame** (hierarchy matches debug app; non-black live pixels) **before** re-attempting About or save/export visual gates.
2. **Parallel:** owner **A/B/C/defer** on S4d-353 Compose/Skiko pack remains open for iOS text/templates.
3. No product code or full rebuild required solely for this documentation closeout.

---

## 5. Verification (this closeout)

| Check | Result |
|---|---|
| Product code | None |
| Full multiplatform build | Not run (docs-only; install already logged) |
| Protected untracked `2026-07-11-project-branch-goals-progress.md` | Not staged |
