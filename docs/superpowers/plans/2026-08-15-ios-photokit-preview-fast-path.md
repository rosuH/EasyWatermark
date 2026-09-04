# iOS PhotoKit / Library Read 实施计划（2026-08-15）

**Grill 锁：ADR-0029 Accepted。** 本文件旧文里「PhotoKit 先 compose 水印」**作废**。产品合同以 ADR 为准：冷切图先铺未叠水印的 **Library derivative**，再交叉淡入 ImageIO **Watermarked preview**；PhotoKit 永不进 `CommonWatermarkPipeline` / `SourcePlaceholder`。

**状态：** 计划待实现（无生产代码）

**依据：** ADR-0029（已 Accepted，读相册用于预览/chrome 缩略图）、ADR-0021（path-first 渐进导入，Session 仍只持有 owned path）、ADR-0009（导出剥离 EXIF）、ADR-0010（截图验证，不做字节 golden）、ADR-0028（Coil 3 负责 UI 缩略图）、ADR-0020（单 scene）、J5（`Shared.framework` 只保留经典 ObjC 动态框架，公开面不得随意增长）
**证据：** `docs/superpowers/research/2026-08-15-ios-watermark-preview-perf-1plus2.md`、`docs/superpowers/research/2026-08-15-ios-system-thumb-cache-apis.md`、`docs/superpowers/research/2026-08-14-ios-preview-perf-leftovers.md`（S1/S3/S4/S5）

---

## 1. 背景与目标

### 1.1 问题

iPhone 相册 HEIC 的冷预览是**解码主导**，不是"画水印"慢。S1 真机（iPhone 16 Pro，长边 ≥3000，n=8）：

| Lap | Total | Decode | Compose | Icon | Dispatch | Other |
|---|---:|---:|---:|---:|---:|---:|
| L1 | 226 ms | 214 ms (94%) | 7 ms (3%) | 0 | 0 | 1 ms |
| L2 | 234 ms | 219 ms | 9 ms | 0 | 0 | 2 ms |

原因是相机 HEIC 是 tiled HEVC 网格，`kCGImageSourceCreateThumbnailFromImageAlways` 会**先全解再缩**，因此请求 128 px 和请求 1920 px 成本几乎一样（S3：`io128_med=202 ms` vs `io1920_med=138 ms`，反而更贵）。`kCGImageSourceSubsampleFactor` 在 1920 只有约 −19 ms，在 128 反而 +15 ms —— 换句话说，**在 ImageIO 这条路上没有剩余空间可挖**。

ADR-0021 把 picker 文件 stage 成 app-owned path（`ewm_import_provisional_*` → `ewm_src_*`），这带来了内存边界和渐进填充，但也丢掉了唯一一条"不解码就能拿到照片缩略图"的公共 API：`PHImageManager` 只接受 `PHAsset`，不接受文件 URL。ADR-0029 已由 owner 批准补上这条路：**用户授权的相册读权限，只作为快路径**。

### 1.2 成功标准

**① 冷切图 → 水印预览出现**

- 现状（授权前 / 未命中）：`IosProductRootHost.onImageSelected` → `previewImages.peekCached(watermarkedPreviewKey)` 未命中 → `renderPreviewForCurrentSelection` → `IosPreviewRaster.decodeSourcePlaceholder` → `IosImageIODecoder.decodeThumbnail`（~200 ms）→ `CommonWatermarkPipeline.compose`（~7 ms）。
- 目标（`.authorized` 且 `PHAsset` 可解析）：**首帧水印画面在 `DEVICE_PERF_SWITCH` 上落到 ~40 ms 量级**（PhotoKit derivative + 一次 compose），随后 ImageIO 全质量版本静默替换。
- 目标（Limited miss / `.denied` / `.notDetermined`）：**不劣化**，中位数与今天的 miss 档持平（±10 ms 内），且不得引入新的等待或空白帧。

**② 拖参实时预览（透明度 / 间距 / 偏移）**

- 现状：命中 Source 时只付 compose（~7 ms 量级）；`IosDraftRenderConflator` 保证飞行中最多 1 + 1 pending；draft 复用 `sourcePreviewKey` 的 Source 解码。
- 目标：**PhotoKit 不进入 draft 路径**。拖参唯一的改善是"Source 更早驻留"：P3 之后，ImageIO 的 `SourcePlaceholder` 仍然照常在后台填充，所以用户切到一张图、稍等片刻再拖参时，Source 命中率不降反升（不会因为快路径而跳过 ImageIO 解码）。
- 硬性要求：`draftRenders ≪ draftSamples` 的比值不得因本期改动变差；拖动过程中 Attribution 的 decode 阶段中位仍应 ≈ 0。

