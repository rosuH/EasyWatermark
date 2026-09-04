# S4d-346 — iOS shared templates-sheet readiness (read-only)

**Date:** 2026-07-11
**Type:** ready / no-go recommendation (no code)
**Question:** Can iOS production Templates consume existing commonMain `EditorTemplateSheetHost` / `TemplateListSheet` as a **narrow A3** slice **without** focused CMP text input and **without** violating S4d-338?

**Verdict: NO-GO** for the **existing** host/sheet as a production iOS consumer.

---

## 1. Evidence inspected

| Source | Role |
|---|---|
| `shared/.../ui/EditorTemplateSheetHost.kt` | Sheet visibility host + `content(showTemplateSheet)` |
| `shared/.../ui/compose/TemplateListSheet.kt` | Full list + add/edit sheet + confirm dialogs |
| `app/.../ui/EditorScreen.kt` | Android production call site |
| `desktopApp/.../DesktopWindow.kt` | Desktop production call site (S4d-290) |
| `iosApp/iosApp/ContentView.swift` | Current SwiftUI Templates section |
| `iosApp/iosApp/WatermarkWorkflow.swift` | load/save/apply/delete |
| `shared/iosMain/.../IosTemplateBridge.kt` | Swift-facing snapshot + `TemplateEditor` |
| `iosApp/iosAppUITests/PickerFlowUITests.swift` | `testTemplatesSaveApplyDelete` |
| S4d-338 / findings | CMP iOS text + sheet/dialog crash surface |

---

## 2. Shared API / event contract

### 2.1 `EditorTemplateSheetHost`

```text
EditorTemplateSheetHost(
  templates: List<Template>,
  strings: TemplateListSheetStrings,
  editIcon / deleteIcon: Painter?,
  enabled: Boolean,
  newTemplateInitialText: String,   // seeds Add sheet only
  onUse(Template),
  onAdd(String),
  onUpdate(Template),
  onDelete(Template),
  content: (showTemplateSheet: () -> Unit) -> Unit,
)
```

- Owns **only** `showTemplateSheet` boolean.
- When true, renders full `TemplateListSheet`.
- Platform supplies strings, painters, and repo/editor callbacks.

### 2.2 `TemplateListSheet` UI composition (critical)

| Layer | Compose API | User actions |
|---|---|---|
| List surface | **`ModalBottomSheet`** | dismiss; Add; row tap → use confirm; Edit; Delete |
| Add / Edit | nested **`ModalBottomSheet`** + **`OutlinedTextField`** | draft text; Confirm → `onAdd` / `onUpdate` |
| Use confirm | **`AlertDialog`** | confirm → `onUse` + dismiss sheet |
| Delete confirm | **`AlertDialog`** | confirm → `onDelete` |

There is **no** list-only mode, no flag to disable Add/Edit, and no path that avoids `ModalBottomSheet`.

### 2.3 Android production wiring

`EditorScreen` → `EditorTemplateSheetHost` → editor chrome; `onGoTemplateList = showTemplateSheet`.
Callbacks from `MainViewModel` / `TemplateEditor` (use → watermark text update).

### 2.4 Desktop production wiring

`DesktopWindow` → same host; `newTemplateInitialText = watermarkText`;
`onUse` → `WatermarkConfigEditor.updateText` + preview refresh;
`onAdd`/`onUpdate`/`onDelete` → `TemplateEditor`; launcher = "Templates" button.

### 2.5 Current iOS production flow

| Affordance | UI | Workflow / bridge |
|---|---|---|
| List | SwiftUI `ForEach(workflow.templates)` | `loadTemplates()` → `IosTemplateBridge.currentTemplates()` |
| Use / apply | Tap row label | `applyTemplate` → `setWatermarkText` (config editor + re-render) |
| Delete | Trash button | `deleteTemplate(id)` → bridge + reload |
| Save current | "Save current" | `saveCurrentTextAsTemplate` → `addTemplate(watermarkText)` (uses **Swift** draft / persisted text, **not** a CMP field) |
| Update / edit row content | **absent** | bridge has no `update` API exposed to Swift |

---

## 3. Affordance classification (existing shared host)

| Affordance | Maps to shared API | Classification for **existing** host on iOS |
|---|---|---|
| **List** | sheet body / LazyColumn | **Blocked** — packaged inside `ModalBottomSheet` (S4d-338 runtime: `LocalKeyboardOverlapHeight`) |
| **Use / apply** | `onUse` + use `AlertDialog` | **Blocked in-host** — dialog path hits S4d-338 `LocalSafeArea` class of failures; callback itself is fine |
| **Delete** | `onDelete` + delete `AlertDialog` | **Blocked in-host** — same dialog surface |
| **Save current / Add** | `onAdd(String)` via Add → edit sheet | **Blocked** — Add opens `TemplateEditSheet` with **`OutlinedTextField`** (S4d-338 `unclippedTextOffsetInRoot`) |
| **Update / edit** | `onUpdate(Template)` via Edit → same text sheet | **Blocked** — focused CMP text field |

**Swift-side today (without shared host):**

