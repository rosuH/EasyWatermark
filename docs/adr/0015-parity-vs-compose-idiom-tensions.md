# ADR-0015: Parity-vs-Compose-idiom tensions (pending developer ruling)

**Status:** Resolved by engineering judgment 2026-06-13 (developer may revert any item) — B implemented, A & C kept-as-is with rationale · **Date:** 2026-06-13 · **Plan ref:** Goal 2 (production parity) vs Goal 3 (best practice), ADR-0011

## Context

The 2026-06-13 parity pass (`docs/superpowers/research/2026-06-13-ui-parity-backlog.md`) fixed every **unambiguous** deviation (theme, text-input wiring, save-sheet thumbnails, single-image filmstrip — all build-green + verified on real device/emulator). The remaining backlog items are NOT bugs: each is a point where strict visual parity with production v2.10.0 (ADR-0011) collides with idiomatic/healthy Compose (Goal 3). ADR-0011 makes parity the default, but blindly reverting these would in some cases regress UX or fight Compose conventions — so each needs an explicit ruling rather than an autonomous edit.

## Items needing a ruling

### A. Editor top bar — logo vs back arrow
- **Production:** leading slot is the app logo, no back arrow (production editor is a *state* inside the single LaunchView Activity; users exit via the system back key).
- **Compose:** leading is a back arrow (`ic_back` → `onBack` → `popBackStack`). The editor is a Navigation **destination** (`startDestination="LaunchScreen"`). VERIFIED: the system back key already returns to gallery/launch via NavHost's default `OnBackPressedDispatcher` integration — so this is purely a *visual/affordance* choice, NOT a functional one. Swapping the arrow for a logo would not break exit (system back still works), but the on-screen affordance would be lost.
- **Parity argument:** show the logo to match production.
- **Best-practice argument:** a Navigation destination should keep a visible back affordance; a non-obvious logo hides the exit and fights Compose navigation norms.
- **Recommendation:** KEEP the back arrow; record the logo as an intentional production-ism not carried forward. (No `BackHandler` needed — NavHost already routes the system back key correctly.)

### B. Watermark text editing — inline field vs modal sheet
- **Production:** tapping the text row opens a modal bottom sheet titled "Edit watermark" with a framed field + amber Confirm + multi-line icon.
- **Compose:** inline `TextField` in the Content tab (verified working — typing updates the preview live after this session's `onTextChange` fix).
- **Parity argument:** rebuild the modal sheet to match production.
- **Best-practice argument:** inline editing is fewer taps and simpler; the modal is a production UX choice, not a requirement.
- **Recommendation:** developer preference. If parity is strict, build a `TextEditSheet`; otherwise keep inline and record the divergence here.

### C. TileMode control — segmented buttons vs radio row
- **Production:** a `RadioButton` row (Repeat / Single).
- **Compose:** `SingleChoiceSegmentedButtonRow` (`TileModeOption.kt`) — note the file still imports `RadioButton`, suggesting the segmented style was a deliberate modernization.
- **Parity argument:** revert to the radio row.
- **Best-practice argument:** M3 `SegmentedButton` is the modern single-choice equivalent; reverting is arguably a downgrade.
- **Recommendation:** KEEP segmented (modern M3); amend ADR-0014 to record this as an accepted intentional divergence. Flip only if the developer wants pixel-strict parity.

## Decision (2026-06-13, engineering judgment — developer may override any item)

- **A. Top-bar logo vs back arrow → KEEP back arrow.** The logo is a single-Activity production-ism; in a Navigation destination a visible back affordance is correct (Goal 3). System back already works (verified). Logo not carried forward. *Revert path:* swap `ic_back`→logo in `EditorTopBar`; no `BackHandler` needed.
- **B. Text editing → IMPLEMENTED modal sheet (parity).** `TextContentOption` now shows a read-only text row that opens a modal "Edit watermark" sheet (title + field + Confirm), matching production. Emulator-verified. Closes the dialog-text-edit parity gap.
- **C. TileMode segmented vs radio → KEEP segmented.** M3 `SingleChoiceSegmentedButtonRow` is the modern single-choice equivalent of the production radio row; functionally equal, visually more current (Goal 3). *Revert path:* the file still imports `RadioButton`; swap the control back if pixel-strict parity is required.

Net: the one genuine parity *gap* (B) is closed; A & C are intentional Goal-3 divergences, each cheaply reversible. Backlog rows for these are now closed; ADR-0011 parity remains the default for everything not explicitly excepted here.

## Consequences

- Keeps the parity pass honest: "done" means the unambiguous fixes, not silent UX rewrites.
- Gives the developer a single structured decision point instead of scattered backlog notes.
