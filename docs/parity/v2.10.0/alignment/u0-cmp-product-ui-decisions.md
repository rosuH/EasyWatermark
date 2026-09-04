# U0 Product decisions (binding) — CMP product UI for iOS/Desktop

**Date:** 2026-07-12  
**Status:** Binding  
**Registry:** `ios-desktop-exception-registry.md` (E01/E02/E04/E06)

Owner direction: **iOS/Desktop UI/UX structure first aligns to Android** so one CMP product surface is maintainable. Pixel engines stay platform (ADR-0004).

## Decisions

| ID | Question | Decision |
|----|----------|----------|
| **E01** | Desktop Launch? | **ADD** shared `LaunchScreenShell` (structure parity). Not permanent editor-only. |
| **E02** | In-app gallery off-Android? | **SYSTEM PICK FOREVER** — iOS PHPicker; Desktop FileDialog + drop. No invented gallery. |
| **E04** | About on Desktop/iOS? | **ADD** shared `AboutScreenShell` production route. URL open stays platform edge. |
| **E06** | Multi-image filmstrip? | **YES** when session selection is non-empty (`showPhotoStrip` from session list). |

## Non-decisions (unchanged)

- E11/E12: Skiko vs Android native raster — no byte parity  
- E09/E10: share/save mechanisms stay platform  
- E14: PHPicker grid XCUITest residual remains toolchain, not product  

## Unlocks

U1 shared editor bottom → U2 Desktop ProductApp → U3 iOS single Compose root → U4 Launch/About → U5 filmstrip/export chrome.

---

## Strategic target (owner, 2026-07-12 follow-up)

**Product UI migration target = Option C** (strong unification): shared `ProductApp` in commonMain + shared resources strategy + unified paint path.

### Permanent non-common edges (all options including C)

- System pick / share / save-to-photos / permissions / app entry  
- Codecs / EXIF bake policy at platform decode edge  

### Export panel vs export I/O (owner, C2 continue 2026-07-12)

| Layer | Route of record | Notes |
|-------|-----------------|-------|
| **Export panel + interaction** | Shared CMP **`SaveExportSheetShell`** (same as Android Compose) | Format / quality / list / primary CTA / dismiss. Desktop + iOS host this shell; do **not** invent parallel save chrome. |
| **Final export I/O** | Platform edges only | Android MediaStore + share Intent; iOS Photos + UIActivity; Desktop FS write + reveal-in-folder / share substitute (E09/E10). |

### C1 vs C2 — **C2 binding** (owner **「c2！」** 2026-07-12)

| Path | Meaning | Status |
|------|---------|--------|
| C1 | Preview common; Android **export stays native** | Rejected as end state |
| **C2** | Preview **and** Android **production export** use **common 光栅** (`WatermarkCellComposer` + `composeOverBackground`) | **Accepted** — see **ADR-0018** |

**Accepted consequences:** Android export may diverge from Play v2.10.0 native pixels (esp. CJK); strict FNV goldens rebaseline or move to perceptual policy; S4d-8 / S4d-17 / S4d-190 “Android stays native forever” **reopened and superseded for production routing** by ADR-0018. Implementers **must not** treat “do not route Android through composeIconCell/composeTextCell” as a permanent ban anymore — follow ADR-0018 gated schedule instead.

**Still do not:** claim byte-parity with legacy native; skip measurement gates; invent in-app gallery off-Android (E02).