胶片条（`EditorFilmstripScaffold` 56/48/40 + Coil `ProductThumb`）是次要 chrome，可以复用同一个 PhotoKit producer，但**不作为本期的成功判据**。

---

## 2. 非目标

1. **不**用 PhotoKit 替换全分辨率导出解码。导出仍是 `IosFinalRenderSpine` + `IosRenderRequest` 读 owned path 全解、显式 sRGB、剥离 EXIF（ADR-0009）。PhotoKit 只产生屏幕像素。
2. **不**把 `PHAsset` 变成 Session 的 source of truth。Session 继续只发布 Ready 的 `ImageInfo(uri = MediaRef(ownedPath))`（ADR-0021 第 2 条）。
3. **不**声称 PhotoKit 首帧与 ImageIO 预览像素一致。系统 derivative 可能更软、色调不同（HDR gain map 处理不同），并可能带 `PHImageResultIsDegradedKey = true`。**渐进精修（先快后好）是本计划明确接受的行为**，验证方式是看截图（ADR-0010），不是比字节。
4. **不**新增 `Shared.framework` 公开 ABI。新代码全部 `internal`（`iosMain`）+ 现有 NotificationCenter 控制面（ADR-0021 第 3 条 / J5）。
5. **不**做 commonMain `expect`/`actual` 的 PhotoKit 抽象。Android 已有 `ContentResolver.loadThumbnail` 路径，没有第二个真实消费者（ADR-0029 第 7 条 / 仓库约定）。
6. **不**持久化 `localIdentifier` 到 DataStore / Room。内存态、host 生命周期内有效。
7. **不**在本期引入 `QLThumbnailGenerator`、libheif/FFmpeg、CoreSpotlight。研究已把它们分别标成"候选 B / 关闭"。
8. **不**为导出或保存要求读权限。add-only 保存（`NSPhotoLibraryAddUsageDescription` + `ImageExport.saveToPhotos`）保持不变。
9. **不**引入多 scene 相关改动（ADR-0020，`IosAppServices` 仍是进程内单 Session）。

---

## 3. 架构决定

### 3.1 阶梯（cheapest first，每一级可独立回退）

预览首帧（① 的主路径）：

```
0. previewImages.peekCached(watermarkedPreviewKey)              — 今天已有（wm / wm_optimistic 命中）
1. previewImages.peekCached(sourcePreviewKey)                   — 今天已有（source 命中，仅占位）
2. PhotoKit fast source: PHImageManager.requestImage(asset,     — 新增（P3，需授权 + 身份可解析）
   targetSize = committedPreviewBucket, .opportunistic, .fast)
   → compose → 立即上屏（不写 Watermarked 缓存）
3. ImageIO: IosPreviewRaster.decodeSourcePlaceholder            — 今天已有，永远保留为地板
   → 写 SourcePlaceholder 缓存 → compose → 写 Watermarked 缓存 → 替换上屏
```

关键约束：

- **第 3 级永远是地板。** 无权限、Limited miss、`PHAsset` 解析失败、PhotoKit 超时——一律直接走今天的路径，不阻塞编辑器，不重复弹窗。
- **第 2 级永不写 `Watermarked` 缓存。** 这条规则和 draft 的规则同源（`renderPreviewForCurrentSelection` 里 `watermarkedPreviewSourcePath = sourcePath.takeIf { !isDraft }`）：低质量帧不得被后续的 cache hit 复用，否则用户会永久停在软图上。
- **第 2 级也不取代第 3 级。** 快路径上屏之后，ImageIO 解码**照常继续**，填 `SourcePlaceholder`（②拖参依赖它）和 `Watermarked`（邻居预热与再次切回依赖它）。

胶片条（次要，P4 可选）：在 `ProductThumbFetcher`（`shared/src/iosMain/.../ui/image/ProductThumbFetcher.ios.kt`）之前插一个 PhotoKit fetcher，未命中时原样落回现有 `SourceFetchResult` + `IosHeifImageDecoder`。必须遵守现有 `IosHeifDecodePolicy.SampledMode.Never` 的 `isSampled = false` 约定，否则 `LazyRow` 回收会闪白。

### 3.2 Session 仍然只有 path

不变量（逐条对应 ADR-0021）：

| 不变量 | 保障点 |
|---|---|
| Session 只发布 Ready owned path | `IosProgressiveImportController.adoptFileReady` → `IosSourceStager` → `AppIntent`；本计划不动这条链 |
| 渲染/导出不知道 PhotoKit 存在 | `CommonWatermarkPipeline` / `IosFinalRenderSpine` 签名不变 |
| 零公开 ABI 增长 | 新增全部 `internal`；控制面复用 `ProgressiveImportNotifications` |
| 身份不落盘 | 新 registry 为内存 map，`IosProductRootHost.dispose()` / `releaseEditorMediaResources()` 清空 |

