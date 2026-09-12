# ADR-0036: Pin R8 pg-map-id on the GitHub APK

**Status:** Proposed  
**Related:** ADR-0034 (Ship Android release)

## Context

AGP 8.12+ embeds a 64-hex R8 `pg-map-id` in `classes.dex` (`~~R8{...}` and `r8-map-id-<id>`). The id is a hash of that machine’s mapping body, so IzzyOnDroid and GitHub builds diverge. 3.0.0 reproduced after they pinned the published APK with `reproducible-apk-tools` v0.3.2. Play AAB and `mapping.txt` are a different surface. 3.0.0 is already shipped; do not rebuild it.

## Decision

The next GitHub Release APK (and Build-packages signed APK) pins the id to 64 zeros **before** signing:

`0000000000000000000000000000000000000000000000000000000000000000`

Order is fixed:

1. `inplace-fix.py fix-pg-map-id` on the unsigned APK (fetch `reproducible-apk-tools` v0.3.2 / `ca728486d42a79f1c9ec0c6ec755c39f251911a8` at CI runtime; do not vendor the AGPL tools). The pin step must not see signing secrets.
2. `zipalign.py --page-size 16 --pad-like-apksigner --replace`
3. `apksigner sign --alignment-preserved true`, then `apksigner verify` and `zipalign -c -P 16 -v 4`

Play AAB and `mapping.txt` are not pinned. Do not change R8/ProGuard or app source. Fail the job if `classes.dex` does not contain exactly two copies of that 64-hex string.

## Consequences

- F-Droid/Izzy can apply the same 64-zero pin instead of chasing each release hash.
- 3.0.0 stays the published exception (they pinned that build’s real id).
- Signing must keep the Python zipalign extra field (`--alignment-preserved`).