| Affordance | Classification |
|---|---|
| List / Use / Delete / Save current | **SwiftUI platform product UI** (already works; XCUITest green) |
| Update/edit in place | **not present** (optional product gap, not S4d-338) |

**Domain/persist:** already shared (`TemplateEditor` / Room / seed) — **not** the readiness question.

---

## 4. S4d-338 boundary vs this surface

S4d-338 closed three iOS CMP runtime crash families under the current Compose/Skiko mix:

1. `ModalBottomSheet` → missing `LocalKeyboardOverlapHeight`
2. Compose `Dialog` / default insets → missing `LocalSafeArea`
3. Focused `OutlinedTextField` → unimplemented `unclippedTextOffsetInRoot`

`TemplateListSheet` uses **all three** classes of API (two `ModalBottomSheet`s, two `AlertDialog`s, one `OutlinedTextField`).

| Question | Answer |
|---|---|
| Does S4d-338 block fully? | **Yes — fully blocks production use of the existing host/sheet as implemented** |
| Partially? | Only in a **hypothetical future split** (list without sheet/dialog/text) — that is **not** the current API |
| Not at all? | **No** |

Avoiding “focused text only” is **insufficient**: even List + Use + Delete without Add/Edit still opens `ModalBottomSheet` + `AlertDialog`.

---

## 5. iOS → shared callback mapping (if unblocked later)

| iOS current | Shared callback | Notes |
|---|---|---|
| `workflow.templates` as `IosTemplate` | `List<Template>` | Need map id/content ↔ entity (or bridge returns `Template`) |
| `applyTemplate` | `onUse` | Already pure text apply |
| `saveCurrentTextAsTemplate` | `onAdd(watermarkText)` | Can supply string from workflow without CMP field **if** UI never opens edit sheet |
| — | `onUpdate` | Would need bridge `update` + edit UI |
| `deleteTemplate` | `onDelete` | Need full `Template` or id resolve like bridge today |
| Section always visible | `content { showTemplateSheet }` | Launcher only |

**Not required:** shared ViewModel, new deps, new persistence, renderer change.

---

## 6. Smallest viable E2E slice — decision

### NO-GO (this question, existing host)

**Reason:** Production consumption of `EditorTemplateSheetHost` / `TemplateListSheet` **as they exist** requires Material3 **ModalBottomSheet**, **AlertDialog**, and (for Add/Edit) **OutlinedTextField** on iOS CMP — all in the S4d-338 crash set. A “narrow” slice that still uses this host is not safe.

**Not a partial GO:** hiding Add/Edit icons does not remove list sheet or confirm dialogs.

### What would become a different slice (out of scope for “existing host”)

| Option | Notes |
|---|---|
| Owner unblocks S4d-338 (Compose/Skiko align) | Then full host is in scope; new slice |
| New shared **list-only** API without ModalBottomSheet/Dialog/TextField | New design + A0-style approval; **not** drop-in `EditorTemplateSheetHost` |
| Keep SwiftUI Templates | Status quo — already proven |

### Allowed files *if* a future GO (illustrative only)

Would **not** apply until NO-GO is lifted. Hypothetical: iosMain host + ContentView wiring + optional bridge map + XCUITest — still **after** dependency align or a new list-only shared API.

---

## 7. Required XCUITest / gates (status quo vs future)

### Today (SwiftUI section — keep)

- Existing `testTemplatesSaveApplyDelete` (Save current → Apply row → Delete) remains the production gate.
- No change required for a NO-GO decision.

### If full shared host later unblocked (S4d-338 fixed)

- Rerun full `iosAppUITests` (expect 18+).
- Adapt template test to open shared sheet (new accessibility ids), then Use/Delete/Add if product keeps them.
- Full Gradle (shared iOS compile/link + desktop consumers of unchanged host).
- **VIEW** screenshots of sheet list + apply effect on watermark text.

### If only a future list-only shared API

- New tests for that API’s surface; do not claim parity with full Android sheet.

---

## 8. Hard constraints restated

| Constraint | Status |
|---|---|
| No new dependency / compose-resources | Required |
| No persistence/renderer change | Required |
| No shared ViewModel | Required (§6.12 / S4d-191) |
| S4d-338 | **Fully blocks existing sheet host on iOS** |
| Phase A/B / 1:1 complete | **Not claimed** |

---

## 9. Recommendation (for coordinator)

1. **Do not schedule** an A3 implementation slice that wires iOS production UI to current `EditorTemplateSheetHost` / `TemplateListSheet`.
2. **Leave** SwiftUI Templates + `IosTemplateBridge` as the production path until either:
   - owner-approved Compose/Skiko alignment reopens S4d-338, or
   - an explicit new shared **list-only** surface is designed without sheet/dialog/text-field CMP APIs.
3. Prefer parallel A1 thinning / non-text A3 controls / optional A2 roots from the A0 matrix.

---

## 10. Verification

- Read-only research; single new file under `docs/superpowers/research/`.
- `git diff --check` on this doc (post-write).
- No code, plan, build, or commit.

---

*End of S4d-346 readiness note*
