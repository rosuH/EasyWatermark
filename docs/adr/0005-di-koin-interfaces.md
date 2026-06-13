# ADR-0005: DI — interfaces bound in Koin modules; expect/actual only at the edges

**Status:** Accepted (2026-06-13) · **Plan ref:** D5

## Context
Official KMP guidance prefers plain interfaces over expect/actual classes (testable, multiple impls, no Beta compiler flag) and explicitly recommends using the existing DI framework for platform dependencies. The app already uses Koin 4.2.1.

## Decision
Platform capabilities (ImageCodec, PhotoLibraryStore, share, permissions, dynamic color, crash hook) are interfaces in commonMain, bound in per-platform Koin modules; `expect val platformModule: Module` is the only expect/actual wiring. ViewModels resolve via `koin-compose-viewmodel`'s `koinViewModel()` in commonMain.

## Consequences
- ViewModel split required: commonMain state core + platform use-cases behind the interfaces (MainViewModel kill-list, plan C1.1).
- Validate `koinViewModel()` lifecycle/clearing on iOS early in C5 (known sharp edge).