### 3.3 `PHAsset` 身份放在哪：Host（Swift）产出，Kotlin（iosMain）消费

- **身份来源只能是 Swift。** `PhotosPickerItem.itemIdentifier` 只在 `ContentView.swift` 的 `.photosPicker(..., photoLibrary: .shared())` 条件下非 nil（今天已经满足），`PhotoImportCoordinator` 消费 `PhotosPickerItem` 并只吐 path，标识符在那里被丢弃。
- **像素生产放在 Kotlin `iosMain`。** K/N 平台 klib `org.jetbrains.kotlin.native.platform.Photos` 已验证存在（研究 §11）。理由：
  1. 结果要直接进 `IosPreviewImageRepository` / `CommonWatermarkPipeline.compose`，放 Kotlin 侧免去把像素塞进 NotificationCenter；
  2. 复用已有的 CGImage→Skia 原语（目前是 `IosImageIODecoder` 内的 `private fun cgImageToSkiaOwned` / `cgImageToSkiaBitmapOwned`，本期把它提取成 `internal object IosCgImageBridge`，仍在 `iosMain`，不进公开面）；
  3. Swift 侧零改动（除 Info.plist 与身份透传），XCUITest 不受影响。
- **备选（若 owner 更希望权限相关代码集中在 Swift）：** 在 `WatermarkWorkflow.swift` 加一个 PhotoKit producer，通过一个新的 NC 名把 `CVPixelBuffer`/`Data` 回传。**不推荐**：payload 变重、跨语言拷贝、与现有 repository 单飞（single-flight）语义割裂。

### 3.4 NotificationCenter payload 增长

只加**一个可选字符串键**：

```swift
// ProgressiveImportNotifications.Key
static let assetId = "assetId"   // PhotosPickerItem.itemIdentifier，可能为 nil
```

- 生产点：`PhotoImportCoordinator.QueuedItem` / `RetrySource` 各带一个 `assetId: String?`；`publishProvisional` → `postFileReadyAndAwait` 时附带。
- 消费点：`IosProgressiveImportController.handleFileReady` 用现成的 `note.userInfoString(KEY_ASSET_ID)`（该 helper 已对 blank / `"null"` 做过滤），在 `adoptFileReady` 成功后写入 registry，**键为最终 owned path**（`ewm_src_*`），因为 provisional path 会在采纳时被替换。
- 兼容性：旧 Kotlin 读不到新键就是 `null`，旧 Swift 不发新键也是 `null`。双向可回退，无版本协商。
- `begin` / `finish` / `cancel` / 控制面各键均不变。

### 3.5 授权能力

- 新 `internal object IosPhotoLibraryAccess`（`iosMain`）：`status()` 读 `PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)`；`requestIfNeeded()` 只在**首次真正需要 PhotoKit 像素时**调用一次，并把"本进程已请求过"记在内存里，杜绝循环弹窗（ADR-0029 第 5 条）。
- **不在冷启动请求**。ADR-0029 第 1 条明确写了请求时机是"首次编辑器需要 PhotoKit 像素时，或一个显式的设置入口"。
- `PHPhotoLibraryChangeObserver` / 授权状态变化监听**不在本期范围**；状态在每次快路径尝试前现读，成本是一次同步 enum 读取。

---

## 4. 分阶段实施

每一阶段一个 commit、一个可回退点；PR body 记录 rollback HEAD（沿用 J4 的纪律）。

### P1 — 透传 `itemIdentifier`（免授权收益，先落地）

**为什么先做：** 完全不需要权限、不需要 Info.plist、不改变任何像素，却是后面每一级的前提；即使 owner 之后改主意不做 PhotoKit，这一片也独立有价值（研究 §9.2）。

**要动的文件**

- `iosApp/iosApp/ProgressiveImportNotifications.swift` — 加 `Key.assetId`；`postFileReadyAndAwait(...)` 增加 `assetId: String?` 形参并写入 `userInfo`（nil 时不写键）。
- `iosApp/iosApp/PhotoImportCoordinator.swift` — `QueuedItem` / `RetrySource` 携带 `assetId`；`importBatch` 建队列时从 `PhotosPickerItem.itemIdentifier` 取值；`publishProvisional` 透传；`retry` 复用 provisional path 时同样透传。
- `shared/src/iosMain/.../ui/IosProgressiveImportController.kt` — 新 `const val KEY_ASSET_ID = "assetId"`；`handleFileReady` 读取；`adoptFileReady` 成功后调用 registry。
- 新增 `shared/src/iosMain/.../session/IosAssetIdentityRegistry.kt` — `internal`，`NSLock` 保护的 `ownedPath -> assetId` map，带上限（≥ 50，与 picker `maxSelectionCount` 对齐），`clear()` 在 `IosProductRootHost.dispose()` 与 `releaseEditorMediaResources()` 调用。

