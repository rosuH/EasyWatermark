# 03 — A5c iOS About production scope or owner-signed absence

**What to build:** Either production shared `AboutScreenShell` on iOS **if the product needs About**, **or** an **owner-signed** “no About on iOS Phase A” absence exception. Do not invent an About screen only to fill the matrix. Do not move `AboutViewModel` to commonMain without a real multi-platform consumer (§6.12).

**Blocked by:** None — can start immediately (parallel with 01, 02, 04).

**Status:** ready-for-agent

- [ ] Production About route **or** owner-signed absence recorded on this ticket
- [ ] No speculative About feature expansion beyond shared shell content already used on Android
- [ ] No new deps; not Phase B pixel work; not Desktop About (see 04)
