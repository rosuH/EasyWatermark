# Plan: CMP composeResources + multi-language (Weblate)

**Date:** 2026-07-12  
**Status:** **Phases 0–4 complete (2026-07-12)** — packaging, catalog, Res UI, drawables, ADR-0019 + docs  
**Weblate retarget (P1.5):** **deferred until merge to `master`** — keep Weblate on `app/res` until then  
**Residual:** gallery-only Android drawables optional; in-app language picker not in scope  
**Related:** Option C2 product UI (shared screens), ADR-0002 (AGP/module), historical “no compose-resources / CMP-9547” ban in AGENTS.md  
**Does not supersede:** ADR-0004 renderer split, Android native production raster, Weblate ownership of non-default locales (only changes *where* those files live)

---

## 0. Goal

Make **product UI strings (and later shared drawables)** available from **`shared/commonMain`** on **Android + Desktop + iOS**, with **one Weblate-backed source of truth** and **system-locale selection**, so shared CMP screens no longer depend on Android-only `R.string` or long-lived English bags on off-Android hosts.

**Non-goals (this plan):**

- In-app language picker (system locale only; `LocalAppLocale` workaround is a follow-up if product asks)
- Migrating watermark **Noto** fonts into composeResources (keep existing byte / NSBundle / classpath boundaries)
- Migrating Room seed DBs into composeResources
- Replacing Android **system** strings that must stay in `:app` `res/` (if any: launcher name can stay dual or move later)
- moko-resources as default path
- Byte-identical Android `R` packaging vs composeResources (we need **correct locale resolution**, not FNV goldens of APK assets)

---

## 1. Locked recommendations (from research)

| Decision | Choice | Why |
|----------|--------|-----|
| **R1 Target library** | JetBrains **official `composeResources`** + `compose.components.resources` | First-class i18n (`values-*`), plurals, arrays, Weblate ≥5.12 native format, generated `Res` |
| **R2 Not default** | **moko-resources** | Extra plugin; community migrating toward official; only B-plan if spike fails |
| **R3 Not long-term** | Edge **string bags** only | Correct interim; blocks real iOS/Desktop i18n as screens grow |
| **R4 Weblate** | Single component on `shared/.../composeResources/values(-*)/strings.xml` | Format: **Compose Multiplatform Resource** (`cmp-resource`); stop dual-sourcing product UI |
| **R5 Gate** | **Phase 0 packaging spike** on current stack before mass migration | `:shared` already uses AGP 9.x + `com.android.kotlin.multiplatform.library` — historical **CMP-9547** risk |
| **R6 Locale model** | Follow **system locale** (same as today’s Android production behavior) | Official in-app switch is still expect/actual workaround; not required for v1 |
| **R7 Escaping** | Treat CMP strings as **Android-like but not identical** | Weblate docs: CMP format differs in escaping; do not assume raw copy never needs fixups |

**Hard rule until Phase 0 green:**

> Do **not** land production `implementation(compose.components.resources)` or mass-move Weblate trees.  
> Historical AGENTS ban becomes: **“forbidden until packaging spike accepted.”**

---

## 2. Current truth (repo snapshot)

### Stack (relevant)

| Piece | State |
|-------|--------|
| AGP | `9.2.1` (catalog; Studio-compatible pin, not 9.4 alpha) |
| `:shared` Android plugin | `com.android.kotlin.multiplatform.library` (S4d-360) |
| Compose Multiplatform | `1.12.0-beta01` + compose plugin on `:shared` |
| compose-resources | **Explicitly not** on `:shared` (comments cite CMP-9547) |
| Product screens | commonMain `LaunchScreen` / `EditorScreen` / About / templates / save shell… |
| Android strings | `app/src/main/res/values/strings.xml` (~93 keys) + **15+ locale dirs** (de, es, fr, it, ja, nb-NO, nl, nn, pt, pt-BR, ru, ta, uk, zh-CN, zh-TW, …) |
| Weblate | Owns non-default `strings.xml`; agents must not hand-edit translations |
| Off-Android strings | English hardcode or bags; Android thin overloads use `stringResource(R.string.*)` |