**行为**

- 有身份：记录；无身份（非 `.shared()` picker、legacy `deliverPickedPhotosBatch` fixture 路径、`-uiTestFixtureImage` seam）：不记录，一切照旧。
- 本阶段**不消费**身份做任何像素决策。

**测试**

- `shared/src/iosTest/.../ui/IosProgressiveNotificationBridgeTest.kt` 增加：带 `assetId` / 不带 `assetId` 的 `fileReady`，均需正常 ACK 并发布 Session。
- 新 `IosAssetIdentityRegistryTest`：provisional→owned 改名后键正确、dispose 清空、超上限淘汰、并发写不崩。
- 现有 `IosProgressiveAdoptionTest` / `IosOwnedPathAdoptionTest` / `IosSourceOwnershipTransactionTest` 必须绿。

**设备门**：无（`:shared:iosSimulatorArm64Test` + `xcodebuild` 通用模拟器即可，与 CI J1 一致）。

**回退**：单 commit revert。新键被两侧忽略。

---

### P2 — 授权能力 + PhotoKit 像素 producer（默认关闭）

**要动的文件**

- `iosApp/iosApp/Info.plist` — 新增 `NSPhotoLibraryUsageDescription`（文案见 §7），保留 `NSPhotoLibraryAddUsageDescription`。
  > 说明：ADR-0029 的实施草图把 plist 列为第 1 步，但**没有消费者的 usage string 是纯粹的商店风险**（审核会问用途，用户会看到一个从不使用的权限）。因此本计划把 plist 与第一个消费者绑在同一阶段落地——这正是 P0 被合并进 P2 的原因。
- 新增 `shared/src/iosMain/.../session/IosPhotoLibraryAccess.kt` — `internal`：`status()`、`isUsable()`（仅 `.authorized`；`.limited` 单独判定见 §5）、`requestOnceIfNeeded()`。
- 新增 `shared/src/iosMain/.../render/IosPhotoKitImageSource.kt` — `internal`：
  - `resolveAsset(localIdentifier)` = `PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null)`，空结果即 miss（Limited 的典型表现）；
  - `requestBitmap(asset, targetPx, deadlineMs)` = `PHImageManager.defaultManager().requestImageForAsset(...)`，`PHImageRequestOptions`：`deliveryMode = .opportunistic`、`resizeMode = .fast`、`networkAccessAllowed = false`（默认即 NO，显式写死以对齐"完全离线"承诺）、`synchronous = false`；
  - 用 `PHImageResultIsDegradedKey` 区分中间帧与最终帧；带 `deadlineMs` 超时后取消（`cancelImageRequest`）并回落。
- 提取 `internal object IosCgImageBridge`（从 `IosImageIODecoder` 现有 private `cgImageToSkiaOwned` / `cgImageToSkiaBitmapOwned` 抽出，行为不变）。
- `shared/src/iosMain/.../ui/IosDevicePerfBench.kt` — 新增 `DEVICE_PERF_PHOTOKIT` 臂（对应研究 §8.1 的 arm H）。

**行为**

- 生产 UI **不调用**这条路径。仅由 bench 启动参数（沿用 `-ewmDevicePerfBench` 的模式，新增 `-ewmPhotoKitFastPath`）或 `NSUserDefaults` 调试开关驱动（`IosContentThemePrefs` 已有同类先例）。
- 授权请求只在该开关打开时触发，保证默认构建的用户不会看到新弹窗。

**测试**

- 单测（模拟器）：无授权时 `isUsable()` 为 false、`resolveAsset` 空结果被当成 miss、超时路径返回 null 且不抛。
- 不做像素断言（模拟器相册不可控）。

**设备门（必需）**：真机 + 真实相册，跑 `DEVICE_PERF_PHOTOKIT`，覆盖 Allow All / Limited（选中集合内 + 集合外）/ Deny 三态。研究 §8.3 提醒：前两次设备实验都因掉线丢了 after 数据，预留重跑。

**回退**：revert commit 即可（含 plist；plist 回退后即恢复 add-only 声明）。

---

### P3 — 冷切图首帧走 PhotoKit（本期主目标，默认开启）

