# ADR-0039: Testmap Android model-directed journey

**Status: Proposed** — requires owner sign-off.

## Context

The existing Agent Device cases provide repeatable scripted checks. A model-directed journey can also exercise a user path whose choices depend on the live picker and editor UI. It must keep one selected Android device under one controller and leave independently reviewable evidence.

## Decision

Add one Android-only Testmap task backed by the pinned E2E runner and existing Testmap subprocess lifecycle. The Python runner owns the selected device, run-specific home fixture, backup journal, timeout/stop handling, and restoration. The agent starts at the system photo picker, selects the owned fixture, edits the watermark, and exports in one app session.

Treat the native report, trace, and step screenshots as journey evidence. Separately bind the export to the run-owned fixture and verify its MediaStore identity and file bytes before accepting the export claim. Always restore the fixture and prior device state. Missing model/device prerequisites are explicit blocked results; failed journey, export, or restoration checks are explicit failures. A successful run ends in `review_required`; it does not create Human Confirm or replace existing scripted cases.

Add the diagnostic sibling task `android-large-image-preview` through the same runner and recovery lifecycle. It uses one fixed 49,861×1,350 synthetic PNG and makes no export claim. The native test fails if preview fails; `bug_reproduced` is set only when fresh logs bind the exact fixture dimensions and sample size to a same-process Canvas fatal.

## Consequences

- The task is available only for an explicitly selected Android device and runs alone once per request.
- Run artifacts stay attached to the run and expose the native report, trace, screenshots, and independent export proof.
- This proposal does not add iOS coverage, a task matrix, a new runner framework, or changes to product code.
- Owner sign-off is required before treating this runner as an accepted Testmap architecture.
