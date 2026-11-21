# S-i18n-1 Weblate retarget record (owner/ops)

**Status:** **Completed (2026-11-21)** — `zh-rCN` backlog synced in PR #461 (`775e357a`), Hosted Weblate `easywatermark/app-strings` retargeted to `composeResources` (`cmp-resource`), `app-store-metadata/zh_Hans` review-protected, and EN dual-write retired.  
**Plan:** `docs/superpowers/plans/2026-07-12-cmp-compose-resources-i18n-plan.md` Phase 1 P1.5  

## Hosted Weblate configuration (`easywatermark/app-strings`)

| Field | Value |
|-------|--------|
| File mask | `shared/src/commonMain/composeResources/values-*/strings.xml` |
| Monolingual base | `shared/src/commonMain/composeResources/values/strings.xml` |
| File format | **Compose Multiplatform Resource** (`cmp-resource`, Weblate ≥5.12) |
| Language code style | `android` (`values-zh-rCN`, `values-de-rDE`, etc.) |

Do **not** switch back to plain “Android string resources” (`aresource`), and do **not** use Android backslash escapes (`\'`, `\?`, `\"`) in `composeResources` XML — Compose Multiplatform only unescapes `\n`, `\t`, `\uXXXX`, and `\\` at build time and renders `\'`, `\?`, and `\"` literally.

## Post-retarget workflow (EN dual-write retired)

| Path | Role |
|------|------|
| `shared/src/commonMain/composeResources/values*/strings.xml` | **Sole** product UI string catalog & Hosted Weblate `app-strings` target |
| `app/src/main/res/values*/strings.xml` | Legacy Android XML retained only for `app_name` (`AndroidManifest.xml`) and historical continuity; no longer tracked by Weblate |

1. New product keys: add to `shared/src/commonMain/composeResources/values/strings.xml` only (no EN dual-write to `app/src/main/res/values/strings.xml`). Use plain XML text (e.g., `'` / `?` / `"` or XML entities if needed, never `\'` / `\?` / `\"`).
2. Non-default locales: owned by Hosted Weblate under `shared/src/commonMain/composeResources/values-*/strings.xml`; agents must never hand-edit or invent translations.
3. Store metadata (`easywatermark/app-store-metadata`): `translation_review` is enabled (`commit_policy: 0`) with `zh_Hans` units (`title.txt`, `short_description.txt`, `full_description.txt`) marked `Approved` (`state: 30`) so non-reviewers submit suggestions rather than overwriting native Chinese store copy.

## Retarget execution summary (2026-11-21)

1. Reverted unintended `fastlane/metadata/android/zh-CN/full_description.txt` overwrite and merged PR #458 (`5c61fa13`).
2. Synced 91 accumulated `zh-rCN` strings + `pick_via_system` from `app/src/main/res/values-zh-rCN/strings.xml` into `composeResources` in PR #461 (`775e357a`).
3. Retargeted `easywatermark/app-strings` via Weblate API to `cmp-resource` on `composeResources`; verified all 16 locales preserved (`zh_Hans` 190/208, `fuzzy: 0`, `failing: 0`) and reset synthetic format-switch pending flags (`pending_units: 0`).
4. Cleaned legacy Android `\'`, `\?`, `\"` escapes across 9 `composeResources` locale files (59 strings) with runtime `.cvr` regression coverage across all 16 locales in `ComposeResourcesCatalogTest`.

## Not done by agents

- Bulk inventing missing translations for thin locales (ru/uk stay incomplete as today)