**要动的文件**

- `shared/src/iosMain/.../render/IosPreviewImageRepository.kt` — `IosPreviewPurpose` 增加 `SourceFastPath`（仅 `internal` enum，无持久化含义），并在 `PreviewWorkingSetBudget` 里给它一个**小额**独立预算（建议与 `SourcePlaceholder` 共享字节上限但条目上限 ≤ 3，只服务 focus±1）。
- `shared/src/iosMain/.../ui/IosProductRootHost.kt` — 三处接入：
  1. `onImageSelected`（`EditorScreen` 回调，约 L1231–L1313）：在"步骤 2 cached source placeholder"之后、"步骤 3 full watermarked preview"之前插入快路径尝试；
  2. `bindProgressiveFocus(mode = ImportPriority)`（约 L1702–L1726）：在 `previewImages.load(sourcePreviewKey…)` 的 ImageIO 解码**之前**先试快路径，命中就先上屏，再继续原逻辑；
  3. `bindProgressiveFocus(mode = UserScroll)`：仅在两级 `peekCached` 都 miss 时尝试。
- 不改 `renderPreviewForCurrentSelection` 的缓存语义；快路径 compose 走一次 `IosPreviewRaster.renderWatermarked(..., background = fastBitmap)`，结果**只赋给 `previewBitmap`**，并且 `watermarkedPreviewSourcePath = null`（等价于"这是占位质量"），这样后续 ImageIO 版本一定会覆盖，且 `ensureEditorPreviewAfterExport` 的空白检测语义不变。

**并发与取消**

- 快路径**不自增 `previewGen`**（与 `paintWatermarkedCacheHitIfPresent` 一致），由调用方统一管理代次；快路径结果在赋值前再检查一次 `gen == previewGen && !disposed`。
- 快路径与 ImageIO 渲染**并行发起**，谁先到谁先画，但 ImageIO 结果永远拥有最终话语权（`watermarkedPreviewSourcePath` 只由它设置）。
- 快路径有硬性 deadline（建议 120 ms，需设备实测校准）：超时即取消，避免"为了快反而更慢"。

**行为矩阵**

| 情形 | 首帧来源 | 后续 |
|---|---|---|
| 授权 + 身份可解析 + 有 derivative | PhotoKit（可能 degraded） | ImageIO 版静默替换 |
| 授权 + 身份可解析 + 无 derivative | PhotoKit 稍慢或超时 → 取消 | 与今天一致 |
| Limited 且照片不在允许集合 | 空 fetch → 立即 miss | 与今天一致 |
| 无身份 / 未授权 / 被拒 | 不尝试 | 与今天一致 |

**测试**

- `IosProductRootHostPreviewIdentityTest` 扩展：快路径帧**不得**写入 `Watermarked` 缓存（用 `previewIdentityForTests()` 的 `wmCachePaths` 断言）。
- 新 `IosPhotoKitFastPathFallbackTest`：producer 注入为"永远 miss / 永远超时"时，切图时序与今天等价（`switchImageAndAwaitForTests` 的 `hit` 分类不变）。
- `IosPreviewFiftyImageBudgetTest` / `IosHostImageCacheBudgetsTest` 必须绿（新增 purpose 不得撑破联合预算）。
- 拖参回归：`ClampDragH01IosBaselineTest`、`IosDraftRenderConflatorTest`。

**设备门（必需）**：`DEVICE_PERF_SUMMARY` 的 `switch_l1_med` / `switch_l1_hits` / 各 lap 的 decode-compose 拆分，前后对照（§6）。**必须看截图**：PhotoKit 首帧 vs ImageIO 终帧并排，确认没有明显偏软/偏色/朝向错误（ADR-0010，不看字节）。

**回退**：单 commit revert；`SourceFastPath` purpose 一并回收。

---

### P4 — `PHCachingImageManager` 邻居预取（对齐 focus±2）

**要动的文件**

- `shared/src/iosMain/.../ui/IosProductRootHost.kt` — `warmNeighborWatermarkedPreviews(focusPath, gen)`（约 L2271–L2317）里的 ±2 索引已经算好，在同一处把这批 `PHAsset` 交给 `startCachingImages(for:targetSize:contentMode:options:)`，离开窗口时 `stopCachingImages`；host `dispose()` 调 `stopCachingImagesForAllAssets()`。
- 可选（次要 chrome）：`shared/src/iosMain/.../ui/image/ProductThumbFetcher.ios.kt` 之前插入 PhotoKit fetcher，未命中原样落回。**注意** `PHCachingImageManager.allowsCachingHighQualityImages` 已在 iOS 26 弃用（研究 §2.2），不要围绕它设计。