### String bag surface (to retire in Phase 2)

| Type | Location (approx.) |
|------|--------------------|
| `EditorUiStrings` | `shared/.../ui/EditorScreen.kt` |
| `AboutScreenStrings` | `about/AboutScreenShell.kt` (or AboutScreen) |
| `OpenSourceScreenStrings` | `about/OpenSourceScreen.kt` |
| `RecoveryScreenStrings` | `ui/RecoveryScreen.kt` |
| `SaveExportSheetStrings` | `ui/save/SaveExportSheetShell.kt` |
| `TemplateListSheetStrings` | `ui/compose/TemplateListSheet.kt` |
| `TextColorOptionStrings` / `TextContentOptionStrings` | compose options |
| Android `FuncTitleModel` + `@StringRes` | `:app` Editor edge — redesign needed |

### Related plans

| Plan | Overlap |
|------|---------|
| `2026-07-12-option-c2-…` | P1.4 bags first; P4.4 “Weblate codegen” — **this plan replaces P4.4 speculation** with official composeResources |
| `2026-07-12-shared-business-state-machine-plan.md` | Session VM independent; strings still needed by CMP UI either way |
| `2026-06-12-cmp-migration-plan.md` | CMP-9547 gate historically blocked resources; stack has already moved to AGP 9 KMP library |

---

## 3. Target architecture

```text
  Weblate (CMP format)
        │
        ▼
  shared/src/commonMain/composeResources/
    values/strings.xml              ← default (EN source of truth)
    values-zh-rCN/strings.xml
    values-zh-rTW/strings.xml
    values-ja/…                     ← same locale set as today
    drawable/                       ← Phase 3 optional (logo, func icons)
        │
        │  generated Res + stringResource / painterResource
        ▼
  commonMain CMP product UI
        │
   ┌────┴────┬────────────┐
   ▼         ▼            ▼
 :app     iosApp      desktopApp
 (system locale selection via CMP resource environment)
```

### What stays in `:app/src/main/res`

| Keep in app `res/` | Move to composeResources |
|--------------------|---------------------------|
| Themes, styles, mipmaps, Android-only anim/xml | Product UI strings used by shared screens |
| Optional: `app_name` if Play/launcher tooling expects it | Icons/logo used only from CMP UI (Phase 3) |
| Permission / system integration copy **only if** required as Android `R` | Everything else product-facing |

**Dual-write window:** allowed only during Phase 1 migration; must end before Phase 2 bags deletion.

### API shape (commonMain)

```kotlin
// After spike + migration
Text(stringResource(Res.string.action_pick))
// plurals if needed:
// Text(pluralStringResource(Res.plurals.xxx, count, count))
```

Gradle (illustrative — exact keys per current CMP docs):

```kotlin
// shared/build.gradle.kts
commonMain.dependencies {
    implementation(compose.components.resources)
}
compose.resources {
    publicResClass = true
    packageOfResClass = "me.rosuh.easywatermark.shared.generated.resources"
    // generateResClass = always  // only if transitive consumption needs it
}
```

**Do not** introduce a parallel `expect fun stringResource(id: Int)`. Prefer generated `StringResource` types.

---

## 4. Phases

### Phase 0 — Packaging spike (hard gate)

**Goal:** Prove composeResources **package and resolve** on **this** AGP + Android-KMP library stack for all three consumers.

