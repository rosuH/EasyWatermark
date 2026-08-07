# ADR-0023: Launch↔Editor uses product-shell route transitions

**Status:** Accepted (2026-08-07)  
**Context slice:** Motion / parity vs Compose navigation  
**Related:** ADR-0011, ADR-0015, I3 MotionPolicy

## Context

Production v2.10.0 morphs Launch→Editor with **in-place SpringAnimation** lists on
`LaunchView` (staggered appear/disappear). The Compose product shell is a **route
destination** model (`ProductShellHost` / `ProductShellNav`): Launch and Editor are
distinct routes, so transitions are **horizontal slide + fade** at
`EwmTheme.motion.shellShortMs` (240ms Full), already scaled by `MotionPolicy`.

Re-implementing spring morph across route boundaries is expensive and fights Navigation
composition. Owner (2026-08-07) authorized shipping the shell route family as intentional.

## Decision

1. **Keep** Launch↔Editor as short H-slide + fade under `MotionPolicy` (not LaunchView springs).
2. Document as intentional divergence from production morph choreography (parity is control
   structure + chrome, not this enter/exit family).
3. Continue wiring other product timed animations to `motionDurationMs` (option panel,
   gallery host/FAB, export wipe, About activity-style transition).

## Consequences

- **Positive:** predictable, policy-aware route motion; no fragile dual-tree spring port.
- **Trade-off:** Launch↔Editor feel differs from v2.10.0 morph (accepted).
- **Revert path:** only with a new ADR and a dedicated spring-parity slice.
