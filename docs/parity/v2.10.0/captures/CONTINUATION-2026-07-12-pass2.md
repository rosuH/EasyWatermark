# Parity capture pass 2 — 2026-07-12 (after owner pick policy)

**Device:** emulator-5554 · en · font 1.0  
**Actions:** `pm clear` debug; re-grant media; capture gallery primary + top-right system picker; clean editor/export.  
**Grok:** opened new PNGs.

## New files (`debug/en/dark/`)

| File | What |
|------|------|
| `launch-idle-clean.png` | After app data clear |
| `gallery-inapp-primary.png` | Choose Images → **in-app** “Choose picture” (product primary) |
| `gallery-topright-system-picker.png` | Gallery **search/top-right** → **system Photo Picker** (product secondary) |
| `editor-text-mode-clean.png` | After clear + image (via system picker Done); default text `👋 DO NOT REDISTRIBUTE` |
| `export-sheet-open-clean.png` | Save sheet after clean editor |

## Policy verification (owner 2026-07-12)

| Step | Debug result |
|------|----------------|
| Choose Images | **In-app gallery** ✅ |
| Gallery top-right `search` | **System Photo Picker** (`com.google.android.photopicker`) ✅ |
| Production Choose Images (rechecked) | Still **system Photo Picker** as primary — **prod residual** vs policy |

## Clean editor vs production (Grok)

| Item | Prod | Debug clean | Notes |
|------|------|-------------|-------|
| Watermark text default | 👋 DO NOT REDISTRIBUTE amber dense tile | Same text/color/density after clear | Pref pollution fixed |
| Top leading | App logo | **Back arrow** | Still P0 chrome |
| Content text field | Inline under strip with caret | **Not shown** on Content tab | P0 content surface |
| Text/Icon selected chrome | Text chip selected | Weaker selection affordance | P1 |
| Templates entry | (prod chrome) | List icon bottom-right | OK product |
| Photo strip | Larger bordered thumb | Smaller thumb | P1 |

## Export clean vs production (Grok)

| Item | Prod | Debug clean |
|------|------|-------------|
| Controls | Format JPEG, Quality 80, list 0/1, Export CTA | Same |
| Sheet chrome | Over dimmed editor | Full-screen sheet + drag handle |
| Quality slider | Continuous thumb ~80% from left | **Discrete steps**; active fill ~80% but **start knob/visual still wrong vs simple prod slider** — still P1 |

## Punch-list remaining (no self-sign)

### 07
1. Launch logo tint (optional).  
2. **Prod residual:** Choose Images → system picker (policy wants in-app first). Owner: accept as known v2.10.0-on-API36 behavior, or plan debug/prod alignment work.  
3. Secondary path **proven on debug**; production secondary N/A if no in-app gallery entry.

### 08
1. Editor top bar logo vs back.  
2. Content tab inline text editing surface.  
3. Export quality slider visual / sheet overlay.  
4. Optional: Style/Layout/icon mode pairs.

## Explicit

No owner screen sign-off claimed. No Phase B code polish in this pass (capture + docs only).
