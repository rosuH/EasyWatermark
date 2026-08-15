# ADR-0029: iOS Library Read for unwatermarked first paint

**Status:** Accepted (owner 2026-08-15, grill-with-docs Rounds 1–3)  
**Owner:** Q1=B, Q2=A, Q3=A, Q4 superseded by Q1, Q5=A, Q6=A, Q7=B, Q8=C, Q9=A, Q10=A, Q11=B, Q12=A, Q13=A, Q14=A, Q15=A  
**Related:** ADR-0021 (path-first Session), ADR-0009 (EXIF strip on export), ADR-0028 (Coil UI only; compose/export stay non-PhotoKit)

iOS album HEIC cold switch is ImageIO-decode-dominated (~214 ms). PHPicker needs no read; save is add-only. `PHImageManager` is the public way to reuse Photos derivatives, but it needs **Library Read** and a `PHAsset` id (today discarded). Staging into Documents (ADR-0021) does not make decode slower; it orphans PhotoKit.

**Decision:** Optional **Library Read** accelerates **already-picked** photos only. Session/export stay owned paths. Cold switch **first paints a Library derivative** (unwatermarked) in the primary pane, then **Watermarked preview** from ImageIO + `CommonWatermarkPipeline`, short crossfade. PhotoKit pixels never enter the pipeline and never land in `SourcePlaceholder` / `Watermarked` caches.

Allow All → Library derivative **on** by default. Limited still tries fetch; empty → ImageIO (not a broken editor). First real need: one system prompt; if not Allow All, a **Library Read upsell** every Editor entry until Allow All (Limited → all photos; Denied → Settings). Dismiss hides that visit only. Banner copy qualitative until a measured gate. ① measures time-to-Library-derivative; Watermarked preview must not regress vs today’s ImageIO. Carry `itemIdentifier` including picker preselection; app-owned disk thumbs are a later slice. `DEVICE_PERF_PHOTOKIT` may ship in Release if redacted (no paths / no asset ids). Hard promise remains fully offline / no tracking.

## Considered and rejected

- Compose watermark onto PhotoKit for “watermark appears faster” — owner chose photo-first (Q1=B).  
- Require read to use the editor — optional only (Q2=A).  
- Quote an unmeasured “30% faster” — forbidden until a device gate (Q8).  
- Persistently hide the upsell after one dismiss — owner wants it every Editor visit until Allow All (Q11=B).
