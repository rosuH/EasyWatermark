# Android v2.10.0 parity archive (Phase B)

**Baseline app:** production `me.rosuh.easywatermark` **v2.10.0** (ADR-0011)  
**Compare app:** debug `me.rosuh.easywatermark.debug` (same device/emulator)  
**Process:** `codex-goal-v2.md` Phase B · local tickets 06–08  
**A5 prerequisite:** PASS (ticket 05, 2026-07-12)

This tree holds **inventory + capture protocol + screenshot/recording evidence**.  
It does **not** claim owner screen sign-off. Tickets **07/08** own sign-off.

**Git tracking:** `docs/parity/**/captures/` is **tracked**. Root `.gitignore` only ignores `/captures/` (Android Studio). If a tool still greys out these files, refresh ignore indexes; files are in git (`git ls-files docs/parity/v2.10.0/captures`).

## Layout

```
docs/parity/v2.10.0/
  README.md                 ← this file
  protocol/capture.md       ← how to capture (device, locale, theme, font)
  inventory/screens.md      ← screen × state matrix
  captures/
    production/{en,zh}/{light,dark}/   ← baseline shots (empty until capture)
    debug/{en,zh}/{light,dark}/        ← debug branch shots (empty until capture)
```

## Packages on device (verified 2026-07-12)

| App | applicationId | versionName | versionCode | role |
|-----|---------------|-------------|-------------|------|
| Production | `me.rosuh.easywatermark` | 2.10.0 | 21000 | **source of truth** |
| Debug | `me.rosuh.easywatermark.debug` | (branch build) | (branch) | candidate under repair |

Both may install side-by-side. Always open **production first**, then debug, same emulator.

## Ticket mapping

| Ticket | Role |
|--------|------|
| **06** (this scaffold) | Inventory + protocol + empty archive |
| **07** | Launch + gallery owner sign-off — **complete** |
| **08** | Editor + save/export owner sign-off — **complete** |
| **09** | iOS/Desktop align + exception registry — **complete** (`alignment/ios-desktop-exception-registry.md`) |
| **10** | §9 DoD audit + PR #358 graduation proposal |