**行为**：预取只影响 Photos 守护进程侧的准备，不占用我们的 `IosPreviewImageRepository` 预算。

**测试**：`IosPreviewLruProductionOrderTest` 不变；新增"dispose 后无残留 caching 窗口"的生命周期断言（用注入的 fake manager 计数 start/stop 配对）。

**设备门**：连续切 8 张，`switch_l3_med` / `switch_l4_med` 与 `switch_l4_hits_detail` 前后对照。

**回退**：单 commit revert。

---

### P5 — 隐私 / 商店文案清单

**要动的文件**

- `iosApp/README.md` §"Privacy / Photos access (ADR-0029)" — 已有 ADR-0029 段落，改为描述**已落地**行为（当前措辞是"tracks ADR-0029; usage string + request must land with the consumer code"）。
- `AGENTS.md` — iOS runtime wiring 段补一句"PhotoKit 快路径 + ImageIO 地板"，并在 §Storage & model invariants 明确"asset identifier 不落盘"。
- `docs/adr/0029-...md` — Consequences 增补真机数字与 Limited 实测结论（ADR 本体决策不改）。
- App Store Connect：Privacy Nutrition Label 复核（照片读取属于"App Functionality"，不上传、不关联身份、不用于追踪）；隐私政策站点文案（仓库内只有 `SharedProductDrawables.privacyZhPainter` / `privacyEnPainter` 两个入口图标，正文托管在站外，需 owner 同步更新）。

**检查清单**

- [ ] `NSPhotoLibraryUsageDescription` 中英文本已本地化（与 `CFBundleDisplayName` 同一 lproj 机制）
- [ ] README / AGENTS.md / ADR-0029 三处描述一致
- [ ] 商店隐私标签：Photos 读取 = App Functionality，未关联用户、未用于追踪
- [ ] 隐私政策正文提到"读权限可选、仅用于本机预览加速、拒绝不影响任何功能"
- [ ] F-Droid / Google Play 的 Android 文案**不受影响**（本期 iOS-only，ADR-0029 第 7 条）

**回退**：文档单独 revert，不影响代码。

### 4.1 顺序说明（与 brief 的差异）

brief 的 P0（plist/文案）被**并入 P2**：usage string 只有和第一个消费者同批落地才有意义，单独先落会在商店留下"声明了却不使用"的窗口。brief 的 P2 里"胶片条 vs 预览占位，先做哪个"选择 **预览首帧**：

- owner 的优先级明确是 ①切图 ②拖参，胶片条是次要 chrome；先做胶片条对两个目标零收益。
- 预览路径只有一个消费点（`IosPreviewImageRepository` + `IosPreviewRaster`），改动面小；胶片条要穿过 Coil 的 Keyer/Fetcher/Decoder 链并遵守 `isSampled = false` 约定，集成面更大。
- 预览首帧的失败模式温和：miss 就是今天的行为；胶片条 miss 处理不当会闪白（`LazyRow` 回收）。

因此胶片条降为 P4 的可选项，复用同一个 producer。

---

## 5. Limited / Denied / Restricted 矩阵

| `PHAuthorizationStatus` | PhotoKit 快路径 | 用户可见行为 | 是否弹窗 |
|---|---|---|---|
| `.authorized` | 启用；`fetchAssets` 通常命中 | 冷切图首帧显著变快，随后被全质量帧替换 | 已授权，不再弹 |
| `.limited` | **尝试但预期常 miss** —— PHPicker **不会**把所选项加入受限集合（Apple DTS），`fetchAssets` 对刚选的照片常返回空 | 与今天完全一致（ImageIO 地板）；不阻塞、不重试风暴 | 不再弹；可选一次性说明卡片 |
| `.denied` | 禁用 | 与今天完全一致 | 永不再弹；只在显式设置入口提供"前往系统设置" |
| `.restricted`（家长控制/MDM） | 禁用 | 与今天完全一致 | 永不弹（系统也不会给用户选择） |
| `.notDetermined` | 首次真正需要时请求一次；本进程内只请求一次 | 请求期间照常走 ImageIO，不等待授权结果 | 一次 |

补充规则：

- **Limited 不是异常分支，是一等公民**（ADR-0029 第 5 条）。miss 后必须直接落到 ImageIO，且**不得**为同一 asset 反复 fetch（registry 里记一个 `resolveFailed` 标记，本 host 生命周期内不再试）。
- **任何状态都不得让编辑器变空。** 快路径失败只是少一次加速。
- **无 dark pattern**（ADR-0029 第 6 条）：说明文案只解释"授权后预览更快"，不做"必须授权"的暗示，不重复打扰。