| ID | Work | Done when |
|----|------|-----------|
| P0.1 | Add `compose.components.resources` to `:shared` commonMain; enable `compose.resources { publicResClass = true; packageOfResClass = … }` | Compiles |
| P0.2 | Add **only** `composeResources/values/strings.xml` with one key e.g. `cmp_spike_hello`; optional `values-zh-rCN` with Chinese | Resources generate `Res` |
| P0.3 | Tiny commonMain `@Composable` (debug-only or temporary) that shows `stringResource(Res.string.cmp_spike_hello)` | Linkable from all hosts |
| P0.4 | **Android:** install debug APK; system language EN → English; switch device language to zh-CN → Chinese (or log/assert) | No missing-resource crash; correct string |
| P0.5 | **Desktop:** `:desktopApp:run` shows spike string under default + forced JVM locale if easy | Same |
| P0.6 | **iOS:** simulator run (or XCUITest label) shows spike string; switch simulator language if feasible | Same |
| P0.7 | Write evidence under goal scratch or `docs/superpowers/research/` (commands + screenshots/logs) | Owner can accept/reject |

**Pass criteria (all required):**

1. No silent empty string / crash on Android (CMP-9547 class failure mode).  
2. Locale qualifier resolves on at least Android + one of Desktop/iOS.  
3. `:app` release minify smoke optional but recommended (`assembleRelease` + open once).  
4. APK size delta recorded (informational).

**Fail → stop.** Options:

| Outcome | Next |
|---------|------|
| Fail packaging | Stay on bags; file/track CMP-9547 against current CMP/AGP versions; **do not** mass-migrate Weblate |
| Fail only iOS | Investigate framework resource bundling; Android/Desktop may still proceed later with owner OK |
| Pass | Unlock Phase 1; update AGENTS.md ban text |

**Out of scope in P0:** Weblate change, bag deletion, full string copy, drawable migration.

**Allowed temporary dual dependency:** spike string only; production screens still use bags/`R.string`.

---

### Phase 1 — Catalog migration + Weblate re-point

**Precondition:** Phase 0 accepted. **Status: code done 2026-07-12; Weblate ops open.**

| ID | Work | Done when | Status |
|----|------|-----------|--------|
| P1.1 | Inventory keys used by shared CMP UI vs app-only (script: grep `R.string` / bag field names) | Key list committed or attached to research note | **Done** — `docs/superpowers/research/2026-07-12-s-i18n-1-string-inventory.md` |
| P1.2 | Create `shared/.../composeResources/values/strings.xml` from default EN (full product set) | Default file complete | **Done** — 94 keys incl. spike |
| P1.3 | Copy locale trees `values-*` → composeResources **via script** (no hand-edit translations) | Locales present; key parity check | **Done** — 16 locale files; incomplete ru/uk/nn preserved |
| P1.4 | Escaping pass: build + smoke; fix only defaults if CMP escaping differs | Green compile; EN UI OK | **Done** — generate accessors + desktop/android compile; `ComposeResourcesCatalogTest` |
| P1.5 | **Weblate (owner/ops):** retarget component to composeResources CMP format | Weblate PRs land in new path | **Deferred until merge to `master`** — checklist ready in `2026-07-12-s-i18n-1-weblate-retarget.md` |
| P1.6 | Freeze hand edits; dual-write until Phase 2 | Documented in AGENTS | **Done** — AGENTS + app values header |
| P1.7 | Optional: Gradle/test guard for catalog | CI guard | **Done as unit test** — `ComposeResourcesCatalogTest` (map size + EN/zh resolve) |

**Key rule:** translators continue to own non-default files; agents only edit default `values/strings.xml`.

**Risk:** incomplete locale files (ru/uk historically thinner) — keep same incompleteness as today; do not invent translations.

---

### Phase 2 — commonMain consumption (delete bags)

**Precondition:** Phase 1 catalog seeded. **Status: done 2026-07-12** (Weblate still deferred).

