# iOS 水印预览性能 · 切图 + 拖参（2026-08-15）

胶片条调研搁置。本笔记只覆盖用户指定的两块：

1. **冷切图** → 水印预览出现  
2. **拖参实时预览**（透明度 / 间距 / 偏移等）

权威设备数字：`2026-08-14-ios-preview-perf-leftovers.md` S1（iPhone 16 Pro，相册 HEIC）。

---

## 一句话

| 场景 | 瓶颈 | 量级 |
|---|---|---|
| ① 冷切图 | **底图 HEIC 全解**（非「画水印」） | 总 ~226 ms，decode ~214 ms（≈94%），compose ~7 ms（≈3%） |
| ② 拖参 | 底图已驻留时 **compose 已便宜**；CLAMP 拖偏移曾会排队多次全解 | 命中 Source 时 ~7 ms/次；未背压时拖动手势可叠 N 次 ~200 ms decode |

胶片条 / subsample / QL / sidecar **不解决 ① 的主体**；对 ② 几乎无关。

---

## ① 冷切图 → 水印预览

### 已证实

- 归因：`IosPreviewBench.Attribution`（decode / compose / icon / dispatch）。
- 架构已分：`SourcePlaceholder` = 贵解码；`Watermarked` = 在已有底图上 compose。
- Phase 1 CG→Skia：预览路径 **内存**大降，e2e 延迟几乎不动（HEIC 仍被 ImageIO 主导）。
- 相机 HEIC = tiled HEVC 网格 → 请求 720/1920 仍付接近「全解」的固定成本（与胶片条同源机制，但档位不同）。
- `IosHeifDecodePolicy.Preview`：**`allowSubsample = false`**。真机 subsample 在 1920 约 143→124 ms（−19 ms），有限；无法把冷切图从百毫秒级打掉。

### 不做 / 低 ROI

- 再拧 ImageIO 选项字典、FFmpeg/libheif、Skia HEIF、PhotoKit 读权限。
- 用胶片条 sidecar/QL「间接」加速主预览（路径/缓存键都不是同一条）。

### 对 ① 有意义的杠杆（按 ROI）

| Rank | 杠杆 | 作用 | 状态 |
|---|---|---|---|
| A | **邻居预热已存在**（focus ±2 Watermarked） | 切到已预热邻图 → 接近 compose 级 | 代码在；需用归因看 hit 率 |
| B | **Preview 开 subsample（仅预览策略）** | 真机 ~−20 ms @1920 量级 | 未开；需清晰度截图门（ADR-0010） |
| C | **降低空闲预览 long-edge**（1920→1440/1080） | 略减 resample/内存，decode 地板仍在 | 产品清晰度权衡 |
| D | **感知：先 Source 占位再 Watermarked** | 不降总功，改善「白屏感」 | 需确认当前是否已足够 |
| E | Phase 2 skip-Draw | 微毫秒级，相对 200 ms 可忽略 | 延期，除非内存再压 |

**① 的诚实上限：** 单张冷相册 HEIC，在不换解码器、不降清晰度的前提下，大约就在 **~120–220 ms** 这一档；要「瞬间」，只能 hit 缓存或先付过邻居预热。

---

## ② 拖参实时预览

### 已证实 / 已落地（部分未测）

- 配置变更：清 `Watermarked`，复用 `Source` → **只付 compose**（~7 ms 量级，S1）。
- 图片水印 icon：`IosWatermarkIconCache`（避免每次 compose 重解 icon）。
- CLAMP 拖偏移：`IosDraftRenderConflator`（飞行中最多 1 + 1 pending）+ draft **共享 Source**（S4）。目标：一势一解，而非一采样一解。
- **S4 真机 `draftSamples` vs `draftRenders` 仍 outstanding。**

### 对 ② 有意义的杠杆

| Rank | 杠杆 | 作用 | 状态 |
|---|---|---|---|
| 1 | **真机验证 S4** | 确认拖动时 `draftRenders ≪ draftSamples`，且无二次 HEIC 全解 | **下一实验** |
| 2 | 确认非 CLAMP 控件（透明度/间距/字号）是否都走「清 Watermarked + 复用 Source」 | 避免某控件误触发重解 | 代码审计 + 归因 |
| 3 | 拖动中降档（更短 long-edge draft） | 进一步砍 compose/带宽 | 已有 draft 短边设计；可测是否足够 |

若 Source 未命中（被逐出），拖参会退化成 ① 的冷解——因此 S5 驱逐优先级（先丢 Watermarked、保 Source）对 ② 也重要；代码已改，真机压力下 hit 率未单独报。

---

## 推荐实验顺序（只服务 1+2）

1. **E-live：CLAMP（及常用滑条）真机归因**  
   - 看 `draftRenders/draftSamples`、Attribution 里 decode 是否在拖动中接近 0。  
   - Pass：快速拖偏移时 decode 阶段中位 ≈ 0（Source hit）；画面跟手无明显排队卡顿。

2. **E-switch：冷切图归因 + 邻居 hit**  
   - 连续切 8 张：首张 ~200 ms 类；回头切已预热邻图应落在 compose 级（&lt;20 ms 量级）。  
   - Pass：邻图 hit 可复现；未 hit 时仍归因到 decode 而非 compose。

3. **可选 E-preview-sub：** Preview 策略开 subsample，1920 真机 + 截图清晰度。  
   - 预期小幅；不承诺「体感飞跃」。

4. **不要**把胶片条 E1（从预览派生 128）当成 ①② 的主修复——那是 chrome 优化。

---

## 与胶片条调研的关系

同源机制（HEIC tile 全解）解释了为什么预览也贵；**解法分叉**：

- 胶片条：派生小图 / QL / 少付 128 全解  
- 水印预览：保 Source、背压拖动、邻居预热、（可选）预览 subsample / 降档  

详见旁支：`2026-08-15-ios-filmstrip-heic-latency-synthesis.md`（不驱动本优先级）。