---

## 6. 测量计划

### 6.1 现成的度量

| 指标 | 来源 | 用途 |
|---|---|---|
| `DEVICE_PERF_SWITCH lap=… hit=… ms=…` | `IosDevicePerfBench.switchLine` | ① 冷切图端到端 |
| `switch_l1_med` / `switch_l1_hits` / `switch_l2_*` / `switch_l3_*` / `switch_l4_hits_detail` | `DEVICE_PERF_SUMMARY` | 冷/暖/邻居命中 |
| `switchSplitSummary`（decode / compose / icon / dispatch / other 拆分） | `IosPreviewBench.Attribution` | 证明 decode 占比确实被削掉，而不是被挪走 |
| `io128_med` / `io{bucket}_med` / `*_sub_*` | `DEVICE_PERF_IO` | ImageIO 地板的回归锚点 |
| `coil_cold_med` / `coil_warm_med` | `DEVICE_PERF_COIL` | 胶片条（仅 P4 相关） |
| `draftSamples` / `draftRenders` | `ClampDragBench` 提交时 extras | ② 拖参背压比值 |
| `first_visible_placeholder` / `first_watermarked_preview` | `ImportTimelineProbe`（只记事件名+代次+脱敏 id） | 导入首帧时间轴 |

### 6.2 新增度量（P2 落地）

一行 `DEVICE_PERF_PHOTOKIT`，**不含路径、不含 localIdentifier**（`ImportTimelineProbe` 已确立的脱敏纪律）：

```
DEVICE_PERF_PHOTOKIT auth=<authorized|limited|denied|restricted|notDetermined>
  n=<样本数> resolve_hit=<x/n> degraded_med=<ms> final_med=<ms>
  timeout=<count> miss=<count> target=<px>
```

### 6.3 前后对照表（真机 iPhone 16 Pro，相册 HEIC，长边 ≥3000，n≥8，顺序均衡）

**① 冷切图**

| 场景 | 指标 | Before（现状） | After（目标） | 通过条件 |
|---|---|---:|---:|---|
| Allow All，冷切 | `switch_l1_med` | ~226 ms（S1） | TBD | ≤ 80 ms 为达成；≤ 120 ms 为可接受 |
| Allow All，冷切 | Attribution decode 中位 | ~214 ms | TBD | 首帧路径 decode ≈ 0（PhotoKit 不计入 ImageIO decode 阶段） |
| Allow All，冷切 | `pk_degraded_med` | — | TBD | 有 degraded 帧时 ≤ 40 ms |
| Limited（集合外） | `switch_l1_med` | ~226 ms | TBD | 与 before 的差 ≤ +10 ms（不劣化） |
| Denied | `switch_l1_med` | ~226 ms | TBD | 与 before 的差 ≤ +5 ms |
| 邻居命中（lap3/4） | `switch_l3_med` / `switch_l4_hits` | 现有基线 | TBD | 不劣化；P4 后期望进一步改善 |

**② 拖参**

| 场景 | 指标 | Before | After | 通过条件 |
|---|---|---:|---:|---|
| CLAMP 拖偏移 | `draftRenders / draftSamples` | S4 未测（outstanding） | TBD | 明显 ≪ 1，且前后不变差 |
| CLAMP 拖偏移 | 拖动中 Attribution decode 中位 | 命中 Source 时 ≈ 0 | TBD | 仍 ≈ 0 |
| 切图后立刻拖参 | Source 命中率 | 现有基线 | TBD | **不低于** before（快路径不得跳过 ImageIO 填 Source） |

**质量（不可省略）**

- 每一个改变像素的阶段都要**看截图**：PhotoKit 首帧 / ImageIO 终帧 / 胶片条 cell 三者并排（ADR-0010）。
- 严格 FNV golden gate 保持 local-only，**永不进 PR CI**（ADR-0010）。

### 6.4 门槛（先于数据写下）

- 若 Allow All 下 `switch_l1_med` 改善 < 30%，**停止 P4**，把 P3 降级为默认关闭并重新评估（很可能是 derivative 不存在或 `PHAsset` 解析本身太贵）。
- 若 Limited/Denied 出现任何可测的劣化，**先修回落路径再谈收益**。
- 若首帧与终帧在截图上差异明显到用户会察觉"图变了两次"，改为只接受非 degraded 结果（见开放问题 Q1）。

---

## 7. 隐私与商店文案草稿（owner 后续可改）

### `NSPhotoLibraryUsageDescription`

**中文（建议主用）**
> 用于更快地显示你选择的照片预览。所有处理都在本机完成，不联网、不上传、不统计。

