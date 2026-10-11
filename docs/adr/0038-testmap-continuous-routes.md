# ADR-0038: Testmap continuous routes retain independent cases

**Status:** Proposed — owner approved the execution mode; implementation awaits review.
**Related:** ADR-0037

## Context

Selecting several device cases currently runs an isolated setup, session and restoration for each case. This does not represent one user journey and reconnects the monitor at every boundary.

## Decision

Add an explicit continuous-route mode beside the existing independent-case mode. The backend validates the ordered selection before execution. A route uses one platform, one device, one initial setup, one SDK session and one final restoration, including failure and Stop. Each step retains its source case, source step and script digest.

Only audited script fragments with compatible entry and exit states can join a route. Checks, unsupported fragments and mixed platforms are rejected with a reason. Special fixture, export and recovery gates must not be bypassed by concatenating scripts. The first implementation supports a bounded set of compatible scripts; it does not promise arbitrary graph walks.

A route has its own task identity and remains review_required after successful execution. It does not mark its source cases as independently passed, change map drive classifications or grant Human Confirm. Existing independent execution and acceptance gates remain available.
