# S-i18n-1 Weblate retarget checklist (owner/ops)

**Status:** code catalog migrated (2026-07-12); **Weblate retarget DEFERRED until this work is merged to `master`** (owner 2026-07-12)  
**Plan:** `docs/superpowers/plans/2026-07-12-cmp-compose-resources-i18n-plan.md` Phase 1 P1.5  
**Until then:** Weblate continues to own `app/src/main/res/values-*/strings.xml`. Agents dual-write new default keys into both trees; do **not** retarget Weblate on the feature branch.

## After merge to `master` — new component (or retarget existing)

| Field | Value |
|-------|--------|
| File mask | `shared/src/commonMain/composeResources/values-*/strings.xml` |
| Monolingual base | `shared/src/commonMain/composeResources/values/strings.xml` |
| File format | **Compose Multiplatform Resource** (`cmp-resource`, Weblate ≥5.12) |
| Template for new translations | Empty (or same as base) |

Do **not** keep using plain “Android string resources” for this path if Weblate offers the CMP format — escaping differs slightly.

## Dual-write period (until Weblate retarget + Phase 2)

| Path | Role |
|------|------|
| `shared/.../composeResources/values*` | Multiplatform product UI / `Res.string` (seeded copy of catalog) |
| `app/src/main/res/values*` | **Weblate still points here** until post-`master` retarget; also Android `R.string` / bags until Phase 2 |

Until Weblate is retargeted after merge to `master`:

1. New keys: add to **both** default EN files (`composeResources/values` + `app/res/values`).
2. Non-default locales: Weblate updates **`app/res` only**; agents/scripts **sync** those into `composeResources` when needed (no hand-invented translations).
3. Agents: never hand-edit non-default locale files in either tree.
4. After retarget + Phase 2 bag deletion: composeResources becomes sole product source; deprecate product keys under `app/res` as needed.

## After retarget

1. Pull once; confirm a zh-CN (or other) PR lands under `composeResources/values-zh-rCN/`.
2. Spot-check placeholders (`%1$s`) and apostrophes (`\'`) still parse.
3. Mark old Android `app/src/main/res/values-*` component read-only or archive for product UI strings.

## Not done by agents

- Changing hosted Weblate project configuration
- Bulk inventing missing translations for thin locales (ru/uk stay incomplete as today)