| ID | Work | Done when | Status |
|----|------|-----------|--------|
| P2.1 | Replace bag construction with `stringResource(Res.string.*)` inside commonMain | Screens read `Res` | **Done** |
| P2.2 | Delete product `*Strings` bags | No bag types for migrated screens | **Done** (Launch/About/OpenSource/Recovery/Editor/Template/Text*/Tile/Save) |
| P2.3 | FuncType labels via `FuncType.toStringResource()` / `.label()` | Shared label path | **Done**; `FuncTitleModel` stays `:app` for `@DrawableRes` icons only until Phase 3 |
| P2.4 | Desktop/iOS remove product EN bags | Hosts no longer pass product bags | **Done** |
| P2.5 | Dual-write | Keep `app/res` until Weblate retarget post-`master` | **Deferred with Weblate** (not sole source yet) |
| P2.6 | Visual smoke zh-CN | Launch/layout OK | **Done** — device zh shows Res labels |

**Order suggestion (lowest risk first):**

1. Launch + About + OpenSource (few strings)  
2. Recovery  
3. Editor chrome (tabs, templates, typeface, tile)  
4. Save/export sheet  

Each sub-slice: compile all targets + one device language smoke.

---

### Phase 3 — Drawables / icons (optional, after strings)

**Status: done 2026-07-12**

| ID | Work | Notes | Status |
|----|------|-------|--------|
| P3.1 | Move shared UI vectors/rasters into `composeResources/drawable` | Sanitized vectors (no `?attr` / `@android`) | **Done** (~34 assets) |
| P3.2 | Brand logo via composeResources | Deleted BrandLogo expect/actual loaders; `BrandLogo` uses `Res.drawable.ic_log_transparent` | **Done** |
| P3.3 | Func + chrome icons → `painterResource(Res…)` / `FuncType.iconPainter()` | `SharedProductDrawables` + Android/Desktop/iOS hosts | **Done** |
| P3.4 | No fonts / seed DBs | Unchanged | **OK** |

---

### Phase 4 — Hardening & docs

**Status: done 2026-07-12**

| ID | Work | Status |
|----|------|--------|
| P4.1 | ADR composeResources + Weblate + spike; retire absolute ban | **Done** — `docs/adr/0019-cmp-compose-resources-i18n.md` |
| P4.2 | AGENTS.md / CONTEXT.md resource layout | **Done** |
| P4.3 | Option C2 plan residual P4.4 | **Done** — superseded by ADR-0019 |
| P4.4 | Catalog key smoke tests | **Done** — `ComposeResourcesCatalogTest` + `BrandLogoAssetTest` (drawable map) |
| P4.5 | In-app language | **Out of scope** (separate if product asks) |

---

## 5. Weblate operations checklist (owner)

Do **not** automate production Weblate config without owner.

1. Add/retarget component file mask to composeResources paths.  
2. Set format to **Compose Multiplatform Resource** (not plain Android if Weblate offers both).  
3. Keep monolingual base = EN `values/strings.xml`.  
4. After first push, verify one language PR round-trips (escaping, plurals if any).  
5. Archive or mark obsolete the old Android `app/src/main/res/values-*` component for product strings.  
6. Communicate to translators: path change only; keys largely same.

---

## 6. B-plan if Phase 0 fails

| Priority | Fallback | When |
|----------|----------|------|
| B1 | Keep bags + Android Weblate as today | Always safe |
| B2 | **Codegen** from Android `strings.xml` → commonMain `object Strings` / maps (script in CI); still one Weblate source at `app/res` | Need iOS/Desktop i18n without composeResources packaging |
| B3 | moko-resources temporary | Only if B2 insufficient and owner accepts extra plugin + later exit |
| B4 | Re-evaluate CMP/AGP versions when CMP-9547 fix lands | Re-run Phase 0 |

B2 is preferred over B3 for this repo (fewer plugins, Weblate unchanged).

---

## 7. Risks & mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| **CMP-9547** silent missing resources on Android APK | High — empty UI | Phase 0 hard gate; device proof |
| Weblate escaping drift | Medium — broken placeholders | Format-specific component; smoke templates with `%1$s` |
| Dual source during migration | Medium — stale translations | Deadline: end of Phase 2; CI key-diff optional |
| Longer CJK strings break layout | Medium | zh-CN visual smoke P2.6 |
| `FuncTitleModel` R ids | Medium | Explicit redesign slice P2.3 |
| iOS resource bundle incomplete | Medium | P0.6; Xcode Copy Bundle / framework packaging notes |
| APK size increase | Low | Record delta; drawable phase optional |
| In-app language expectation | Low | Out of scope; document system-locale only |

