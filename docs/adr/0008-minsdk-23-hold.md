# ADR-0008: minSdk stays 23

**Status:** Accepted (2026-06-13), revisit with install-base data · **Plan ref:** D8

## Context
Raising minSdk to 29 would delete the pre-Q save path and the `WRITE_EXTERNAL_STORAGE` manifest entry, but drops Android 6–9 users (F-Droid audience skews older devices). Room 2.8 / Lifecycle 2.10 require ≥23.

## Decision
Keep minSdk 23. The pre-Q path lives entirely inside the Android `PhotoLibraryStore` actual, so it neither blocks nor pollutes common code.

## Consequences
- One extra branch in one androidMain actual; zero commonMain impact.
- Re-open only with install-distribution data in hand.
