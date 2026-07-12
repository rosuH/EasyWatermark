# 04 — A5d Desktop editor-window-only exception registry

**What to build:** An explicit **editor-window-only** Phase A exception registry for Desktop: which Android product screens are intentionally absent and why (window entry, AWT file dialogs, no in-app gallery, no About, packaging edge, etc.). **Do not invent** launch/gallery/about product screens on Desktop for matrix symmetry. If owner instead requires full multi-screen Desktop product, stop with a decision package — full nav is out of scope for this ticket unless re-scoped.

**Blocked by:** None — can start immediately (parallel with 01–03).

**Status:** ready-for-agent

- [ ] Exception registry table published on this ticket (surface → edge/absent → reason)
- [ ] Confirms Desktop already uses shared editor shell/options where claimed; lists remaining edges
- [ ] No invented Launch/Gallery/About Desktop UI; no new deps; Android native renderer policy untouched