---

## 8. Verification matrix

| Gate | Command / action | Phase |
|------|------------------|--------|
| Compile shared all targets | `./gradlew :shared:compileDebugKotlinAndroid` + desktop + ios compile/link as usual | 0+ |
| Android unit (existing) | `./gradlew :app:testDebugUnitTest` non-strict | 1+ |
| Android install smoke | language EN + zh-CN on debug app | 0, 2 |
| Desktop | `:desktopApp:run` language smoke | 0, 2 |
| iOS | simulator launch / existing XCUITest fixture path | 0, 2 |
| Strict goldens | Unchanged; resources plan must **not** require golden rebaseline | all |
| Weblate | One successful PR into composeResources path | 1 |

---

## 9. Suggested slice IDs (for issue tracker / briefs)

| Slice | Title | Depends |
|-------|-------|---------|
| **S-i18n-0** | composeResources packaging spike (1 string, 3 hosts) | — |
| **S-i18n-1a** | Copy EN + locales into composeResources + key inventory | S-i18n-0 |
| **S-i18n-1b** | Weblate component retarget (owner) | S-i18n-1a |
| **S-i18n-2a** | Launch/About/OpenSource → Res; drop bags | S-i18n-1b |
| **S-i18n-2b** | Editor + options + templates → Res; FuncTitleModel | S-i18n-2a |
| **S-i18n-2c** | Save/export + Recovery; remove app dual-write | S-i18n-2b |
| **S-i18n-3** | Optional drawable/logo unification | S-i18n-2a |
| **S-i18n-4** | ADR + AGENTS + Option C2 residual closeout | S-i18n-2c |

---

## 10. Acceptance (plan-level)

Plan is **implementation-complete** when:

1. Phase 0 evidence accepted.  
2. Product UI strings for shared screens resolve from `composeResources` on Android, Desktop, and iOS under system locale.  
3. Weblate writes to composeResources path (or owner-signed exception with B-plan documented).  
4. Major `*Strings` bags for product screens removed.  
5. AGENTS.md no longer bans compose-resources unconditionally; documents layout + Weblate rules.  
6. No golden rebaseline required solely due to this work.

---

## 11. First implementer brief (S-i18n-0 only)

When authorized:

1. Read this plan §4 Phase 0 + current `shared/build.gradle.kts`.  
2. Minimal dependency + one string + one composable witness.  
3. Prove Android APK + Desktop + iOS (best effort on available simulators).  
4. Do **not** move Weblate trees, do **not** delete bags, do **not** commit “full i18n done”.  
5. Report pass/fail with artifacts; stop for owner decision.

---

## 12. Doc impact when implementing

| Doc | When |
|-----|------|
| New ADR (e.g. `0019-cmp-compose-resources-i18n.md`) | After Phase 0 accept or at Phase 4 |
| AGENTS.md | Phase 0 accept (gate wording) + Phase 1 (paths) + Phase 2 (no bags default) |
| Option C2 plan residual P4.4 | Mark superseded by this plan |
| CONTEXT.md | Optional glossary: `composeResources`, product string source |

---

## 13. Summary for owner

| Question | Answer |
|----------|--------|
| Best practice? | **Official composeResources + Weblate CMP format** |
| Multi-language? | **Yes** — `values-*` same model as Android; 13+ locales migrate as files |
| Biggest risk? | **CMP-9547 packaging on current AGP/KMP library plugin** |
| First action? | **S-i18n-0 packaging spike only** |
| moko? | **No** unless spike fails and B2 insufficient |

**Status:** Proposed — ready for owner accept of Phase 0 scope, then implementer brief.