**English**
> Used to show faster previews of the photos you pick. Everything is processed on your device — no network, no upload, no tracking.

备选（更短）：

- 中文：读取相册可让预览更快显示；图片始终留在本机。
- English: Reading your library makes previews appear faster. Your photos never leave this device.

### `NSPhotoLibraryAddUsageDescription`（保持现状，列出对照）

> Save your watermarked photo to your library.（中文站点文案建议："把加好水印的照片保存到相册。"）

### README / 隐私政策段落草稿

**中文**
> 简单水印在 iOS 上会请求两项相册权限：**添加权限**用于保存导出的照片；**读取权限**是可选的，仅用于加速你已选照片的预览显示。App 完全离线运行，不含任何网络、统计或崩溃上报 SDK；拒绝读取权限不会影响任何功能，只是预览会稍慢一些。

**English**
> On iOS, Easy Watermark asks for two Photos permissions: **add-only** access to save your exported photos, and an **optional read** access used solely to speed up previews of the photos you already picked. The app is fully offline — no network, analytics, or crash-reporting SDKs. Declining read access changes nothing except preview speed.

### 商店隐私标签

- Photos：**Data Not Collected**（数据从不离开设备）。若平台强制归类，则选 App Functionality / 不关联用户 / 不用于追踪。
- 不新增任何 SDK、不新增网络能力。

---

## 8. 风险与开放问题（请 owner 逐条回复）

1. **首帧质量策略**：是否接受 PhotoKit 的 degraded（较软/色调可能不同）帧先上屏、随后被 ImageIO 全质量帧替换？还是只接受非 degraded 的最终帧（更慢但只画一次）？
2. **授权请求时机**：进入编辑器、第一次真正需要 PhotoKit 像素时自动请求一次；还是完全不自动请求，只在"关于/设置"里放一个显式开关由用户主动开启？
3. **首版默认值**：真机数据达标后，P3 是否对所有已授权用户默认开启；还是首版默认关闭、只留调试开关，观望一个版本？
4. **第一个消费者**：预览首帧（本计划推荐）还是胶片条？
5. **Limited 的用户沟通**：Limited 用户注定常走 ImageIO 地板。是否要一次性说明（"你选择了仅允许部分照片，预览加速可能不生效"），还是完全静默？
6. **`preselectedAssetIdentifiers`**：P1 拿到身份后，重开 picker 时回显当前选中集合是免费收益，是否纳入本期（属于 UX 改善，非性能）？
7. **app-owned 磁盘缩略图缓存**（研究 §6.3，Coil iOS 侧目前无 `diskCache`，冷启动每次重解 128 px）：与本期同批做，还是单独一期？
8. **度量日志的构建范围**：`DEVICE_PERF_PHOTOKIT` 只记录授权状态/命中计数/耗时（无路径、无标识符）。是否允许进 Release 构建的设备日志以便真机取数，还是必须 Debug-only？

---

## 9. 推荐默认答案（owner 可直接回 LGTM，或指名改 Qn）

| # | 推荐 | 一句话理由 |
|---|---|---|
| Q1 | **接受 degraded 首帧 + 渐进精修**，但加 120 ms deadline，并在截图门里确认替换不刺眼 | ①的目标就是"先看到东西"；`.opportunistic` 的同步首帧正是 Apple 为此设计的 |
| Q2 | **首次真正需要时自动请求一次**（非冷启动），同时在关于页提供开关与"前往设置" | 与 ADR-0029 第 1 条一致；用户此刻正好在等预览，动机可解释 |
| Q3 | **达标后默认开启**，但保留 `NSUserDefaults` 关闭开关 | 关掉就是今天的行为，风险可控；默认关闭等于白付一个权限提示 |
| Q4 | **预览首帧优先**，胶片条在 P4 复用同一 producer | owner 优先级是 ①②；胶片条集成面更大、收益不在目标内 |
| Q5 | **静默 + 一次性可关闭说明**（只在 Limited 且首次快路径 miss 时出现一次） | 不解释会被当成 bug；反复解释就是 dark pattern |
| Q6 | **纳入 P1**（同一份身份数据，几行 Swift） | 免授权、零像素风险，且是 iOS 侧真实存在的 UX 缺口 |
| Q7 | **单独一期**，排在本计划之后 | 它解决的是"第二次及以后"的冷启动，与 ①的首次解码正交；混在一起无法归因 |
| Q8 | **Release 也允许**，字段保持脱敏（沿用 `ImportTimelineProbe` 的纪律） | 真机取数就在 Release 上跑；不记路径/标识符就没有隐私增量 |
